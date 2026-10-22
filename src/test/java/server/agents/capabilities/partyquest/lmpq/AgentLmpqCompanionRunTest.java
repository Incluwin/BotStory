package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2 at the run's level: a companion run's companions speak (the plan, the directions, the regroup, Pietri,
 * the clear) in party chat in their own words while stock runs keep their stock lines; a companion run meets in Room
 * 16 when admitted; it reads the clear from the event; it completes once its companions are out, never waiting for
 * its humans; it goes back to the rooms when its coupons come up short; and a leader walking through a room never
 * evicts the companion working it (R17).
 */
class AgentLmpqCompanionRunTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");

    @AfterEach
    void close() {
        run.close();
    }

    /** A registered companion run led by Ben, meeting in Room 16, its guidance on, in {@code phase}. */
    private AgentLmpqSession companionRun(AgentLmpqSession.Phase phase) {
        AgentLmpqSession session = run.unregistered(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.PREPARING, ben, mira, kes);
        session.setRendezvousRoom(AgentLmpqDefinition.CLEAR_ROOM);
        session.setMemberCoordinationChatEnabled(true);
        if (phase != AgentLmpqSession.Phase.PREPARING) session.transition(phase, 2_000L);
        AgentLmpqSessionRegistry.registerComplete(session);
        return session;
    }

    private List<String> partyLines() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(run.party, atLeastOnce()).sendPartyChat(any(), lines.capture());
        return lines.getAllValues();
    }

    @Test
    void aCompanionTellsThePlanOnceAndTheLeaderHisWay() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 3_500L);

        int portal = AgentLmpqDefinition.nextPortalId(3, AgentLmpqDefinition.CLEAR_ROOM);
        assertEquals(List.of(AgentLmpqCompanionPlay.plan(16),
                AgentLmpqCompanionPlay.direction("Ben", 3, portal, 16, false)), partyLines());
        verify(run.party).sendPartyChat(mira, AgentLmpqCompanionPlay.plan(16));
    }

    @Test
    void theLeaderHearsAReminderOnlyAfterTheRepeatWindowAndANewLineInEachRoom() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);
        AgentLmpqCoordinator.tickSession(session, 3_000L);
        long repeat = AgentLmpqCompanionRun.DIRECTION_REPEAT_MS;
        AgentLmpqCoordinator.tickSession(session, 3_000L + repeat - 1);
        AgentLmpqCoordinator.tickSession(session, 3_000L + repeat);
        AgentLmpqCoordinator.tickSession(session, 3_000L + 3 * repeat);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(9));
        AgentLmpqCoordinator.tickSession(session, 3_000L + 3 * repeat + 100);

        List<String> lines = partyLines();
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(2).contains("still in room 3"), lines.toString());
        assertEquals("Ben, room 9 now. the middle portal goes straight to pietri.", lines.get(3));
    }

    @Test
    void aStockRunSaysNoneOfIt() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes);
        session.setMemberCoordinationChatEnabled(true);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void theRegroupIsTheCompanionsLineInACompanionRunAndTheStockLineOtherwise() {
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(20);
        when(kes.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(20);
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase());
        assertTrue(partyLines().contains("we've got enough. meet you in room 16."), partyLines().toString());
        assertEquals(40, session.couponsAtRegroup());
    }

    @Test
    void aStockRunKeepsItsStockRegroupLine() {
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(20);
        when(kes.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(20);
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes);
        session.rooms().depleted(6, 0, 2_500L); // the Tauromacis hold has nothing left to wait for
        session.rooms().depleted(7, 0, 2_500L);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        verify(run.party).sendPartyChat(any(), eq("Coupon target secured. Meet in Maze Room 9."));
    }

    @Test
    void shortCouponsSendTheRunBackToTheRoomsWithOneLine() {
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(12);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(5);
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REGROUPING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
        assertEquals(List.of(AgentLmpqCompanionPlay.shortOfCoupons(), AgentLmpqCompanionPlay.plan(16),
                AgentLmpqCompanionPlay.direction("Ben", 3, AgentLmpqDefinition.nextPortalId(3, 16), 16, false)),
                partyLines());
    }

    @Test
    void stacksLyingForTheLeaderCountTowardThirty() {
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(12);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(16));
        MapItem stack = mock(MapItem.class);
        when(stack.isPlayerDrop()).thenReturn(true);
        when(stack.getItemId()).thenReturn(AgentLmpqDefinition.COUPON);
        when(stack.getOwnerId()).thenReturn(41);
        when(stack.getPosition()).thenReturn(new Point(-200, 104));
        client.inventory.Item coupons = mock(client.inventory.Item.class);
        when(coupons.getQuantity()).thenReturn((short) 20);
        when(stack.getItem()).thenReturn(coupons);
        when(run.map.getItems()).thenReturn(List.<MapObject>of(stack));
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REGROUPING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase(), "12 in his bag and 20 at his feet");
    }

    @Test
    void theStockRunNeverGoesBack() {
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(12);
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER,
                AgentLmpqSession.Phase.REGROUPING, ben, mira, kes);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase());
    }

    @Test
    void everyoneInRoomSixteenWithThirtyHearsThePietriLine() {
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(31);
        for (Character member : List.of(ben, mira, kes)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(16));
        }
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REGROUPING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 3_500L);

        assertEquals(AgentLmpqSession.Phase.CLEARING, session.phase());
        assertEquals(List.of(AgentLmpqCompanionPlay.pietri()), partyLines(), "once, in the companions' words");
        verify(run.party, never()).sendPartyChat(any(), eq(
                "Ben, everyone is together. Please talk to Pietri to finish the maze."));
    }

    @Test
    void aCompanionRunReadsTheClearFromTheEventAndSaysSo() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP); // he claimed at once
        for (Character agent : List.of(mira, kes)) {
            when(agent.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        }
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.CLEARING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REWARD, session.phase());
        assertEquals(List.of(AgentLmpqCompanionPlay.cleared()), partyLines());
    }

    @Test
    void aCompanionRunCompletesOnceItsCompanionsAreOutWithoutWaitingForItsHumans() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP); // still choosing his reward
        for (Character agent : List.of(mira, kes)) {
            when(agent.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        }
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void aStockRunStillWaitsForEveryone() {
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        for (Character agent : List.of(mira, kes)) {
            when(agent.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        }
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REWARD,
                ben, mira, kes);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REWARD, session.phase());
    }

    @Test
    void theLeaderWalkingThroughNeverEvictsTheCompanionWorkingHisRoom() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);
        session.rooms().reserve(3, 41, 2_500L);
        session.rooms().beginWork(3, 41, 2_500L);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(new AgentLmpqRoomLedger.Lease(3, 41, AgentLmpqRoomLedger.State.WORKING, 2_500L),
                session.rooms().room(3), "Mira keeps Room 3 while Ben stands in it");
    }

    @Test
    void inAStockRunAHumanStillTakesTheRoom() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes);
        session.rooms().reserve(3, 41, 2_500L);
        session.rooms().beginWork(3, 41, 2_500L);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqRoomLedger.State.HUMAN_OCCUPIED, session.rooms().room(3).state());
    }

    @Test
    void theLedgerKeepsAWorkingOwnerOnlyWhenAsked() {
        AgentLmpqRoomLedger ledger = new AgentLmpqRoomLedger();
        ledger.reserve(4, 41, 1L);
        ledger.humanOccupied(4, 2L, true);
        assertEquals(AgentLmpqRoomLedger.State.RESERVED, ledger.room(4).state());
        ledger.humanOccupied(5, 2L, true);
        assertEquals(AgentLmpqRoomLedger.State.HUMAN_OCCUPIED, ledger.room(5).state(), "an unowned room is his");
        ledger.humanOccupied(4, 3L, false);
        assertEquals(AgentLmpqRoomLedger.State.HUMAN_OCCUPIED, ledger.room(4).state());
    }

    @Test
    void theLeadersArrivalInPietrisRoomIsNotedOnceAndSaidWhileTheOthersFarm() {
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(16));
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 4_000L);

        assertEquals(3_000L, session.leaderArrivedAtMs());
        assertEquals(List.of(AgentLmpqCompanionPlay.plan(16), AgentLmpqCompanionPlay.leaderArrived(16)),
                partyLines());
    }

    @Test
    void withTheGuidanceOffNobodySpeaks() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);
        session.setMemberCoordinationChatEnabled(false);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        verify(run.party, never()).sendPartyChat(any(), anyString());
        assertFalse(session.leaderArrivedAtMs() > 0L);
    }
}
