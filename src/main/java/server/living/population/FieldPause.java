package server.living.population;

import client.Character;
import server.agents.capabilities.combat.AgentCombatTargetEligibilityPolicy;
import server.agents.perception.AgentMapPerception;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Random;

/**
 * Decision D6 (docs/BOT_CONCEPT_PLAN.md F8; the user's rule: no long idling, no extra deaths): a citizen that goes AFK
 * on a training field only pauses ("brb water"), 30 s to population.FIELD_AFK_MAX_MS, and only where nothing can hurt
 * it; the presence sweep ends the pause early when it is hit low or a mob comes close. Towns, Free Market rooms and
 * lobbies keep the long AFK.
 */
final class FieldPause {
    static final long MIN_MS = 30_000L;
    /** The tell audit reads a field AFK over this as a tell; no pause is planned longer. */
    static final long HARD_MAX_MS = 120_000L;
    static final double START_MIN_HP = 0.8d;
    static final int START_CLEAR_PX = 400;
    static final double END_BELOW_HP = 0.6d;
    static final int END_NEAR_PX = 150;

    private FieldPause() {
    }

    /** How long an AFK planned on a map of kind {@code where} lasts: a field's pause, else the town AFK of 5-15 min. */
    static long durationMs(String where, int fieldMaxMs, Random random) {
        if (!"field".equals(where) || fieldMaxMs <= 0) {
            return (5 + random.nextInt(11)) * 60_000L;
        }
        long max = Math.min(HARD_MAX_MS, Math.max(MIN_MS, fieldMaxMs));
        return MIN_MS + (long) (random.nextDouble() * (max - MIN_MS));
    }

    /** A pause may start: HP at 80% or more and no hostile mob within 400 px (a ledger-only citizen always may). */
    static boolean safeToStart(Character agent) {
        if (agent == null) {
            return true;
        }
        int maxHp = agent.getCurrentMaxHp();
        return agent.isAlive() && maxHp > 0 && agent.getHp() >= START_MIN_HP * maxHp && !mobWithin(agent, START_CLEAR_PX);
    }

    /** A running pause ends now: hit under 60% HP, dead, or a hostile mob within 150 px. */
    static boolean endsEarly(Character agent) {
        if (agent == null) {
            return false;
        }
        int maxHp = agent.getCurrentMaxHp();
        return !agent.isAlive() || maxHp <= 0 || agent.getHp() < END_BELOW_HP * maxHp || mobWithin(agent, END_NEAR_PX);
    }

    private static boolean mobWithin(Character agent, int px) {
        MapleMap map = agent.getMap();
        Point here = agent.getPosition();
        if (map == null || here == null) {
            return false;
        }
        for (Monster mob : AgentMapPerception.monsters(map)) {
            Point there = mob.getPosition();
            if (AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(mob)
                    && (there == null || there.distanceSq(here) <= (double) px * px)) {
                return true;
            }
        }
        return false;
    }
}
