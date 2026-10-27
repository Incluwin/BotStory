package server.agents.capabilities.behavior;

import client.BuffStat;
import client.Character;
import server.StatEffect;
import server.agents.capabilities.combat.AgentCombatTargetEligibilityPolicy;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.agents.perception.AgentMapPerception;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntBiFunction;

/**
 * The numbers behind the imperfections (docs/BOT_CONCEPT_PLAN.md F8) and the caps that make them safe, apart from who
 * shows them (AgentImperfections' rule). Pure and memory only, so the guard tests prove each cap without a host.
 */
public final class AgentImperfectionPolicy {
    private static final String TUNING_PREFIX = "server.agents.capabilities.behavior.AgentImperfectionPolicy.";
    /** A reaction to a new mob is never quicker than this (the tell audit's floor is 150 ms); the cap above it is
     * AVAILABLE_MOB_RESPONSE_MAX_MS. A hard bound, not a tuning. */
    public static final int REACTION_MIN_MS = 180;
    /** Each Agent's own typical reaction lies in this range (by its id), spread around it log-normally. */
    static final int REACTION_BASE_MIN_MS = tuningInt("REACTION_BASE_MIN_MS");
    static final int REACTION_BASE_SPAN_MS = tuningInt("REACTION_BASE_SPAN_MS");
    static final double REACTION_SIGMA = tuningDouble("REACTION_SIGMA");

    /** Each Agent's own potion point lies this far around the configured one: 0.60-0.75 around 0.70. */
    static final float POTION_BAND_BELOW = (float) tuningDouble("POTION_BAND_BELOW");
    static final float POTION_BAND_ABOVE = (float) tuningDouble("POTION_BAND_ABOVE");

    /** A late drink only while HP is at this share of max or more (the plan's cap)... */
    public static final double LATE_DRINK_MIN_HP = 0.50d;
    /** ...and no mob in reach could take it below this share with one hit (its formula maximum times the margin). */
    public static final double LATE_DRINK_HIT_FLOOR = 0.40d;
    static final double WORST_HIT_MARGIN = tuningDouble("WORST_HIT_MARGIN");
    static final int DANGER_RANGE_X_PX = tuningInt("DANGER_RANGE_X_PX");
    static final int DANGER_RANGE_Y_PX = tuningInt("DANGER_RANGE_Y_PX");

    /** A buff is recast at this share of its duration at the earliest; survival buffs at most at the usual 90%. */
    public static final double BUFF_REFRESH_MIN_SHARE = 0.85d;
    public static final double BUFF_REFRESH_SHARE = 0.9d;
    /** A lapsed buff is back up within this long after it ran out. */
    public static final long BUFF_LAPSE_MIN_MS = 2_000L;
    public static final long BUFF_LAPSE_MAX_MS = 10_000L;
    /**
     * The only stats a buff may lapse on: attack, accuracy, speed and the like. A buff carrying anything else (a guard,
     * defence, Hyper Body, invincibility, a charge, the combo, a summon, Shadow Partner) is a survival buff here.
     */
    static final Set<BuffStat> LAPSABLE = EnumSet.of(BuffStat.WATK, BuffStat.MATK, BuffStat.ACC, BuffStat.SPEED,
            BuffStat.JUMP, BuffStat.BOOSTER, BuffStat.SHARP_EYES, BuffStat.SOULARROW, BuffStat.HANDS,
            BuffStat.CONCENTRATE, BuffStat.MAPLE_WARRIOR, BuffStat.HOLY_SYMBOL, BuffStat.ECHO_OF_HERO, BuffStat.MESOUP);

    private AgentImperfectionPolicy() {
    }

    // ---- reaction to a new mob (F8.1) ------------------------------------------------------------

    /**
     * How long this Agent takes to go for a new set of mobs: its own typical time (230-380 ms by its id) spread
     * log-normally per sighting, within [REACTION_MIN_MS, maxMs]. The same sighting gives the same answer.
     */
    public static int reactionDelayMs(int agentId, long stimulus, int maxMs) {
        int base = REACTION_BASE_MIN_MS + Math.floorMod(mix(agentId, 0x51ED27L), REACTION_BASE_SPAN_MS);
        double spread = Math.exp(REACTION_SIGMA * new Random(mix(agentId, stimulus)).nextGaussian());
        int delay = (int) Math.round(base * spread);
        return Math.max(REACTION_MIN_MS, Math.min(Math.max(REACTION_MIN_MS, maxMs), delay));
    }

    // ---- potions (F8.2) ---------------------------------------------------------------------------

    /** This Agent's own HP point around {@code base}: base-0.10 to base+0.05 by its id, never below {@code floor}. */
    public static float personalHpAlert(int agentId, float base, float floor) {
        int step = Math.floorMod(mix(agentId, 0x9071L), 16); // 0.01 steps over the band
        float own = base - POTION_BAND_BELOW + step * (POTION_BAND_BELOW + POTION_BAND_ABOVE) / 15f;
        return Math.max(Math.min(base, floor), Math.min(1f, own));
    }

    /** How long a late drink waits, from a uniform draw in [0, 1). */
    public static long lateDrinkDelayMs(double draw) {
        double clamped = Math.max(0d, Math.min(1d, draw));
        return AgentImperfections.LATE_DRINK_MIN_MS
                + Math.round(clamped * (AgentImperfections.LATE_DRINK_MAX_MS - AgentImperfections.LATE_DRINK_MIN_MS));
    }

    /** Whether a drink may wait now: see {@link #lateDrinkSafe(Character, ToIntBiFunction)}, the live hit estimate. */
    public static boolean lateDrinkSafe(Character agent) {
        return lateDrinkSafe(agent, AgentDefenseDataProvider.getInstance()::maxIncomingHit);
    }

    /**
     * True while a drink may wait: alive with HP at LATE_DRINK_MIN_HP of max or more, no boss on the map, and no hostile
     * mob within reach whose worst hit (times WORST_HIT_MARGIN) would take HP under LATE_DRINK_HIT_FLOOR of max.
     */
    public static boolean lateDrinkSafe(Character agent, ToIntBiFunction<Character, Monster> worstHit) {
        int maxHp = agent == null ? 0 : agent.getCurrentMaxHp();
        MapleMap map = agent == null ? null : agent.getMap();
        Point at = agent == null ? null : agent.getPosition();
        if (maxHp <= 0 || map == null || at == null || !agent.isAlive() || agent.getHp() < LATE_DRINK_MIN_HP * maxHp) {
            return false;
        }
        double room = agent.getHp() - LATE_DRINK_HIT_FLOOR * maxHp;
        for (Monster mob : AgentMapPerception.monsters(map)) {
            if (!AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(mob)) {
                continue;
            }
            if (mob.isBoss()) {
                return false;
            }
            Point there = mob.getPosition();
            if (there == null) {
                return false;
            }
            if (Math.abs(there.x - at.x) > DANGER_RANGE_X_PX || Math.abs(there.y - at.y) > DANGER_RANGE_Y_PX) {
                continue;
            }
            if (WORST_HIT_MARGIN * worstHit.applyAsInt(agent, mob) > room) {
                return false;
            }
        }
        return true;
    }

    // ---- buffs (F8.3) ------------------------------------------------------------------------------

    /** True when every stat the buff carries may lapse a few seconds (attack, accuracy, speed...): no survival stat. */
    public static boolean lapsable(StatEffect effect) {
        if (effect == null || effect.getStatups().isEmpty()) {
            return false;
        }
        for (var statup : effect.getStatups()) {
            if (!LAPSABLE.contains(statup.getLeft())) {
                return false;
            }
        }
        return true;
    }

    /**
     * When a calibrated Agent recasts a buff it just cast: survival buffs anywhere in 85-90% of the duration (never
     * later than today), the rest in 85-100%; a lapse (only for a lapsable buff, {@code lapse} true) 2-10 s after it ran
     * out. {@code draw} is uniform in [0, 1).
     */
    public static long refreshAt(long nowMs, long durationMs, boolean survival, boolean lapse, double draw) {
        double clamped = Math.max(0d, Math.min(1d, draw));
        if (lapse && !survival) {
            return nowMs + durationMs + BUFF_LAPSE_MIN_MS + Math.round(clamped * (BUFF_LAPSE_MAX_MS - BUFF_LAPSE_MIN_MS));
        }
        double top = survival ? BUFF_REFRESH_SHARE : 1d;
        return nowMs + (long) (durationMs * (BUFF_REFRESH_MIN_SHARE + clamped * (top - BUFF_REFRESH_MIN_SHARE)));
    }

    private static int tuningInt(String name) {
        return config.AgentTuning.intValue(TUNING_PREFIX + name);
    }

    private static double tuningDouble(String name) {
        return config.AgentTuning.doubleValue(TUNING_PREFIX + name);
    }

    private static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
