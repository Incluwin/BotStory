package server.agents.runtime.activity.control;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentFileWorldDirectorSessionStore;
import server.agents.runtime.activity.world.AgentWorldActivityDecision;
import server.agents.runtime.activity.world.AgentWorldContext;
import server.agents.runtime.activity.world.AgentWorldDirectorMode;
import server.agents.runtime.activity.world.AgentWorldDirectorSession;
import server.agents.runtime.activity.world.AgentWorldMilestoneEvaluator;
import server.agents.runtime.activity.world.AgentWorldShadowReport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The World Director's own choices: stable-sample runs, the policy step, and Observe evidence that outlives a session. */
class AgentWorldDirectorPolicyRuntimeTest {
    @TempDir Path directory;

    @Test
    void countsOnlyAnUnbrokenRunOfTheSameSwitch() {
        AgentWorldDirectorObserveState state = new AgentWorldDirectorObserveState();
        state.configure(AgentWorldDirectorMode.AUTONOMOUS, 5_000L);

        state.sampled(report(AgentActivityKind.HUNTING, "hunt-a", true), 1_000L);
        state.sampled(report(AgentActivityKind.HUNTING, "hunt-a", true), 6_000L);
        assertEquals(2L, state.snapshot().stableSamples());

        state.sampled(report(AgentActivityKind.QUESTING, "quest-b", true), 11_000L); // a different choice restarts
        assertEquals(1L, state.snapshot().stableSamples());

        state.sampled(report(AgentActivityKind.QUESTING, "quest-b", false), 16_000L); // already doing it: no switch
        assertEquals(0L, state.snapshot().stableSamples());
        assertEquals(4L, state.snapshot().sampleCount());

        state.sampled(report(AgentActivityKind.QUESTING, "quest-b", true), 21_000L);
        state.policyActed(21_000L, 300_000L); // acted: a fresh run comes first, and quiet time
        assertEquals(0L, state.snapshot().stableSamples());
        assertTrue(state.policyQuiet(21_001L));
        assertFalse(state.policyQuiet(321_000L));
    }

    @Test
    void samplesInObserveAndInTheModesWhosePolicyNeedsThem() {
        assertTrue(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.OBSERVE));
        assertTrue(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.ASSISTED));
        assertTrue(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.AUTONOMOUS));
        assertFalse(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.MANUAL));
        assertFalse(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.DISABLED));
        assertFalse(AgentWorldDirectorObserveState.samples(AgentWorldDirectorMode.EMERGENCY_HOLD));
    }

    @Test
    void proposesInAssistedStartsInAutonomousAndWaitsOtherwise() {
        var stable = snapshot(AgentWorldDirectorMode.ASSISTED, 6L);
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.PROPOSE,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.ASSISTED, stable, 6L, false, true));
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.SUBMIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.AUTONOMOUS, stable, 6L, false, true));
        // not stable long enough, quiet, outside the cohort, or a mode without a policy
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.WAIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.AUTONOMOUS,
                        snapshot(AgentWorldDirectorMode.AUTONOMOUS, 5L), 6L, false, true));
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.WAIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.AUTONOMOUS, stable, 6L, true, true));
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.WAIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.AUTONOMOUS, stable, 6L, false, false));
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.WAIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.MANUAL, stable, 6L, false, true));
        assertEquals(AgentWorldDirectorPolicyRuntime.Step.WAIT,
                AgentWorldDirectorPolicyRuntime.next(AgentWorldDirectorMode.OBSERVE, stable, 6L, false, true));
        assertEquals("proposal:hunt-a", AgentWorldDirectorPolicyRuntime.actionId("hunt-a"));
    }

    @Test
    void countsDirectorFailuresInsideTheWindowOnly() {
        AgentWorldDirectorObserveState state = new AgentWorldDirectorObserveState();
        state.directorFailed(1_000L, 3_600_000L);
        state.directorFailed(2_000L, 3_600_000L);
        state.directorFailed(3_600_000L, 3_600_000L);
        assertEquals(3, state.recentDirectorFailures(3_600_500L, 3_600_000L));
        assertEquals(1, state.recentDirectorFailures(3_700_000L, 3_600_000L));
    }

    @Test
    void observeEvidenceSurvivesTheSessionThroughItsFile() {
        AgentWorldDirectorObserveEvidenceStore evidence =
                new AgentWorldDirectorObserveEvidenceStore(directory.resolve("observe-samples"));
        AgentWorldDirectorObserveState live = new AgentWorldDirectorObserveState();
        live.configure(AgentWorldDirectorMode.OBSERVE, 5_000L);
        for (int sample = 0; sample < 130; sample++) {
            live.sampled(report(AgentActivityKind.HUNTING, "hunt-a", true), 1_000L + sample * 5_000L);
            AgentWorldDirectorObserveRuntime.checkpoint(evidence, 27, live, 1_000L + sample * 5_000L);
        }
        assertEquals(120L, evidence.load(27)); // checkpointed every 60 samples (agent-engine.yaml CHECKPOINT_SAMPLES)

        // the next session (a relogin or a restart) in AUTONOMOUS mode starts from the stored count and samples
        AgentFileWorldDirectorSessionStore sessions =
                new AgentFileWorldDirectorSessionStore(directory.resolve("sessions"));
        sessions.save(AgentWorldDirectorSession.create(27, AgentWorldDirectorMode.AUTONOMOUS, 1_000L));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), null, null);
        new AgentWorldDirectorModeRestoreRuntime(sessions, 5_000L, evidence).restore(entry, 27, 900_000L);
        var restored = entry.capabilityStates().require(AgentWorldDirectorObserveState.STATE_KEY).snapshot();
        assertEquals(120L, restored.sampleCount());
        assertEquals(AgentWorldDirectorMode.AUTONOMOUS, restored.mode());
        assertEquals(0L, new AgentWorldDirectorObserveEvidenceStore(directory.resolve("none")).load(27));
    }

    private static AgentWorldDirectorObserveState.Snapshot snapshot(AgentWorldDirectorMode mode, long stable) {
        return new AgentWorldDirectorObserveState.Snapshot(mode, 5_000L, 1_000L, 200L,
                AgentActivityKind.HUNTING, "hunt-a", "best field", stable);
    }

    private static AgentWorldShadowReport report(AgentActivityKind kind, String proposalId, boolean switchRequired) {
        AgentWorldContext context = new AgentWorldContext(1_000L, 1_000L, 27, "KiwiAgent", 15, 100, 100000000,
                100, 100, 50, 50, 1_000L, true, false, Set.of(), Set.of(),
                null, "", "", "", "FIRST_JOB", Map.of());
        return new AgentWorldShadowReport(context, AgentWorldMilestoneEvaluator.evaluate(context), List.of(),
                new AgentWorldActivityDecision(kind, proposalId, switchRequired, "best field"));
    }
}
