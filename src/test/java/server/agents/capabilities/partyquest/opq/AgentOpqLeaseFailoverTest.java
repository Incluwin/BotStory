package server.agents.capabilities.partyquest.opq;

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
 * P6h OPQ base fix OB9 (with AgentOpqWatchdogTest): a dead (or no longer loaded) coordinator lease holder hands the
 * OPQ lease over at once, as LPQ's does. A dead Agent's tick ends before the OPQ runtime, so its lease used to freeze
 * every coordinator pass until it ran out. A live holder keeps its lease until it expires, as before.
 */
class AgentOpqLeaseFailoverTest {
    private static final int HUMAN = 763_400;
    private static final int A = 763_401;
    private static final int B = 763_402;
    private static final long LEASE_MS = 3_000L;

    private static AgentOpqSession session() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, HUMAN, 6, 0L);
        session.addMember(HUMAN, AgentOpqMemberState.MemberType.HUMAN);
        session.addMember(A, AgentOpqMemberState.MemberType.AGENT);
        session.addMember(B, AgentOpqMemberState.MemberType.AGENT);
        session.setLeadership(HUMAN, A);
        return session;
    }

    private static IntPredicate alive(boolean holderAlive) {
        return id -> holderAlive;
    }

    @Test
    void aLiveAgentTakesOverFromADeadHolderAtOnce() {
        AgentOpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aLiveHolderKeepsItsLeaseUntilItExpires() {
        AgentOpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 3_000L, LEASE_MS, alive(true)), "an expired lease is free");
        assertTrue(session.claimExecutionTick(B, 3_100L, LEASE_MS), "the old overload is unchanged for the holder");
        assertFalse(session.claimExecutionTick(A, 3_200L, LEASE_MS), "and still refuses while the lease runs");
    }

    @Test
    void theWatchdogTakesOverOnlyAnExpiredOrDeadHoldersLease() {
        AgentOpqSession dead = session();
        assertTrue(dead.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(dead.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(false)));
        assertEquals(B, dead.executionAgentId());

        AgentOpqSession live = session();
        assertTrue(live.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(B, 500L, LEASE_MS, alive(true)));
        assertFalse(live.claimExpiredExecutionTick(A, 600L, LEASE_MS, alive(true)), "not even its own live lease");
        assertTrue(live.claimExpiredExecutionTick(B, 3_000L, LEASE_MS, alive(true)), "an expired lease is free");
        assertEquals(B, live.executionAgentId());
    }

    @Test
    void aHumanNeverTakesTheWatchdogsLease() {
        AgentOpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));

        assertFalse(session.claimExpiredExecutionTick(HUMAN, 500L, LEASE_MS, alive(false)));
        assertFalse(session.claimExpiredExecutionTick(763_499, 9_000L, LEASE_MS, alive(false)), "not a member");
        assertEquals(A, session.executionAgentId());
    }

    @Test
    void theOldHolderCannotReclaimWhileTheNewLeaseRuns() {
        AgentOpqSession session = session();
        assertTrue(session.claimExecutionTick(A, 0L, LEASE_MS, alive(true)));
        assertTrue(session.claimExecutionTick(B, 500L, LEASE_MS, id -> id != A));

        assertFalse(session.claimExecutionTick(A, 600L, LEASE_MS, alive(true)), "no second driver");
        assertEquals(B, session.executionAgentId());
    }

    @Test
    void aHolderIsAliveWhileItsAgentIsLoadedWithHpLeft() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character living = run.companion(763_410, "Lioren");
            Character dead = run.companion(763_411, "Halvard");
            when(dead.getHp()).thenReturn(0);

            assertTrue(AgentOpqRuntime.holderAlive(763_410));
            assertFalse(AgentOpqRuntime.holderAlive(763_411));
            assertFalse(AgentOpqRuntime.holderAlive(763_419), "an Agent that is not loaded holds nothing");
            assertTrue(living.getHp() > 0);
        }
    }

    @Test
    void theRuntimeTickAsksWhetherTheHolderLives() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/opq/AgentOpqRuntime.java")).replaceAll("\\s+", " ");
        assertTrue(runtime.contains("session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS,"
                + " AgentOpqRuntime::holderAlive)"), "the Agent tick's claim");
    }
}
