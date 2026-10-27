package server.agents.capabilities.behavior;

import client.BuffStat;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F8 guard tests on the engine side (docs/BOT_CONCEPT_PLAN.md F8): with no rule every answer is today's; nothing is
 * calibrated or slips in an event instance, beside a boss or in a party quest whatever the rule says; and the caps the
 * engine keeps itself hold: the potion floor, the reaction band, the late drink's HP and worst-hit guards, survival buffs
 * never later than 90%, a lapse at most 10 s.
 */
class AgentImperfectionsTest {
    private final List<AgentImperfections.Slip> asked = new ArrayList<>();

    @AfterEach
    void off() {
        AgentImperfections.install(null);
    }

    private void install(float ownPoint) {
        AgentImperfections.install(new AgentImperfections.Rule() {
            @Override public boolean calibrated(Character agent) { return true; }
            @Override public boolean slips(Character agent, AgentImperfections.Slip slip) { asked.add(slip); return true; }
            @Override public boolean leavesDrop(Character agent, int itemId, int dropObjectId) { return true; }
            @Override public float personalHpAlert(Character agent, float base) { return ownPoint; }
        });
    }

    private static MapleMap map(Monster... mobs) {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of(mobs));
        return map;
    }

    private static Character agent(MapleMap map, int hp, int maxHp) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(40_401);
        when(agent.getMap()).thenReturn(map);
        when(agent.getHp()).thenReturn(hp);
        when(agent.getCurrentMaxHp()).thenReturn(maxHp);
        when(agent.isAlive()).thenReturn(hp > 0);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        return agent;
    }

    private static Monster mob(boolean boss, int x) {
        Monster mob = mock(Monster.class);
        when(mob.isAlive()).thenReturn(true);
        when(mob.isBoss()).thenReturn(boss);
        when(mob.getPosition()).thenReturn(new Point(x, 0));
        return mob;
    }

    @Test
    void withNoRuleEveryAnswerIsTodays() {
        Character agent = agent(map(), 100, 100);
        assertFalse(AgentImperfections.on());
        assertEquals(0.7f, AgentImperfections.hpAlert(agent, 0.7f));
        assertFalse(AgentImperfections.calibrated(agent));
        assertFalse(AgentImperfections.slips(agent, AgentImperfections.Slip.LATE_POTION));
        assertFalse(AgentImperfections.leavesDrop(agent, 4000000, 1));
    }

    @Test
    void nothingSlipsOrIsCalibratedInAnEventInstanceBesideABossOrInAPartyQuest() {
        install(0.62f);
        Character inEvent = agent(map(), 100, 100);
        when(inEvent.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        Character besideBoss = agent(map(mob(true, 2_000)), 100, 100);
        for (Character agent : List.of(inEvent, besideBoss)) {
            assertFalse(AgentImperfections.calibrated(agent));
            for (AgentImperfections.Slip slip : AgentImperfections.Slip.values()) {
                assertFalse(AgentImperfections.slips(agent, slip), slip.name());
            }
            assertFalse(AgentImperfections.leavesDrop(agent, 4000000, 7));
            assertEquals(0.7f, AgentImperfections.hpAlert(agent, 0.7f), "boss settings untouched");
        }
        assertTrue(asked.isEmpty(), "the rule is never even asked there");

        Character onAField = agent(map(mob(false, 300)), 100, 100);
        assertTrue(AgentImperfections.calibrated(onAField));
        assertEquals(0.62f, AgentImperfections.hpAlert(onAField, 0.7f));
    }

    @Test
    void noOwnPotionPointGoesUnderTheFloorUnlessItsBaseAlreadyDoes() {
        install(0.10f);
        Character agent = agent(map(), 100, 100);
        assertEquals(AgentImperfections.POTION_FLOOR, AgentImperfections.hpAlert(agent, 0.7f));
        assertEquals(0.40f, AgentImperfections.hpAlert(agent, 0.40f));
        for (int id = 0; id < 500; id++) {
            float own = AgentImperfectionPolicy.personalHpAlert(id, 0.7f, 0.45f);
            assertTrue(own >= 0.60f - 1e-6f && own <= 0.75f + 1e-6f, "band 0.60-0.75: " + own);
            assertTrue(AgentImperfectionPolicy.personalHpAlert(id, 0.5f, 0.45f) >= 0.45f - 1e-6f);
        }
    }

    @Test
    void reactionsStayInTheirBandAndSpreadLikeAPersonsAcrossSightings() {
        double cvSum = 0;
        int bots = 40;
        for (int bot = 1; bot <= bots; bot++) {
            double sum = 0;
            double squares = 0;
            int n = 200;
            for (long sighting = 1; sighting <= n; sighting++) {
                int delay = AgentImperfectionPolicy.reactionDelayMs(bot, sighting * 7919L, 500);
                assertTrue(delay >= AgentImperfectionPolicy.REACTION_MIN_MS && delay <= 500, "delay " + delay);
                assertEquals(delay, AgentImperfectionPolicy.reactionDelayMs(bot, sighting * 7919L, 500));
                sum += delay;
                squares += (double) delay * delay;
            }
            double mean = sum / n;
            cvSum += Math.sqrt(squares / n - mean * mean) / mean;
        }
        assertTrue(cvSum / bots >= 0.25d, "per-bot CV " + cvSum / bots + " (the tell audit's band)");
    }

    @Test
    void aLateDrinkWaits300To1500Ms() {
        assertEquals(AgentImperfections.LATE_DRINK_MIN_MS, AgentImperfectionPolicy.lateDrinkDelayMs(0d));
        assertEquals(AgentImperfections.LATE_DRINK_MAX_MS, AgentImperfectionPolicy.lateDrinkDelayMs(1d));
        assertEquals(AgentImperfections.LATE_DRINK_MAX_MS, AgentImperfectionPolicy.lateDrinkDelayMs(7d));
    }

    @Test
    void noLateDrinkUnderHalfHpOrWithAMobThatCouldTakeItUnderFortyPercent() {
        assertFalse(AgentImperfectionPolicy.lateDrinkSafe(agent(map(), 49, 100), (bot, mob) -> 1), "under 50% HP");
        assertTrue(AgentImperfectionPolicy.lateDrinkSafe(agent(map(), 50, 100), (bot, mob) -> 1));
        // at 100/100 the room is 60 HP: a worst hit of 39 (x1.5 = 58.5) may wait, 41 (61.5) may not
        assertTrue(AgentImperfectionPolicy.lateDrinkSafe(agent(map(mob(false, 100)), 100, 100), (bot, mob) -> 39));
        assertFalse(AgentImperfectionPolicy.lateDrinkSafe(agent(map(mob(false, 100)), 100, 100), (bot, mob) -> 41));
        assertTrue(AgentImperfectionPolicy.lateDrinkSafe(agent(map(mob(false, 5_000)), 100, 100), (bot, mob) -> 999),
                "a mob out of reach is not weighed");
        assertFalse(AgentImperfectionPolicy.lateDrinkSafe(agent(map(mob(true, 5_000)), 100, 100), (bot, mob) -> 1),
                "never beside a boss");
        assertFalse(AgentImperfectionPolicy.lateDrinkSafe(agent(map(), 0, 100), (bot, mob) -> 1));
    }

    @Test
    void survivalBuffsAreNeverRecastLaterThanNinetyPercentAndALapseIsAtMostTenSeconds() {
        StatEffect attack = effect(BuffStat.WATK);
        StatEffect guard = effect(BuffStat.MAGIC_GUARD);
        StatEffect mixed = effect(BuffStat.WATK, BuffStat.WDEF);
        assertTrue(AgentImperfectionPolicy.lapsable(attack));
        assertFalse(AgentImperfectionPolicy.lapsable(guard));
        assertFalse(AgentImperfectionPolicy.lapsable(mixed));
        assertFalse(AgentImperfectionPolicy.lapsable(effect()));
        long now = 1_000_000L;
        long duration = 200_000L;
        for (double draw = 0d; draw <= 1d; draw += 0.05d) {
            long survival = AgentImperfectionPolicy.refreshAt(now, duration, true, true, draw);
            assertTrue(survival >= now + 170_000L && survival <= now + 180_000L, "survival " + (survival - now));
            long other = AgentImperfectionPolicy.refreshAt(now, duration, false, false, draw);
            assertTrue(other >= now + 170_000L && other <= now + duration, "other " + (other - now));
            long lapse = AgentImperfectionPolicy.refreshAt(now, duration, false, true, draw);
            assertTrue(lapse >= now + duration + 2_000L && lapse <= now + duration + 10_000L, "lapse " + (lapse - now));
        }
    }

    @SuppressWarnings("unchecked")
    private static StatEffect effect(BuffStat... stats) {
        StatEffect effect = mock(StatEffect.class);
        List<Pair<BuffStat, Integer>> statups = new ArrayList<>();
        for (BuffStat stat : stats) {
            statups.add(new Pair<>(stat, 10));
        }
        when(effect.getStatups()).thenReturn(statups);
        return effect;
    }
}
