package server.living.roster;

import client.Character;
import net.packet.Packet;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.TimerManager;
import server.integration.AgentPresence;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestQueueRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.CitizenInviteDecision;
import server.living.roster.CompanionDirectory.Relation;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Party invite by name (op 4), decided before anything is loaded (docs/BOT_MANAGEMENT_PLAN.md P1,
 * "CompanionPartyRouter"). Your own companion is called through the roster (on companion-ops); another player's
 * companion or a Resident denies the invite (23); an Agent you may not control is not found (19); a citizen answers
 * for itself ({@link CitizenInviteDecision}) after a moment, like a player; a starter party takes nobody but your own
 * companions (10). A human alone with their companions gets the stock invite instead of "already in a party" (P0
 * probe 1), and their accept (op 3, {@link #accept}) merges the two parties. Other humans, Agents you control and PQ
 * lobby waiters keep the stock invite.
 */
public final class CompanionPartyRouter {
    /** What the party handler does next. */
    public enum Route {
        /** The stock invite, unchanged. */
        STOCK,
        /** Your own companion is already out: the stock invite seats it without asking the Agent lobby again. */
        ACCEPT,
        /** Answered here (a call or a status message); nothing more to do. */
        ANSWERED
    }

    /** A companion's answer when its owner's share of a shared party is used and the other seats are a friend's. */
    static final String SHARE_USED_LINE = "the other seats are your friend's. bench one of us first.";

    private final LivingWorld world;

    public CompanionPartyRouter(LivingWorld world) {
        this.world = world;
    }

    /** {@code invited} is the online character with that name, or null when none is online. */
    public Route invite(Character inviter, Character invited, String name) {
        CompanionDirectory directory = world.directory();
        Relation relation = invited != null ? directory.resolve(inviter, invited) : directory.resolveName(inviter, name);
        String shown = invited != null ? invited.getName() : name;
        return switch (relation) {
            case Relation.OwnCompanion own -> ownCompanion(inviter, invited, own.entry());
            case Relation.OthersCompanion others -> answer(inviter, PacketCreator.partyStatusMessage(23, shown));
            case Relation.Resident resident -> answer(inviter, PacketCreator.partyStatusMessage(23, shown));
            case Relation.Citizen citizen -> citizen(inviter, invited);
            case Relation.PlainAgent agent -> plainAgent(inviter, invited, agent.characterId());
            case Relation.Human human -> invited == null ? notFound(inviter)
                    : inStarterParty(inviter) ? beginner(inviter) : mergeInvite(inviter, invited) ? Route.ANSWERED : Route.STOCK;
            case Relation.Unknown unknown -> notFound(inviter);
        };
    }

    /**
     * Benched, away, offline or bench-live: call it (a load). Already out with you: the stock invite seats it.
     * The companion answers for itself (a whisper), and the stock join notice announces it; nothing is printed.
     */
    private Route ownCompanion(Character owner, Character loaded, RosterEntry entry) {
        Persona persona = world.content().persona(entry.personaId());
        if (loaded != null && entry.state() == RosterState.ACTIVE) {
            if (!ownParty(owner)) {
                return Route.ANSWERED;
            }
            // out with you but not in the party: the stock invite seats it on a seat held for the join
            if (loaded.getParty() == null && !(world.roster().canSeat(owner, entry) && world.slots().reserve(owner.getId(),
                    entry.personaId(), CompanionSlots.Kind.TRAVEL, System.currentTimeMillis() + CompanionSlots.SEAT_HOLD_MS))) {
                return noSeat(owner, persona);
            }
            return Route.ACCEPT;
        }
        if (persona == null) {
            return notFound(owner);
        }
        if (!world.roster().canSeat(owner, entry)) {
            return noSeat(owner, persona);
        }
        // checked, not admitted: the roster load itself takes the owner's load slot
        CompanionBudget.Admission admission = CompanionBudget.decide(CompanionBudget.Kind.COMPANION,
                world.budget().usage(owner.getId()), world.config());
        if (!admission.allowed()) {
            world.store().event(owner.getId(), persona.id(), "companion.budget_denied",
                    CompanionBudget.Kind.COMPANION + ": " + admission.reason());
            world.residents().whisper(persona, owner, "it's too crowded out here right now. ask me again in a bit.");
            return Route.ANSWERED;
        }
        if (ownParty(owner)) {
            world.executor().submit("roster.call " + persona.id(), () -> world.roster().call(owner, persona));
        }
        return Route.ANSWERED;
    }

    /**
     * A human in an own party (alone with their companions) is sent the stock invite, as a partyless one would be,
     * where the stock answer is "already in a party" (16); P0 probe 1: their client shows it and accepts with op 3.
     * False when that is not the case, for the stock invite.
     */
    private boolean mergeInvite(Character inviter, Character invited) {
        Party theirs = invited.getParty();
        Party ours = inviter.getParty();
        if (theirs == null || ours != null && ours.getId() == theirs.getId() || !world.directory().isCompanionParty(theirs)
                || inEventInstance(theirs)) {
            return false; // the stock "already in a party": a party in a run is not asked to merge (its accept could not)
        }
        if (ours == null && !Party.createParty(inviter, false)) {
            return true; // the stock refusal (a beginner, a coliseum) was sent
        }
        ours = inviter.getParty();
        if (ours.getMembers().size() >= CompanionSlots.PARTY_CAP) {
            answer(inviter, PacketCreator.partyStatusMessage(17));
        } else if (InviteCoordinator.createInvite(InviteType.PARTY, inviter, ours.getId(), invited.getId())) {
            invited.sendPacket(PacketCreator.partyInvite(inviter));
        } else {
            answer(inviter, PacketCreator.partyStatusMessage(22, invited.getName()));
        }
        return true;
    }

    /**
     * Op 3, after the invite was answered: true when the living world seats the player on companion-ops
     * ({@link CompanionPartySync#accept}). That is an owner in an own party merging into the inviter's (P0 probe 1),
     * or any partyless player joining a full party, where someone steps out first (make-room, probe 12). Anything
     * else is the stock join, whose JOIN the sync rebalances afterwards.
     */
    public boolean accept(Character player, int partyId) {
        if (AgentPresence.isAgent(player)) {
            return false;
        }
        Party target = player.getWorldServer().getParty(partyId);
        Party own = player.getParty();
        if (target == null || own != null && own.getId() == partyId) {
            return false;
        }
        boolean merge = own != null && world.directory().isCompanionParty(own);
        boolean full = target.getMembers().size() >= CompanionSlots.PARTY_CAP;
        if (!merge && (own != null || !full)) {
            return false; // the stock join, or its "already in a party"
        }
        int playerId = player.getId();
        world.executor().submit("companion.accept " + playerId + " -> " + partyId,
                () -> world.partySync().accept(playerId, partyId));
        return true;
    }

    /**
     * A willing citizen gets the stock invite and accepts a moment later; one too far from your level denies (23)
     * and a busy one is taking care of something else (22), as late.
     */
    private Route citizen(Character inviter, Character citizen) {
        if (citizen == null) {
            return notFound(inviter);
        }
        if (citizen.getParty() != null || lobbyDecides(citizen, inviter)) {
            return Route.STOCK;
        }
        if (inStarterParty(inviter)) {
            return beginner(inviter);
        }
        CitizenInviteDecision.Answer answer = CitizenInviteDecision.decide(inviter, citizen);
        if (answer != CitizenInviteDecision.Answer.ACCEPT) {
            Packet denied = PacketCreator.partyStatusMessage(answer.status(), citizen.getName());
            later(() -> {
                if (inviter.isLoggedinWorld()) {
                    inviter.sendPacket(denied);
                }
            });
            return Route.ANSWERED;
        }
        if (inviter.getParty() == null && !Party.createParty(inviter, false)) {
            return Route.ANSWERED; // the stock refusal (a beginner, a coliseum) was sent
        }
        Party party = inviter.getParty();
        if (world.roster().freeSlots(inviter) <= 0) { // a guest takes one of the inviter's own seats
            // at 6/6 the stock "full"; with the open seats a friend's share, the citizen declines like a player
            return world.slots().occupied(inviter) >= CompanionSlots.PARTY_CAP
                    ? answer(inviter, PacketCreator.partyStatusMessage(17))
                    : answer(inviter, PacketCreator.partyStatusMessage(23, citizen.getName()));
        }
        if (!InviteCoordinator.createInvite(InviteType.PARTY, inviter, party.getId(), citizen.getId())) {
            return answer(inviter, PacketCreator.partyStatusMessage(22, citizen.getName()));
        }
        int partyId = party.getId();
        // the GUEST seat and the join are companion mutations: on companion-ops, the timer only hands them over
        later(() -> world.executor().submit("citizen.accept " + citizen.getId(),
                () -> acceptInvite(inviter, citizen, partyId)));
        return Route.ANSWERED;
    }

    /**
     * The citizen's yes, if it still stands, on companion-ops: both online, the citizen partyless, the inviter's party
     * with a seat for it. The seat is a GUEST reservation charged to the inviter, taken before the join (the party's
     * seat gate asks for it) and given back when the guest leaves the party.
     */
    private void acceptInvite(Character inviter, Character citizen, int partyId) {
        Party party = inviter.getParty();
        String guest = world.slots().keyOf(citizen.getId());
        boolean stands = inviter.isLoggedinWorld() && citizen.isLoggedinWorld() && citizen.getParty() == null
                && party != null && party.getId() == partyId && world.roster().freeSlots(inviter) > 0
                && world.slots().reserve(inviter.getId(), guest, CompanionSlots.Kind.GUEST, Long.MAX_VALUE);
        InviteCoordinator.answerInvite(InviteType.PARTY, citizen.getId(), partyId, stands);
        if (stands && !Party.joinParty(citizen, partyId, false)) {
            world.slots().release(guest, CompanionSlots.Kind.GUEST);
        }
    }

    /** A citizen's answer comes after a short random delay, like a player's. */
    private void later(Runnable answer) {
        long delayMs = ThreadLocalRandom.current().nextLong(world.config().CITIZEN_INVITE_REPLY_MIN_MS,
                world.config().CITIZEN_INVITE_REPLY_MAX_MS + 1L);
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, answer, delayMs);
    }

    /** Admins and runtime leaders keep the stock invite (an offline Agent is loaded, after the seat and budget checks). */
    private Route plainAgent(Character inviter, Character loaded, int characterId) {
        if (!world.directory().mayControl(inviter, characterId)) {
            return loaded != null && lobbyDecides(loaded, inviter) ? Route.STOCK : notFound(inviter);
        }
        if (inStarterParty(inviter)) {
            return beginner(inviter);
        }
        if (loaded != null) {
            return Route.STOCK;
        }
        if (world.roster().freeSlots(inviter) <= 0) {
            return noSeat(inviter, null);
        }
        if (!world.budget().admit(CompanionBudget.Kind.COMPANION, inviter).allowed()) {
            inviter.dropMessage(5, "No Agent can be loaded for you right now.");
            return Route.ANSWERED;
        }
        return Route.STOCK;
    }

    /** Like stock op 4, the inviter's party is made first; below level 10 it is a starter party for their companions. */
    private static boolean ownParty(Character owner) {
        return owner.getParty() != null || Party.createParty(owner, false, true);
    }

    /**
     * No seat for one more of the owner's: the stock "full" (17) while the party is at 6/6. With seats open they are a
     * friend's share (P2a's share is a cap; lending is P9) or held empty by the leader's expel (the leader cap), so
     * the companion says so in character, or pink text.
     */
    private Route noSeat(Character owner, Persona speaker) {
        if (world.slots().occupied(owner) >= CompanionSlots.PARTY_CAP) {
            return answer(owner, PacketCreator.partyStatusMessage(17));
        }
        if (speaker != null) {
            world.residents().whisper(speaker, owner,
                    world.slots().leaderCapped(owner) ? RosterService.LEADER_CAP_LINE : SHARE_USED_LINE);
        } else {
            owner.dropMessage(5, "The other seats in this party are your friend's.");
        }
        return Route.ANSWERED;
    }

    private static boolean inEventInstance(Party party) {
        for (PartyCharacter member : party.getPartyMembersOnline()) {
            Character player = member.getPlayer();
            if (player != null && player.getEventInstance() != null) {
                return true;
            }
        }
        return false;
    }

    /** A PQ lobby or queue decides this Agent's invites, as before. */
    private static boolean lobbyDecides(Character agent, Character inviter) {
        return AgentPartyQuestLobbyRegistry.forMember(agent.getId()) != null
                || AgentPartyQuestQueueRuntime.canAcceptInvite(agent, inviter);
    }

    private static boolean inStarterParty(Character inviter) {
        Party party = inviter.getParty();
        return party != null && party.isStarterParty();
    }

    private static Route beginner(Character inviter) {
        return answer(inviter, PacketCreator.partyStatusMessage(10));
    }

    private static Route notFound(Character inviter) {
        return answer(inviter, PacketCreator.partyStatusMessage(19));
    }

    private static Route answer(Character inviter, Packet status) {
        inviter.sendPacket(status);
        return Route.ANSWERED;
    }
}
