package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Readiness before an activity: the recovery potions an Agent carries into it (a party quest now; a boss arrives with
 * its expedition work), and the owner-free seam through which a host gives an Agent a standing duty to keep them.
 *
 * <p>One standard serves everyone who asks: the host's entry desk reads {@link #shortfall(int, int, boolean, Activity)}
 * with the counts it already has, and the Agent's own procurement reads {@link #shortfall(Character, Activity)} with
 * {@link AgentPotionService#countPotions}. While the installed {@link Rule} answers {@link Rule#standing} for an Agent,
 * the Agent walks to the nearest potion shop by itself on a town map whose supplier is close (AgentSupplyReadinessRuntime)
 * and buys toward {@code BUY_TO_PERCENT} of the standard ({@link #buyTarget}); it never goes when it cannot afford the
 * trip, and says so through the rule instead of asking its party. With nothing installed, nothing here changes what an
 * Agent does.</p>
 *
 * <p>The host's reads ({@link #status}, {@link #checkSoon}, {@link #resumeFollowAfterTrip}) touch memory only and may run
 * on any thread; the rule's callbacks run on the Agent tick and must only touch memory and hand work over.</p>
 */
public final class AgentSupplyReadiness {
    private static final Logger log = LoggerFactory.getLogger(AgentSupplyReadiness.class);
    private static final String TUNING_PREFIX = "server.agents.capabilities.supplies.AgentSupplyReadiness.";
    private static final AtomicBoolean clampWarned = new AtomicBoolean();
    private static volatile Rule rule;

    private AgentSupplyReadiness() {
    }

    /** What an Agent gets ready for. */
    public enum Activity { PARTY_QUEST }

    /** Recovery potions an Agent goes in with at least: HP potions, and MP potions when it fights with skills. */
    public record Standard(int hp, int mp) { }

    /** How many HP and MP potions an Agent still lacks for a standard (0 when it has enough). */
    public record Shortfall(int hp, int mp) {
        public static final Shortfall NONE = new Shortfall(0, 0);

        public boolean none() {
            return hp <= 0 && mp <= 0;
        }

        /** How many potion categories are short: each takes a Use slot of its own when bought. */
        public int categories() {
            return (hp > 0 ? 1 : 0) + (mp > 0 ? 1 : 0);
        }
    }

    /** Why a potion trip was not started. */
    public enum Refusal { CANNOT_AFFORD, NO_SPACE, NO_SUPPLIER }

    /** How a readiness trip ended. */
    public enum Outcome { READY, SHORT_MESOS, SHORT_SPACE, INTERRUPTED }

    /** What a host can show about an Agent's potions: nothing, on its way to a shop, or why it cannot go. */
    public enum StatusKind { NONE, RESTOCKING, CANNOT_AFFORD, NO_SPACE, NO_SUPPLIER }

    /**
     * {@code etaMs}: about how long until it is back (RESTOCKING only); {@code missingMesos}: how much more it needs
     * (CANNOT_AFFORD only).
     */
    public record Status(StatusKind kind, long etaMs, int missingMesos) {
        public static final Status NONE = new Status(StatusKind.NONE, 0L, 0);

        public boolean restocking() {
            return kind == StatusKind.RESTOCKING;
        }

        public boolean cannotAfford() {
            return kind == StatusKind.CANNOT_AFFORD;
        }
    }

    /** The host's answer: which Agents keep a standing readiness duty, and what it hears about their trips. */
    public interface Rule {
        /** The activity this loaded Agent keeps itself ready for; null: no duty. Memory only, any thread. */
        AgentSupplyReadiness.Activity standing(int agentCharacterId);

        /** It set off for a shop, about {@code etaMs} from being back. On the Agent tick. */
        default void started(int agentCharacterId, Activity activity, long etaMs) {
        }

        /** It did not set off ({@code missingMesos}: how much more the trip needs, CANNOT_AFFORD only). */
        default void refused(int agentCharacterId, Activity activity, Refusal why, Shortfall shortfall,
                             int missingMesos) {
        }

        /** Its readiness trip ended. On the Agent tick. */
        default void finished(int agentCharacterId, Activity activity, Outcome outcome) {
        }
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** The installed rule's standing duty for this Agent; null without a rule, without a duty, or if it failed. */
    public static Activity standing(int agentCharacterId) {
        Rule current = rule;
        if (current == null) {
            return null;
        }
        try {
            return current.standing(agentCharacterId);
        } catch (RuntimeException failure) {
            log.debug("readiness rule failed to answer for Agent {}", agentCharacterId, failure);
            return null;
        }
    }

    static void started(int agentCharacterId, Activity activity, long etaMs) {
        Rule current = rule;
        if (current == null) {
            return;
        }
        try {
            current.started(agentCharacterId, activity, etaMs);
        } catch (RuntimeException failure) {
            log.warn("readiness rule failed on a start for Agent {}", agentCharacterId, failure);
        }
    }

    static void refused(int agentCharacterId, Activity activity, Refusal why, Shortfall shortfall,
                        int missingMesos) {
        Rule current = rule;
        if (current == null) {
            return;
        }
        try {
            current.refused(agentCharacterId, activity, why, shortfall, missingMesos);
        } catch (RuntimeException failure) {
            log.warn("readiness rule failed on a refusal for Agent {}", agentCharacterId, failure);
        }
    }

    static void finished(int agentCharacterId, Activity activity, Outcome outcome) {
        Rule current = rule;
        if (current == null) {
            return;
        }
        try {
            current.finished(agentCharacterId, activity, outcome);
        } catch (RuntimeException failure) {
            log.warn("readiness rule failed on an end for Agent {}", agentCharacterId, failure);
        }
    }

    // ---- the standard ----------------------------------------------------------------------

    /**
     * The standard for {@code activity}, read from agent-engine.yaml at every call. Mana potions must stay above
     * POT_STOP ({@link #validate}); a misconfigured value is clamped to POT_STOP + 1 and warned about once, never thrown.
     */
    public static Standard standard(Activity activity) {
        int hp = Math.max(0, tuningInt("PARTY_QUEST_HP_POTIONS"));
        int mp = tuningInt("PARTY_QUEST_MP_POTIONS");
        int potStop = AgentRuntimeConfig.cfg.POT_STOP;
        try {
            validate(hp, mp, potStop);
        } catch (IllegalArgumentException invalid) {
            if (clampWarned.compareAndSet(false, true)) {
                log.warn("{}; using {} mana potions", invalid.getMessage(), potStop + 1);
            }
            return new Standard(hp, potStop + 1);
        }
        return new Standard(hp, mp);
    }

    /**
     * A standard's mana potions must be above POT_STOP: at or under it, the Agent's own critical resupply would ask
     * the party for some the moment the run begins.
     */
    static void validate(int hp, int mp, int potStop) {
        if (hp < 0) {
            throw new IllegalArgumentException("PARTY_QUEST_HP_POTIONS " + hp + " is negative");
        }
        if (mp <= potStop) {
            throw new IllegalArgumentException("PARTY_QUEST_MP_POTIONS " + mp + " must be above POT_STOP " + potStop);
        }
    }

    /** Every job past a beginner's (Beginner, Noblesse, Legend) fights with skills and needs mana potions. */
    public static boolean needsManaPotions(Character agent) {
        return agent != null && agent.getJob() != null && agent.getJob().getId() % 1000 != 0;
    }

    /** What {@code hpPotions} and {@code mpPotions} lack for {@code activity}'s standard. Pure. */
    public static Shortfall shortfall(int hpPotions, int mpPotions, boolean skillUser, Activity activity) {
        Standard standard = standard(activity);
        return new Shortfall(Math.max(0, standard.hp() - Math.max(0, hpPotions)),
                skillUser ? Math.max(0, standard.mp() - Math.max(0, mpPotions)) : 0);
    }

    /** What the Agent's own potions ({@link AgentPotionService#countPotions}) lack for {@code activity}. */
    public static Shortfall shortfall(Character agent, Activity activity) {
        if (agent == null) {
            return Shortfall.NONE;
        }
        int[] potions = agent.getInventory(InventoryType.USE) == null ? new int[]{0, 0}
                : AgentPotionService.countPotions(agent);
        return shortfall(potions[0], potions[1], needsManaPotions(agent), activity);
    }

    /**
     * How many HP (or MP) potions a shop visit buys toward while the Agent keeps a standing duty: BUY_TO_PERCENT of
     * the standard, rounded up; 0 without a duty, and for MP when it fights without skills.
     */
    public static int buyTarget(Character agent, boolean forHp) {
        Activity activity = agent == null ? null : standing(agent.getId());
        if (activity == null || !forHp && !needsManaPotions(agent)) {
            return 0;
        }
        Standard standard = standard(activity);
        long count = forHp ? standard.hp() : standard.mp();
        return (int) Math.min(Integer.MAX_VALUE, (count * Math.max(0, tuningInt("BUY_TO_PERCENT")) + 99L) / 100L);
    }

    // ---- what a host reads and nudges (memory only, any thread) ------------------------------

    /**
     * RESTOCKING while a potion trip is under way (a readiness trip's own estimate, at least a check interval; another
     * potion trip's from its hop budget); otherwise the last readiness verdict when it was found on the map the Agent
     * stands on; otherwise NONE.
     */
    public static Status status(AgentRuntimeEntry entry, long nowMs) {
        if (entry == null) {
            return Status.NONE;
        }
        AgentSupplyProcurementState execution =
                entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY).orElse(null);
        AgentSupplyReadinessState readiness =
                entry.capabilityStates().find(AgentSupplyReadinessState.STATE_KEY).orElse(null);
        if (execution != null && execution.isActive()) {
            AgentResourceCategory category = execution.category();
            if (category == AgentResourceCategory.HP_POTION || category == AgentResourceCategory.MP_POTION) {
                String requestId = execution.requestId();
                long etaAt = readiness == null ? 0L : readiness.etaAtMs(requestId);
                long eta = AgentSupplyReadinessRuntime.readiness(requestId) && etaAt > 0L
                        ? Math.max(checkIntervalMs(), etaAt - nowMs)
                        : tripEtaBaseMs() + Math.max(1, execution.supplierHopBudget()) * tripEtaPerHopMs();
                return new Status(StatusKind.RESTOCKING, eta, 0);
            }
        }
        if (readiness == null || entry.bot() == null) {
            return Status.NONE;
        }
        AgentSupplyReadinessState.Verdict verdict = readiness.verdictOn(entry.bot().getMapId());
        if (verdict == null) {
            return Status.NONE;
        }
        return switch (verdict) {
            case CANNOT_AFFORD -> new Status(StatusKind.CANNOT_AFFORD, 0L, readiness.verdictMissingMesos());
            case NO_SPACE -> new Status(StatusKind.NO_SPACE, 0L, 0);
            case NO_SUPPLIER -> new Status(StatusKind.NO_SUPPLIER, 0L, 0);
        };
    }

    /** The Agent looks at its potions on its next tick instead of at its next check interval. */
    public static void checkSoon(AgentRuntimeEntry entry) {
        if (entry != null) {
            entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).checkSoon();
        }
    }

    /**
     * A host let the Agent follow again while it is on a supply trip: once the trip is over, it rejoins by following
     * (a follow warp) instead of walking back to the map it left. Nothing while no trip is under way.
     */
    public static void resumeFollowAfterTrip(AgentRuntimeEntry entry) {
        if (entry == null) {
            return;
        }
        entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .filter(AgentSupplyProcurementState::isActive)
                .ifPresent(execution -> execution.rememberForegroundMode(true, false));
    }

    // ---- tuning (agent-engine.yaml), read at call time ----------------------------------------

    static long checkIntervalMs() {
        return tuningLong("CHECK_INTERVAL_MS");
    }

    static int standingMaxHops() {
        return tuningInt("STANDING_MAX_HOPS");
    }

    static long requestTtlMs() {
        return tuningLong("REQUEST_TTL_MS");
    }

    static long shortTripCooldownMs() {
        return tuningLong("SHORT_TRIP_COOLDOWN_MS");
    }

    static long tripEtaBaseMs() {
        return tuningLong("TRIP_ETA_BASE_MS");
    }

    static long tripEtaPerHopMs() {
        return tuningLong("TRIP_ETA_PER_HOP_MS");
    }

    private static int tuningInt(String name) {
        return config.AgentTuning.intValue(TUNING_PREFIX + name);
    }

    private static long tuningLong(String name) {
        return config.AgentTuning.longValue(TUNING_PREFIX + name);
    }
}
