package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Readiness before an activity: the recovery potions, and the projectiles for a claw, bow, crossbow or gun, an Agent
 * carries into it (a party quest or a boss run), and the owner-free seam through which a host gives an Agent a
 * standing duty to keep them.
 *
 * <p>One standard serves everyone who asks: the host's entry desk reads {@link #shortfall(int, int, boolean, Activity)}
 * with the potion counts it already has and {@link #ammoShortfall(int, Activity)} with its projectile count, and the
 * Agent's own procurement reads {@link #shortfall(Character, Activity)}: {@link AgentPotionService#countPotions} and
 * {@link AgentAmmoService#carriedProjectiles} together (for a boss whose standard is HP and MP points,
 * {@link #recoveryStandard}, what its potions restore instead of how many it carries). While the installed
 * {@link Rule} answers {@link Rule#standing} for an Agent, the Agent walks to the nearest potion shop by itself on a
 * town map whose supplier is close (AgentSupplyReadinessRuntime) when it lacks either; one visit fills both, potions
 * first, buying toward {@code BUY_TO_PERCENT} of the potion standard ({@link #buyTarget}) and toward
 * {@link #ammoTarget} projectiles; it never goes when it cannot afford the trip, and says so through the rule instead
 * of asking its party; given mesos or supplies there that still do not pay for it, it tells the rule what it still
 * lacks ({@link Rule#stillShort}), and a gift that pays for it starts the trip. With nothing installed, nothing here
 * changes what an Agent does.</p>
 *
 * <p>The host's reads ({@link #status}, {@link #restockPending}, {@link #carriedBy}, {@link #checkSoon},
 * {@link #resumeFollowAfterTrip}) touch memory only and may run on any thread; the rule's callbacks run on the Agent
 * tick and must only touch memory and hand work over.</p>
 */
public final class AgentSupplyReadiness {
    private static final Logger log = LoggerFactory.getLogger(AgentSupplyReadiness.class);
    private static final String TUNING_PREFIX = "server.agents.capabilities.supplies.AgentSupplyReadiness.";
    private static final AtomicBoolean clampWarned = new AtomicBoolean();
    private static volatile Rule rule;

    private AgentSupplyReadiness() {
    }

    /** What an Agent gets ready for. */
    /**
     * What an Agent keeps itself ready for: a party quest, by its key (kpq, lpq, ...). {@link #PARTY_QUEST} (no key)
     * is the default standard, and a key without its own standard in PARTY_QUEST_STANDARDS uses it too.
     */
    public record Activity(String partyQuestKey) {
        public static final Activity PARTY_QUEST = new Activity("");

        public Activity {
            partyQuestKey = partyQuestKey == null ? "" : partyQuestKey.trim().toLowerCase(java.util.Locale.ROOT);
        }

        /** The party quest {@code key}'s activity; blank or null is {@link #PARTY_QUEST}. */
        public static Activity partyQuest(String key) {
            Activity activity = new Activity(key);
            return activity.partyQuestKey().isEmpty() ? PARTY_QUEST : activity;
        }
    }

    /** Recovery potions an Agent goes in with at least: HP potions, and MP potions when it fights with skills. */
    public record Standard(int hp, int mp) { }

    /**
     * Recovery an Agent goes into a boss run with at least, in HP and MP points rather than potions: what its recovery
     * potions restore together at its current maximum HP and MP ({@link #recoveryCarried}). BOSS_RECOVERY_STANDARDS.
     */
    public record RecoveryStandard(long hp, long mp) { }

    /**
     * How many HP and MP potions, and projectiles for its weapon, an Agent still lacks for a standard (0 when it has
     * enough). The desk's count overload measures potions only; the Agent's own count includes the projectiles, and
     * for a points standard ({@link #recoveryStandard}) gives HP and MP points instead of potions, which a shop turns
     * into a count of the potion it buys.
     */
    public record Shortfall(int hp, int mp, int ammo) {
        public static final Shortfall NONE = new Shortfall(0, 0, 0);

        /** Potions only: no projectiles lacking. */
        public Shortfall(int hp, int mp) {
            this(hp, mp, 0);
        }

        /** Nothing lacking at all. */
        public boolean none() {
            return potionsNone() && ammo <= 0;
        }

        /** No potions lacking, whatever the projectiles. */
        public boolean potionsNone() {
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

        /**
         * It did not set off ({@code missingMesos}: how much more the trip needs, CANNOT_AFFORD only). True when the
         * host said so (a line its owner hears); false when it kept quiet (the same news said too recently, or none to
         * say). A gift still to be told ({@link #stillShort}) is forgotten only after a refusal that was said. On the
         * Agent tick.
         */
        default boolean refused(int agentCharacterId, Activity activity, Refusal why, Shortfall shortfall,
                                int missingMesos) {
            return false;
        }

        /** Its readiness trip ended. On the Agent tick. */
        default void finished(int agentCharacterId, Activity activity, Outcome outcome) {
        }

        /**
         * After it could not pay for its trip ({@link #refused} CANNOT_AFFORD, or a trip that ended SHORT_MESOS), it
         * was given mesos, potions or projectiles where it stands (a trade, a drop it picked up, a host's gift), and
         * what it has still does not pay for the trip: {@code missingMesos} more ({@code shortfall}: what it lacks
         * now). At most once per STILL_SHORT_EVERY_MS; gifts in between are told together. A gift that pays for it
         * starts the trip ({@link #started}) instead, and one that leaves it lacking nothing is not told. Never on a
         * map it may not set off from ({@link #restocksFrom} false), and never for what it bought or earned itself (a
         * supply trip or shop visit of its own, time without a duty). When the same check finds a new refusal
         * ({@link #refused} CANNOT_AFFORD: the potions it was given lifted the cooldown, or it stands on another map
         * than the last refusal), this comes first. True when the host said a line: STILL_SHORT_EVERY_MS counts from a
         * said one, so news it kept quiet holds back no later gift. On the Agent tick.
         */
        default boolean stillShort(int agentCharacterId, Activity activity, Shortfall shortfall, int missingMesos) {
            return false;
        }

        /**
         * Whether the Agent may set off for a shop from {@code mapId} at all; false keeps it there whatever it lacks
         * (a boss's gate: its critical request only asks the party). Memory only, any thread.
         */
        default boolean restocksFrom(int agentCharacterId, int mapId) {
            return true;
        }

        /**
         * Whether the Agent may set off for a shop from {@code mapId} for {@code activity} although that map is no
         * town and has monsters (a hunting field, where a duty holder otherwise never leaves its anchor): true where
         * the host lets it top up on its way to that activity, from a store on that very map (Ant Tunnel Park's 24 Hr
         * Mobile Store on the way down to Easy Balrog). False by default. Memory only, any thread.
         */
        default boolean restocksOnField(int agentCharacterId, int mapId, Activity activity) {
            return false;
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

    /** The installed rule's answer to {@link Rule#restocksFrom}; true without a rule or if it failed. */
    public static boolean restocksFrom(int agentCharacterId, int mapId) {
        Rule current = rule;
        if (current == null) {
            return true;
        }
        try {
            return current.restocksFrom(agentCharacterId, mapId);
        } catch (RuntimeException failure) {
            log.debug("readiness rule failed to answer for Agent {} on map {}", agentCharacterId, mapId, failure);
            return true;
        }
    }

    /** The installed rule's answer to {@link Rule#restocksOnField}; false without a rule or if it failed. */
    public static boolean restocksOnField(int agentCharacterId, int mapId, Activity activity) {
        Rule current = rule;
        if (current == null || activity == null) {
            return false;
        }
        try {
            return current.restocksOnField(agentCharacterId, mapId, activity);
        } catch (RuntimeException failure) {
            log.debug("readiness rule failed to answer for Agent {} on field {}", agentCharacterId, mapId, failure);
            return false;
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

    static boolean refused(int agentCharacterId, Activity activity, Refusal why, Shortfall shortfall,
                           int missingMesos) {
        Rule current = rule;
        if (current == null) {
            return false;
        }
        try {
            return current.refused(agentCharacterId, activity, why, shortfall, missingMesos);
        } catch (RuntimeException failure) {
            log.warn("readiness rule failed on a refusal for Agent {}", agentCharacterId, failure);
            return false;
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

    static boolean stillShort(int agentCharacterId, Activity activity, Shortfall shortfall, int missingMesos) {
        Rule current = rule;
        if (current == null) {
            return false;
        }
        try {
            return current.stillShort(agentCharacterId, activity, shortfall, missingMesos);
        } catch (RuntimeException failure) {
            log.warn("readiness rule failed on a gift short of the trip for Agent {}", agentCharacterId, failure);
            return false;
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
        int[] own = activity == null ? null : partyQuestStandard(activity.partyQuestKey());
        if (own != null) {
            hp = own[0];
            mp = own[1];
        }
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

    /**
     * What {@code hpPotions} and {@code mpPotions} lack for {@code activity}'s standard ({@link #standard}), by count
     * whatever BOSS_RECOVERY_STANDARDS lists: a party quest desk's check. Pure.
     */
    public static Shortfall shortfall(int hpPotions, int mpPotions, boolean skillUser, Activity activity) {
        Standard standard = standard(activity);
        return new Shortfall(Math.max(0, standard.hp() - Math.max(0, hpPotions)),
                skillUser ? Math.max(0, standard.mp() - Math.max(0, mpPotions)) : 0);
    }

    /**
     * Projectiles a fighter carrying {@code carriedAmmo} for its weapon lacks for {@code activity}'s ammo standard
     * ({@link #ammoStandard}); 0 without an activity, or for one that shoots none ({@code Integer.MAX_VALUE}). Pure.
     * The host's desk and the standing duty both read it; the duty's trip buys them at the potion supplier, after the
     * potions.
     */
    public static int ammoShortfall(int carriedAmmo, Activity activity) {
        if (activity == null) {
            return 0;
        }
        long standard = ammoStandard(activity);
        return (int) Math.max(0L, standard - Math.max(0, carriedAmmo));
    }

    /**
     * The projectiles a fighter with a claw, bow, crossbow or gun goes into {@code activity} with at least: the count
     * AMMO_STANDARDS lists for its key ("lpq=1600,..."), else PARTY_QUEST_AMMO; 0 without an activity. Read from
     * agent-engine.yaml at every call; never negative.
     */
    public static int ammoStandard(Activity activity) {
        if (activity == null) {
            return 0;
        }
        Integer own = listedCount(activity.partyQuestKey(), config.AgentTuning.stringValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.AMMO_STANDARDS"));
        return Math.max(0, own != null ? own : tuningInt("PARTY_QUEST_AMMO"));
    }

    /**
     * How many projectiles a readiness trip's counter buys toward for {@code activity}: its standard, and at least one
     * above AMMO_LOW_WARN, so a companion restocked to a standard at the warning does not say it runs low at its first
     * throw; 0 for a standard of 0 (no check).
     */
    public static int ammoTarget(Activity activity) {
        int standard = ammoStandard(activity);
        return standard <= 0 ? 0 : Math.max(standard, AgentSupplyConfig.cfg.AMMO_LOW_WARN + 1);
    }

    /**
     * How many projectiles a readiness trip's counter buys toward once the standard is met, with what is left after
     * everything else: AMMO_BUY_TO_PERCENT of the standard, rounded up, and at least {@link #ammoTarget}; 0 for a
     * standard of 0. The headroom keeps a bow that just restocked from walking back for a handful of arrows after
     * its next hunt; the trip's checks and its end price the standard alone.
     */
    public static int ammoBuyTarget(Activity activity) {
        int target = ammoTarget(activity);
        if (target <= 0) {
            return 0;
        }
        long wanted = ((long) ammoStandard(activity) * Math.max(0, tuningInt("AMMO_BUY_TO_PERCENT")) + 99L) / 100L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(target, wanted));
    }

    /** The count {@code standards} ("key=count,...") lists for {@code key}, or null: blank, unlisted or malformed. */
    private static Integer listedCount(String key, String standards) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (String pair : standards == null ? new String[0] : standards.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !pair.substring(0, equals).trim().equalsIgnoreCase(key)) {
                continue;
            }
            try {
                return Integer.parseInt(pair.substring(equals + 1).trim());
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }

    /** What the Agent's projectiles for its equipped weapon ({@link #carriedAmmo}) lack for {@code activity}. */
    public static int ammoShortfall(Character agent, Activity activity) {
        return agent == null ? 0 : ammoShortfall(carriedAmmo(agent), activity);
    }

    /**
     * The projectiles in the Agent's bag for its equipped weapon (Soul Arrow and Shadow Claw do not fill an empty bag);
     * {@code Integer.MAX_VALUE} when it shoots none.
     */
    public static int carriedAmmo(Character agent) {
        return AgentAmmoService.carriedProjectiles(agent);
    }

    /**
     * What the Agent's own potions ({@link AgentPotionService#countPotions}) and projectiles ({@link #carriedAmmo})
     * lack for {@code activity}; for a key BOSS_RECOVERY_STANDARDS lists ({@link #recoveryStandard}), the HP and MP
     * points its recovery potions ({@link #recoveryCarried}) lack of that standard instead of the potions.
     */
    public static Shortfall shortfall(Character agent, Activity activity) {
        if (agent == null) {
            return Shortfall.NONE;
        }
        RecoveryStandard points = recoveryStandard(activity);
        if (points != null) {
            return recoveryShortfall(agent, activity, points);
        }
        int[] potions = agent.getInventory(InventoryType.USE) == null ? new int[]{0, 0}
                : AgentPotionService.countPotions(agent);
        Shortfall lacking = shortfall(potions[0], potions[1], needsManaPotions(agent), activity);
        return new Shortfall(lacking.hp(), lacking.mp(), ammoShortfall(agent, activity));
    }

    /**
     * The activity a shop visit buys toward: on a readiness trip under way, the activity that trip set off for (the
     * one its end is judged by); otherwise the standing duty the host answers for this Agent. At the counter the host
     * may answer another duty or none: travel clears the follow target a duty is read from, a chat can move its
     * interaction target, and a shop map is no desk map. Null: neither.
     */
    public static Activity shoppingActivity(AgentRuntimeEntry entry, Character agent) {
        Activity trip = tripActivity(entry);
        if (trip != null) {
            return trip;
        }
        return agent == null ? null : standing(agent.getId());
    }

    /**
     * The activity of the readiness trip under way (the one its end is judged by); null when none is. A shop visit
     * runs its readiness ammo step only then (AgentShopService.buyReadinessAmmo).
     */
    public static Activity tripActivity(AgentRuntimeEntry entry) {
        AgentSupplyReadinessState state = entry == null ? null
                : entry.capabilityStates().find(AgentSupplyReadinessState.STATE_KEY).orElse(null);
        if (state == null || state.request() == null) {
            return null;
        }
        return state.activity() == null ? Activity.PARTY_QUEST : state.activity();
    }

    /**
     * How many HP (or MP) potions a shop visit buys toward while the Agent keeps a standing duty: BUY_TO_PERCENT of
     * the standard, rounded up; 0 without a duty, and for MP when it fights without skills.
     */
    public static int buyTarget(Character agent, boolean forHp) {
        return buyTarget(agent == null ? null : standing(agent.getId()), agent, forHp);
    }

    /** As {@link #buyTarget(Character, boolean)}, for {@code activity} (0 when null). */
    public static int buyTarget(Activity activity, Character agent, boolean forHp) {
        if (activity == null || agent == null || !forHp && !needsManaPotions(agent)) {
            return 0;
        }
        Standard standard = standard(activity);
        long count = forHp ? standard.hp() : standard.mp();
        return (int) Math.min(Integer.MAX_VALUE, (count * Math.max(0, tuningInt("BUY_TO_PERCENT")) + 99L) / 100L);
    }

    /**
     * The {HP, MP} counts PARTY_QUEST_STANDARDS lists for {@code key} ("lpq=40/50,..."), else the ones BOSS_STANDARDS
     * lists ("easy_balrog=100/60"), or null: the key is blank or in neither, or its pair is malformed (skipped, the
     * default applies).
     */
    private static int[] partyQuestStandard(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int[] own = listed(key, config.AgentTuning.stringValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_STANDARDS"));
        return own != null ? own : listed(key, config.AgentTuning.stringValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_STANDARDS"));
    }

    // ---- a points standard (BOSS_RECOVERY_STANDARDS) ------------------------------------------

    /**
     * The HP and MP points BOSS_RECOVERY_STANDARDS lists for {@code activity}'s key ("easy_balrog=130000/32000"), or
     * null: no activity, a key not listed, or a malformed pair. With one, the Agent's own count
     * ({@link #shortfall(Character, Activity)}) measures what its recovery potions restore instead of how many it
     * carries, and a shop turns the gap into a count of the potion it buys ({@link #potionsFor}); without one the
     * potion counts ({@link #standard}) apply. Read from agent-engine.yaml at every call.
     */
    public static RecoveryStandard recoveryStandard(Activity activity) {
        if (activity == null || activity.partyQuestKey().isEmpty()) {
            return null;
        }
        int[] own = listed(activity.partyQuestKey(), config.AgentTuning.stringValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS"));
        return own == null ? null : new RecoveryStandard(own[0], Math.max(0, own[1]));
    }

    /**
     * The HP (or MP) the Agent's recovery potions restore together at its current maximum
     * (AgentPotionInventoryPolicy.recoveryCapacity, the measure of an ordinary restock's reserve); 0 without a Use tab.
     */
    public static long recoveryCarried(Character agent, boolean forHp) {
        Inventory use = agent == null ? null : agent.getInventory(InventoryType.USE);
        return use == null ? 0L : AgentPotionInventoryPolicy.recoveryCapacity(use.list(),
                AgentUseItemClassificationPolicy::itemEffect, agent.getCurrentMaxHp(), agent.getCurrentMaxMp(), forHp);
    }

    /**
     * The HP (or MP) points a shop visit tops up toward for {@code activity}'s points standard: BUY_TO_PERCENT of it,
     * rounded up; 0 without a points standard, and for MP when the Agent fights without skills.
     */
    public static long recoveryBuyTarget(Activity activity, Character agent, boolean forHp) {
        RecoveryStandard points = recoveryStandard(activity);
        if (points == null || agent == null || !forHp && !needsManaPotions(agent)) {
            return 0L;
        }
        long standard = forHp ? points.hp() : points.mp();
        return (standard * Math.max(0, tuningInt("BUY_TO_PERCENT")) + 99L) / 100L;
    }

    /**
     * The potions restoring {@code perPotion} points each that hold {@code points}, rounded up: how a shop turns a
     * points gap into a count of the potion it buys. 0 for no points, and for a potion that restores none.
     */
    public static int potionsFor(long points, int perPotion) {
        if (points <= 0L || perPotion <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (points + perPotion - 1L) / perPotion);
    }

    /** What the Agent's recovery ({@link #recoveryCarried}) lacks of {@code points}: HP, and MP for a skill user. */
    private static Shortfall recoveryShortfall(Character agent, Activity activity, RecoveryStandard points) {
        long hp = Math.max(0L, points.hp() - recoveryCarried(agent, true));
        long mp = needsManaPotions(agent) ? Math.max(0L, points.mp() - recoveryCarried(agent, false)) : 0L;
        return new Shortfall((int) Math.min(Integer.MAX_VALUE, hp), (int) Math.min(Integer.MAX_VALUE, mp),
                ammoShortfall(agent, activity));
    }

    /** The {HP, MP} counts {@code standards} ("key=HP/MP,...") lists for {@code key}, or null. */
    private static int[] listed(String key, String standards) {
        for (String pair : standards == null ? new String[0] : standards.split(",")) {
            int equals = pair.indexOf('=');
            int slash = pair.indexOf('/', equals + 1);
            if (equals < 0 || slash < 0 || !pair.substring(0, equals).trim().equalsIgnoreCase(key)) {
                continue;
            }
            try {
                return new int[]{Math.max(0, Integer.parseInt(pair.substring(equals + 1, slash).trim())),
                        Integer.parseInt(pair.substring(slash + 1).trim())};
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }

    // ---- what a host reads and nudges (memory only, any thread) ------------------------------

    /**
     * RESTOCKING while a potion trip is under way (a readiness trip's own estimate, at least a check interval; another
     * potion trip's from its hop budget); otherwise the last readiness verdict when it was found on the map the Agent
     * stands on; otherwise, while it keeps a duty, the cooldown that still holds it back since it last looked
     * (CANNOT_AFFORD with what it still lacks, or NO_SPACE: it was in a run meanwhile, or came over from another map);
     * otherwise NONE.
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
        Character agent = entry.bot();
        AgentSupplyReadinessState.Verdict verdict = readiness.verdictOn(agent.getMapId());
        if (verdict == null) {
            return heldBack(readiness, agent, nowMs);
        }
        return switch (verdict) {
            case CANNOT_AFFORD -> new Status(StatusKind.CANNOT_AFFORD, 0L, readiness.verdictMissingMesos());
            case NO_SPACE -> new Status(StatusKind.NO_SPACE, 0L, 0);
            case NO_SUPPLIER -> new Status(StatusKind.NO_SUPPLIER, 0L, 0);
        };
    }

    /** What a cooldown set before still says about an Agent with a duty that has not looked on this map yet. */
    private static Status heldBack(AgentSupplyReadinessState readiness, Character agent, long nowMs) {
        Activity activity = standing(agent.getId());
        if (activity == null) {
            return Status.NONE;
        }
        int mesos = AgentSupplyReadinessRuntime.mesos(agent);
        AgentSupplyReadinessState.Cooldown cooldown = readiness.cooldownShown(nowMs, mesos,
                AgentSupplyReadinessRuntime.freeUseSlots(agent), shortfall(agent, activity), agent.getMapId());
        if (cooldown == null) {
            return Status.NONE;
        }
        return switch (cooldown) {
            case CANNOT_AFFORD, SHORT_MESOS -> new Status(StatusKind.CANNOT_AFFORD, 0L,
                    readiness.cooldownMissingMesos(mesos));
            case NO_SPACE, SHORT_SPACE -> new Status(StatusKind.NO_SPACE, 0L, 0);
            case INTERRUPTED, GAVE_UP -> Status.NONE;
        };
    }

    /**
     * True while the Agent's own restock decides on its next check: it keeps a duty, lacks potions, is free to go
     * (AgentSupplyReadinessRuntime.free), and no supply trip, verdict on this map or cooldown stands in the way. A host
     * leaves the asking to it meanwhile. Memory only, any thread.
     */
    public static boolean restockPending(AgentRuntimeEntry entry, long nowMs) {
        Character agent = entry == null ? null : entry.bot();
        Activity activity = agent == null ? null : standing(agent.getId());
        if (activity == null || entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false)) {
            return false;
        }
        Shortfall lacking = shortfall(agent, activity);
        if (lacking.none() || !AgentSupplyReadinessRuntime.free(entry, agent, activity)) {
            return false;
        }
        AgentSupplyReadinessState readiness =
                entry.capabilityStates().find(AgentSupplyReadinessState.STATE_KEY).orElse(null);
        return readiness == null || readiness.verdictOn(agent.getMapId()) == null
                && readiness.cooldownShown(nowMs, AgentSupplyReadinessRuntime.mesos(agent),
                AgentSupplyReadinessRuntime.freeUseSlots(agent), lacking, agent.getMapId()) == null;
    }

    /**
     * True while the Agent goes along with {@code leaderCharacterId}: it follows them (its follow target, or with none
     * its interaction target, is them), or is on a supply trip that ends by following them again. An Agent told to
     * wait, sent somewhere or hunting on its own is not. Memory only, any thread.
     */
    public static boolean carriedBy(AgentRuntimeEntry entry, int leaderCharacterId) {
        if (entry == null) {
            return false;
        }
        boolean goesAlong = AgentModeStateRuntime.following(entry)
                || entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(trip -> trip.isActive() && trip.resumeFollow()).orElse(false);
        Character anchor = goesAlong ? AgentSupplyReadinessRuntime.followAnchor(entry) : null;
        return anchor != null && anchor.getId() == leaderCharacterId;
    }

    /**
     * True while the Agent keeps a duty and cannot pay for its readiness trip ({@link #status} CANNOT_AFFORD: its
     * refusal on this map, or a meso cooldown it carried here): it buys nothing on its own until its purse grows by
     * what it lacked or it lacks fewer potions, so what it holds more of meanwhile was given. Memory only, any thread.
     */
    public static boolean heldForMesos(AgentRuntimeEntry entry, long nowMs) {
        return status(entry, nowMs).cannotAfford();
    }

    /** The Agent looks at its potions on its next tick instead of at its next check interval. */
    public static void checkSoon(AgentRuntimeEntry entry) {
        if (entry != null) {
            entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).checkSoon();
        }
    }

    /**
     * A host let the Agent follow again while it is on a supply trip: once the trip is over, it rejoins by following
     * (a follow warp) instead of walking back to the map it left. False when no trip is under way any more (it ended
     * meanwhile, in the same lock as this check): the host then lets it follow itself.
     */
    public static boolean resumeFollowAfterTrip(AgentRuntimeEntry entry) {
        return entry != null && entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::resumeFollowIfActive).orElse(false);
    }

    // ---- tuning (agent-engine.yaml), read at call time ----------------------------------------

    static long checkIntervalMs() {
        return tuningLong("CHECK_INTERVAL_MS");
    }

    static int standingMaxHops() {
        return tuningInt("STANDING_MAX_HOPS");
    }

    static int deskMaxHops() {
        return tuningInt("DESK_MAX_HOPS");
    }

    /**
     * P12 G9: a boss gate listed in BOSS_DESK_MAPS (the Papulatus gate, 220080000) is a desk like a party quest's: a
     * trip from it may reach DESK_MAX_HOPS, both ways. Only a gate the host lets trips leave from sends one.
     * The list takes ids and id ranges, as AgentEngineConfigValidator allows.
     */
    static boolean bossDesk(int mapId) {
        String raw = config.AgentTuning.stringValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_DESK_MAPS");
        if (raw == null || "none".equals(raw.trim())) {
            return false;
        }
        for (String entry : raw.split(",")) {
            int dash = entry.indexOf('-');
            int first = Integer.parseInt(entry.substring(0, dash < 0 ? entry.length() : dash).trim());
            int last = dash < 0 ? first : Integer.parseInt(entry.substring(dash + 1).trim());
            if (mapId >= first && mapId <= last) {
                return true;
            }
        }
        return false;
    }

    /** How long a readiness request to a supplier {@code hops} away stays good: REQUEST_TTL_MS, longer for far ones. */
    static long requestTtlMs(int hops) {
        return Math.max(tuningLong("REQUEST_TTL_MS"), (long) Math.max(0, hops) * tuningLong("REQUEST_TTL_PER_HOP_MS"));
    }

    static long shortTripCooldownMs() {
        return tuningLong("SHORT_TRIP_COOLDOWN_MS");
    }

    static long stillShortEveryMs() {
        return tuningLong("STILL_SHORT_EVERY_MS");
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
