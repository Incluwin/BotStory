package server.agents.capabilities.partyquest.ppq;

import config.AgentTuning;
import org.junit.jupiter.api.Test;
import server.agents.field.AgentPpqTestFixtureService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPQ Step 1 (plan 4.2): the coordinator's, runtime's, session's and fixture's numbers moved to agent-engine.yaml with
 * the values they had in code, so the move changes nothing.
 */
class AgentPpqTuningTest {
    private static final String PP = "server.agents.capabilities.partyquest.ppq.";

    @Test
    void everyMovedNumberKeepsItsValue() {
        assertEquals(75L * 60_000L, AgentTuning.longValue(PP + "AgentPpqCoordinator.EVENT_TIMEOUT_MS"),
                "1b.4 raised the cap to the stock timers' worst case");
        assertEquals(10L * 60_000L, AgentTuning.longValue(PP + "AgentPpqCoordinator.STALL_TIMEOUT_MS"));
        assertEquals(900L, AgentTuning.longValue(PP + "AgentPpqCoordinator.RETRY_MS"));
        assertEquals(70, AgentTuning.intValue(PP + "AgentPpqCoordinator.INTERACTION_RADIUS"));
        assertEquals(3_000L, AgentTuning.longValue(PP + "AgentPpqRuntime.COORDINATOR_LEASE_MS"));
        assertEquals(250L, AgentTuning.longValue(PP + "AgentPpqSession.MIN_LEASE_MS"));
        assertEquals(500L, AgentTuning.longValue(PP + "AgentPpqTestService.SPAWN_STAGGER_MS"));
        assertEquals(67, AgentPpqTestFixtureService.LEVEL);
        assertTrue(AgentPpqTestFixtureService.LEVEL >= 55 && AgentPpqTestFixtureService.LEVEL < 70,
                "PiratePQ.js admits 55-100; the second-job fixture build refuses 70 and above");
    }
}
