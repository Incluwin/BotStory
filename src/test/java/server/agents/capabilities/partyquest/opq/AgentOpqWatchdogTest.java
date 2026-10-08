package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentSchedulerRuntime;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * P6h OPQ base fix OB9: OPQ gets LPQ's watchdog. Every OPQ session is scanned on a schedule (PERIOD_MS, agent-engine.yaml):
 * when no Agent's own tick drives it (the lease expired, or its holder is dead or unloaded) its first live Agent takes
 * the lease and ticks the coordinator; when no live Agent is left the run is ended. It is started once a session is
 * published, by the admission and by the fixture harness.
 */
class AgentOpqWatchdogTest {
    private Object savedTask;

    @BeforeEach
    void forgetTheRunningWatchdog() throws ReflectiveOperationException {
        savedTask = task().get(null);
        task().set(null, null);
    }

    @AfterEach
    void restoreTheRunningWatchdog() throws ReflectiveOperationException {
        task().set(null, savedTask);
    }

    private static Field task() throws NoSuchFieldException {
        Field task = AgentOpqWatchdogRuntime.class.getDeclaredField("task");
        task.setAccessible(true);
        return task;
    }

    @Test
    void itIsStartedOnceWithTheConfiguredPeriodAndScansEverySession() {
        try (OpqRunFixture run = new OpqRunFixture();
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            scheduler.when(() -> AgentSchedulerRuntime.register(any(), anyLong())).thenAnswer(call -> future);

            AgentOpqWatchdogRuntime.ensureStarted();
            AgentOpqWatchdogRuntime.ensureStarted();

            ArgumentCaptor<Runnable> scan = ArgumentCaptor.forClass(Runnable.class);
            scheduler.verify(() -> AgentSchedulerRuntime.register(scan.capture(), eq(1_000L)), times(1));

            // A registered six-Agent run whose lease nobody holds: the scan drives it.
            AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.PREPARING,
                    run.companion(11, "Lea"), run.companion(12, "Mira"), run.companion(13, "Kes"),
                    run.companion(14, "Pel"), run.companion(15, "Hal"), run.companion(16, "Gus"));
            session.markProgress(System.currentTimeMillis()); // the scan runs on the wall clock
            scan.getValue().run();
            assertEquals(AgentOpqSession.Phase.ENTERING, session.phase());
        }
    }

    @Test
    void anExpiredLeaseIsTakenByTheFirstLiveAgentAndTheCoordinatorTicks() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character ben = run.human(1, "Ben");
            Character mira = run.companion(2, "Mira");
            Character kes = run.companion(3, "Kes");
            AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                    AgentOpqSession.Phase.PREPARING, ben, mira, kes, run.companion(4, "Pel"), run.companion(5, "Hal"));
            when(mira.getHp()).thenReturn(0); // Mira is dead: Kes is the first live Agent

            AgentOpqWatchdogRuntime.tick(session, 5_000L);

            assertEquals(kes.getId(), session.executionAgentId());
            assertEquals(AgentOpqSession.Phase.ENTERING, session.phase());
        }
    }

    @Test
    void aLiveHoldersRunningLeaseIsLeftAlone() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character ben = run.human(1, "Ben");
            Character mira = run.companion(2, "Mira");
            AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                    AgentOpqSession.Phase.PREPARING, ben, mira, run.companion(3, "Kes"), run.companion(4, "Pel"),
                    run.companion(5, "Hal"));
            assertTrue(session.claimExecutionTick(mira.getId(), 4_000L, 1_500L, AgentOpqRuntime::holderAlive));

            AgentOpqWatchdogRuntime.tick(session, 4_500L);

            assertEquals(AgentOpqSession.Phase.PREPARING, session.phase(), "Mira's own tick drives the run");
        }
    }

    @Test
    void aDeadHoldersLeaseIsTakenAtOnce() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character ben = run.human(1, "Ben");
            Character mira = run.companion(2, "Mira");
            Character kes = run.companion(3, "Kes");
            AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                    AgentOpqSession.Phase.PREPARING, ben, mira, kes, run.companion(4, "Pel"), run.companion(5, "Hal"));
            assertTrue(session.claimExecutionTick(mira.getId(), 4_000L, 60_000L, AgentOpqRuntime::holderAlive));
            when(mira.getHp()).thenReturn(0);

            AgentOpqWatchdogRuntime.tick(session, 4_500L);

            assertEquals(kes.getId(), session.executionAgentId());
            assertEquals(AgentOpqSession.Phase.ENTERING, session.phase());
        }
    }

    @Test
    void aRunWithNoLiveAgentIsEnded() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character ben = run.human(1, "Ben");
            Character[] agents = {run.companion(2, "Mira"), run.companion(3, "Kes"), run.companion(4, "Pel"),
                    run.companion(5, "Hal")};
            AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                    AgentOpqSession.Phase.SPLIT_ROOMS, ben, agents[0], agents[1], agents[2], agents[3]);
            for (Character agent : agents) when(agent.getHp()).thenReturn(0);

            AgentOpqWatchdogRuntime.tick(session, 5_000L);

            assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
            assertEquals("No live Agent remains", session.failure());
            assertNull(AgentOpqSessionRegistry.forMember(ben.getId()));
        }
    }

    @Test
    void aPausedOrEndedRunIsNotTicked() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.PREPARING,
                    run.companion(11, "Lea"), run.companion(12, "Mira"), run.companion(13, "Kes"),
                    run.companion(14, "Pel"), run.companion(15, "Hal"), run.companion(16, "Gus"));
            session.setPaused(true);
            AgentOpqWatchdogRuntime.tick(session, 5_000L);
            assertEquals(AgentOpqSession.Phase.PREPARING, session.phase());
            AgentOpqWatchdogRuntime.tick(null, 5_000L);
        }
    }

    @Test
    void theAdmissionStartsItOnceTheSessionIsPublished() {
        try (OpqAdmitFixture fixture = new OpqAdmitFixture()) {
            AgentOpqAdmissionService.AdmissionResult admitted =
                    AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
            assertTrue(admitted.success(), admitted.message());
            fixture.scheduler.verify(() -> AgentSchedulerRuntime.register(any(), eq(1_000L)));
        }
    }

    @Test
    void aRefusedAdmissionDoesNotStartIt() {
        try (OpqAdmitFixture fixture = new OpqAdmitFixture()) {
            when(fixture.pel.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
            AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
            fixture.scheduler.verify(() -> AgentSchedulerRuntime.register(any(), anyLong()), never());
        }
    }

    @Test
    void theFixtureHarnessStartsItOnceItsSessionIsPublished() throws Exception {
        String harness = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/opq/AgentOpqTestService.java"))
                .replaceAll("\\s+", " ");
        assertTrue(harness.contains("AgentOpqSessionRegistry.registerComplete(session); "
                + "AgentOpqWatchdogRuntime.ensureStarted();"));
    }
}
