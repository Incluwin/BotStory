package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeConfig;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2d's hand-over in Room 16: a companion carrying coupons walks to the human leader and drops the whole
 * stack at his feet, saying so once for the companions handing over together; with nothing to hand over, or with him
 * elsewhere, it waits at its own spot off his click spot. A stack he leaves lying is pointed out once and picked back
 * up by its dropper before it can expire. A stock run drops where it stands with the stock line, as before.
 */
class AgentLmpqHandOverTest {
    private static final Point LEADER_AT = new Point(-220, 104);
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");
    private final MockedStatic<AgentScriptItemActionService> items = mockStatic(AgentScriptItemActionService.class);
    private final LmpqLogLines lines = new LmpqLogLines(AgentLmpqHandOver.class);
    private final List<MapObject> lying = new ArrayList<>();

    AgentLmpqHandOverTest() {
        for (Character member : List.of(ben, mira, kes)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(16));
            when(run.actions.footing(member)).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        }
        when(ben.getPosition()).thenReturn(LEADER_AT);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        items.when(() -> AgentScriptItemActionService.dropItem(any(), eq(InventoryType.ETC),
                eq(AgentLmpqDefinition.COUPON), anyShort())).thenReturn(true);
        when(run.map.getItems()).thenAnswer(call -> new ArrayList<>(lying));
    }

    @AfterEach
    void close() {
        lines.close();
        items.close();
        run.close();
    }

    private AgentLmpqSession regrouping(boolean companionRun) {
        AgentLmpqSession session = run.unregistered(AgentLmpqSession.Mode.HUMAN_LEADER, companionRun,
                AgentLmpqSession.Phase.PREPARING, ben, mira, kes);
        session.setRendezvousRoom(AgentLmpqDefinition.CLEAR_ROOM);
        session.setMemberCoordinationChatEnabled(true);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 2_000L);
        AgentLmpqSessionRegistry.registerComplete(session);
        return session;
    }

    @Test
    void atHisFeetTheWholeStackDropsWithOneLine() {
        AgentLmpqSession session = regrouping(true);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(17);
        when(kes.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(14);
        when(mira.getPosition()).thenReturn(new Point(-200, 104));
        when(kes.getPosition()).thenReturn(new Point(-240, 104));
        when(run.actions.predictDropLanding(any(), any())).thenAnswer(call -> new Point(call.<Point>getArgument(1)));

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 3_100L);

        items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC,
                AgentLmpqDefinition.COUPON, (short) 17));
        items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(kes), InventoryType.ETC,
                AgentLmpqDefinition.COUPON, (short) 14));
        verify(run.party).sendPartyChat(mira, "Ben, coupons are at your feet.");
        verify(run.party, times(1)).sendPartyChat(any(), anyString());
        List<String> handovers = lines.startingWith("LMPQ handover:");
        assertEquals(2, handovers.size(), handovers.toString());
        assertTrue(handovers.getFirst().contains("member=Mira count=17 place=FEET"), handovers.getFirst());
    }

    @Test
    void fromAcrossTheRoomItWalksToHimFirst() {
        AgentLmpqSession session = regrouping(true);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(17);
        when(mira.getPosition()).thenReturn(new Point(208, 86));
        when(run.actions.groundPoint(run.map, LEADER_AT)).thenReturn(LEADER_AT);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.actions).navigate(run.entry(mira), LEADER_AT, true);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
    }

    @Test
    void withHimElsewhereItWaitsAtItsSpotRightOfPietrisSteps() {
        AgentLmpqSession session = regrouping(true);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(9));
        when(kes.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(14);
        when(kes.getPosition()).thenReturn(new Point(208, 86));

        AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 3_000L);

        verify(run.actions).navigate(run.entry(kes), AgentLmpqCompanionPlay.waitSpot(1), true);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
    }

    private MapItem stack(int objectId, int ownerId, long droppedAt, Point at) {
        MapItem stack = mock(MapItem.class);
        when(stack.getObjectId()).thenReturn(objectId);
        when(stack.getItemId()).thenReturn(AgentLmpqDefinition.COUPON);
        when(stack.getOwnerId()).thenReturn(ownerId);
        when(stack.isPlayerDrop()).thenReturn(true);
        when(stack.getDropTime()).thenReturn(droppedAt);
        when(stack.getPosition()).thenReturn(at);
        lying.add(stack);
        return stack;
    }

    @Test
    void aStackHeLeavesIsPointedOutOnceThenPickedBackUpByItsDropper() {
        AgentLmpqSession session = regrouping(true);
        when(mira.getPosition()).thenReturn(new Point(-210, 104));
        stack(950, 41, 10_000L, new Point(-215, 104));

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L + AgentLmpqHandOver.PICKUP_NUDGE_MS);
        session.member(41).deferUntil(0L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 11_000L + AgentLmpqHandOver.PICKUP_NUDGE_MS);
        verify(run.party, times(1)).sendPartyChat(mira, "Ben, the coupons are still on the floor.");

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L + AgentLmpqHandOver.RECOLLECT_MS);

        assertEquals(41, session.claimerOf(950));
        verify(run.actions).lootItem(mira, 950, AgentRuntimeConfig.cfg.LOOT_RADIUS);
    }

    @Test
    void aStackPickedBackUpIsHandedOverAgainWithItsOwnLine() {
        AgentLmpqSession session = regrouping(true);
        when(mira.getPosition()).thenReturn(new Point(-210, 104));
        MapItem stack = stack(950, 41, 10_000L, new Point(-215, 104));
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L + AgentLmpqHandOver.RECOLLECT_MS);
        lying.remove(stack);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(17);
        when(run.actions.predictDropLanding(any(), any())).thenAnswer(call -> new Point(call.<Point>getArgument(1)));

        session.member(41).deferUntil(0L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 11_000L + AgentLmpqHandOver.RECOLLECT_MS);

        verify(run.party).sendPartyChat(mira, AgentLmpqCompanionPlay.pickedBackUp());
        assertEquals(1, lines.startingWith("LMPQ recollect:").size());
        assertTrue(lines.startingWith("LMPQ handover:").getFirst().contains("recollect=true"));
    }

    @Test
    void aStockRunDropsWhereItStandsWithTheStockLine() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REGROUPING,
                ben, mira, kes);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(9));
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(9));
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(17);
        when(mira.getPosition()).thenReturn(new Point(208, 86));

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC,
                AgentLmpqDefinition.COUPON, (short) 17));
        verify(run.party).sendPartyChat(mira,
                "Coupons are beside you in Room 9. Please pick them up and confirm you have at least 30.");
        verify(run.actions, never()).navigate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
