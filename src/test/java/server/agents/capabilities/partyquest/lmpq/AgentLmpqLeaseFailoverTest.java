package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 8, lease failover (LPQ's rule): a dead or unloaded coordinator lease holder hands the lease over at
 * once, both to another Agent's tick and to the watchdog; a live holder keeps it until it expires; a human never
 * claims it; the old three-argument claim is unchanged.
 */
class AgentLmpqLeaseFailoverTest {
    private static final int HUMAN = 782_400;
    private static final int A = 782_401;
    private static final int B = 782_402;
    private static final long LEASE_MS = 3_000L;

    private static AgentLmpqSession session() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, HUMAN, 3, 0L);
        session.addMember(HUMAN, AgentLmpqMemberState.MemberType.HUMAN);
        session.addMember(A, AgentLmpqMemberState.MemberType.AGENT);
        session.addMember(B, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(HUMAN, A);
        return session;
    }

    private static IntPredicate alive(boolean holderAlive) {
        return id -> holderAlive;
    }

    @Test
    void aLiveAgentTakesOverFromADeadHolderAtOnce() {
        AgentLmpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aLiveHolderKeepsItsLeaseUntilItExpires() {
        AgentLmpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 3_000L, LEASE_MS, alive(true)), "an expired lease is free");
        assertTrue(session.claimExecutionTick(B, 3_100L, LEASE_MS), "the old overload is unchanged for the holder");
        assertFalse(session.claimExecutionTick(A, 3_200L, LEASE_MS), "and still refuses while the lease runs");
    }

    @Test
    void theWatchdogTakesOverFromADeadHolderOrAnExpiredLeaseOnly() {
        AgentLmpqSession dead = session();
        assertTrue(dead.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(dead.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, dead.executionAgentId());

        AgentLmpqSession live = session();
        assertTrue(live.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(A, 600L, LEASE_MS, alive(true)), "not even its own holder");
        assertEquals(A, live.executionAgentId());
        assertTrue(live.claimExpiredExecutionTick(B, 3_000L, LEASE_MS, alive(true)), "an expired lease is free");
    }

    @Test
    void aHumanNeverClaims() {
        AgentLmpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExecutionTick(HUMAN, 500L, LEASE_MS, alive(false)));
        assertFalse(session.claimExpiredExecutionTick(HUMAN, 500L, LEASE_MS, alive(false)));
        assertEquals(A, session.executionAgentId());
    }

    @Test
    void theOldHolderCannotReclaimWhileTheNewLeaseRuns() {
        AgentLmpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, id -> id != A));

        assertFalse(session.claimExecutionTick(A, 600L, LEASE_MS, alive(true)), "no second driver");
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aHolderIsAliveWhileItsAgentIsLoadedWithHpLeft() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            Character living = run.companion(782_410, "Lioren");
            Character dead = run.companion(782_411, "Halvard");
            when(dead.getHp()).thenReturn(0);

            assertTrue(AgentLmpqRuntime.holderAlive(782_410));
            assertFalse(AgentLmpqRuntime.holderAlive(782_411));
            assertFalse(AgentLmpqRuntime.holderAlive(782_419), "an Agent that is not loaded holds nothing");
            assertTrue(living.getHp() > 0);
        }
    }

    @Test
    void theTickTakesOverFromADeadHolder() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            Character ben = run.human(782_420, "Ben");
            Character mira = run.companion(782_421, "Mira");
            Character kes = run.companion(782_422, "Kes");
            AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER,
                    AgentLmpqSession.Phase.FARMING, ben, mira, kes);
            assertTrue(session.claimExecutionTick(mira.getId(), 10_000L, LEASE_MS));
            when(mira.getHp()).thenReturn(0); // she died holding the lease

            AgentLmpqRuntime.tick(run.entry(kes), kes, 10_500L);

            assertEquals(kes.getId(), session.executionAgentId());
        }
    }

    @Test
    void bothLeasePathsAskWhetherTheHolderLives() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqRuntime.java")).replaceAll("\\s+", " ");
        String watchdog = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqWatchdogRuntime.java"))
                .replaceAll("\\s+", " ");

        assertTrue(runtime.contains("session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS, "
                + "AgentLmpqRuntime::holderAlive)"));
        assertTrue(watchdog.contains("session.claimExpiredExecutionTick(agent.getId(), nowMs, "
                + "AgentLmpqRuntime.coordinatorLeaseMs(), AgentLmpqRuntime::holderAlive)"));
    }
}
