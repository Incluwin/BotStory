package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.7: a companion run's companions bring the monster marbles to their leader's feet. A carrier sets out with a
 * batch while he is near, or with anything once the twenty are made or no flower is left; it drops the whole stack
 * where the map lands it at his feet (or right next to him) and says so; a leader it cannot reach gets the stack by
 * Ellin. A stack he leaves lying is pointed out once, then picked back up by its dropper before it can expire and
 * dropped for him again, at most RECOLLECT_MAX times; a carrier about to go down leaves its stack where it stands and
 * another companion takes it back at once. Nobody asks Ellin for purifiers with no flower left, and no flower respawn
 * is forced. A stock run keeps today's drop beside Ellin.
 */
class AgentEpqMarbleHandOverTest {
    private static final int MARBLE = AgentEpqDefinition.MONSTER_MARBLE;
    private static final Point BEN_SPOT = new Point(300, 0);
    private static final Point ELLIN_SPOT = new Point(500, 0);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);

    AgentEpqMarbleHandOverTest() {
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        when(run.actions.npcPosition(any(), eq(AgentEpqDefinition.STAGE_NPC))).thenReturn(new Point(548, 1));
        when(ben.getPosition()).thenReturn(new Point(BEN_SPOT));
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    /** One pass of this member's own tick, whatever retry its last pass deferred to. */
    private void tick(AgentEpqSession session, Character member, long nowMs) {
        session.member(member.getId()).deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private void at(Character member, Point point) {
        when(member.getPosition()).thenReturn(new Point(point));
    }

    /** Each drop the gateway makes lands where the dropper stands, as its own player drop. */
    private void dropsLandWhereTheyAreMade(long nowMs) {
        doAnswer(call -> {
            Character dropper = call.getArgument(0);
            run.drop(MARBLE, call.<Short>getArgument(3), dropper.getPosition(), true, dropper.getId(), nowMs);
            return null;
        }).when(run.inventory()).dropItem(any(), eq(InventoryType.ETC), anyShort(), anyShort());
    }

    private void said(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    @Test
    void aCarrierWithABatchWalksToItsNearLeader() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 6);

        tick(session, mira, 2_000L);

        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);
        verify(run.inventory(), never()).dropItem(any(), any(), anyShort(), anyShort());
    }

    @Test
    void atHisSpotItDropsTheWholeStackAtHisFeetAndSaysSo() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 6);
        at(mira, BEN_SPOT);
        when(run.actions.predictDropLanding(eq(mira), any())).thenReturn(new Point(BEN_SPOT));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqHandOver.class)) {
            tick(session, mira, 2_000L);

            assertEquals(List.of("EPQ handover: session=" + session.sessionId() + " item=marbles member=Mira count=6"
                    + " place=FEET recollect=false leader=Ben at=(300,0) landing=(300,0)"),
                    lines.startingWith("EPQ handover:"));
        }
        verify(run.inventory(), times(1)).dropItem(mira, InventoryType.ETC, (short) 1, (short) 6);
        said(mira, "the monster marbles are at your feet, Ben. pick them up.");
    }

    @Test
    void theStackThatMakesTwentyTellsHimToGoToEllin() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 6);
        when(run.actions.itemCount(ben, MARBLE)).thenReturn(14);
        when(ben.getPosition()).thenReturn(new Point(1_200, 0)); // far: no batch rule, but the twenty are made
        at(mira, new Point(1_200, 0));

        tick(session, mira, 2_000L);

        verify(run.inventory(), times(1)).dropItem(mira, InventoryType.ETC, (short) 1, (short) 6);
        said(mira, "that's all twenty at your feet, Ben. pick them up and give them to ellin up top.");
        assertEquals(2_000L, session.pace().snapshot().marblesAtMs(), "Step 5.2: the hand-over that makes twenty");
    }

    @Test
    void aLeaderItCannotReachGetsTheStackByEllin() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 6);
        when(run.actions.canReach(any(), any(), any())).thenReturn(false);
        at(mira, ELLIN_SPOT);
        when(run.actions.predictDropLanding(eq(mira), any())).thenReturn(new Point(ELLIN_SPOT));

        tick(session, mira, 2_000L);

        verify(run.inventory(), times(1)).dropItem(mira, InventoryType.ETC, (short) 1, (short) 6);
        said(mira, "Ben, couldn't get to you, left the marbles by ellin.");
    }

    @Test
    void itsDropperPointsOutAStackStillLyingOnce() {
        AgentEpqSession session = companionRun();
        run.drop(MARBLE, 6, new Point(0, 0), true, mira.getId(), 10_000L);
        String line = "Ben, the monster marbles are still on the ground. grab them before they vanish.";

        tick(session, mira, 49_999L);
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        tick(session, tobi, 50_000L); // not his stack
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());

        tick(session, mira, 50_000L);
        tick(session, mira, 60_000L);

        said(mira, line);
    }

    @Test
    void itsDropperPicksAStackLeftLyingBackUpAndDropsItForHimAgain() {
        AgentEpqSession session = companionRun();
        MapItem stack = run.drop(MARBLE, 6, new Point(0, 0), true, mira.getId(), 10_000L);

        tick(session, mira, 159_999L);
        assertEquals(0, session.recollectorOf(stack.getObjectId()));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, stack, 159_999L), "before it is due");

        tick(session, tobi, 160_000L); // the dropper stands here
        assertEquals(0, session.recollectorOf(stack.getObjectId()));
        tick(session, mira, 160_000L);

        assertEquals(mira.getId(), session.recollectorOf(stack.getObjectId()));
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(mira, stack, 160_000L));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, stack, 160_000L));
        int stackId = stack.getObjectId();
        verify(run.actions).lootItem(eq(mira), eq(stackId), anyInt());

        run.pickUp(stack); // she has it back
        run.holds(mira, MARBLE, 6);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqHandOver.class)) {
            tick(session, mira, 161_000L);
            assertEquals(1, lines.startingWith("EPQ recollect: session=" + session.sessionId()
                    + " item=marbles member=Mira stack=" + stack.getObjectId() + " count=1").size());
        }
        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);

        at(mira, BEN_SPOT);
        dropsLandWhereTheyAreMade(162_000L);
        tick(session, mira, 162_000L);

        said(mira, "picked the marbles back up so they don't vanish. here they are again.");
        MapItem again = AgentEpqHandOver.lyingStacks(session, mira, MARBLE).getFirst();
        assertEquals(1, session.recollectCount(again.getObjectId()), "the count follows the re-dropped stack");
    }

    @Test
    void aLineagePickedBackUpRecollectMaxTimesIsLeftToExpire() {
        AgentEpqSession session = companionRun();
        MapItem stack = run.drop(MARBLE, 6, new Point(0, 0), true, mira.getId(), 10_000L);
        session.setRecollectCount(stack.getObjectId(), 2);

        tick(session, mira, 175_000L);

        assertEquals(0, session.recollectorOf(stack.getObjectId()));
        verify(run.actions, never()).lootItem(any(), anyInt(), anyInt());
    }

    @Test
    void aCarrierAboutToGoDownLeavesItsStackAndAnotherTakesItBackAtOnce() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 6);
        when(mira.getHp()).thenReturn(100);
        when(mira.getCurrentMaxHp()).thenReturn(1_000);
        when(run.actions.hpPotionCount(mira)).thenReturn(0);
        dropsLandWhereTheyAreMade(2_000L);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqHandOver.class)) {
            tick(session, mira, 2_000L);

            assertEquals(1, lines.startingWith("EPQ handover: session=" + session.sessionId()
                    + " item=marbles member=Mira count=6 place=DYING").size());
        }
        said(mira, "almost out of pots, leaving the marbles here, grab them.");
        MapItem left = AgentEpqHandOver.lyingStacks(session, mira, MARBLE).getFirst();
        assertTrue(session.dyingStack(left.getObjectId()));

        tick(session, tobi, 2_001L);

        int leftId = left.getObjectId();
        assertEquals(tobi.getId(), session.recollectorOf(leftId));
        verify(run.actions).lootItem(eq(tobi), eq(leftId), anyInt());
    }

    @Test
    void pinAStockRunStillDropsBesideEllin() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.npcPosition(tobi, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        run.holds(tobi, MARBLE, 20);
        when(run.actions.itemCount(tobi, MARBLE)).thenReturn(20, 20, 20, 0);

        tick(session, tobi, 2_000L);

        verify(run.inventory()).dropItem(tobi, InventoryType.ETC, (short) 1, (short) 20);
        verify(run.party).sendPartyChat(tobi, "I left my Monster Marbles beside Ellin for our leader.");
    }

    @Test
    void nobodyAsksEllinForPurifiersWithNoFlowerLeft() {
        AgentEpqSession session = companionRun();
        at(mira, ELLIN_SPOT);

        tick(session, mira, 2_000L);
        verify(run.epq, never()).runNpc(any(), anyInt());

        run.monster(AgentEpqDefinition.POISON_FLOWER); // pin: with a flower she asks as today
        tick(session, mira, 5_000L);
        verify(run.epq).runNpc(mira, AgentEpqDefinition.STAGE_NPC);
    }

    @Test
    void noFlowerRespawnIsForcedInACompanionRun() {
        AgentEpqSession session = companionRun();
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(3);

        tick(session, mira, 2_000L);

        verify(run.map, never()).instanceMapForceRespawn();
        assertEquals(Map.of(), session.rescueCounts());
    }

    @Test
    void pinAStockRunStillForcesTheFlowerRespawn() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(3);

        tick(session, mira, 2_000L);

        verify(run.map).instanceMapForceRespawn();
        assertEquals(Map.of("respawn-flowers", 1), session.rescueCounts());
    }
}
