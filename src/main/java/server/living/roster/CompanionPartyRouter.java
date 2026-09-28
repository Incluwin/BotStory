package server.living.roster;

import client.Character;
import net.packet.Packet;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import server.TimerManager;
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
 * "CompanionPartyRouter"). Your own companion is called through the roster; another player's companion or a
 * Resident denies the invite (23); an Agent you may not control is not found (19); a citizen answers for itself
 * ({@link CitizenInviteDecision}) after a moment, like a player; a starter party takes nobody but your own
 * companions (10). Humans, Agents you control and PQ lobby waiters keep the stock invite.
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
                    : inStarterParty(inviter) ? beginner(inviter) : Route.STOCK;
            case Relation.Unknown unknown -> notFound(inviter);
        };
    }

    /**
     * Benched, away, offline or bench-live: call it (a load). Already out with you: the stock invite seats it.
     * The companion answers for itself (a whisper), and the stock join notice announces it; nothing is printed.
     */
    private Route ownCompanion(Character owner, Character loaded, RosterEntry entry) {
        if (loaded != null && entry.state() == RosterState.ACTIVE) {
            return ownParty(owner) ? Route.ACCEPT : Route.ANSWERED;
        }
        Persona persona = world.content().persona(entry.personaId());
        if (persona == null) {
            return notFound(owner);
        }
        if (entry.state() != RosterState.AWAY && world.roster().freeSlots(owner) <= 0) {
            return answer(owner, PacketCreator.partyStatusMessage(17));
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
            world.roster().call(owner, persona);
        }
        return Route.ANSWERED;
    }

    /** A willing citizen gets the stock invite and accepts a moment later; an unwilling one denies (23) as late. */
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
        if (!CitizenInviteDecision.accepts(inviter, citizen)) {
            Packet denied = PacketCreator.partyStatusMessage(23, citizen.getName());
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
        if (party.getMembers().size() >= 6) {
            return answer(inviter, PacketCreator.partyStatusMessage(17));
        }
        if (!InviteCoordinator.createInvite(InviteType.PARTY, inviter, party.getId(), citizen.getId())) {
            return answer(inviter, PacketCreator.partyStatusMessage(22, citizen.getName()));
        }
        int partyId = party.getId();
        later(() -> acceptInvite(inviter, citizen, partyId));
        return Route.ANSWERED;
    }

    /** The citizen's yes, if it still stands: both online, the citizen partyless, the inviter's party with room. */
    private static void acceptInvite(Character inviter, Character citizen, int partyId) {
        Party party = inviter.getParty();
        boolean stands = inviter.isLoggedinWorld() && citizen.isLoggedinWorld() && citizen.getParty() == null
                && party != null && party.getId() == partyId && party.getMembers().size() < 6;
        InviteCoordinator.answerInvite(InviteType.PARTY, citizen.getId(), partyId, stands);
        if (stands) {
            Party.joinParty(citizen, partyId, false);
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
            return answer(inviter, PacketCreator.partyStatusMessage(17));
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
