package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * PPQ Step 1b.2 (D3, R1; every mode): when one Agent's runtime goes away (a logout, a cleanup), it leaves the run and
 * the run goes on without it: nobody is taken out of the event and the event is not disposed. A lease it held passes
 * on at once and it never claims one again. The whole run is released only when the last Agent goes; forceStop of one
 * Agent stays the whole-run release.
 */
class AgentPpqRuntimeRemovedTest {
    @Test
    void oneRemovedAgentLeavesAndTheRunGoesOn() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqRuntime.class)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                AgentPpqRuntime.runtimeRemoved(TOBI, 5_000L);

                assertFalse(run.session.terminal(), run.session.failure());
                assertFalse(run.session.terminationStarted());
                assertNull(run.session.member(TOBI));
                assertEquals(3, run.session.memberCount());
                assertNull(AgentPpqSessionRegistry.forMember(TOBI));
                assertSame(run.session, AgentPpqSessionRegistry.forMember(MIRA));
                verify(run.event, never()).exitPlayer(any());
                verify(run.event, never()).dispose();
                assertEquals(List.of("PPQ member left: session=" + run.session.sessionId() + " member=" + TOBI
                        + " reason=runtime-removed members=3 companionRun=true"), lines.startingWith("PPQ member left:"));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void aLeaseTheRemovedAgentHeldPassesOnAtOnce() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                assertTrue(run.session.claimExecutionTick(MIRA, 5_000L, 3_000L));
                assertFalse(run.session.claimExecutionTick(TOBI, 5_100L, 3_000L), "Mira holds the lease");

                AgentPpqRuntime.runtimeRemoved(MIRA, 5_200L);

                assertFalse(run.session.claimExecutionTick(MIRA, 5_300L, 3_000L), "a departed Agent never claims");
                assertTrue(run.session.claimExecutionTick(UNA, 5_300L, 3_000L),
                        "the lease is free at once, for any Agent left");
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void theLastAgentRemovedReleasesTheRun() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                AgentPpqRuntime.runtimeRemoved(TOBI, 5_000L);
                AgentPpqRuntime.runtimeRemoved(UNA, 5_100L);
                assertFalse(run.session.terminal());

                AgentPpqRuntime.runtimeRemoved(MIRA, 5_200L);

                assertEquals(AgentPpqSession.Phase.FAILED, run.session.phase());
                assertEquals("Agent runtime removed", run.session.failure());
                assertTrue(run.session.terminationStarted());
                assertNull(AgentPpqSessionRegistry.forOperator(run.session.operatorId()));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void anAgentThatJustDepartedIsNotTheWholeRun() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                // The departure pass took Tobi out of the session; its hand-back has not unindexed it yet.
                run.session.departAgent(TOBI);

                AgentPpqRuntime.runtimeRemoved(TOBI, 5_000L);

                assertFalse(run.session.terminal(), run.session.failure());
                assertFalse(run.session.terminationStarted());
                assertNull(AgentPpqSessionRegistry.forMember(TOBI));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    @Test
    void pinForceStopOfOneAgentReleasesTheWholeRun() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                AgentPpqRuntime.forceStop(TOBI, "stopped by its owner", 5_000L);

                assertEquals("stopped by its owner", run.session.failure());
                assertTrue(run.session.terminationStarted());
                assertEquals(4, run.session.memberCount());
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }
}
