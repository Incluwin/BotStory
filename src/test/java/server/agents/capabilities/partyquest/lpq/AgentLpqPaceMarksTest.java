package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C4.2: where a companion run marks the pace boundaries its phases do not show. Stage 1 begins with who entered;
 * a pass stage's count at the leader's feet; every judged Blue Balloon check (a wrong formation that was standing, and
 * the accepted one); Alishar seen with his HP, then down; the clear; each time a member goes down. A stock run marks
 * nothing.
 */
class AgentLpqPaceMarksTest {
    private static final Point AT = new Point(0, 0);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession run(boolean companionRun, AgentLpqSession.Phase phase, int mapId) {
        crew = fx.crew(mapId, AT);
        return fx.session(companionRun, phase, crew.toArray(Character[]::new));
    }

    @Test
    void stageOneBeginsWithWhoEntered() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.ENTERING, AgentLpqDefinition.stage(1).mapId());

        fx.tick(session, 3_000L);

        AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
        assertEquals(3_000L, marks.enteredAtMs());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), marks.entrants().stream().sorted().toList());
        assertEquals(List.of(), marks.members(), "the profile is cold: no power read");
    }

    @Test
    void aPassStagesCountAtHisFeetIsMarkedOnce() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_1, AgentLpqDefinition.stage(1).mapId());
        session.narrateOnce("s1-start");
        fx.tick(session, 3_000L);
        assertEquals(0L, session.pace().snapshot().passesAtFeetAtMs(1));

        fx.holds(crew.getFirst(), AgentLpqDefinition.PASS, 25);
        fx.tick(session, 4_000L);
        fx.tick(session, 5_000L);

        assertEquals(4_000L, session.pace().snapshot().passesAtFeetAtMs(1));
    }

    @Test
    void theAcceptedCheckAndAJudgedWrongOneAreCounted() {
        fx.areas(AgentLpqDefinition.stage(8).mapId(), AgentLpqStageEightCoachTest.boxes());
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_8, AgentLpqDefinition.stage(8).mapId());
        List<Integer> formation = session.stage8Combination();
        for (int i = 0; i < 5; i++) fx.moveTo(crew.get(i + 1), AgentLpqStageEightCoachTest.onBox(formation.get(i)));

        AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), false, 10_000L);
        AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), true, 20_000L);

        AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(10_000L, marks.firstCheckAtMs());
        assertEquals(2, marks.checks(), "the wrong formation, then the accepted one");
    }

    @Test
    void alisharIsSeenWithHisHpThenDown() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_9, AgentLpqDefinition.stage(9).mapId());
        session.narrateOnce("s9-start");
        fx.tick(session, 3_000L);
        assertEquals(0L, session.pace().snapshot().alisharSeenAtMs(), "not before he shows");

        Monster alishar = fx.monster(AgentLpqDefinition.stage(9).mapId(), AgentLpqDefinition.ALISHAR, new Point(300, 0));
        when(alishar.getMaxHp()).thenReturn(125_000L);
        fx.tick(session, 4_000L);
        fx.tick(session, 5_000L);
        fx.kill(alishar);
        fx.tick(session, 6_000L);

        AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(4_000L, marks.alisharSeenAtMs());
        assertEquals(125_000L, marks.alisharHp());
        assertEquals(6_000L, marks.alisharDownAtMs());
    }

    @Test
    void theKeyAtHisFeetOrInHisBagIsMarked() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_9, AgentLpqDefinition.stage(9).mapId());
        session.narrateOnce("s9-start");
        fx.tick(session, 3_000L);
        assertEquals(0L, session.pace().snapshot().keyAtFeetAtMs());

        fx.holds(crew.getFirst(), AgentLpqDefinition.BOSS_KEY, 1);
        fx.tick(session, 4_000L);
        fx.tick(session, 5_000L);

        assertEquals(4_000L, session.pace().snapshot().keyAtFeetAtMs());
    }

    @Test
    void theClearAndADeathAreMarked() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_3, AgentLpqDefinition.stage(3).mapId());
        session.narrateOnce("s3-start");
        when(crew.get(3).getHp()).thenReturn(0);
        fx.tick(session, 3_000L);
        fx.tick(session, 4_000L);
        assertEquals(Map.of(4, 1), session.pace().snapshot().deaths(), "once per time down");

        when(fx.event.isEventCleared()).thenReturn(true);
        fx.tick(session, 5_000L);

        assertEquals(5_000L, session.pace().snapshot().clearedAtMs());
    }

    @Test
    void aStockRunMarksNothing() {
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_1, AgentLpqDefinition.stage(1).mapId());
        fx.holds(crew.getFirst(), AgentLpqDefinition.PASS, 25);
        fx.tick(session, 3_000L);

        AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
        assertEquals(0L, marks.passesAtFeetAtMs(1));
        assertEquals(0L, marks.enteredAtMs());
        assertEquals(Map.of(), marks.deaths());
    }
}
