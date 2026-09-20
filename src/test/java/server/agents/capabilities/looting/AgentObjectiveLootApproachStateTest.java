package server.agents.capabilities.looting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentObjectiveLootApproachStateTest {
    @Test
    void slowProgressCannotKeepOneDropCommittedForever() {
        var state = new AgentObjectiveLootApproachState();
        for (int step = 0; step < 9; step++) {
            assertTrue(state.approach(1, 10, 1000 - step * 30, step * 10_000L));
        }
        assertFalse(state.approach(1, 10, 700, 90_000L));
        assertTrue(state.suppressed(1, 10, 100_000L));
        assertFalse(state.suppressed(1, 10, 150_000L));
    }

    @Test
    void abandonedDropIdsDoNotLeakIntoAnotherMap() {
        var state = new AgentObjectiveLootApproachState();
        assertTrue(state.approach(1, 10, 500, 1L));
        assertFalse(state.approach(1, 10, 500, 30_001L));
        assertTrue(state.suppressed(1, 10, 30_002L));
        assertFalse(state.suppressed(2, 10, 30_002L));
        assertTrue(state.approach(2, 10, 500, 30_002L));
    }
}
