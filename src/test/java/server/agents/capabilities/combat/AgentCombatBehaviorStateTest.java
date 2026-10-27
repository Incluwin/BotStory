package server.agents.capabilities.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reaction the tell audit times: from the start of a wait for a target to its first ready tick, once a wait. */
class AgentCombatBehaviorStateTest {
    @Test
    void aReactionIsTakenOnceAWaitFromItsStart() {
        AgentCombatBehaviorState state = new AgentCombatBehaviorState();
        assertFalse(state.ready(11L, 1_000L, 300));
        assertEquals(-1L, state.takeReactionMs(1_000L), "still waiting");
        assertFalse(state.ready(12L, 1_100L, 300), "the mobs changed: the delay starts again");
        assertTrue(state.ready(12L, 1_400L, 300));
        assertEquals(400L, state.takeReactionMs(1_400L), "timed from the wait's start, not the change");
        assertTrue(state.ready(12L, 1_500L, 300));
        assertEquals(-1L, state.takeReactionMs(1_500L), "once a wait");

        state.clearStimulus();
        assertTrue(state.ready(13L, 2_000L, 0));
        assertEquals(0L, state.takeReactionMs(2_000L), "a new wait");
    }
}
