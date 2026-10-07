package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.5: a companion run's passes come to the leader's feet. Each carrier walks to his grounded spot and drops
 * its whole stack there in one drop (no batch drop at the balloon, no vacuum); the drop that makes the stage's count
 * says so (H1), the first batch that does not explains once (H0); an unreachable leader finds them by the balloon
 * (H1b); a stack he leaves lying gets a nudge (H2) and is picked back up before it vanishes (H3), at most twice a
 * stage; a carrier hands over only on his map.
 */
class AgentLpqPassHandOverTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final Point BALLOON = new Point(-157, -192);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character mira;
    private Character kes;
    private Character pel;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession stageOne(boolean companion, Point leaderAt) {
        fx.npcAt(AgentLpqDefinition.stage(1).npcId(), BALLOON);
        crew = fx.crew(S1, BALLOON);
        mira = crew.get(1);
        kes = crew.get(2);
        pel = crew.get(3);
        fx.moveTo(crew.getFirst(), leaderAt);
        fx.moveTo(mira, new Point(300, -192));
        fx.moveTo(kes, new Point(320, -192));
        fx.moveTo(pel, new Point(340, -192));
        fx.holds(mira, AgentLpqDefinition.PASS, 10);
        fx.holds(kes, AgentLpqDefinition.PASS, 10);
        fx.holds(pel, AgentLpqDefinition.PASS, 5);
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_1, crew.toArray(Character[]::new));
        session.narrateOnce("s1-start");
        return session;
    }

    private void verifyDropped(Character carrier, int quantity) {
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(carrier), InventoryType.ETC,
                AgentLpqDefinition.PASS, (short) quantity));
    }

    @Test
    void eachCarrierBringsItsWholeStackToHisFeetAndTheLastOneSaysSo() {
        AgentLpqSession session = stageOne(true, BALLOON);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);
            for (Character carrier : List.of(mira, kes, pel)) {
                verify(fx.actions).navigate(fx.entry(carrier), BALLOON, true);
            }
            fx.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

            fx.moveTo(mira, new Point(-150, -192));
            fx.moveTo(kes, new Point(-145, -192));
            fx.moveTo(pel, new Point(-140, -192));
            fx.tick(session, 4_000L);

            verifyDropped(mira, 10);
            verifyDropped(kes, 10);
            verifyDropped(pel, 5);
            fx.said(session, "batch-explained", mira, "i'll drop my passes at your feet as we get them, grab them.");
            fx.said(session, "s1-passes", pel,
                    "Crewfriend, the passes are at your feet. grab them and talk to the red balloon.");
            assertEquals(3, log.startingWith("LPQ hand-over: session=" + session.sessionId()).size());
            assertTrue(log.startingWith("LPQ hand-over: session=" + session.sessionId() + " member=Pel(4) stage=1"
                    + " item=4001022 quantity=5 place=FEET recollect=false leader=1 at=").size() == 1);
            assertEquals(List.of(), log.startingWith("LPQ Stage 1 coordinated balloon handoff"));
            fx.lootInhibit.verify(() -> AgentInventoryStateRuntime.setLootInhibitMs(fx.entry(crew.get(4)), 3_000),
                    times(3));
            verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
            verify(fx.mapGateway, never()).removeItemDrop(any(), any(MapItem.class), anyInt(), anyInt());
        }
    }

    @Test
    void anUnreachableLeaderFindsThePassesByTheBalloon() {
        AgentLpqSession session = stageOne(true, new Point(600, -500));
        when(fx.actions.canReach(any(), any(), any())).thenReturn(false);
        fx.tick(session, 3_000L);
        verify(fx.actions).navigate(fx.entry(pel), BALLOON, true);

        fx.moveTo(pel, new Point(-150, -192));
        fx.tick(session, 4_000L);

        verifyDropped(pel, 5);
        fx.said(session, "s1-balloon", pel, "Crewfriend, couldn't get to you, left the passes by the red balloon.");
    }

    @Test
    void stacksLeftLyingAreNudgedOnceThenPickedBackUpAtMostTwice() {
        AgentLpqSession session = stageOne(true, BALLOON);
        fx.tick(session, 3_000L);
        fx.moveTo(mira, new Point(-150, -192));
        fx.moveTo(kes, new Point(-145, -192));
        fx.moveTo(pel, new Point(-140, -192));
        fx.tick(session, 4_000L);
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), startsWith("s1-nudge"), any(), anyString()),
                never());

        fx.tickEvery(session, 5_000L, 44_001L, 1_000L);
        fx.tick(session, 44_001L);
        // Three stacks came due together: one reminder, as a player says it once.
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(eq(session.sessionId()), startsWith("s1-nudge"), any(),
                anyString()), times(1));
        fx.said(session, "s1-nudge", crew.get(1), "Crewfriend, the passes are still by your feet.");

        fx.tickEvery(session, 45_000L, 600_000L, 1_000L);

        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(eq(session.sessionId()), startsWith("recollect-"), any(),
                eq("Crewfriend, picked the passes back up so they don't vanish. they're at your feet again.")),
                times(2));
        assertEquals(2, session.recollects());
        assertTrue(session.lastProgressAtMs() > 150_000L, "a re-collect is progress");
    }

    @Test
    void stacksHeWalkedAwayFromAreNudgedOnceAsBackThere() {
        AgentLpqSession session = stageOne(true, BALLOON);
        fx.tick(session, 3_000L);
        fx.moveTo(mira, new Point(-150, -192));
        fx.moveTo(kes, new Point(-145, -192));
        fx.moveTo(pel, new Point(-140, -192));
        fx.tick(session, 4_000L);
        fx.moveTo(crew.getFirst(), new Point(600, -192));

        fx.tickEvery(session, 5_000L, 60_000L, 1_000L);

        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(eq(session.sessionId()), startsWith("s1-nudge"), any(),
                anyString()), times(1));
        fx.said(session, "s1-nudge-back", crew.get(1), "Crewfriend, you left some passes on the ground back there.");
    }

    @Test
    void aCarrierHandsOverOnlyOnHisMap() {
        fx.npcAt(AgentLpqDefinition.stage(2).npcId(), new Point(0, 0));
        crew = fx.crew(S2, new Point(300, 0));
        mira = crew.get(1);
        fx.onMap(crew.getFirst(), S1, new Point(0, 0));
        fx.holds(mira, AgentLpqDefinition.PASS, 5);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));
        session.markStage2ScoutPlanAnnounced(2_000L);
        session.markStage2TrapClearAnnounced(2_000L);

        fx.tickEvery(session, 3_000L, 8_000L, 1_000L);
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

        fx.onMap(crew.getFirst(), S2, new Point(20, 0));
        fx.tick(session, 9_000L);
        verify(fx.actions).navigate(fx.entry(mira), new Point(20, 0), true);
        fx.moveTo(mira, new Point(30, 0));
        fx.tick(session, 10_000L);
        verifyDropped(mira, 5);
    }

    @Test
    void aDropTheMapWouldLandUpAboveHimIsNotMade() {
        AgentLpqSession session = stageOne(true, BALLOON);
        when(fx.actions.predictDropLanding(any(), any())).thenAnswer(call -> {
            Point at = call.getArgument(1);
            return new Point(at.x, at.y - 100);
        });
        fx.moveTo(pel, new Point(-150, -192));

        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);

        fx.items.verify(() -> AgentScriptItemActionService.dropItem(eq(fx.entry(pel)), any(), anyInt(), anyShort()),
                never());
        verify(fx.actions, org.mockito.Mockito.atLeastOnce()).navigate(fx.entry(pel), BALLOON, true);
    }

    @Test
    void aCarrierWaitsByTheBalloonWhileTheLeaderIsInARoom() {
        int s5 = AgentLpqDefinition.stage(5).mapId();
        Point balloon = new Point(58, -225);
        fx.npcAt(AgentLpqDefinition.stage(5).npcId(), balloon);
        crew = fx.crew(s5, new Point(0, 0));
        mira = crew.get(1);
        fx.onMap(crew.getFirst(), 922_010_503, new Point(5, 0));
        fx.holds(mira, AgentLpqDefinition.PASS, 4);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.narrateOnce("s5-start");
        AgentLpqDefinition.roomMaps(5).forEach(room -> session.rooms().complete(room));

        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);

        fx.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        verify(fx.actions, org.mockito.Mockito.atLeastOnce()).navigate(eq(fx.entry(mira)), any(), eq(true));
    }

    @Test
    void aStageFiveDropHandsOffTheEarnedPasses() {
        int s5 = AgentLpqDefinition.stage(5).mapId();
        fx.npcAt(AgentLpqDefinition.stage(5).npcId(), new Point(58, -225));
        crew = fx.crew(s5, new Point(0, 0));
        mira = crew.get(1);
        fx.holds(mira, AgentLpqDefinition.PASS, 4);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.narrateOnce("s5-start");
        AgentLpqDefinition.roomMaps(5).forEach(room -> session.rooms().complete(room));
        session.member(mira.getId()).recordStageFiveEarnedPasses(4);

        fx.tick(session, 3_000L);

        verifyDropped(mira, 4);
        assertEquals(0, session.member(mira.getId()).stageFiveUnhandedPasses());
    }

    @Test
    void aStockRunStillBatchDropsAtTheBalloon() {
        AgentLpqSession session = stageOne(false, BALLOON);
        session.beginCouponRegroup(1, 2_000L);
        for (Character carrier : List.of(mira, kes, pel)) fx.moveTo(carrier, new Point(-150, -192));
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);
            assertEquals(1, log.startingWith("LPQ Stage 1 coordinated balloon handoff").size());
            fx.saidNothing();
        }
    }
}
