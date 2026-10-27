package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import constants.skills.Bishop;
import constants.skills.Magician;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.operations.events.AgentOperationalEventPublisher;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapleMap;
import tools.Pair;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * F8 combat guards (docs/BOT_CONCEPT_PLAN.md F8): buff timing (survival buffs never later than 90%, a lapse at most 10 s,
 * nothing in boss or PQ content), the reaction to new mobs (only for calibrated bots, never after a hit), and "rez pls?"
 * (a Resurrection wait never past 10 s from death, only beside a party member who has it ready, a revive in place).
 */
class AgentImperfectionCombatTest {
    private boolean calibrated = true;
    private boolean slips = true;

    @AfterEach
    void off() {
        AgentImperfections.install(null);
    }

    private void install() {
        AgentImperfections.install(new AgentImperfections.Rule() {
            @Override public boolean calibrated(Character agent) { return calibrated; }
            @Override public boolean slips(Character agent, AgentImperfections.Slip slip) { return calibrated && slips; }
            @Override public boolean leavesDrop(Character agent, int itemId, int dropObjectId) { return false; }
            @Override public float personalHpAlert(Character agent, float base) { return base; }
        });
    }

    private static Character agent(int id) {
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(agent.getMap()).thenReturn(map);
        when(agent.getId()).thenReturn(id);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        return agent;
    }

    @SuppressWarnings("unchecked")
    private static StatEffect effect(BuffStat stat) {
        StatEffect effect = mock(StatEffect.class);
        List<Pair<BuffStat, Integer>> statups = new ArrayList<>();
        statups.add(new Pair<>(stat, 10));
        when(effect.getStatups()).thenReturn(statups);
        return effect;
    }

    // ---- buffs (F8.3) ----------------------------------------------------------------------------

    @Test
    void buffsRecastAtNinetyPercentUnlessCalibrated() {
        Character bot = agent(1);
        assertEquals(1_000L + 90_000L, AgentCombatBuffRuntime.refreshAt(bot, 1001003, effect(BuffStat.WATK), 1_000L, 100_000L));
        install();
        calibrated = false;
        assertEquals(1_000L + 90_000L, AgentCombatBuffRuntime.refreshAt(bot, 1001003, effect(BuffStat.WATK), 1_000L, 100_000L));
    }

    @Test
    void survivalBuffsNeverLapseAndOtherBuffsLapseAtMostTenSeconds() {
        install();
        Character bot = agent(2);
        for (int i = 0; i < 200; i++) {
            long guard = AgentCombatBuffRuntime.refreshAt(bot, Magician.MAGIC_GUARD, effect(BuffStat.WATK), 0L, 100_000L);
            assertTrue(guard >= 85_000L && guard <= 90_000L, "Magic Guard is a survival buff whatever it carries: " + guard);
            long defence = AgentCombatBuffRuntime.refreshAt(bot, 1001003, effect(BuffStat.WDEF), 0L, 100_000L);
            assertTrue(defence >= 85_000L && defence <= 90_000L, "a defence buff never later: " + defence);
            long attack = AgentCombatBuffRuntime.refreshAt(bot, 1101006, effect(BuffStat.WATK), 0L, 100_000L);
            assertTrue(attack >= 102_000L && attack <= 110_000L, "the slip came up: a lapse of 2-10 s: " + attack);
        }
        slips = false;
        for (int i = 0; i < 200; i++) {
            long attack = AgentCombatBuffRuntime.refreshAt(bot, 1101006, effect(BuffStat.WATK), 0L, 100_000L);
            assertTrue(attack >= 85_000L && attack <= 100_000L, "85-100% of the duration: " + attack);
        }
    }

    @Test
    void noBuffTimingChangesInBossOrPartyQuestContent() {
        install();
        Character inEvent = agent(3);
        when(inEvent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        Character besideBoss = agent(4);
        Monster boss = mock(Monster.class);
        when(boss.isAlive()).thenReturn(true);
        when(boss.isBoss()).thenReturn(true);
        when(besideBoss.getMap().getAllMonsters()).thenReturn(List.of(boss));
        for (Character bot : List.of(inEvent, besideBoss)) {
            assertEquals(90_000L, AgentCombatBuffRuntime.refreshAt(bot, 1101006, effect(BuffStat.WATK), 0L, 100_000L));
        }
    }

    // ---- reaction to new mobs (F8.1) ---------------------------------------------------------------

    @Test
    void aCalibratedBotWaitsAHumanBeatForNewMobsButAnswersAHitAtOnce() {
        Character agent = agent(5);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        Monster mob = mock(Monster.class);
        when(mob.getObjectId()).thenReturn(77);
        List<Monster> candidates = List.of(mob);
        assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 1_000L), "no rule: at once");
        entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY).clearStimulus();

        install();
        assertFalse(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 10_000L), "a beat first");
        assertTrue(AgentCombatBehaviorRuntime.waitingForResponse(entry));
        assertFalse(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 10_179L));
        assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 10_500L), "never past 500 ms");

        entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY).clearStimulus();
        assertFalse(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 20_000L));
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entry, 1_500);
        assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 20_001L), "hit: it answers at once");

        entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY).clearStimulus();
        AgentCombatCooldownStateRuntime.setMobHitCooldownMs(entry, 0);
        calibrated = false;
        assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 30_000L), "not one of its bots");
    }

    // ---- "rez pls?" (F8.6) -------------------------------------------------------------------------

    private static Character bishop(boolean alive, boolean cooling) {
        Character bishop = agent(900);
        when(bishop.isAlive()).thenReturn(alive);
        when(bishop.getSkillLevel(Bishop.RESURRECTION)).thenReturn(1);
        when(bishop.skillIsCooling(Bishop.RESURRECTION)).thenReturn(cooling);
        return bishop;
    }

    @Test
    void theRezWaitNeverRunsPastTenSecondsFromDeathAndNeedsAPartyMemberWithResurrectionReady() {
        install();
        Character dead = agent(6);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(dead, null, null);
        AgentDeathStateRuntime.enterDeadState(entry, 1_000L, 5_000L);

        List<Character> cannot = List.of(bishop(true, true), bishop(false, false));
        when(dead.getPartyMembersOnSameMap()).thenReturn(cannot);
        AgentRezWaitRuntime.begin(entry, dead, 1_000L, 5_000L);
        assertEquals(6_000L, AgentDeathStateRuntime.deadUntilMs(entry), "nobody can cast it now: the usual respawn");

        List<Character> can = List.of(dead, bishop(true, false));
        when(dead.getPartyMembersOnSameMap()).thenReturn(can);
        AgentRezWaitRuntime.begin(entry, dead, 1_000L, 10_000L);
        assertEquals(6_000L, AgentDeathStateRuntime.deadUntilMs(entry), "a respawn delay already 10 s long");
        slips = false;
        AgentRezWaitRuntime.begin(entry, dead, 1_000L, 5_000L);
        assertEquals(6_000L, AgentDeathStateRuntime.deadUntilMs(entry), "the gate said no");

        slips = true;
        AgentRezWaitRuntime.begin(entry, dead, 1_000L, 5_000L);
        assertEquals(1_000L + AgentImperfections.REZ_WAIT_MAX_MS, AgentDeathStateRuntime.deadUntilMs(entry));
        assertTrue(AgentImperfections.REZ_WAIT_MAX_MS <= 10_000L);
        assertTrue(AgentRezWaitRuntime.waiting(entry));
    }

    @Test
    void noRezWaitInAnEventInstance() {
        install();
        Character dead = agent(7);
        when(dead.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        List<Character> can = List.of(bishop(true, false));
        when(dead.getPartyMembersOnSameMap()).thenReturn(can);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(dead, null, null);
        AgentDeathStateRuntime.enterDeadState(entry, 1_000L, 5_000L);
        AgentRezWaitRuntime.begin(entry, dead, 1_000L, 5_000L);
        assertEquals(6_000L, AgentDeathStateRuntime.deadUntilMs(entry));
        assertFalse(AgentRezWaitRuntime.waiting(entry));
    }

    @Test
    void aResurrectionStandsAWaitingBotUpWhereItLayWithNoTripToTown() {
        install();
        Character dead = agent(8);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(dead, null, null);
        AgentDeathStateRuntime.enterDeadState(entry, 1_000L, 5_000L);
        AtomicInteger respawns = new AtomicInteger();
        try (MockedStatic<AgentRespawnCoordinator> standUp = mockStatic(AgentRespawnCoordinator.class);
             MockedStatic<AgentOperationalEventPublisher> events = mockStatic(AgentOperationalEventPublisher.class)) {
            when(dead.getHp()).thenReturn(500);
            assertTrue(AgentDeathTickService.handleDeadTick(entry, dead, () -> false, (e, a) -> { },
                    respawns::incrementAndGet, 2_000L), "not waiting for a rez: dead until its respawn");
            assertFalse(AgentRezWaitRuntime.revivedInPlace(entry, dead));

            entry.capabilityStates().require(AgentDeathState.STATE_KEY).setRezWaiting(true);
            when(dead.getHp()).thenReturn(0);
            assertTrue(AgentDeathTickService.handleDeadTick(entry, dead, () -> false, (e, a) -> { },
                    respawns::incrementAndGet, 2_000L), "still down");
            when(dead.getHp()).thenReturn(500);
            assertFalse(AgentDeathTickService.handleDeadTick(entry, dead, () -> false, (e, a) -> { },
                    respawns::incrementAndGet, 20_000L), "revived: alive this tick");
            assertFalse(AgentDeathStateRuntime.isDead(entry));
            assertFalse(AgentRezWaitRuntime.waiting(entry));
            assertEquals(0, respawns.get(), "no trip to town");
        }
    }

    @Test
    void aBishopCastsOnlyOnADeadPartyMemberInsideItsBox() {
        Character bot = agent(9);
        StatEffect resurrection = mock(StatEffect.class);
        when(resurrection.hasBoundingBox()).thenReturn(true);
        when(resurrection.calculateBoundingBox(any(), anyBoolean())).thenReturn(new Rectangle(-200, -150, 400, 300));
        Character inBox = mock(Character.class);
        when(inBox.getId()).thenReturn(10);
        when(inBox.isAlive()).thenReturn(false);
        when(inBox.getPosition()).thenReturn(new Point(150, 0));
        Character outside = mock(Character.class);
        when(outside.getId()).thenReturn(11);
        when(outside.isAlive()).thenReturn(false);
        when(outside.getPosition()).thenReturn(new Point(600, 0));
        Character standing = mock(Character.class);
        when(standing.getId()).thenReturn(12);
        when(standing.isAlive()).thenReturn(true);
        when(standing.getPosition()).thenReturn(new Point(10, 0));

        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(standing, outside));
        assertNull(AgentRezWaitRuntime.deadInBox(bot, resurrection));
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(standing, outside, inBox));
        assertSame(inBox, AgentRezWaitRuntime.deadInBox(bot, resurrection));
    }
}
