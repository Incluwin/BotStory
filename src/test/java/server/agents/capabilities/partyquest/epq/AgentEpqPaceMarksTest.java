package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Step 5.2: a companion run marks its pace as it goes, each boundary at its first sighting: the party inside (the
 * bind), each stage's first phase, the twentieth marble and the stone (at the leader's feet or in his bag), Poison
 * Golem 9300180 first alive, the clear, every time a member went down and every tap-out. A stock run marks nothing.
 */
class AgentEpqPaceMarksTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void eachMarkKeepsItsFirstSighting() {
        AgentEpqPaceMarks marks = new AgentEpqPaceMarks();
        marks.markEntered(List.of(), List.of(1, 2), 2_000L);
        marks.markEntered(List.of(), List.of(9), 3_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_TWO, 4_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_TWO, 5_000L);
        marks.markMarbles(6_000L);
        marks.markMarbles(7_000L);
        marks.markStone(8_000L);
        marks.markStone(9_000L);
        marks.markGolem(10_000L);
        marks.markGolem(11_000L);
        marks.markCleared(12_000L);
        marks.markCleared(13_000L);
        marks.markDown(2);
        marks.markDown(2);
        marks.markTappedOut(3);

        AgentEpqPaceMarks.Snapshot snapshot = marks.snapshot();

        assertEquals(2_000L, snapshot.enteredAtMs());
        assertEquals(List.of(1, 2), snapshot.entrants());
        assertEquals(4_000L, snapshot.phase(AgentEpqSession.Phase.STAGE_TWO));
        assertEquals(0L, snapshot.phase(AgentEpqSession.Phase.STAGE_THREE));
        assertEquals(6_000L, snapshot.marblesAtMs());
        assertEquals(8_000L, snapshot.stoneAtMs());
        assertEquals(10_000L, snapshot.golemAtMs());
        assertEquals(12_000L, snapshot.clearedAtMs());
        assertEquals(Map.of(2, 2), snapshot.deaths());
        assertEquals(Set.of(3), snapshot.tappedOut());
    }

    /** The bind and a phase change seen by the lease holder's passes; the run's own marks afterwards. */
    private AgentEpqPaceMarks.Snapshot aRunGoesIn(boolean companionRun) {
        AgentEpqSession session = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun,
                ben, mira, tobi, una);
        run.party(ben, mira, tobi, una);
        AgentEpqCoordinator.tickSession(session, 2_000L); // the bind: the leader stands in the event
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_ONE_MAP);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        when(mira.getHp()).thenReturn(0); // she goes down on stage 1
        AgentEpqCoordinator.tickSession(session, 4_000L);
        return session.pace().snapshot();
    }

    @Test
    void aCompanionRunMarksItsBindItsPhasesAndItsDeaths() {
        AgentEpqPaceMarks.Snapshot marks = aRunGoesIn(true);

        assertEquals(2_000L, marks.enteredAtMs());
        assertEquals(List.of(1, 2, 3, 4), marks.entrants());
        assertEquals(2_000L, marks.phase(AgentEpqSession.Phase.ENTERING));
        assertEquals(3_000L, marks.phase(AgentEpqSession.Phase.STAGE_ONE));
        assertEquals(Map.of(2, 1), marks.deaths());
    }

    @Test
    void pinAStockRunMarksNothing() {
        AgentEpqPaceMarks.Snapshot marks = aRunGoesIn(false);

        assertEquals(0L, marks.enteredAtMs());
        assertTrue(marks.phaseAtMs().isEmpty());
        assertTrue(marks.deaths().isEmpty());
    }

    @Test
    void theLeaderFirstHoldingTheTwentyOrTheStoneIsAMark() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        AgentEpqCoordinator.tickSession(session, 4_000L);
        AgentEpqCoordinator.tickSession(session, 5_000L);

        assertEquals(3_000L, session.pace().snapshot().marblesAtMs());
        assertEquals(4_000L, session.pace().snapshot().stoneAtMs());
    }
}
