package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentPotionService;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 3.2: a companion on the leader's map coaches him through each stage, once each: what the stage wants of him on
 * entry, and a nudge once he has held a stage item for LEADER_HINT_MS (said at that time, not 1 ms before). The stone's
 * finder says the stone lines when it stands on his map. Nothing is said without a companion on his map (the line is
 * said once one arrives); nothing about the altar while the golem fight is on, its revive gaps included (Z3). A stock
 * run hears none of it.
 */
class AgentEpqCoachingTest {
    private static final long HINT = 20_000L; // LEADER_HINT_MS
    private static final Point ALTAR = new Point(392, 120);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);

    AgentEpqCoachingTest() {
        run.party(ben, mira, tobi, una);
        when(run.actions.liveMonsterCount(eq(ben), any())).thenReturn(5);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun(AgentEpqSession.Phase phase, int mapId) {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, mapId);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void said(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    private void notSaid(String line) {
        verify(run.packets, never()).broadcastChatText(any(), eq(line), anyBoolean(), anyInt());
    }

    private Reactor altar(byte state) {
        Reactor altar = mock(Reactor.class);
        when(altar.getPosition()).thenReturn(new Point(ALTAR));
        when(altar.getState()).thenReturn(state);
        when(altar.getArea()).thenReturn(new Rectangle(301, 86, 174, 70)); // x 301..475, y 86..156
        when(run.map.getReactorById(AgentEpqDefinition.ALTAR_REACTOR)).thenReturn(altar);
        return altar;
    }

    @Test
    void stageOneOnEntryAndOnceTheLastTreeFalls() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_ONE, AgentEpqDefinition.STAGE_ONE_MAP);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        said(mira, "these trees are poisoned. we clear every one and the way opens.");
        notSaid("that's the last tree. the portal's on the right.");

        when(run.actions.liveMonsterCount(eq(ben), any())).thenReturn(0);
        AgentEpqCoordinator.tickSession(session, 4_000L);
        said(mira, "that's the last tree. the portal's on the right.");
    }

    @Test
    void stageTwoOnEntryAndADilutedPoisonHeldTooLong() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_TWO, AgentEpqDefinition.STAGE_TWO_MAP);
        when(run.actions.itemCount(ben, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
        String hint = "Ben, drop that diluted poison by the thorns on the right.";

        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(mira, "the bugs carry the poison. we pull them into the fountain first, then hit them in the water.");
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT - 1L);
        notSaid(hint);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);
        said(mira, hint);
    }

    @Test
    void theMazeOnEntryAndTheFirstCompanionAtEllinSaysSoFromItsOwnTick() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_THREE, AgentEpqDefinition.STAGE_THREE_MAP);
        when(run.actions.npcPosition(any(), eq(AgentEpqDefinition.STAGE_NPC))).thenReturn(new Point(548, 1));
        when(tobi.getPosition()).thenReturn(new Point(500, 0));
        when(una.getPosition()).thenReturn(new Point(500, 0));

        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(mira, "it's a maze. wait here, we'll find ellin. don't talk to the mileposts, they send you out.");

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 3_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 3_000L);
        said(tobi, "found ellin! she's bringing everyone over.");
        verify(run.packets, never()).broadcastChatText(una, "found ellin! she's bringing everyone over.", false, 0);
    }

    @Test
    void stageFourOnEntryThenTheTwentyHeldAndTheHandInNudge() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(mira, "ellin hands out purification marbles. hit a poison flower till it's weak, then use a marble on it.");

        when(run.actions.itemCount(ben, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        said(mira, "you've got all twenty. ellin's up top, give them to her.");
        AgentEpqCoordinator.tickSession(session, 3_000L + HINT - 1L);
        notSaid("Ben, talk to ellin with the marbles and we're through.");
        AgentEpqCoordinator.tickSession(session, 3_000L + HINT);
        said(mira, "Ben, talk to ellin with the marbles and we're through.");
    }

    @Test
    void afterTheHandOverThatMadeTwentyTheTwentyHeldLineIsNotSaidAgain() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FOUR, AgentEpqDefinition.STAGE_FOUR_MAP);
        assertTrue(session.narrateOnce("marbles-twenty")); // E9t was said at his feet
        when(run.actions.itemCount(ben, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        notSaid("you've got all twenty. ellin's up top, give them to her.");
    }

    @Test
    void stageFiveOnEntryAndTheStoneHeldTooLongByItsFinder() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FIVE, AgentEpqDefinition.STAGE_FIVE_MAP);
        assertTrue(session.noteStoneFinder(tobi.getId()));
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        String hint = "Ben, talk to the sprite with the stone. she'll take us to the altar.";

        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(mira, "long climb. the stone's in one of the boxes up top. wait by the sprite, we'll bring it down.");
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT - 1L);
        notSaid(hint);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);
        said(tobi, hint);
    }

    @Test
    void theStoneLinesFallBackToTheNarratorWhenTheFinderIsNotThere() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_FIVE, AgentEpqDefinition.STAGE_FIVE_MAP);
        session.noteStoneFinder(tobi.getId());
        run.onMap(tobi, AgentEpqDefinition.STAGE_FOUR_MAP);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);

        said(mira, "Ben, talk to the sprite with the stone. she'll take us to the altar.");
    }

    @Test
    void atTheAltarTheFinderSaysReadyAtOnceWhenNobodyDrinksAndNudgesLater() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        session.noteStoneFinder(tobi.getId());
        altar((byte) 0);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(tobi, "ready when you are, Ben. drop the purple stone right under the altar.");
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT - 1L);
        notSaid("Ben, drop the stone under the altar and the golem comes out.");
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);
        said(tobi, "Ben, drop the stone under the altar and the golem comes out.");
    }

    @Test
    void atTheAltarACompanionStillDrinkingHoldsTheReadyLineUntilTheWaitEnds() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        when(una.getHp()).thenReturn(100);
        when(una.getCurrentMaxHp()).thenReturn(1_000);
        when(run.actions.hpPotionCount(una)).thenReturn(5);
        String ready = "ready when you are, Ben. drop the purple stone right under the altar.";

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 1_000L + 20_000L - 1L); // the BOSS phase began at 1000
        notSaid(ready);
        AgentEpqCoordinator.tickSession(session, 1_000L + 20_000L);
        said(mira, ready);
    }

    @Test
    void aCompanionStartingToDrinkOnTheGolemsMapSaysSoFromItsOwnTick() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        when(una.getHp()).thenReturn(100);
        when(una.getCurrentMaxHp()).thenReturn(1_000);
        when(run.actions.hpPotionCount(una)).thenReturn(5);

        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);
        }

        said(una, "sec, drinking up first.");
    }

    @Test
    void aStoneDroppedOffTheAltarIsPointedOutOnceItHasLainThere() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        run.drop(AgentEpqDefinition.MAGIC_STONE, 1, new Point(-300, 150), true, ben.getId(), 2_000L);
        String line = "Ben, the stone has to land right under the altar. pick it up and try again.";

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT - 1L);
        notSaid(line);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);
        said(mira, line);
    }

    @Test
    void aStoneInTheAltarsAreaIsPlacedAndGetsNoLine() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 0);
        run.drop(AgentEpqDefinition.MAGIC_STONE, 1, new Point(390, 150), true, ben.getId(), 2_000L);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);

        notSaid("Ben, the stone has to land right under the altar. pick it up and try again.");
    }

    @Test
    void noAltarLineInTheGolemsReviveGap() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        session.noteStoneFinder(tobi.getId());
        altar((byte) 0);
        assertTrue(session.claimLogMark("golem-9300180")); // the first golem was seen; none alive this pass
        when(run.actions.itemCount(ben, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        run.drop(AgentEpqDefinition.MAGIC_STONE, 1, new Point(-300, 150), true, ben.getId(), 2_000L);
        when(una.getHp()).thenReturn(100);
        when(una.getCurrentMaxHp()).thenReturn(1_000);
        when(run.actions.hpPotionCount(una)).thenReturn(5);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L + HINT);
        }

        notSaid("ready when you are, Ben. drop the purple stone right under the altar.");
        notSaid("Ben, the stone has to land right under the altar. pick it up and try again.");
        notSaid("sec, drinking up first.");
    }

    @Test
    void theGolemFightTable() {
        assertFalse(AgentEpqCompanionPlay.golemFight(false, false, false, 0, false), "nothing yet");
        assertTrue(AgentEpqCompanionPlay.golemFight(false, true, false, 0, false), "a golem alive");
        assertTrue(AgentEpqCompanionPlay.golemFight(false, false, true, 0, false), "the revive gap");
        assertTrue(AgentEpqCompanionPlay.golemFight(false, false, false, 1, false), "the altar fired");
        assertTrue(AgentEpqCompanionPlay.golemFight(false, false, false, 0, true), "the altar takes the stone");
        assertFalse(AgentEpqCompanionPlay.golemFight(true, true, true, 1, true), "cleared");
    }

    @Test
    void eachGolemsLineTheFirstTimeItIsAlive() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS, AgentEpqDefinition.BOSS_MAP);
        altar((byte) 1);
        run.monster(9_300_180);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 3_000L);

        said(mira, "here it comes! everyone on the golem.");
        notSaid("it's charging up! keep hitting it.");
    }

    @Test
    void noLineWithoutACompanionOnTheLeadersMapThenOnceOneArrives() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_ONE, AgentEpqDefinition.STAGE_ONE_MAP);
        for (Character agent : List.of(mira, tobi, una)) run.onMap(agent, AgentEpqDefinition.ENTRANCE_MAP);
        String line = "these trees are poisoned. we clear every one and the way opens.";

        AgentEpqCoordinator.tickSession(session, 2_000L);
        notSaid(line);

        run.onMap(tobi, AgentEpqDefinition.STAGE_ONE_MAP);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        AgentEpqCoordinator.tickSession(session, 4_000L);
        said(tobi, line);
    }

    @Test
    void pinAStockRunHearsNoCoaching() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        when(run.actions.itemCount(ben, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + HINT);

        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }
}
