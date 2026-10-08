package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.store.LivingStore.RosterEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decision 8b, the instance logout handoff: an owner who logs off inside an event instance that a human of their party
 * is still in leaves their companions committed to that run to finish it for that human, as far as the run's
 * {@link Delegation} agrees (P6: CompanionRunLifecycle, for a companion KPQ run that goes on without the owner). The
 * roster owner, the row (ACTIVE), the commitment and the runtime leader all stay; the run's PQ controller drives the
 * companion meanwhile. When the commitment ends (the run ended, or the companion left it) the companion leaves that
 * party and parks (RosterService.parkAfterRun). With no human left to hand them to, or no delegation that agrees, they
 * leave with their owner under stock eim rules and their commitments end (RosterService.onOwnerLogout).
 */
public final class InstanceLogoutHandoff {
    /**
     * PQ-scoped consent (plan "Owner-scoped authority"): true when {@code companion} finishes the run it is committed to
     * ({@code key}) for {@code human}, still its owner's.
     */
    @FunctionalInterface
    public interface Delegation {
        boolean delegate(Character companion, Character human, String key);
    }

    private final LivingWorld world;
    private final RosterService roster;
    private volatile Delegation delegation;
    /** Companion character id -> its owner, while it finishes a run for a human of its owner's party. */
    private final Map<Integer, Integer> finishing = new ConcurrentHashMap<>();

    InstanceLogoutHandoff(LivingWorld world, RosterService roster) {
        this.world = world;
        this.roster = roster;
    }

    void install(Delegation installed) {
        delegation = installed;
    }

    /**
     * The owner logged off inside {@code instance} (on companion-ops): each companion of {@code out} registered in it
     * and committed to a run is handed to a human of the owner's party still in it. Returns the persona ids handed over;
     * the caller parks the others.
     */
    Set<String> handOff(Character owner, EventInstanceManager instance, List<RosterEntry> out) {
        Delegation installed = delegation;
        Commitments commitments = world.commitments();
        Character heir = installed == null || commitments == null ? null : heir(owner, instance);
        if (heir == null) {
            return Set.of();
        }
        Set<String> handed = new HashSet<>();
        for (RosterEntry entry : out) {
            Character bot = roster.companion(entry);
            String key = bot == null ? null : commitments.keyOf(bot.getId());
            if (key != null && instance.getPlayerById(bot.getId()) != null && installed.delegate(bot, heir, key)) {
                finishing.put(bot.getId(), owner.getId());
                handed.add(entry.personaId());
                world.store().event(owner.getId(), entry.personaId(), "companion.handoff",
                        "finishes " + key + " with " + heir.getName());
            }
        }
        return handed;
    }

    /**
     * The human of the owner's party still online in {@code instance} to hand the run to: its leader when that is one,
     * else the first. Null when there is none.
     */
    static Character heir(Character owner, EventInstanceManager instance) {
        Party party = owner.getParty();
        if (party == null || instance == null) {
            return null;
        }
        Character heir = null;
        for (PartyCharacter member : party.getMembers()) {
            if (member.isBot() || member.getId() == owner.getId() || !member.isOnline()) {
                continue;
            }
            Character human = instance.getPlayerById(member.getId());
            if (human != null && (heir == null || member.getId() == party.getLeaderId())) {
                heir = human;
            }
        }
        return heir;
    }

    /**
     * P7 Part B, for a bench ally only (CompanionBossAllies; a party companion keeps {@link #heir}): the human still in
     * {@code instance} to see the run through for, past the owner's party: the party's heir ({@link #heir}), else the
     * expedition's leader while a human player of it, else its first human player still logged in. Never the owner and
     * never an Agent; null when there is none.
     */
    static Character anyHuman(Character owner, EventInstanceManager instance) {
        if (owner == null || instance == null) {
            return null;
        }
        Character heir = heir(owner, instance);
        if (heir != null) {
            return heir;
        }
        Expedition expedition = instance.getExpedition();
        Character leader = expedition == null ? null : expedition.getLeader();
        Character leading = leader == null ? null : instance.getPlayerById(leader.getId());
        if (anotherHuman(owner, leading)) {
            return leading;
        }
        for (Character player : instance.getPlayers()) {
            if (anotherHuman(owner, player)) {
                return player;
            }
        }
        return null;
    }

    /** A human player still logged in who is not the owner. */
    private static boolean anotherHuman(Character owner, Character player) {
        return player != null && player.getId() != owner.getId() && !AgentPresence.isAgent(player)
                && player.isLoggedin();
    }

    /** True while the character finishes a run for someone other than its owner. */
    boolean finishing(int characterId) {
        return characterId != 0 && finishing.containsKey(characterId);
    }

    /** A Commitments listener (on the releasing thread): a companion that finished its run parks, on companion-ops. */
    void commitmentEnded(Commitments.Ended ended) {
        for (Commitments.Member member : ended.members()) {
            Integer ownerId = finishing.remove(member.characterId());
            if (ownerId != null) {
                world.executor().submit("roster.run_finished " + member.characterId(),
                        () -> roster.parkAfterRun(ownerId, member.characterId()));
            }
        }
    }
}
