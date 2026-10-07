package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.plans.AgentScriptItemActionService;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.6: a companion run's split rooms without assists. A stage-5 room that runs long is re-planned every
 * STAGE_5_ROOM_CEILING_MS (no remote box hits, no vacuum, no teleport) and an ordinary room (502-505) is given up at
 * STAGE_5_ROOM_GIVE_UP_MS while a box or a pass is left in it, the owner walking out and telling the leader which door
 * (H6b/H6p); rooms 501 and 506 never give up; a room with nothing left in it is done short and its owner walks out with
 * what it holds; stages 4 and 5 open with their lines (S4, S5).
 */
class AgentLpqCompanionRoomsTest {
    private static final int S4 = AgentLpqDefinition.stage(4).mapId();
    private static final int S5 = AgentLpqDefinition.stage(5).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character pel;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Pel alone in {@code room}, a box (when {@code box}) out of his reach, its exit portal at (400, 0). */
    private AgentLpqSession pelInRoom(boolean companion, int room, boolean box) {
        fx.npcAt(AgentLpqDefinition.stage(5).npcId(), new Point(58, -225));
        crew = fx.crew(S5, new Point(0, 0));
        pel = crew.get(3);
        fx.onMap(pel, room, new Point(0, 0));
        fx.portal(room, room == AgentLpqDefinition.STAGE_5_TELEPORT_ROOM ? 3 : 2, new Point(400, 0), S5);
        if (box) {
            Reactor reactor = mock(Reactor.class);
            when(reactor.isAlive()).thenReturn(true);
            when(reactor.isActive()).thenReturn(true);
            when(reactor.getObjectId()).thenReturn(91);
            when(reactor.getPosition()).thenReturn(new Point(900, -1_200));
            when(fx.actions.reactors(pel)).thenReturn(List.of(reactor));
        }
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_5, crew.toArray(Character[]::new));
        session.narrateOnce("s5-start");
        fx.tick(session, 3_000L);
        assertEquals(room, session.member(pel.getId()).assignedMapId(), "Pel owns the room he stands in");
        return session;
    }

    @Test
    void aLongRoomIsReplannedNotRecovered() {
        AgentLpqSession session = pelInRoom(true, 922_010_503, true);
        fx.holds(pel, AgentLpqDefinition.PASS, 2);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class);
             LpqLogLines rescues = new LpqLogLines(AgentLpqRescue.class)) {
            fx.tick(session, 243_001L);
            fx.tick(session, 244_000L);

            assertEquals(List.of("LPQ stage5 room retry: session=" + session.sessionId() + " room=922010503"
                    + " member=Pel(4) step=RETRY elapsedMs=240001 passes=2/4"), log.startingWith("LPQ stage5 room retry"));
            assertEquals(List.of(), log.startingWith("LPQ Stage 5 four-minute room recovery"));
            assertEquals(List.of(), rescues.startingWith("LPQ rescue: "));
        }
        verify(fx.actions, atLeastOnce()).refreshNavigation(fx.entry(pel), pel);
        assertFalse(session.rooms().completed(922_010_503));
        verify(fx.actions, never()).stagePosition(any(), any(), any());
    }

    @Test
    void anOrdinaryRoomIsGivenUpAndTheLeaderIsToldWhichDoor() {
        AgentLpqSession session = pelInRoom(true, 922_010_503, true);
        fx.holds(pel, AgentLpqDefinition.PASS, 2);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 243_001L);
            fx.tick(session, 483_001L);

            assertEquals(1, log.startingWith("LPQ stage5 room retry: session=" + session.sessionId()
                    + " room=922010503 member=Pel(4) step=GIVE_UP elapsedMs=480001 passes=2/4").size());
        }
        assertTrue(session.rooms().completed(922_010_503));
        verify(fx.actions, atLeastOnce()).navigate(fx.entry(pel), new Point(400, 0), true);
        verify(fx.actions, never()).stagePosition(any(), any(), any());
        fx.neverSaid("s5-room-922010503");

        fx.onMap(pel, S5, new Point(30, 0));
        fx.tick(session, 484_000L);
        fx.said(session, "s5-room-922010503", pel,
                "Crewfriend, there's a box i can't get to in the third door from the top. can you break it?");
    }

    @Test
    void theTeleportRoomNeverGivesUp() {
        AgentLpqSession session = pelInRoom(true, AgentLpqDefinition.STAGE_5_TELEPORT_ROOM, true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 243_001L);
            fx.tick(session, 483_001L);

            assertEquals(2, log.startingWith("LPQ stage5 room retry: session=" + session.sessionId()
                    + " room=922010501 member=Pel(4) step=RETRY").size());
            assertEquals(List.of(), log.startingWith("LPQ stage5 room retry: session=" + session.sessionId()
                    + " room=922010501 member=Pel(4) step=GIVE_UP"));
        }
        assertFalse(session.rooms().completed(AgentLpqDefinition.STAGE_5_TELEPORT_ROOM));
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), startsWith("s5-room-"), any(), anyString()),
                never());
    }

    @Test
    void aRoomWithNothingLeftIsDoneShortAndItsOwnerBringsWhatItHolds() {
        AgentLpqSession session = pelInRoom(true, 922_010_504, false);
        fx.holds(pel, AgentLpqDefinition.PASS, 3);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 4_000L);
            fx.tick(session, 7_001L);
            fx.tick(session, 8_000L);

            assertEquals(List.of("LPQ stage5 room short: session=" + session.sessionId() + " room=922010504"
                    + " member=Pel(4) passes=3/4"), log.startingWith("LPQ stage5 room short"));
        }
        assertTrue(session.rooms().completed(922_010_504));
        verify(fx.actions, atLeastOnce()).navigate(fx.entry(pel), new Point(400, 0), true);

        fx.onMap(pel, S5, new Point(20, 0));
        fx.tick(session, 9_000L);
        fx.items.verify(() -> AgentScriptItemActionService.dropItem(fx.entry(pel), InventoryType.ETC,
                AgentLpqDefinition.PASS, (short) 3));
        verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), startsWith("s5-room-"), any(), anyString()),
                never());
    }

    @Test
    void aPassOutOfReachIsRetriedThenGivenUp() {
        AgentLpqSession session = pelInRoom(true, 922_010_505, false);
        fx.holds(pel, AgentLpqDefinition.PASS, 3);
        fx.drop(922_010_505, AgentLpqDefinition.PASS, 1, new Point(-700, -2_000), false, 0, 2_000L);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 243_001L);
            assertEquals(1, log.startingWith("LPQ stage5 room retry: session=" + session.sessionId()
                    + " room=922010505 member=Pel(4) step=RETRY").size());
            fx.tick(session, 483_001L);
            assertEquals(1, log.startingWith("LPQ stage5 room retry: session=" + session.sessionId()
                    + " room=922010505 member=Pel(4) step=GIVE_UP").size());
        }
        fx.onMap(pel, S5, new Point(30, 0));
        fx.tick(session, 484_000L);
        fx.said(session, "s5-room-922010505", pel,
                "Crewfriend, there's a pass i can't reach in the door above the balloon. can you get it?");
    }

    @Test
    void aStockRoomStillGetsTheFourMinuteRecovery() {
        AgentLpqSession session = pelInRoom(false, 922_010_503, true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 243_001L);
            assertEquals(1, log.startingWith("LPQ Stage 5 four-minute room recovery activated").size());
        }
    }

    @Test
    void stageFourOpensWithItsLine() {
        crew = fx.crew(S4, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_4, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);

        fx.said(session, "s4-start", crew.get(1),
                "we'll split up for the dark rooms. wait by the lime balloon, we'll bring the passes.");
    }
}
