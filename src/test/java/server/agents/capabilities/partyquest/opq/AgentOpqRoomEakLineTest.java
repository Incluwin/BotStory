package server.agents.capabilities.partyquest.opq;

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
        when(run.partyQuest.runNpc(lea, AgentOpqDefinition.EAK_NPC)).thenReturn(true);
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

    /** {@code leader} stands on {@code mapId} next to Eak. */
    private void nearEak(Character leader, int mapId) {
        when(leader.getMapId()).thenReturn(mapId);
        when(leader.getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(leader, AgentOpqDefinition.EAK_NPC)).thenReturn(new Point(20, 0));
    }
}
