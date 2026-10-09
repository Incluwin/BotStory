package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentSchedulerRuntime;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 8 (all modes), the watchdog: a run whose Agents no longer tick it (its lease ran out, or its holder
 * is dead) is ticked through its first live Agent, so it still fails or completes and is released; while every
 * loaded Agent is dead the run is ticked through the lowest-id one and its own rules decide; only a run with no Agent
 * loaded fails at once; a live lease is left alone. It starts once, every PERIOD_MS (1000 ms);
 * AgentLmpqWatchdogStartTest pins who starts it.
 */
class AgentLmpqWatchdogTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        run.close();
        resetTask();
    }

    private static void resetTask() throws ReflectiveOperationException {
        Field task = AgentLmpqWatchdogRuntime.class.getDeclaredField("task");
        task.setAccessible(true);
        task.set(null, null);
    }

    @Test
    void itStartsOnceEverySecond() throws ReflectiveOperationException {
        resetTask();
        try (MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            scheduler.when(() -> AgentSchedulerRuntime.register(any(), anyLong())).thenReturn(mock(ScheduledFuture.class));

            AgentLmpqWatchdogRuntime.ensureStarted();
            AgentLmpqWatchdogRuntime.ensureStarted();

            scheduler.verify(() -> AgentSchedulerRuntime.register(any(), eq(1_000L)), times(1));
        }
    }

    @Test
    void aRunNobodyTicksStillFailsAndIsReleased() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(run.event.isEventDisposed()).thenReturn(true); // the event ended; no Agent tick saw it

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals("LMPQ event ended before the clear", session.failure());
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()), "released");
        verify(run.event).dispose();
    }

    @Test
    void aRunWhoseAgentsAllLeftCompletesAndIsReleased() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REWARD,
                ben, mira, kes, pel);
        when(run.event.isEventCleared()).thenReturn(true);
        for (Character member : new Character[]{ben, mira, kes, pel}) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        }

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()), "released");
    }

    @Test
    void aRunWhoseAgentsAreAllDeadButLoadedIsTickedNotFailed() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        for (Character agent : new Character[]{mira, kes, pel}) when(agent.getHp()).thenReturn(0);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertFalse(session.terminal(), "their respawns decide: they depart, or the event ends the run");
        assertEquals(mira.getId(), session.executionAgentId(), "the lowest-id loaded Agent drives the pass");
        verify(run.event, never()).dispose();
    }

    @Test
    void aRunWhoseAgentsAreAllDeadIsStillJudgedByItsOwnRules() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        for (Character agent : new Character[]{mira, kes, pel}) when(agent.getHp()).thenReturn(0);
        when(run.event.isEventDisposed()).thenReturn(true);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals("LMPQ event ended before the clear", session.failure());
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()), "released");
    }

    @Test
    void aRunWithNoAgentLoadedFailsAtOnce() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        for (Character agent : new Character[]{mira, kes, pel}) run.unload(agent);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals("No LMPQ Agent is loaded", session.failure());
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()), "released");
    }

    @Test
    void aLiveLeaseIsLeftAlone() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        assertTrue(session.claimExecutionTick(kes.getId(), 9_000L, 3_000L));
        when(run.event.isEventDisposed()).thenReturn(true);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertFalse(session.terminal(), "Kes's own tick drives it");
        assertEquals(kes.getId(), session.executionAgentId());
        verify(run.event, never()).dispose();
    }

    @Test
    void aRunThatEndedUnreleasedIsReleased() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        session.fail("LMPQ stalled in FARMING", 9_000L); // its Agents stopped ticking before they released it

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()), "released");
        verify(run.event).dispose();
        assertEquals("LMPQ stalled in FARMING", session.failure());
    }

    @Test
    void aReleaseUnderWayIsLeftToIt() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        session.fail("LMPQ stalled in FARMING", 9_000L);
        assertTrue(session.beginTermination()); // an Agent's tick is releasing it

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
    }

    @Test
    void aPausedOrEndedRunIsLeftAlone() {
        AgentLmpqSession paused = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        paused.setPaused(true);
        for (Character agent : new Character[]{mira, kes, pel}) when(agent.getHp()).thenReturn(0);

        AgentLmpqWatchdogRuntime.tick(paused, 10_000L);
        AgentLmpqWatchdogRuntime.tick(null, 10_000L);

        assertFalse(paused.terminal());
    }
}
