package server.agents.capabilities.partyquest.opq;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import server.agents.integration.InventoryGateway;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import org.mockito.MockedStatic;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.inventory.Inventory;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ step 1 (C4), the coordinator's lines in every mode: 'OPQ room:' once per change of a room's lease (a claim,
 * a completion), and 'OPQ eak:' after each of its own Eak runs that ran, with the talker, the stage, the puzzle attempt
 * and the stage's event property as the run left it. Logging only: the run plays as before.
 */
class AgentOpqRoomEakLineTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character lea = run.companion(11, "Lea");
    private final Character mira = run.companion(12, "Mira");
    private final Character kes = run.companion(13, "Kes");
    private final Character pel = run.companion(14, "Pel");
    private final Character hal = run.companion(15, "Hal");
    private final Character gus = run.companion(16, "Gus");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession sixAgents(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.AUTONOMOUS, phase, lea, mira, kes, pel, hal, gus);
    }

    @Test
    void eachRoomClaimAndCompletionIsLoggedOnce() {
        AgentOpqSession session = sixAgents(AgentOpqSession.Phase.SPLIT_ROOMS);
        String id = session.sessionId();
        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 10_000L);
            assertEquals(List.of(
                    "OPQ room: session=" + id + " room=SEALED state=CLAIMED owner=11 runMs=9000",
                    "OPQ room: session=" + id + " room=ON_WAY_UP state=CLAIMED owner=15 runMs=9000",
                    "OPQ room: session=" + id + " room=LOUNGE state=CLAIMED owner=16 runMs=9000"),
                    lines.startingWith("OPQ room:"));

            AgentOpqCoordinator.tickSession(session, 11_000L); // the same claims: no new line
            assertEquals(3, lines.startingWith("OPQ room:").size());

            // Eak sealed the room and Kes holds its piece: the room is complete.
            when(run.partyQuest.property(lea, "statusStg4")).thenReturn("1");
            when(kes.getItemQuantity(AgentOpqDefinition.STATUE_PIECES.get(3), false)).thenReturn(1);
            AgentOpqCoordinator.tickSession(session, 12_000L);
            AgentOpqCoordinator.tickSession(session, 13_000L);

            List<String> completions = lines.startingWith("OPQ room:").stream()
                    .filter(line -> line.contains("state=COMPLETE")).toList();
            assertEquals(List.of("OPQ room: session=" + id + " room=SEALED state=COMPLETE owner=11 runMs=11000"),
                    completions);
        }
        assertEquals(AgentOpqSession.Phase.SPLIT_ROOMS, session.phase());
    }

    @Test
    void theStatueEakRunIsLoggedOnlyWhenItRan() {
        AgentOpqSession session = sixAgents(AgentOpqSession.Phase.RESTORING_STATUE);
        Reactor restored = mock(Reactor.class);
        when(restored.getState()).thenReturn((byte) 1);
        when(run.map.getReactorByName(anyString())).thenReturn(restored);
        nearEak(lea, AgentOpqDefinition.CENTER_MAP);
        when(run.partyQuest.property(lea, "statusStg7")).thenReturn("-1");

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            when(run.partyQuest.runNpc(lea, AgentOpqDefinition.EAK_NPC)).thenReturn(false);
            AgentOpqCoordinator.tickSession(session, 10_000L);
            assertEquals(List.of(), lines.startingWith("OPQ eak:"), "a run that did not run logs nothing");

            when(run.partyQuest.runNpc(lea, AgentOpqDefinition.EAK_NPC)).thenReturn(true);
            AgentOpqCoordinator.tickSession(session, 11_000L);
            assertEquals(List.of("OPQ eak: session=" + session.sessionId()
                            + " map=920010100 talker=Lea kind=STATUE attempt=0 result=-1"),
                    lines.startingWith("OPQ eak:"));
        }
    }

    @Test
    void eachSealedCheckIsLoggedWithItsAttempt() {
        AgentOpqSession session = sixAgents(AgentOpqSession.Phase.SPLIT_ROOMS);
        session.member(11).assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.SEALED, 0);
        Character[] holders = {mira, kes, pel};
        Point[] platforms = {new Point(-162, -808), new Point(-40, -919), new Point(78, -813)};
        for (int platform = 0; platform < 3; platform++) {
            AgentOpqMemberState holder = session.member(holders[platform].getId());
            holder.assign(AgentOpqMemberState.Role.SEALED_PLATFORM, AgentOpqDefinition.Room.SEALED, 0);
            holder.assignPlatform(platform);
            when(holders[platform].getMapId()).thenReturn(AgentOpqDefinition.SEALED_MAP);
            when(holders[platform].getPosition()).thenReturn(platforms[platform]);
        }
        nearEak(lea, AgentOpqDefinition.SEALED_MAP);
        when(run.partyQuest.runNpc(lea, AgentOpqDefinition.EAK_NPC)).thenAnswer(inv -> {
            AgentOpqSessionRegistry.recordSealedVerdict(lea.getId(), false); // 2013001.js: "All ... wrong"
            return true;
        });
        String id = session.sessionId();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickMember(session, run.entries.get(11), lea, 10_000L);
            AgentOpqCoordinator.tickMember(session, run.entries.get(11), lea, 12_000L);
            when(run.partyQuest.property(lea, "statusStg4")).thenReturn("1");
            AgentOpqCoordinator.tickMember(session, run.entries.get(11), lea, 14_000L);

            assertEquals(List.of(
                    "OPQ eak: session=" + id + " map=920010500 talker=Lea kind=SEALED attempt=1 result=-1",
                    "OPQ eak: session=" + id + " map=920010500 talker=Lea kind=SEALED attempt=2 result=-1"),
                    lines.startingWith("OPQ eak:"), "the third tick sees the room sealed and leaves");
        }
        assertEquals(2, session.sealedAttempt());
    }

    @Test
    void eaksAnswersNarrowTheSearchAndTheSealingSplitIsRemembered() {
        // 2026-10-17: the holders stand in the search's guess; Eak's answers come through 2013001.js's hook
        AgentOpqSession session = sixAgents(AgentOpqSession.Phase.SPLIT_ROOMS);
        session.member(11).assign(AgentOpqMemberState.Role.LEADER, AgentOpqDefinition.Room.SEALED, 0);
        Character[] holders = {mira, kes, pel};
        Point[] platforms = {new Point(-162, -808), new Point(-40, -919), new Point(78, -813)};
        for (int platform = 0; platform < 3; platform++) {
            AgentOpqMemberState holder = session.member(holders[platform].getId());
            holder.assign(AgentOpqMemberState.Role.SEALED_PLATFORM, AgentOpqDefinition.Room.SEALED, 0);
            holder.assignPlatform(platform);
            when(holders[platform].getMapId()).thenReturn(AgentOpqDefinition.SEALED_MAP);
            when(holders[platform].getPosition()).thenReturn(platforms[platform]);
        }
        nearEak(lea, AgentOpqDefinition.SEALED_MAP);
        String[] status = {"0"};
        when(run.partyQuest.property(lea, "statusStg4")).thenAnswer(inv -> status[0]);
        int[] checks = {0};
        when(run.partyQuest.runNpc(lea, AgentOpqDefinition.EAK_NPC)).thenAnswer(inv -> {
            if (++checks[0] == 1) AgentOpqSessionRegistry.recordSealedVerdict(lea.getId(), true);
            else status[0] = "1"; // the second check seals the room
            return true;
        });
        String id = session.sessionId();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickMember(session, run.entries.get(11), lea, 10_000L);
            AgentOpqSealedSearch.Split second = session.sealedGuess();
            AgentOpqCoordinator.tickMember(session, run.entries.get(11), lea, 12_000L);

            assertEquals("012", second.key(), "the best of the six splits that share a platform's count with 003");
            assertEquals(List.of(
                    "OPQ sealed: session=" + id + " split=003 answer=one-right candidates=6 solvedBefore=0",
                    "OPQ sealed: session=" + id + " split=012 answer=sealed candidates=6 solvedBefore=0"),
                    lines.startingWith("OPQ sealed:"));
            assertEquals(1, AgentOpqSealedSplitMemory.current().solvedCount(second), "remembered for later runs");
        }
        assertEquals(1, session.sealedAttempt(), "one wrong check");
    }

    /** {@code leader} stands on {@code mapId} next to Eak. */
    private void nearEak(Character leader, int mapId) {
        when(leader.getMapId()).thenReturn(mapId);
        when(leader.getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(leader, AgentOpqDefinition.EAK_NPC)).thenReturn(new Point(20, 0));
    }

    @Test
    void aPlacedStatuePieceIsLogged() {
        AgentOpqSession session = sixAgents(AgentOpqSession.Phase.RESTORING_STATUE);
        int piece = AgentOpqDefinition.STATUE_PIECES.get(0); // the walkway piece goes on scar4
        when(mira.getItemQuantity(piece, false)).thenReturn(1);
        when(mira.getPosition()).thenReturn(new Point(AgentOpqDefinition.STATUE_SCAR_POSITION.get("scar4")));
        Item held = mock(Item.class);
        when(held.getQuantity()).thenReturn((short) 1);
        when(held.getPosition()).thenReturn((short) 3);
        Inventory etc = mock(Inventory.class);
        when(etc.findById(piece)).thenReturn(held);
        when(mira.getInventory(InventoryType.ETC)).thenReturn(etc);
        InventoryGateway inventory = mock(InventoryGateway.class);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventories = mockStatic(AgentInventoryGatewayRuntime.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class);
             OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
            reservations.when(() -> AgentInventoryReservationRuntime.mayConsume(any(), any(), anyLong()))
                    .thenReturn(true);

            AgentOpqCoordinator.tickMember(session, run.entries.get(12), mira, 10_000L);

            verify(inventory).dropItem(mira, InventoryType.ETC, (short) 3, (short) 1);
            assertEquals(List.of("OPQ statue: session=" + session.sessionId() + " scar=scar4 piece=4001044 by=Mira"),
                    lines.startingWith("OPQ statue:"));
        }
    }
}
