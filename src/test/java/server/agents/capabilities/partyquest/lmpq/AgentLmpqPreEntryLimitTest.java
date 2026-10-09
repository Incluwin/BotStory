package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A run that never enters the maze is bounded. A companion run fails when its leader has not entered within
 * humanIdleLimitMs() of the admission ("LMPQ was never entered"), which is the wait '!lmpqtest party prod' promises
 * him. Before the entry only a phase change is progress, so members walking around town (the leader wandering, an
 * Agent refused at Rolly) no longer keep a stock run from stalling; and the event timer cap runs from admission
 * until the run is inside.
 */
class AgentLmpqPreEntryLimitTest {
    private static final long ADMITTED = 1_000L;
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** Ben's map changes on every read: he walks from map to map around Ludibrium. */
    private void benWanders() {
        AtomicInteger step = new AtomicInteger();
        when(ben.getMapId()).thenAnswer(call -> 220_000_000 + 100 * (step.incrementAndGet() % 7));
        when(ben.getEventInstance()).thenReturn(null);
    }

    private AgentLmpqSession atRolly(AgentLmpqSession.Mode mode, boolean companionRun, Character... members) {
        AgentLmpqSession session = run.session(mode, companionRun, AgentLmpqSession.Phase.PREPARING, members);
        session.clearEventInstance();
        return session;
    }

    @Test
    void aCompanionRunWhoseLeaderNeverEntersFailsWhenItsPromisedWaitRunsOut() {
        AgentLmpqSession session = atRolly(AgentLmpqSession.Mode.HUMAN_LEADER, true, ben, mira, kes, pel);
        benWanders();
        long promised = AgentLmpqCoordinator.humanIdleLimitMs();

        for (long now = ADMITTED + 1_000L; now < ADMITTED + promised; now += 1_000L) {
            AgentLmpqCoordinator.tickSession(session, now);
        }
        assertFalse(session.terminal(), session.failure());

        AgentLmpqCoordinator.tickSession(session, ADMITTED + promised);
        assertEquals("LMPQ was never entered", session.failure());
    }

    @Test
    void aCompanionRunThatEnteredInTimeGoesOn() {
        AgentLmpqSession session = atRolly(AgentLmpqSession.Mode.HUMAN_LEADER, true, ben, mira, kes, pel);
        AgentLmpqCoordinator.tickSession(session, ADMITTED + 200_000L); // Ben is inside Room 3: the run binds
        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());

        session.markProgress(ADMITTED + 300_000L);
        AgentLmpqCoordinator.tickSession(session, ADMITTED + 300_000L);

        assertFalse(session.terminal(), session.failure());
    }

    @Test
    void anAgentRefusedAtRollyStallsTheStockRunAlthoughItsHumanWalksAround() {
        // A director's run: Mira leads and clicks Rolly, who keeps refusing; Ben, a human member, walks around town.
        AgentLmpqSession session = atRolly(AgentLmpqSession.Mode.HUMAN_MEMBER, false, mira, kes, pel, ben);
        benWanders();
        Point rolly = new Point(76, -712);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        when(mira.getPosition()).thenReturn(rolly);
        when(mira.getEventInstance()).thenReturn(null);
        when(run.actions.npcPosition(mira, AgentLmpqDefinition.ENTRY_NPC)).thenReturn(rolly);
        when(run.partyQuests.runNpc(mira, AgentLmpqDefinition.ENTRY_NPC, 0)).thenReturn(true);

        long now = ADMITTED;
        while (!session.terminal() && now < ADMITTED + 300_000L) {
            now += 1_000L;
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, now);
            AgentLmpqCoordinator.tickSession(session, now);
        }

        assertTrue(session.terminal(), "bounded by the stall, not by Rolly's refusals");
        assertEquals("LMPQ stalled in ENTERING", session.failure());
        assertTrue(now <= ADMITTED + 2_000L + AgentLmpqCoordinator.humanIdleLimitMs(), "at " + now);
    }

    @Test
    void aStockRunIsNotBoundByTheCompanionWait() {
        AgentLmpqSession session = atRolly(AgentLmpqSession.Mode.HUMAN_LEADER, false, ben, mira, kes, pel);
        benWanders();
        long now = ADMITTED + AgentLmpqCoordinator.humanIdleLimitMs() + 1_000L;
        session.markProgress(now); // whatever keeps it busy, only the stall and the timer may end it

        AgentLmpqCoordinator.tickSession(session, now);

        assertFalse(session.terminal(), session.failure());
    }
}
