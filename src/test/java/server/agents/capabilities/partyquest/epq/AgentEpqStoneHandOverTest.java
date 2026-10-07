package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

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
 * Step 2.8: in a companion run the companion that finds the purple stone says so once and brings it down to its
 * leader's feet, as the marbles: a leader it cannot reach, or a walk past STONE_TIMEOUT_MS (the long climb down), gets
 * the stone by the sprite, and the line follows where the stone lands. A stone he leaves lying is pointed out once and
 * picked back up by its dropper only; a carrier about to go down leaves it where it stands. The collector never walks
 * to a stone a companion dropped. A stock run keeps today's drop beside the sprite.
 */
class AgentEpqStoneHandOverTest {
    private static final int STONE = AgentEpqDefinition.MAGIC_STONE;
    private static final Point UP_TOP = new Point(0, -3_470);
    private static final Point BEN_SPOT = new Point(300, 150);
    private static final Point SPRITE_SPOT = new Point(106, 150);

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FIVE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FIVE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FIVE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FIVE_MAP);

    AgentEpqStoneHandOverTest() {
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        // The sprite stands at (154,151): its approach point is 48 px to its left, on the floor.
        when(run.actions.npcPosition(any(), eq(AgentEpqDefinition.STONE_NPC))).thenReturn(new Point(154, 151));
        when(ben.getPosition()).thenReturn(new Point(BEN_SPOT));
        when(mira.getPosition()).thenReturn(new Point(UP_TOP));
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FIVE, ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        session.member(member.getId()).deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private void at(Character member, Point point) {
        when(member.getPosition()).thenReturn(new Point(point));
    }

    private void landsAt(Character member, Point landing) {
        when(run.actions.predictDropLanding(eq(member), any())).thenReturn(new Point(landing));
    }

    /** Each drop the gateway makes lands where the dropper stands, as its own player drop. */
    private void dropsLandWhereTheyAreMade(long nowMs) {
        doAnswer(call -> {
            Character dropper = call.getArgument(0);
            run.drop(STONE, call.<Short>getArgument(3), dropper.getPosition(), true, dropper.getId(), nowMs);
            return null;
        }).when(run.inventory()).dropItem(any(), eq(InventoryType.ETC), anyShort(), anyShort());
    }

    private void said(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    @Test
    void theFinderBringsTheStoneDownAndDropsItAtHisFeet() {
        AgentEpqSession session = companionRun();
        run.holds(mira, STONE, 1);

        tick(session, mira, 2_000L);

        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);
        verify(run.inventory(), never()).dropItem(any(), any(), anyShort(), anyShort());
        said(mira, "found the purple stone! bringing it down to you.");

        at(mira, BEN_SPOT);
        landsAt(mira, BEN_SPOT);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqHandOver.class)) {
            tick(session, mira, 40_000L);

            assertEquals(List.of("EPQ handover: session=" + session.sessionId() + " item=stone member=Mira count=1"
                            + " place=FEET recollect=false leader=Ben at=(300,150) landing=(300,150)"),
                    lines.startingWith("EPQ handover:"));
        }
        verify(run.inventory(), times(1)).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        said(mira, "the purple stone is at your feet, Ben. pick it up and show it to the sprite.");
        assertTrue(session.stageFiveStoneHandedOff(), "the collector no longer picks up a stone here");
        assertEquals(40_000L, session.pace().snapshot().stoneAtMs(), "Step 5.2: the stone at his feet");
        verify(run.epq, never()).runNpc(any(), eq(AgentEpqDefinition.STONE_NPC));
        verify(run.party, never()).sendPartyChat(any(), any());
    }

    @Test
    void aLeaderItCannotReachGetsTheStoneByTheSprite() {
        AgentEpqSession session = companionRun();
        run.holds(mira, STONE, 1);
        when(run.actions.canReach(any(), any(), any())).thenReturn(false);

        tick(session, mira, 2_000L);
        verify(run.actions).navigate(run.entry(mira), SPRITE_SPOT, true);

        at(mira, SPRITE_SPOT);
        landsAt(mira, SPRITE_SPOT);
        tick(session, mira, 3_000L);

        verify(run.inventory(), times(1)).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        said(mira, "Ben, couldn't get to you, left the stone by the sprite.");
    }

    @Test
    void theClimbDownMayTakeLongerThanTheMarblesWalkAndTheLineFollowsTheLanding() {
        AgentEpqSession session = companionRun();
        run.holds(mira, STONE, 1);
        when(ben.getPosition()).thenReturn(new Point(130, 150)); // he waits right by the sprite

        tick(session, mira, 2_000L);
        tick(session, mira, 32_001L); // 30001 ms: past the marbles' limit, still walking down to him
        verify(run.actions, times(2)).navigate(run.entry(mira), new Point(130, 150), true);
        verify(run.actions, never()).navigate(run.entry(mira), SPRITE_SPOT, true);

        tick(session, mira, 122_001L); // 120001 ms: the sprite instead
        verify(run.actions).navigate(run.entry(mira), SPRITE_SPOT, true);

        at(mira, SPRITE_SPOT);
        landsAt(mira, SPRITE_SPOT);
        tick(session, mira, 123_000L);

        said(mira, "the purple stone is right next to you, Ben. pick it up and show it to the sprite.");
        verify(run.packets, never()).broadcastChatText(mira, "Ben, couldn't get to you, left the stone by the sprite.",
                false, 0);
    }

    @Test
    void itsDropperPointsOutAStoneStillLyingOnce() {
        AgentEpqSession session = companionRun();
        run.drop(STONE, 1, BEN_SPOT, true, mira.getId(), 10_000L);

        tick(session, mira, 49_999L);
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());

        tick(session, mira, 50_000L);
        tick(session, mira, 60_000L);

        said(mira, "Ben, the stone's still on the ground. the sprite needs to see it.");
    }

    @Test
    void onlyItsDropperPicksTheStoneBackUpAndDropsItForHimAgain() {
        AgentEpqSession session = companionRun();
        MapItem stone = run.drop(STONE, 1, BEN_SPOT, true, mira.getId(), 10_000L);
        at(mira, BEN_SPOT);

        tick(session, mira, 159_999L);
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, stone, 159_999L), "before it is due");

        tick(session, tobi, 160_000L);
        assertEquals(0, session.recollectorOf(stone.getObjectId()), "the dropper stands here: hers");
        tick(session, mira, 160_000L);

        assertEquals(mira.getId(), session.recollectorOf(stone.getObjectId()));
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(mira, stone, 160_000L));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, stone, 160_000L));
        int stoneId = stone.getObjectId();
        verify(run.actions).lootItem(eq(mira), eq(stoneId), anyInt());

        run.pickUp(stone);
        run.holds(mira, STONE, 1);
        landsAt(mira, BEN_SPOT);
        dropsLandWhereTheyAreMade(161_000L);
        tick(session, mira, 161_000L);

        said(mira, "picked the stone back up so it doesn't vanish. here it is again.");
    }

    @Test
    void aCarrierAboutToGoDownLeavesTheStoneWhereItStands() {
        AgentEpqSession session = companionRun();
        run.holds(mira, STONE, 1);
        when(mira.getHp()).thenReturn(100);
        when(mira.getCurrentMaxHp()).thenReturn(1_000);
        when(run.actions.hpPotionCount(mira)).thenReturn(0);
        dropsLandWhereTheyAreMade(2_000L);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqHandOver.class)) {
            tick(session, mira, 2_000L);

            assertEquals(1, lines.startingWith("EPQ handover: session=" + session.sessionId()
                    + " item=stone member=Mira count=1 place=DYING").size());
        }
        said(mira, "out of pots, leaving the stone here.");
        MapItem left = AgentEpqHandOver.lyingStacks(session, mira, STONE).getFirst();
        assertTrue(session.dyingStack(left.getObjectId()));
    }

    @Test
    void theFinderIsTheFirstCompanionSeenHoldingTheStone() {
        AgentEpqSession session = companionRun();
        assertEquals(0, session.stoneFinderId());
        run.holds(mira, STONE, 1);

        tick(session, mira, 2_000L);
        run.holds(tobi, STONE, 1);
        tick(session, tobi, 3_000L);

        assertEquals(mira.getId(), session.stoneFinderId());
        said(mira, "found the purple stone! bringing it down to you.");
        verify(run.packets, never()).broadcastChatText(tobi, "found the purple stone! bringing it down to you.",
                false, 0);
    }

    @Test
    void theCollectorNeverWalksToAStoneACompanionDropped() {
        AgentEpqSession session = companionRun();
        Point lying = new Point(500, -3_470);
        run.drop(STONE, 1, lying, true, tobi.getId(), 1_900L);

        tick(session, mira, 2_000L);

        verify(run.actions, never()).navigate(any(), eq(lying), anyBoolean());
        verify(run.actions, never()).lootItem(any(), anyInt(), anyInt());

        Point fromTheBox = new Point(700, -3_470); // pin: a stone a box dropped is still its to pick up
        run.drop(STONE, 1, fromTheBox, false, 0, 2_500L);
        tick(session, mira, 3_000L);

        verify(run.actions).navigate(run.entry(mira), fromTheBox, true);
    }

    @Test
    void pinAStockRunStillDropsTheStoneBesideTheSprite() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_FIVE, ben, mira, tobi, una);
        at(mira, SPRITE_SPOT);
        run.holds(mira, STONE, 1);
        when(run.actions.itemCount(mira, STONE)).thenReturn(1, 1, 0);

        tick(session, mira, 2_000L);

        verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        verify(run.party).sendPartyChat(mira, "Purple Stone of Magic found. Bringing it to Yuris.");
        verify(run.party).sendPartyChat(mira,
                "I dropped the Magic Stone beside Yuris. Leader, please pick it up and continue.");
        assertEquals(0, session.stoneFinderId());
    }
}
