package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.5: a companion run whose stage objective is gone for good (confirmed OBJECTIVE_LOST_CONFIRM_MS without a
 * break) ends LOST at once instead of waiting out the stall watchdog: stage 4 when the marbles in bags and on the floor
 * plus one for each live Poison Flower fall short of twenty; stage 5 when no stone box is left and no purple stone is
 * in a bag or on the floor; the golem's map before any golem when no stone is anywhere and the altar has not fired.
 */
class AgentEpqObjectiveLostTest {
    private static final long CONFIRM = 10_000L;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun(AgentEpqSession.Phase phase, int mapId) {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, mapId);
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una);
    }

    private List<Monster> flowers(int count) {
        List<Monster> flowers = new ArrayList<>();
        for (int i = 0; i < count; i++) flowers.add(run.monster(AgentEpqDefinition.POISON_FLOWER));
        return flowers;
    }

    private void marbles(int leaderHolds, int companionHolds) {
        when(run.actions.itemCount(ben, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(leaderHolds);
        when(run.actions.itemCount(mira, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(companionHolds);
    }

    private Reactor altar(byte state) {
        Reactor altar = mock(Reactor.class);
        when(altar.getPosition()).thenReturn(new Point(392, 120));
        when(altar.getState()).thenReturn(state);
        when(altar.getArea()).thenReturn(new Rectangle(301, 86, 174, 70));
        when(run.map.getReactorById(AgentEpqDefinition.ALTAR_REACTOR)).thenReturn(altar);
        return altar;
    }

    /** Ticks at the start, 1 ms before the confirm time and at it; true when the run ended LOST only at it. */
    private void lostOnlyAtTheConfirmTime(AgentEpqSession session, String item) {
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 2_000L + CONFIRM - 1L);
            assertFalse(session.terminal(), "1 ms before the confirm time");

            AgentEpqCoordinator.tickSession(session, 2_000L + CONFIRM);

            assertEquals(1, lines.startingWith("EPQ objective lost: session=" + session.sessionId() + " item=" + item)
                    .size());
            assertEquals(1, lines.startingWith("EPQ parting: session=" + session.sessionId() + " kind=LOST").size());
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    private void neverLost(AgentEpqSession session) {
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + CONFIRM);
        AgentEpqCoordinator.tickSession(session, 2_000L + 3 * CONFIRM);
        assertFalse(session.terminal());
    }

    @Test
    void fifteenMarblesAndFourFlowersAreLostAndHisSixAreCountedOnce() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        marbles(6, 9);
        flowers(4);

        lostOnlyAtTheConfirmTime(session, "marbles");
        verify(run.packets, times(1)).broadcastChatText(mira,
                "we lost the monster marbles, so there's no way to finish now. let's head out.", false, 0);
    }

    @Test
    void fifteenMarblesAndFiveFlowersCanStillMakeTwenty() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        marbles(6, 9);
        flowers(5);

        neverLost(session);
    }

    @Test
    void aFlowerRevivingInsideTheConfirmTimeStopsTheClock() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        marbles(10, 9);
        Monster flower = flowers(1).getFirst();
        when(flower.isAlive()).thenReturn(false);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 11_000L); // 9000 ms with no flower alive
        when(flower.isAlive()).thenReturn(true); // it revived
        AgentEpqCoordinator.tickSession(session, 12_000L);
        when(flower.isAlive()).thenReturn(false);
        AgentEpqCoordinator.tickSession(session, 13_000L);
        AgentEpqCoordinator.tickSession(session, 13_000L + CONFIRM - 1L);

        assertFalse(session.terminal(), "the clock started again at 13000");
    }

    @Test
    void aLeaderAlreadyUpOnStageFiveAfterHisHandInIsNeverLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        run.onMap(ben, AgentEpqDefinition.STAGE_FIVE_MAP);
        marbles(0, 0);
        Reactor box = mock(Reactor.class); // stage 5's boxes are still there
        when(box.getId()).thenReturn(AgentEpqDefinition.STONE_BOX);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        when(run.actions.reactors(ben)).thenReturn(List.of(box));

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + CONFIRM);
        AgentEpqCoordinator.tickSession(session, 2_000L + 3 * CONFIRM);

        assertFalse(session.terminal());
    }

    @Test
    void aStoneThatExpiredWithNoBoxLeftIsLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FIVE, AgentEpqDefinition.STAGE_FIVE_MAP);
        session.markStageFiveStoneHandedOff(1_500L);

        lostOnlyAtTheConfirmTime(session, "stone");
    }

    @Test
    void aStoneInTheLeadersBagIsNeverLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FIVE, AgentEpqDefinition.STAGE_FIVE_MAP);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);

        neverLost(session);
    }

    @Test
    void noStoneAnywhereOnTheGolemsMapBeforeAnyGolemIsLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);

        lostOnlyAtTheConfirmTime(session, "stone");
    }

    @Test
    void aStoneTheAltarIsTakingIsNotLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        run.drop(AgentEpqDefinition.MAGIC_STONE, 1, new Point(390, 150), true, ben.getId(), 1_900L);

        neverLost(session);
    }

    @Test
    void anAltarThatFiredBeforeAnyGolemIsNotLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 1);

        neverLost(session);
    }

    @Test
    void aGolemSeenIsNeverALostStone() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        assertTrue(session.claimLogMark("golem-9300181"));

        neverLost(session);
    }

    @Test
    void theStoneInTheLeadersBagOnTheGolemsMapIsNeverLost() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);

        neverLost(session);
    }

    @Test
    void pinAStockRunIsNeverLost() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FIVE_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_FIVE, ben, mira, tobi, una);

        neverLost(session);
    }
}
