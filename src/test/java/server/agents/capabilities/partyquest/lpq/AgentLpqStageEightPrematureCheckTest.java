package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P6g LPQ C2.7 (revision P3): a companion run's wrong Blue Balloon check is judged on the boxes the event's players
 * stand on at the click, read as 2040043.js reads them. Standing as this attempt's formation, the attempt was judged
 * and the next one comes; otherwise someone was still moving: the attempt is kept (advancing would lose a combination
 * for good), the check is logged as ignored, and a companion says S8w once per attempt. A stock wrong check advances.
 */
class AgentLpqStageEightPrematureCheckTest {
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession atAttemptThree(boolean companion) {
        fx.areas(AgentLpqStageEightCoachTest.S8, AgentLpqStageEightCoachTest.boxes());
        crew = fx.crew(AgentLpqStageEightCoachTest.S8, new Point(-300, -100));
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_8, crew.toArray(Character[]::new));
        session.advanceStage8(2_500L);
        session.advanceStage8(2_600L);
        assertEquals(2, session.stage8Attempt());
        return session;
    }

    /** The five companions on the boxes of {@code boxes}, in order. */
    private void standOn(List<Integer> boxes) {
        for (int index = 0; index < 5; index++) {
            fx.moveTo(crew.get(index + 1), AgentLpqStageEightCoachTest.onBox(boxes.get(index)));
        }
    }

    @Test
    void aCheckWhileSomeoneIsStillMovingKeepsTheAttempt() {
        AgentLpqSession session = atAttemptThree(true);
        List<Integer> expected = session.stage8Combination();
        List<Integer> wrong = new java.util.ArrayList<>(expected);
        wrong.set(4, java.util.stream.IntStream.rangeClosed(1, 9).filter(box -> !expected.contains(box))
                .findFirst().orElseThrow());
        standOn(wrong);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), false, 5_000L);
            AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), false, 6_000L);

            assertEquals(2, session.stage8Attempt(), "the attempt is not skipped");
            assertEquals(2, log.startingWith("LPQ stage8 check ignored: session=" + session.sessionId()
                    + " attempt=3 reason=formation-mismatch occupied=").size());
        }
        fx.said(session, "s8-wait-3", crew.get(1), "wait, still moving. try again when we're all on.");
        assertEquals(6_000L, session.lastProgressAtMs());
    }

    @Test
    void aCheckOnThisAttemptsFormationMovesOnEvenIfTheCoordinatorHasNotSeenThemLand() {
        AgentLpqSession session = atAttemptThree(true);
        standOn(session.stage8Combination());

        AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), false, 5_000L);

        assertEquals(3, session.stage8Attempt());
        fx.neverSaid("s8-wait-3");
    }

    @Test
    void aStockWrongCheckAlwaysMovesOn() {
        AgentLpqSession session = atAttemptThree(false);
        standOn(List.of(1, 2, 3, 4, 5));

        AgentLpqHumanInteractionRuntime.stageEightChecked(crew.getFirst(), false, 5_000L);

        assertEquals(3, session.stage8Attempt());
    }
}
