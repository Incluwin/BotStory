package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * P6i Step 2, plan 7.2-7.7: how a PPQ companion moves through the ship (P3's default: it goes ahead once a stage is
 * clear), except where the leader is needed: a deck whose chest he can still open (it waits by the chest room's portal
 * and follows him in only once he is inside, decision D-c), the chest room itself (it leaves after him), the door keys
 * (none spent before they cover every door, his not counted) and the boss door (only with him beside it).
 */
class AgentPpqMovePolicyTest {
    private static final Point IN00 = new Point(523, 229);
    private static final Point NEXT00 = new Point(2133, 240);
    private static final Point BOSS_DOOR = new Point(1327, 238);

    @Test
    void theDeckRuleWaitsForAChestTheLeaderCanStillOpen() {
        // deck, leader in this chest room, mobs, skipped, done, his key, he is past
        assertEquals(AgentPpqCompanionPlay.DeckMove.FOLLOW_INTO_CHEST,
                AgentPpqCompanionPlay.deckMove(true, true, true, false, false, true, false));
        assertEquals(AgentPpqCompanionPlay.DeckMove.FIGHT,
                AgentPpqCompanionPlay.deckMove(true, false, true, false, false, true, false));
        assertEquals(AgentPpqCompanionPlay.DeckMove.WAIT_BY_CHEST,
                AgentPpqCompanionPlay.deckMove(true, false, false, false, false, true, false));
        assertEquals(AgentPpqCompanionPlay.DeckMove.GO_ON,
                AgentPpqCompanionPlay.deckMove(true, false, false, true, false, true, false), "chests skipped");
        assertEquals(AgentPpqCompanionPlay.DeckMove.GO_ON,
                AgentPpqCompanionPlay.deckMove(true, false, false, false, true, true, false), "chest done");
        assertEquals(AgentPpqCompanionPlay.DeckMove.GO_ON,
                AgentPpqCompanionPlay.deckMove(true, false, false, false, false, false, false), "no key");
        assertEquals(AgentPpqCompanionPlay.DeckMove.GO_ON,
                AgentPpqCompanionPlay.deckMove(true, false, false, false, false, true, true), "he went on");
        assertEquals(AgentPpqCompanionPlay.DeckMove.GO_ON,
                AgentPpqCompanionPlay.deckMove(false, false, false, false, false, true, false), "stage one");
    }

    @Test
    void theStageRanksPutTheRoomsWithTheirDeck() {
        assertFalse(AgentPpqCompanionPlay.past(AgentPpqStage.CHEST_ONE, AgentPpqStage.DECK_ONE));
        assertFalse(AgentPpqCompanionPlay.past(AgentPpqStage.SIDE_ONE, AgentPpqStage.DECK_ONE));
        assertFalse(AgentPpqCompanionPlay.past(AgentPpqStage.MEDALS, AgentPpqStage.DECK_ONE));
        assertTrue(AgentPpqCompanionPlay.past(AgentPpqStage.DECK_TWO, AgentPpqStage.DECK_ONE));
        assertTrue(AgentPpqCompanionPlay.past(AgentPpqStage.DOORS, AgentPpqStage.DECK_TWO));
        assertFalse(AgentPpqCompanionPlay.past(AgentPpqStage.OUTSIDE, AgentPpqStage.DECK_ONE));
    }

    @Test
    void theBossDoorTakesTheLeaderBesideItAndAnEmptyMap() {
        assertEquals(AgentPpqCompanionPlay.DoorMove.ENTER, AgentPpqCompanionPlay.bossDoor(false, true, false));
        assertEquals(AgentPpqCompanionPlay.DoorMove.ENTER, AgentPpqCompanionPlay.bossDoor(false, false, true));
        assertEquals(AgentPpqCompanionPlay.DoorMove.FIGHT, AgentPpqCompanionPlay.bossDoor(true, true, false));
        assertEquals(AgentPpqCompanionPlay.DoorMove.FIGHT, AgentPpqCompanionPlay.bossDoor(true, false, true));
        assertEquals(AgentPpqCompanionPlay.DoorMove.WAIT, AgentPpqCompanionPlay.bossDoor(false, false, false));
    }

    @Test
    void noKeyGoesInADoorBeforeTheCompanionsAndTheFloorCoverEveryDoor() {
        assertFalse(AgentPpqCompanionPlay.mayDropKeys(2, 1, 4));
        assertTrue(AgentPpqCompanionPlay.mayDropKeys(3, 1, 4));
        assertTrue(AgentPpqCompanionPlay.mayDropKeys(1, 0, 1));
        assertFalse(AgentPpqCompanionPlay.mayDropKeys(5, 0, 0), "every door open");
        assertTrue(AgentPpqCompanionPlay.leaderKeyWanted(1, 2, 3));
        assertFalse(AgentPpqCompanionPlay.leaderKeyWanted(1, 3, 3));
        assertFalse(AgentPpqCompanionPlay.leaderKeyWanted(0, 0, 3));
    }

    @Test
    void aCompanionWaitsByTheChestRoomWhileTheLeaderHoldsAKey() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = at(run, MIRA, IN00);
            when(run.character(BEN).getItemQuantity(AgentPpqDefinition.CHEST_KEY, false)).thenReturn(1);
            when(run.actions.portalPosition(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL)).thenReturn(IN00);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest, never()).enterPortal(any(), anyInt());
            verify(run.actions).stop(run.entries.get(MIRA));
            verify(run.packets).broadcastChatText(mira, "c865, chest room's down here. you go in first, we'll follow.",
                    false, 0);
        }
    }

    @Test
    void itFollowsTheLeaderIntoTheChestRoomOnlyOnceHeIsInside() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = at(run, MIRA, IN00);
            when(run.actions.portalPosition(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL)).thenReturn(IN00);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(5); // the deck still has mobs
            run.onMap(BEN, AgentPpqDefinition.CHEST_ONE_MAP);
            when(run.partyQuest.enterPortal(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL);
            verify(run.actions, never()).grind(any(), any());
        }
    }

    @Test
    void aStockRunStillSendsEveryoneIntoTheChestRoom() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = at(run, MIRA, IN00);
            when(run.character(BEN).getItemQuantity(AgentPpqDefinition.CHEST_KEY, false)).thenReturn(1);
            when(run.actions.portalPosition(mira, 1)).thenReturn(IN00);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, 1);
        }
    }

    @Test
    void withoutAChestToOpenItGoesAheadOfTheLeader() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = at(run, MIRA, NEXT00);
            when(run.actions.portalPosition(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DECK_ONE_MAP)))
                    .thenReturn(NEXT00);
            run.onMap(BEN, AgentPpqDefinition.MEDAL_MAP); // still behind: P3's default goes ahead

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DECK_ONE_MAP));
        }
    }

    @Test
    void aLeaderWhoWentOnPastTheDeckIsFollowedEvenWithHisKey() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = at(run, MIRA, NEXT00);
            when(run.actions.portalPosition(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DECK_ONE_MAP)))
                    .thenReturn(NEXT00);
            when(run.character(BEN).getItemQuantity(AgentPpqDefinition.CHEST_KEY, false)).thenReturn(1);
            run.onMap(BEN, AgentPpqDefinition.DECK_TWO_MAP);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DECK_ONE_MAP));
        }
    }

    @Test
    void inTheChestRoomItFightsBesideTheLeaderAndLeavesAfterHim() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CHEST_ONE_MAP)) {
            Character mira = at(run, MIRA, new Point(744, 234));
            when(run.actions.portalPosition(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL)).thenReturn(new Point(744, 234));
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(3);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);
            verify(run.actions).grind(run.entries.get(MIRA), AgentPpqDefinition.COMBAT_MOBS);
            verify(run.partyQuest, never()).enterPortal(any(), anyInt());

            run.onMap(BEN, AgentPpqDefinition.DECK_ONE_MAP); // he left the room
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 6_000L);
            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.CHEST_ROOM_PORTAL);
        }
    }

    @Test
    void noKeyIsSpentWhileTheCompanionsKeysCannotOpenEveryDoorAndHisDoNotCount() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DOOR_MAP)) {
            Character mira = at(run, MIRA, new Point(17, 75));
            List<Reactor> doors = doors(4);
            when(run.actions.reactors(any())).thenReturn(List.copyOf(doors));
            when(mira.getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false)).thenReturn(2);
            when(run.actions.itemCount(mira, AgentPpqDefinition.OLD_METAL_KEY)).thenReturn(2);
            // Ben holds two more: with his the party could open all four, but they are his to drop
            when(run.character(BEN).getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false)).thenReturn(2);
            when(run.actions.itemCount(run.character(BEN), AgentPpqDefinition.OLD_METAL_KEY)).thenReturn(2);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            assertEquals(0, run.session.member(MIRA).committedReactorObjectId(), "no door claimed");
            verify(run.actions).stop(run.entries.get(MIRA));
            // the leader is told once that his key is wanted in a door
            run.tick(6_000L);
            run.tick(7_000L);
            verify(run.packets).broadcastChatText(mira, "c865, drop that old key in a door.", false, 0);
        }
    }

    @Test
    void onceTheCompanionsKeysCoverEveryDoorEachHolderClaimsOne() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DOOR_MAP);
             org.mockito.MockedStatic<server.agents.plans.AgentScriptItemActionService> drops =
                     org.mockito.Mockito.mockStatic(server.agents.plans.AgentScriptItemActionService.class)) {
            Character mira = at(run, MIRA, new Point(17, 75));
            List<Reactor> doors = doors(2);
            when(run.actions.reactors(any())).thenReturn(List.copyOf(doors));
            when(mira.getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false)).thenReturn(2);
            when(run.actions.itemCount(mira, AgentPpqDefinition.OLD_METAL_KEY)).thenReturn(2);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            assertEquals(100, run.session.member(MIRA).committedReactorObjectId(), "the nearest closed door claimed");
            drops.verify(() -> server.agents.plans.AgentScriptItemActionService.dropItem(run.entries.get(MIRA),
                    client.inventory.InventoryType.ETC, AgentPpqDefinition.OLD_METAL_KEY, (short) 1));
        }
    }

    @Test
    void withEveryDoorOpenItKeepsTheBossDoorUntilTheLeaderIsBesideIt() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DOOR_MAP)) {
            Character mira = at(run, MIRA, BOSS_DOOR);
            List<Reactor> open = openDoors(4);
            when(run.actions.reactors(any())).thenReturn(List.copyOf(open));
            when(run.actions.portalPosition(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DOOR_MAP)))
                    .thenReturn(BOSS_DOOR);
            when(run.character(BEN).getPosition()).thenReturn(new Point(-400, 182));

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);
            verify(run.partyQuest, never()).enterPortal(any(), anyInt());

            when(run.character(BEN).getPosition()).thenReturn(new Point(1250, 238));
            when(run.partyQuest.enterPortal(any(), anyInt())).thenReturn(true);
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 6_000L);

            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DOOR_MAP));
            verify(run.packets).broadcastChatText(mira, "boss door. we'll go in with you, c865.", false, 0);
        }
    }

    @Test
    void theBossDoorWaitsForAnEmptyMapEvenWithTheLeaderThere() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DOOR_MAP)) {
            Character mira = at(run, MIRA, BOSS_DOOR);
            List<Reactor> open = openDoors(4);
            when(run.actions.reactors(any())).thenReturn(List.copyOf(open));
            when(run.actions.portalPosition(mira, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DOOR_MAP)))
                    .thenReturn(BOSS_DOOR);
            when(run.character(BEN).getPosition()).thenReturn(BOSS_DOOR);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(2); // the 10-second respawn

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest, never()).enterPortal(any(), anyInt());
            verify(run.actions).grindFromAnchor(run.entries.get(MIRA), BOSS_DOOR, AgentPpqDefinition.COMBAT_MOBS,
                    java.util.Set.of());
            verify(run.packets, never()).broadcastChatText(any(), anyString(), eq(false), eq(0));
        }
    }

    @Test
    void aCompanionNeverLootsTheChestKeyButAStockAgentMay() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                server.maps.MapItem key = mock(server.maps.MapItem.class);
                when(key.getItemId()).thenReturn(AgentPpqDefinition.CHEST_KEY);
                assertFalse(AgentPpqSessionRegistry.canLootExclusive(run.character(MIRA), key));
                server.maps.MapItem oldKey = mock(server.maps.MapItem.class);
                when(oldKey.getItemId()).thenReturn(AgentPpqDefinition.OLD_METAL_KEY);
                assertTrue(AgentPpqSessionRegistry.canLootExclusive(run.character(MIRA), oldKey));
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.BOSS_MAP,
                AgentPpqSession.Mode.AUTONOMOUS)) {
            run.session.addMember(14, AgentPpqMemberState.MemberType.AGENT);
            run.session.addMember(15, AgentPpqMemberState.MemberType.AGENT);
            AgentPpqSessionRegistry.registerComplete(run.session);
            try {
                server.maps.MapItem key = mock(server.maps.MapItem.class);
                when(key.getItemId()).thenReturn(AgentPpqDefinition.CHEST_KEY);
                assertTrue(AgentPpqSessionRegistry.canLootExclusive(run.character(MIRA), key), "today's rule");
            } finally {
                AgentPpqSessionRegistry.remove(run.session);
            }
        }
    }

    private static Character at(PpqRunFixture run, int id, Point point) {
        Character character = run.character(id);
        when(character.getPosition()).thenReturn(point);
        when(run.character(BEN).getPosition()).thenReturn(new Point(point.x + 300, point.y));
        return character;
    }

    private static List<Reactor> doors(int closed) {
        List<Reactor> doors = new java.util.ArrayList<>();
        int[] ids = {2_519_000, 2_519_001, 2_519_002, 2_519_003};
        for (int i = 0; i < 4; i++) {
            Reactor door = mock(Reactor.class);
            when(door.getId()).thenReturn(ids[i]);
            when(door.getObjectId()).thenReturn(100 + i);
            when(door.getState()).thenReturn((byte) (i < closed ? 0 : 1));
            when(door.getPosition()).thenReturn(new Point(17 + i * 300, 75));
            when(door.getArea()).thenReturn(new Rectangle(i * 300, 40, 60, 60));
            doors.add(door);
        }
        return doors;
    }

    private static List<Reactor> openDoors(int count) {
        return doors(0).subList(0, count);
    }
}
