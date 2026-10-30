package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * 2026-10-18, OPQ room work in a companion run its human leader leads (AgentOpqConductor): the stock layout needed six
 * Agents, so Ben and his five companions got no room work past the centre. Eak answers only the leader (2013001.js),
 * so Ben holds no room: Mira, Kes, Pel, Hal and Gus take the walkway, the storage room, the lobby, the way up and the
 * lounge, call him when a room needs his click at its Eak, drop the walkway's thirty at his feet, swap the sealed
 * formation and the levers by his checks' hints, and put up the statue pieces he drops in the centre.
 */
class AgentOpqHumanLedRoomsTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final PacketGateway packets = mock(PacketGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<MapObject> lying = new ArrayList<>();
    private AgentOpqSession session;

    @BeforeEach
    void setUp() {
        MockedStatic<AgentPacketGatewayRuntime> packetRuntime = mockStatic(AgentPacketGatewayRuntime.class);
        packetRuntime.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        statics.add(packetRuntime);
        MockedStatic<AgentInventoryGatewayRuntime> inventoryRuntime = mockStatic(AgentInventoryGatewayRuntime.class);
        inventoryRuntime.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        statics.add(inventoryRuntime);
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        when(run.map.getItems()).thenReturn(lying);
        for (Character member : List.of(ben, mira, kes, pel, hal, gus)) {
            when(member.getPosition()).thenReturn(new Point(0, 0));
        }
        session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, mira, kes, pel, hal, gus);
        session.setMemberCoordinationChatEnabled(true);
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        run.close();
    }

    private void stand(Character member, int mapId, int x, int y) {
        when(member.getMapId()).thenReturn(mapId);
        when(member.getPosition()).thenReturn(new Point(x, y));
    }

    private void completeRooms(AgentOpqDefinition.Room... rooms) {
        for (AgentOpqDefinition.Room room : rooms) {
            AgentOpqRoomLedger.Lease lease = session.rooms().lease(room);
            int owner = lease == null ? 2 : lease.ownerId();
            session.rooms().claim(room, owner, 3_000L);
            session.rooms().advance(room, owner, AgentOpqRoomLedger.State.COMPLETE, 3_000L);
        }
    }

    private MapItem playerDrop(int objectId, int itemId, int ownerId, int quantity, Point at, long droppedAt) {
        MapItem drop = mock(MapItem.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getItem()).thenReturn(item);
        when(drop.isPlayerDrop()).thenReturn(true);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getPosition()).thenReturn(at);
        when(drop.getDropTime()).thenReturn(droppedAt);
        lying.add(drop);
        return drop;
    }

    private void holds(Character member, int itemId, int quantity) {
        when(member.getItemQuantity(itemId, false)).thenReturn(quantity);
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 1);
        when(etc.findById(itemId)).thenReturn(item);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
    }

    private Reactor lever(String name, boolean pushed) {
        Reactor lever = mock(Reactor.class);
        when(lever.getId()).thenReturn(AgentOpqConductor.LEVER_REACTOR_ID);
        when(lever.getName()).thenReturn(name);
        when(lever.isAlive()).thenReturn(true);
        when(lever.isActive()).thenReturn(true);
        when(lever.getState()).thenReturn((byte) (pushed ? 1 : 0));
        return lever;
    }

    @Test
    void benHoldsNoRoomAndHisFiveCompanionsTakeFiveRooms() {
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 10_000L);

            String id = session.sessionId();
            assertEquals(List.of(
                    "OPQ room: session=" + id + " room=WALKWAY state=CLAIMED owner=2 runMs=9000",
                    "OPQ room: session=" + id + " room=STORAGE state=CLAIMED owner=3 runMs=9000",
                    "OPQ room: session=" + id + " room=LOBBY state=CLAIMED owner=4 runMs=9000",
                    "OPQ room: session=" + id + " room=ON_WAY_UP state=CLAIMED owner=5 runMs=9000",
                    "OPQ room: session=" + id + " room=LOUNGE state=CLAIMED owner=6 runMs=9000"),
                    lines.startingWith("OPQ room:"));
        }
        assertEquals(AgentOpqMemberState.Role.LEADER, session.member(1).role());
        assertNull(session.member(1).assignedRoom());
        assertEquals(AgentOpqDefinition.Room.WALKWAY, session.member(2).assignedRoom());
        assertEquals(AgentOpqMemberState.Role.STORAGE_RUNNER, session.member(3).role());
        assertEquals(AgentOpqMemberState.Role.LOBBY_RUNNER, session.member(4).role());
        assertEquals(AgentOpqMemberState.Role.WAY_UP_RUNNER, session.member(5).role());
        assertEquals(AgentOpqMemberState.Role.LOUNGE_RUNNER, session.member(6).role());
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(6).assignedSubroomMapId());
        assertNull(session.rooms().lease(AgentOpqDefinition.Room.SEALED), "the sealed room waits for three free");

        AgentOpqCoordinator.tickSession(session, 11_000L);
        verify(packets, times(1)).broadcastChatText(mira,
                "Ben, wait here. we'll do the rooms and call you when eak needs you.", false, 0);
    }

    @Test
    void theWalkwayCallsHimByPartyChatOnceItsThirtyAreGathered() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 300, -138);
        holds(mira, AgentOpqDefinition.WALKWAY_FRAGMENT, 29);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        verify(run.party, never()).sendPartyChat(any(), anyString());

        holds(mira, AgentOpqDefinition.WALKWAY_FRAGMENT, 30);
        String call = "Ben, the walkway's ready. come talk to eak there.";
        try (OpqLogLines lines = new OpqLogLines(AgentOpqConductor.class)) {
            AgentOpqCoordinator.tickSession(session, 12_000L);
            AgentOpqCoordinator.tickSession(session, 13_000L); // inside the call window: said once
            assertEquals(List.of("OPQ call: session=" + session.sessionId() + " key=call-walkway speaker=Mira"
                    + " leaderMap=" + AgentOpqDefinition.CENTER_MAP), lines.startingWith("OPQ call:"));
        }
        verify(run.party, times(1)).sendPartyChat(mira, call);

        AgentOpqCoordinator.tickSession(session, 12_000L + AgentOpqConductor.CALL_REPEAT_MS);
        verify(run.party, times(2)).sendPartyChat(mira, call);

        stand(ben, AgentOpqDefinition.WALKWAY_MAP, 520, -138); // he came: no call in the room he is in
        AgentOpqCoordinator.tickSession(session, 13_000L + 2 * AgentOpqConductor.CALL_REPEAT_MS);
        verify(run.party, times(2)).sendPartyChat(mira, call);
    }

    @Test
    void theCollectorDropsTheThirtyAtHisFeetInTheWalkway() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 500, -138);
        stand(ben, AgentOpqDefinition.WALKWAY_MAP, 530, -138);
        holds(mira, AgentOpqDefinition.WALKWAY_FRAGMENT, 30);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqHandOver.class)) {
            AgentOpqCoordinator.tickMember(session, run.entries.get(2), mira, 11_000L);
            assertEquals(1, lines.startingWith("OPQ handover: session=" + session.sessionId()
                    + " item=4001050 member=Mira count=30 place=FEET recollect=false leader=Ben").size());
        }
        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 1, (short) 30);
        verify(packets).broadcastChatText(mira, "Ben, they're at your feet. pick them up and talk to eak.",
                false, 0);
    }

    @Test
    void theCollectorWaitsByEakWhileHeIsInAnotherRoom() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 100, -138);
        holds(mira, AgentOpqDefinition.WALKWAY_FRAGMENT, 30);

        AgentOpqCoordinator.tickMember(session, run.entries.get(2), mira, 11_000L);

        verify(inventory, never()).dropItem(any(), any(), anyShort(), anyShort());
        verify(run.actions).navigate(run.entries.get(2), new Point(552, -138), true);
    }

    @Test
    void aStackLyingForHimIsHisAndOnlyItsClaimerMayTakeItBack() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 500, -138);
        MapItem stack = playerDrop(901, AgentOpqDefinition.WALKWAY_FRAGMENT, mira.getId(), 30,
                new Point(530, -138), 11_000L);
        MapItem natural = mock(MapItem.class);
        when(natural.getItemId()).thenReturn(AgentOpqDefinition.WALKWAY_FRAGMENT);

        assertFalse(AgentOpqSessionRegistry.canLootExclusive(mira, stack), "dropped for Ben: not hers to take");
        assertTrue(AgentOpqSessionRegistry.canLootExclusive(mira, natural), "a monster's drop is still hers");
        assertTrue(session.beginClaim(stack.getObjectId(), mira.getId()));
        assertTrue(AgentOpqSessionRegistry.canLootExclusive(mira, stack));
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(kes, stack));
    }

    @Test
    void aStackHeLeavesLyingIsPointedOutThenPickedBackUpBeforeItExpires() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 520, -138);
        stand(ben, AgentOpqDefinition.WALKWAY_MAP, 530, -138);
        MapItem stack = playerDrop(901, AgentOpqDefinition.WALKWAY_FRAGMENT, mira.getId(), 30,
                new Point(530, -138), 20_000L);

        AgentOpqCoordinator.tickMember(session, run.entries.get(2), mira, 20_000L + AgentOpqHandOver.PICKUP_NUDGE_MS);
        verify(packets).broadcastChatText(mira, "Ben, they're still on the floor by you.", false, 0);

        session.member(2).deferUntil(0L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 540, -138); // she walked back from Eak: no stall
        AgentOpqCoordinator.tickMember(session, run.entries.get(2), mira, 20_000L + AgentOpqHandOver.RECOLLECT_MS);
        assertEquals(mira.getId(), session.claimerOf(stack.getObjectId()));
        verify(mira).pickupItem(stack);
    }

    @Test
    void hisSealedCheckPrunesBySplitThatStoodAndTheHoldersTakeTheNext() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        completeRooms(AgentOpqDefinition.Room.WALKWAY, AgentOpqDefinition.Room.STORAGE, AgentOpqDefinition.Room.LOBBY);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        for (int holder : List.of(2, 3, 4)) {
            assertEquals(AgentOpqMemberState.Role.SEALED_PLATFORM, session.member(holder).role());
        }
        assertEquals(10, session.sealedCandidateCount());

        AgentOpqSessionRegistry.recordSealedVerdict(ben.getId(), false, "111"); // 2013001.js: all wrong
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 12_000L);
            assertEquals(List.of("OPQ sealed: session=" + session.sessionId()
                    + " split=111 answer=all-wrong candidates=3 solvedBefore=0"), lines.startingWith("OPQ sealed:"));
        }
        assertEquals("003", session.sealedGuess().key(), "no platform holds one: all three on one platform");
        for (int holder : List.of(2, 3, 4)) assertEquals(2, session.member(holder).assignedPlatform());
        verify(packets).broadcastChatText(mira, "all wrong. trying another.", false, 0);
    }

    @Test
    void theSealedRoomCallsHimOnceTheHoldersStandOnTheirPlatforms() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        completeRooms(AgentOpqDefinition.Room.WALKWAY, AgentOpqDefinition.Room.STORAGE, AgentOpqDefinition.Room.LOBBY);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        AgentOpqSealedSearch.Split guess = session.sealedGuess();
        Point[] platforms = {new Point(-162, -808), new Point(-40, -919), new Point(78, -813)};
        for (int holder : List.of(2, 3, 4)) {
            Point spot = platforms[session.member(holder).assignedPlatform()];
            stand(run.characters.findOnlineCharacterById(holder), AgentOpqDefinition.SEALED_MAP, spot.x, spot.y);
        }

        AgentOpqCoordinator.tickSession(session, 12_000L);

        verify(run.party).sendPartyChat(mira, "Ben, we're on the platforms. come talk to eak in the sealed room.");
        assertEquals(guess, session.sealedGuess());

        stand(ben, AgentOpqDefinition.SEALED_MAP, -251, -900);
        AgentOpqCoordinator.tickSession(session, 13_000L);
        verify(packets).broadcastChatText(mira,
                "stay off the platforms when you talk to him, he counts everyone on them.", false, 0);
    }

    @Test
    void theWayUpRunnerSetsAPairHisCheckPrunesAndTheNextPairIsSet() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(hal, AgentOpqDefinition.ON_WAY_UP_MAP, -49, -4_980);
        List<Reactor> levers = new ArrayList<>(List.of(lever("1", true), lever("2", true), lever("3", false),
                lever("4", false), lever("5", false)));
        when(run.actions.reactors(hal)).thenReturn(levers);
        assertEquals("11000", session.leverGuess().key());

        AgentOpqCoordinator.tickSession(session, 11_000L);
        verify(run.party).sendPartyChat(hal, "Ben, levers are set. eak's past the rope on the way up.");

        AgentOpqSessionRegistry.recordWayUpVerdict(ben.getId(), true, "11000"); // "One of the pushed levers is..."
        AgentOpqSessionRegistry.recordWayUpVerdict(hal.getId(), false, "11000"); // a companion's never counts
        try (OpqLogLines lines = new OpqLogLines(AgentOpqConductor.class)) {
            AgentOpqCoordinator.tickSession(session, 12_000L);
            assertEquals(List.of("OPQ levers: session=" + session.sessionId()
                    + " pair=11000 answer=one-right candidates=6 checks=1"), lines.startingWith("OPQ levers:"));
        }
        assertEquals("10100", session.leverGuess().key());
        assertFalse(AgentOpqConductor.leversMatch(hal, session.leverGuess()));
        verify(run.party).sendPartyChat(hal, "one lever's right. trying another pair.");
    }

    /**
     * 2026-10-25 live (run 6): Ben checked the first pair at Eak and stayed in the room; Hal set the next pair, but the
     * calls went only to a leader on another map, so the second check came five minutes later.
     */
    @Test
    void inTheRoomTheWayUpRunnerTellsHimOnceEachPairIsSet() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(hal, AgentOpqDefinition.ON_WAY_UP_MAP, -49, -4_980);
        stand(ben, AgentOpqDefinition.ON_WAY_UP_MAP, 40, -878);
        List<Reactor> levers = new ArrayList<>(List.of(lever("1", true), lever("2", true), lever("3", false),
                lever("4", false), lever("5", false)));
        when(run.actions.reactors(hal)).thenReturn(levers);
        String ready = "levers are set. talk to eak.";
        try (OpqLogLines lines = new OpqLogLines(AgentOpqConductor.class)) {
            AgentOpqCoordinator.tickSession(session, 11_000L);
            AgentOpqCoordinator.tickSession(session, 11_000L + AgentOpqConductor.CALL_REPEAT_MS);
            assertEquals(List.of("OPQ call: session=" + session.sessionId() + " key=ready-on_way_up-0 speaker=Hal"
                    + " leaderMap=" + AgentOpqDefinition.ON_WAY_UP_MAP), lines.startingWith("OPQ call:"));
        }
        verify(packets, times(1)).broadcastChatText(hal, ready, false, 0);
        verify(run.party, never()).sendPartyChat(any(), anyString());

        AgentOpqSessionRegistry.recordWayUpVerdict(ben.getId(), true, "11000");
        AgentOpqCoordinator.tickSession(session, 12_000L + AgentOpqConductor.CALL_REPEAT_MS);
        verify(packets, times(1)).broadcastChatText(hal, ready, false, 0); // 10100 is not set yet
        levers.clear();
        levers.addAll(List.of(lever("1", true), lever("2", false), lever("3", true), lever("4", false),
                lever("5", false)));

        AgentOpqCoordinator.tickSession(session, 13_000L + AgentOpqConductor.CALL_REPEAT_MS);

        verify(packets, times(2)).broadcastChatText(hal, ready, false, 0);
    }

    @Test
    void inTheRoomTheSealedHoldersTellHimOnceEachGuessStands() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        completeRooms(AgentOpqDefinition.Room.WALKWAY, AgentOpqDefinition.Room.STORAGE, AgentOpqDefinition.Room.LOBBY);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        stand(ben, AgentOpqDefinition.SEALED_MAP, -251, -900);
        standOnPlatforms();
        String ready = "we're set. talk to eak.";

        AgentOpqCoordinator.tickSession(session, 12_000L);
        AgentOpqCoordinator.tickSession(session, 13_000L);
        verify(packets, times(1)).broadcastChatText(mira, ready, false, 0);

        String stood = session.sealedGuess().key();
        AgentOpqSessionRegistry.recordSealedVerdict(ben.getId(), false, stood); // all wrong: the holders move
        AgentOpqCoordinator.tickSession(session, 14_000L);
        verify(packets, times(1)).broadcastChatText(mira, ready, false, 0);
        standOnPlatforms();

        AgentOpqCoordinator.tickSession(session, 15_000L);

        verify(packets, times(2)).broadcastChatText(mira, ready, false, 0);
    }

    /** Mira, Kes and Pel stand on the platforms the current guess gives them. */
    private void standOnPlatforms() {
        Point[] platforms = {new Point(-162, -808), new Point(-40, -919), new Point(78, -813)};
        for (int holder : List.of(2, 3, 4)) {
            Point spot = platforms[session.member(holder).assignedPlatform()];
            stand(run.characters.findOnlineCharacterById(holder), AgentOpqDefinition.SEALED_MAP, spot.x, spot.y);
        }
    }

    @Test
    void aStatuePieceHeDropsInTheCentreIsPickedUpByACompanionThere() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        holds(ben, AgentOpqDefinition.STATUE_PIECES.get(0), 1);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        verify(packets).broadcastChatText(mira, "Ben, drop your statue pieces here. i'll put them up.", false, 0);

        completeRooms(AgentOpqDefinition.Room.STORAGE);
        AgentOpqCoordinator.tickSession(session, 12_000L);
        assertEquals(AgentOpqMemberState.Role.IDLE, session.member(3).role(), "Kes waits in the centre");
        MapItem piece = playerDrop(77, AgentOpqDefinition.STATUE_PIECES.get(0), ben.getId(), 1, new Point(0, 0),
                12_000L);
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(kes, piece));

        AgentOpqCoordinator.tickMember(session, run.entries.get(3), kes, 13_000L);

        assertEquals(kes.getId(), session.claimerOf(piece.getObjectId()));
        assertTrue(AgentOpqSessionRegistry.canLootExclusive(kes, piece));
        assertFalse(AgentOpqSessionRegistry.canLootExclusive(pel, piece));
        verify(kes).pickupItem(piece);
    }

    @Test
    void withNoCompanionInTheCentreHeIsAskedToHoldHisPiecesUntilOneComes() {
        AgentOpqCoordinator.tickSession(session, 10_000L);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 300, -138);
        stand(kes, AgentOpqDefinition.STORAGE_MAP, 0, 0);
        stand(pel, AgentOpqDefinition.LOBBY_MAP, 0, 0);
        stand(hal, AgentOpqDefinition.ON_WAY_UP_MAP, 0, -1_000);
        stand(gus, AgentOpqDefinition.LOUNGE_MAP, -45, -728);
        holds(ben, AgentOpqDefinition.STATUE_PIECES.get(0), 1);

        AgentOpqCoordinator.tickSession(session, 11_000L);
        AgentOpqCoordinator.tickSession(session, 12_000L);

        verify(run.party, times(1)).sendPartyChat(mira,
                "Ben, hold the statue pieces till one of us is in the middle, they vanish on the floor.");
        verify(packets, never()).broadcastChatText(any(), eq("Ben, drop your statue pieces here. i'll put them up."),
                anyBoolean(), anyInt());

        stand(kes, AgentOpqDefinition.CENTER_MAP, 0, 0); // back from the storage room
        AgentOpqCoordinator.tickSession(session, 13_000L);
        verify(packets).broadcastChatText(kes, "Ben, drop your statue pieces here. i'll put them up.", false, 0);
    }

    @Test
    void aCompanionWaitingInTheCentreFightsItsFlyingBoogies() {
        // 2026-10-24 live (run 4): the centre's Flying Boogies killed Ben twice while he waited there for the calls.
        AgentOpqCoordinator.tickSession(session, 10_000L);
        completeRooms(AgentOpqDefinition.Room.STORAGE);
        AgentOpqCoordinator.tickSession(session, 11_000L);
        assertEquals(AgentOpqMemberState.Role.IDLE, session.member(3).role());
        when(run.actions.liveMonsterCount(kes, AgentOpqDefinition.CENTER_MOBS)).thenReturn(4);

        AgentOpqCoordinator.tickMember(session, run.entries.get(3), kes, 12_000L);

        verify(run.actions).grind(run.entries.get(3), AgentOpqDefinition.CENTER_MOBS);
        verify(run.actions, never()).stop(run.entries.get(3));
    }

    @Test
    void companionsThatCannotClimbWaitInTheCentreWhenTheLoungeHasNoSubroomForThem() {
        // 2026-10-25 live (run 6): after the sealed room Vex, Marrow and Kettle, none of whom climbs the lounge's
        // ledges, were sent to help in the lounge, whose low subroom was done, and stood at its entry for eight minutes.
        AgentOpqCoordinator.tickSession(session, 10_000L);
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(6).assignedSubroomMapId());
        session.completeLoungeSubroom(AgentOpqCoordinator.LOUNGE_LOW_SUBROOM, 10_500L);
        completeRooms(AgentOpqDefinition.Room.WALKWAY, AgentOpqDefinition.Room.STORAGE, AgentOpqDefinition.Room.LOBBY,
                AgentOpqDefinition.Room.ON_WAY_UP, AgentOpqDefinition.Room.SEALED);
        for (Character grounded : List.of(mira, pel, hal)) when(grounded.getTotalJumpStat()).thenReturn(100);

        AgentOpqCoordinator.tickSession(session, 11_000L);

        assertEquals(AgentOpqMemberState.Role.LOUNGE_RUNNER, session.member(3).role(), "Kes climbs");
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(1), session.member(3).assignedSubroomMapId());
        for (int grounded : List.of(2, 4, 5)) {
            assertEquals(AgentOpqMemberState.Role.IDLE, session.member(grounded).role(), "member " + grounded);
        }
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(6).assignedSubroomMapId());
    }

    @Test
    void theWholeStatueCallsHimToTheCentresEakEvenInTheCentre() {
        session.transition(AgentOpqSession.Phase.RESTORING_STATUE, 9_000L);
        Reactor scar = mock(Reactor.class);
        when(scar.getState()).thenReturn((byte) 1);
        when(run.map.getReactorByName(anyString())).thenReturn(scar);

        AgentOpqCoordinator.tickSession(session, 10_000L);

        verify(packets).broadcastChatText(mira, "Ben, the statue's done. talk to eak in the middle.", false, 0);
    }

    @Test
    void withGuidanceOffTheRoomsAreWorkedWithoutAWord() {
        session.setMemberCoordinationChatEnabled(false);
        stand(mira, AgentOpqDefinition.WALKWAY_MAP, 300, -138);
        holds(mira, AgentOpqDefinition.WALKWAY_FRAGMENT, 30);

        AgentOpqCoordinator.tickSession(session, 10_000L);
        AgentOpqCoordinator.tickSession(session, 11_000L);

        assertEquals(AgentOpqDefinition.Room.WALKWAY, session.member(2).assignedRoom());
        verify(packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }
}
