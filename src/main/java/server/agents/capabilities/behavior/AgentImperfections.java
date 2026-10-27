package server.agents.capabilities.behavior;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.life.Monster;
import server.maps.MapleMap;

/**
 * Small human-looking imperfections with hard caps (docs/BOT_CONCEPT_PLAN.md F8). The Agent engine asks here where a
 * player's habits show: the HP point it drinks at, a drink that comes a beat late, when it recasts a buff, how long it
 * takes to go for a new mob, a cheap drop it walks past, a rope grab it misses, an NPC it walks past, how long it lies
 * dead hoping for a Resurrection. The host (the living world's Imperfections gate) installs the rule that says which
 * Agents show them and spends each one's budget. With no rule installed every answer is today's behaviour, exactly.
 *
 * <p>The safety caps are the engine's, not the rule's: nothing here answers anything but today's behaviour inside an
 * event instance, on a map with a living boss or a field boss, or for an Agent in a party quest, whatever the rule
 * says; each call site keeps its own hard cap too (the late drink's HP floor, survival buffs, the 10 s Resurrection
 * wait). Any thread, memory only.</p>
 */
public final class AgentImperfections {
    /** The slips the rule spends budget on; the calibrations (potion point, reaction time, buff timing) spend none. */
    public enum Slip {
        /** A non-critical autopot drink waits a beat (F8.2). */
        LATE_POTION,
        /** A non-critical buff runs out before the recast (F8.3). */
        BUFF_LAPSE,
        /** In town: a jump beside a rope that does not catch it, then the real grab (F8.5). */
        ROPE_MISS,
        /** In town: walks a little past the NPC it went to, then back (F8.5). */
        NPC_OVERSHOOT,
        /** A cheap drop left on the ground (F8.5). */
        LOOT_SLIP,
        /** Lies dead a little longer while a party member who has Resurrection may cast it ("rez pls?", F8.6). */
        REZ_WAIT
    }

    /** The installed rule. */
    public interface Rule {
        /**
         * True when this Agent shows the human calibrations (its own potion point, a reaction time to new mobs, buff
         * timing spread, a bishop's Resurrection for its party): the host's switch is on and the Agent is one of its
         * own (a citizen or a companion), not in its host's party content.
         */
        boolean calibrated(Character agent);

        /**
         * True when this Agent slips this once: calibrated, its HP above the guard, its owner not in danger, the slip's
         * chance came up and the hourly budget has room. A true spends budget.
         */
        boolean slips(Character agent, Slip slip);

        /**
         * True when this Agent leaves this drop: cheap by the host's price index, the chance and budget allow it; the
         * same answer every time it is asked about the same drop.
         */
        boolean leavesDrop(Character agent, int itemId, int dropObjectId);

        /** The HP point (0 to 1) this calibrated Agent drinks at instead of {@code base}, which has no owner's answer. */
        float personalHpAlert(Character agent, float base);

        /** A dead Agent now waits for a Resurrection ({@link Slip#REZ_WAIT} came up): the host says so. */
        default void waitingForRez(Character agent) {
        }

        /** A dead Agent was revived in place by a Resurrection: the host may thank its party. */
        default void revived(Character agent) {
        }
    }

    /** A dead Agent never waits longer than this for a Resurrection before it respawns as it always does. */
    public static final long REZ_WAIT_MAX_MS = 10_000L;
    /** A late drink waits this long at least and at most. */
    public static final long LATE_DRINK_MIN_MS = 300L;
    public static final long LATE_DRINK_MAX_MS = 1_500L;
    /** No calibrated potion point goes below this unless its base already does (the owner-habit floor too). */
    public static final float POTION_FLOOR = 0.45f;

    private static volatile Rule rule;

    private AgentImperfections() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** True while a rule is installed, so a caller can skip work only an imperfection needs. */
    public static boolean on() {
        return rule != null;
    }

    /** True when {@code agent} shows the human calibrations now (never in excluded content). */
    public static boolean calibrated(Character agent) {
        Rule current = rule;
        return current != null && agent != null && !excluded(agent) && current.calibrated(agent);
    }

    /** True when {@code agent} slips this once (never in excluded content); a true spends its budget. */
    public static boolean slips(Character agent, Slip slip) {
        Rule current = rule;
        return current != null && agent != null && !excluded(agent) && current.slips(agent, slip);
    }

    /** The HP point {@code agent} drinks at when nothing set one: {@code base}, or its own point near it. */
    public static float hpAlert(Character agent, float base) {
        Rule current = rule;
        if (current == null || agent == null || base <= 0f || excluded(agent) || !current.calibrated(agent)) {
            return base;
        }
        float own = current.personalHpAlert(agent, base);
        return Math.max(Math.min(base, POTION_FLOOR), Math.min(1f, own));
    }

    /** True when {@code agent} leaves this drop on the ground; asked only for drops the engine allows a slip on. */
    public static boolean leavesDrop(Character agent, int itemId, int dropObjectId) {
        Rule current = rule;
        return current != null && agent != null && !excluded(agent)
                && current.leavesDrop(agent, itemId, dropObjectId);
    }

    public static void waitingForRez(Character agent) {
        Rule current = rule;
        if (current != null && agent != null) {
            current.waitingForRez(agent);
        }
    }

    public static void revived(Character agent) {
        Rule current = rule;
        if (current != null && agent != null) {
            current.revived(agent);
        }
    }

    /**
     * Content where nothing slips and nothing is calibrated: an event instance (every party quest, expedition and boss
     * run), a map with a living boss (a field boss included), a party quest the Agent is in.
     */
    public static boolean excluded(Character agent) {
        MapleMap map = agent.getMap();
        if (agent.getEventInstance() != null || map == null || map.getEventInstance() != null) {
            return true;
        }
        // the boss scan and the party-quest lookup are kept a moment per Agent and map: the loot check asks per drop
        long now = System.currentTimeMillis();
        ContentCheck seen = CONTENT_CHECKS.get(agent.getId());
        if (seen != null && seen.map == map && now < seen.untilMs) {
            return seen.excluded;
        }
        boolean excluded = bossOn(map) || AgentPartyQuestRuntime.active(agent.getId());
        CONTENT_CHECKS.put(agent.getId(), new ContentCheck(map, now + CONTENT_CHECK_CACHE_MS, excluded));
        return excluded;
    }

    private record ContentCheck(MapleMap map, long untilMs, boolean excluded) {
    }

    private static final java.util.Map<Integer, ContentCheck> CONTENT_CHECKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CONTENT_CHECK_CACHE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.behavior.AgentImperfections.CONTENT_CHECK_CACHE_MS");

    private static boolean bossOn(MapleMap map) {
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && monster.isBoss()) {
                return true;
            }
        }
        return AgentBossField.near(map);
    }
}
