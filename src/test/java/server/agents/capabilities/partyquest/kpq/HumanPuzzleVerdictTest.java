package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.verification.VerificationMode;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.runtime.AgentModeService;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A human leader's Cloto verdict counts against a formation only once the companions hold it. Live (2026-09-30) the
 * leader clicked Cloto while they were still climbing to their ropes; her "wrong" waited until they stood still and
 * then dropped a formation nobody had checked. Stage 2 took 10 minutes, and the run hit KPQ's 30-minute timer.
 */
class HumanPuzzleVerdictTest {
    private static final AgentKpqDefinition.CombinationStage STAGE_2 = AgentKpqDefinition.combinationStage(2);
    private static final String CHECK = "Ben, please check this formation with Cloto.";

    private KpqRunFixture run;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        // The coordinator walks and stops companions through these; the tests place them by position instead.
        modes = mockStatic(AgentModeService.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        // Cloto has set this stage's answer, so each tick assigns and checks the formation.
        when(run.event.getProperty(STAGE_2.answerProperty())).thenReturn("answer");
    }

    @AfterEach
    void tearDown() {
        objective.close();
        variation.close();
        modes.close();
        run.close();
    }

    @Test
    void aRejectionBeforeTheFormationIsHeldKeepsItAndTheLeaderIsAskedAgain() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        onStageTwo(ben, mira, pip, tobi);
        climbing(mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();

        // Ben clicks Cloto while the companions are still climbing, and she finds the ropes empty.
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        holdTheirRopes(session, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        assertEquals(0, session.attemptIndex());
        assertEquals(formation, session.combination());
        askedToCheck(times(1));

        // Pip slips off his rope, and Ben clicks again before he is back on it.
        climbing(pip);
        AgentKpqCoordinator.tick(session, 5_000L);
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        holdTheirRopes(session, pip);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        assertEquals(0, session.attemptIndex());
        assertEquals(formation, session.combination());
        askedToCheck(times(2));
    }

    @Test
    void aRejectionOfTheHeldFormationStillMovesOnButOnlyOnce() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        onStageTwo(ben, mira, pip, tobi);
        climbing(mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();
        holdTheirRopes(session, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);
        askedToCheck(times(1));

        // Ben checks the formation they hold, and Cloto says it is wrong.
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        AgentKpqCoordinator.tick(session, 4_500L);

        assertEquals(1, session.attemptIndex());

        // He clicks once more before the next formation is even assigned; that "wrong" belongs to no formation.
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        AgentKpqCoordinator.tick(session, 5_000L);
        assertNotEquals(formation, session.combination());
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        assertEquals(1, session.attemptIndex());
        askedToCheck(times(2));
    }

    @Test
    void anAcceptanceCountsEvenBeforeTheFormationIsHeld() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        onStageTwo(ben, mira, pip, tobi);
        climbing(mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();

        // Cloto has already cleared the stage, so her yes stands however early it came.
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, true);
        holdTheirRopes(session, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        assertEquals(0, session.attemptIndex());
        assertEquals(formation, session.combination());
        askedToCheck(never());
    }

    @Test
    void aRejectionRightAfterTheFormationIsResetDoesNotCountAgainstIt() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        Character tobi = run.companion(4, "Tobi");
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, mira, pip, tobi);
        onStageTwo(ben, mira, pip, tobi);
        climbing(mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();
        holdTheirRopes(session, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        // A member who leaves or revives resets the formation within a tick, and Ben clicks before the next one.
        session.resetFormation();
        AgentKpqSessionRegistry.recordHumanPuzzleValidation(ben.getId(), 2, false);
        AgentKpqCoordinator.tick(session, 5_000L);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        assertEquals(0, session.attemptIndex());
        assertEquals(formation, session.combination());
        askedToCheck(times(2));
    }

    @Test
    void anAgentLeaderStillChecksWithClotoItselfOnceTheFormationHolds() {
        Character kai = run.companion(1, "Kai");
        Character mira = run.companion(2, "Mira");
        Character pip = run.companion(3, "Pip");
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_2, kai, mira, pip);
        onStageTwo(kai, mira, pip);
        climbing(kai, mira, pip);
        AgentKpqCoordinator.tick(session, 2_000L);
        List<Integer> formation = session.combination();
        holdTheirRopes(session, kai, mira, pip);
        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 4_000L);
        long checkAtMs = 4_000L + AgentKpqCoordinator.puzzleCheckDelayMs(session.seed(), 2, 0);

        AgentKpqCoordinator.tick(session, checkAtMs - 1L);
        assertEquals("", session.blockerKey());

        // This map mock has no Cloto, so the leader's own check is the stock blocker that remains.
        AgentKpqCoordinator.tick(session, checkAtMs);
        assertEquals("stage2-npc-check", session.blockerKey());
        assertEquals(0, session.attemptIndex());
        assertEquals(formation, session.combination());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), contains("check this formation")), never());
    }

    private void askedToCheck(VerificationMode mode) {
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(CHECK)), mode);
    }

    private static void onStageTwo(Character... members) {
        for (Character member : members) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_2_MAP);
        }
    }

    /** Still on the way: below every rope. */
    private static void climbing(Character... companions) {
        for (Character companion : companions) {
            doReturn(new Point(0, 0)).when(companion).getPosition();
        }
    }

    /** Each companion holds the rope the current formation gave it. */
    private static void holdTheirRopes(AgentKpqSession session, Character... companions) {
        for (Character companion : companions) {
            doAnswer(call -> STAGE_2.center(session.member(companion.getId()).assignedPosition()))
                    .when(companion).getPosition();
        }
    }
}
