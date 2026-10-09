package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * P6h review (LOW): a departed, handed-back Agent is alive and loaded but no longer the run's, so it never drives the
 * coordinator. The watchdog never chooses it as the lease holder, and a lease it still holds (claimed by its own tick
 * just before the pass that marked it departed) does not refuse the run's own Agents: before, every in-event Agent's
 * claim was refused for up to COORDINATOR_LEASE_MS, again and again.
 */
class AgentOpqDepartedLeaseTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character lead = run.companion(2006, "Lead");
    private final Character a2001 = run.companion(2001, "Dorran");
    private final Character a2002 = run.companion(2002, "Rurik");
    private final Character a2003 = run.companion(2003, "Yara");
    private final Character a2004 = run.companion(2004, "HappyWolf");
    private final Character a2005 = run.companion(2005, "IceCookie");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** A stock six-Agent run in which 2001 died at six, respawned out (alive) and departed. */
    private AgentOpqSession withDorranDeparted() {
        return withDorranDeparted(false);
    }

    /**
     * As {@link #withDorranDeparted()}; with {@code holdingTheLease}, 2001's own tick claimed the lease (until 5,400)
     * just before the pass that marked it departed.
     */
    private AgentOpqSession withDorranDeparted(boolean holdingTheLease) {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.SPLIT_ROOMS,
                lead, a2001, a2002, a2003, a2004, a2005);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        if (holdingTheLease) {
            assertTrue(session.claimExecutionTick(a2001.getId(), 3_900L, 1_500L, AgentOpqRuntime::holderAlive));
        }
        when(a2001.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(a2001.getEventInstance()).thenReturn(null);
        List<Character> held = List.of(lead, a2002, a2003, a2004, a2005);
        when(run.event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
        AgentOpqCoordinator.tickSession(session, 4_000L);
        assertTrue(session.departed(a2001.getId()));
        if (holdingTheLease) assertEquals(a2001.getId(), session.executionAgentId(), "its lease runs until 5,400");
        return session;
    }

    @Test
    void theWatchdogNeverChoosesADepartedLiveAgent() {
        AgentOpqSession session = withDorranDeparted();
        assertTrue(session.claimExecutionTick(a2004.getId(), 5_000L, 1_500L, AgentOpqRuntime::holderAlive));
        when(a2004.getHp()).thenReturn(0); // the lease holder dies and is held

        AgentOpqWatchdogRuntime.tick(session, 5_100L);

        assertNotEquals(a2001.getId(), session.executionAgentId());
        assertEquals(a2002.getId(), session.executionAgentId(), "the first live Agent the run still holds");
    }

    @Test
    void aLeaseADepartedAgentStillHoldsDoesNotRefuseTheRunsAgents() {
        AgentOpqSession session = withDorranDeparted(true);

        assertTrue(session.claimExecutionTick(a2003.getId(), 4_100L, 1_500L, AgentOpqRuntime::holderAlive),
                "an in-event Agent's own tick takes the lease at once");
        assertEquals(a2003.getId(), session.executionAgentId());
    }

    @Test
    void theWatchdogTakesALeaseADepartedAgentStillHolds() {
        AgentOpqSession session = withDorranDeparted(true);

        assertTrue(session.claimExpiredExecutionTick(a2002.getId(), 4_100L, 1_500L, AgentOpqRuntime::holderAlive));
        assertEquals(a2002.getId(), session.executionAgentId());
    }

    @Test
    void aDepartedAgentNeverClaimsTheLease() {
        AgentOpqSession session = withDorranDeparted();

        assertFalse(session.claimExpiredExecutionTick(a2001.getId(), 9_000L, 1_500L, AgentOpqRuntime::holderAlive));
        assertFalse(session.claimExecutionTick(a2001.getId(), 9_000L, 1_500L, AgentOpqRuntime::holderAlive));
        assertNotEquals(a2001.getId(), session.executionAgentId());
    }
}
