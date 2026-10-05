package server.agents.capabilities.partyquest.lpq;

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
 * LPQ base fix 2: a dead (or no longer loaded) coordinator lease holder hands the LPQ lease over at once. A dead
 * Agent's tick ends before the LPQ runtime, so its lease used to freeze every coordinator pass, re-registration
 * included, until the lease ran out. A live holder keeps its lease until it expires, as before.
 */
class AgentLpqLeaseFailoverTest {
    private static final int HUMAN = 762_400;
    private static final int A = 762_401;
    private static final int B = 762_402;
    private static final long LEASE_MS = 3_000L;

    private static AgentLpqSession session() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, HUMAN, 6, 0L);
        session.addMember(HUMAN, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(A, AgentLpqMemberState.MemberType.AGENT);
        session.addMember(B, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(HUMAN, A);
        return session;
    }

    private static IntPredicate alive(boolean holderAlive) {
        return id -> holderAlive;
    }

    @Test
    void aLiveAgentTakesOverFromADeadHolderAtOnce() {
        AgentLpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aLiveHolderKeepsItsLeaseUntilItExpires() {
        AgentLpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 3_000L, LEASE_MS, alive(true)), "an expired lease is free");
        assertTrue(session.claimExecutionTick(B, 3_100L, LEASE_MS), "the old overload is unchanged for the holder");
        assertFalse(session.claimExecutionTick(A, 3_200L, LEASE_MS), "and still refuses while the lease runs");
    }

    @Test
    void theWatchdogTakesOverFromADeadHolderOnlyWhenItIsDead() {
        AgentLpqSession dead = session();
        assertTrue(dead.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(dead.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, dead.executionAgentId());

        AgentLpqSession live = session();
        assertTrue(live.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(B, 600L, LEASE_MS), "the old overload is unchanged");
        assertEquals(A, live.executionAgentId());
    }

    @Test
    void aHumanNeverClaims() {
        AgentLpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExecutionTick(HUMAN, 500L, LEASE_MS, alive(false)));
        assertFalse(session.claimExpiredExecutionTick(HUMAN, 500L, LEASE_MS, alive(false)));
        assertEquals(A, session.executionAgentId());
    }

    @Test
    void theOldHolderCannotReclaimWhileTheNewLeaseRuns() {
        AgentLpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, id -> id != A));

        assertFalse(session.claimExecutionTick(A, 600L, LEASE_MS, alive(true)), "no second driver");
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aHolderIsAliveWhileItsAgentIsLoadedWithHpLeft() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            Character living = run.companion(762_410, "Lioren");
            Character dead = run.companion(762_411, "Halvard");
            when(dead.getHp()).thenReturn(0);

            assertTrue(AgentLpqRuntime.holderAlive(762_410));
            assertFalse(AgentLpqRuntime.holderAlive(762_411));
            assertFalse(AgentLpqRuntime.holderAlive(762_419), "an Agent that is not loaded holds nothing");
            assertTrue(living.getHp() > 0);
        }
    }

    @Test
    void bothLeasePathsAskWhetherTheHolderLives() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqRuntime.java")).replaceAll("\\s+", " ");
        String watchdog = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqWatchdogRuntime.java"))
                .replaceAll("\\s+", " ");

        assertTrue(runtime.contains(
                "session.claimExecutionTick(agent.getId(), nowMs, LEASE_MS, AgentLpqRuntime::holderAlive)"));
        assertFalse(runtime.contains("session.claimExecutionTick(agent.getId(), nowMs, LEASE_MS))"));
        assertTrue(watchdog.contains(
                "session.claimExpiredExecutionTick(agent.getId(), nowMs, lease, AgentLpqRuntime::holderAlive)"));
        assertFalse(watchdog.contains("session.claimExpiredExecutionTick(agent.getId(), nowMs, lease))"));
    }
}
