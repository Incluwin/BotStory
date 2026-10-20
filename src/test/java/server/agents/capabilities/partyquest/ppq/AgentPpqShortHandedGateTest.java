package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6i Step 4, plan 9.1: the death gate's short-handed clause. In a companion run held at PiratePQ.js's floor (three
 * registered), a dead companion waits while another companion still stands; once the last one is down too the run ends
 * in its words (WENT_DOWN) instead of lying there to the stage timer. A stock run only holds.
 */
class AgentPpqShortHandedGateTest {
    private static final int LEADER = 865_000;
    private static final int A = 865_001;
    private static final int B = 865_002;

    private static final class Event implements AgentPpqDeathDepartureGate.EventView {
        final Set<Integer> registered = new LinkedHashSet<>(List.of(LEADER, A, B));

        @Override
        public boolean disposed() { return false; }

        @Override
        public boolean cleared() { return false; }

        @Override
        public int leaderId() { return LEADER; }

        @Override
        public Set<Integer> registeredIds() { return Set.copyOf(registered); }
    }

    private static AgentPpqSession session(boolean companionRun) {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, LEADER, false, 1_000L);
        session.addMember(LEADER, AgentPpqMemberState.MemberType.HUMAN);
        session.addMember(A, AgentPpqMemberState.MemberType.AGENT);
        session.addMember(B, AgentPpqMemberState.MemberType.AGENT);
        session.setLeadership(LEADER, A);
        session.setCompanionAdopted(companionRun);
        return session;
    }

    @Test
    void theLastCompanionDownAtTheFloorEndsACompanionRun() {
        AgentPpqSession session = session(true);
        Event event = new Event();
        AtomicInteger respawns = new AtomicInteger();

        assertFalse(AgentPpqDeathDepartureGate.respawnOrHold(session, A, "Rurik", event, respawns::incrementAndGet,
                10_000L));
        assertFalse(session.terminal(), "Yara still stands");

        assertFalse(AgentPpqDeathDepartureGate.respawnOrHold(session, B, "Yara", event, respawns::incrementAndGet,
                12_000L));

        assertTrue(session.terminal());
        assertEquals("Yara went down, and no companion of the PPQ run stands", session.failure());
        assertEquals(0, respawns.get(), "nobody pressed OK: the release takes them out");
    }

    @Test
    void aStockRunOnlyHolds() {
        AgentPpqSession session = session(false);
        Event event = new Event();
        AtomicInteger respawns = new AtomicInteger();

        AgentPpqDeathDepartureGate.respawnOrHold(session, A, "Rurik", event, respawns::incrementAndGet, 10_000L);
        AgentPpqDeathDepartureGate.respawnOrHold(session, B, "Yara", event, respawns::incrementAndGet, 12_000L);

        assertFalse(session.terminal());
        assertEquals(2, session.heldDeadCount());
    }

    @Test
    void aCompanionLeavingAboveTheFloorEndsNothing() {
        AgentPpqSession session = session(true);
        Event event = new Event();
        event.registered.add(865_003); // a fourth player: one may leave
        AtomicInteger respawns = new AtomicInteger();

        assertTrue(AgentPpqDeathDepartureGate.respawnOrHold(session, A, "Rurik", event, respawns::incrementAndGet,
                10_000L));

        assertFalse(session.terminal());
        assertEquals(1, respawns.get());
    }
}
