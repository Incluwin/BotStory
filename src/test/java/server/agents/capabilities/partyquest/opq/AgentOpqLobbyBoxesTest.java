package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.PacketGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-25 (user): Wednesday's LP box stays out of reach (its cell has no ladder and steps 123 px apart, see
 * AgentOpqNavigationGraphTest), the original puzzle kept. 2026-10-24 offline, the lobby runner walked under a box it
 * could never reach and stood there in silence. On Wednesday it now says once that it cannot get up to the box and
 * waits by the music player for the LP a player brings; the reward box Eak raises it still opens; on any other day it
 * goes for the day's box.
 */
class AgentOpqLobbyBoxesTest {
    private static final String OUT_OF_REACH =
            "Ben, i can't get up to today's box. if you get the lp, drop it in the music player.";

    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final PacketGateway packets = mock(PacketGateway.class);
    private MockedStatic<AgentPacketGatewayRuntime> packetRuntime;
    private AgentOpqSession session;
    private AgentRuntimeEntry entry;

    @BeforeEach
    void setUp() {
        packetRuntime = mockStatic(AgentPacketGatewayRuntime.class);
        packetRuntime.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        when(run.map.getId()).thenReturn(AgentOpqDefinition.LOBBY_MAP);
        for (Character member : List.of(ben, mira)) {
            when(member.getMapId()).thenReturn(AgentOpqDefinition.LOBBY_MAP);
            when(member.getPosition()).thenReturn(new Point(-1_633, -127)); // the lobby floor by the music player
        }
        session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, mira, kes, pel, hal, gus);
        session.setMemberCoordinationChatEnabled(true);
        entry = run.entries.get(mira.getId());
    }

    @AfterEach
    void tearDown() {
        packetRuntime.close();
        run.close();
    }

    private Reactor box(int objectId, int reactorId, int x) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getObjectId()).thenReturn(objectId);
        when(reactor.getId()).thenReturn(reactorId);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(true);
        when(reactor.getPosition()).thenReturn(new Point(x, -907));
        when(reactor.getMap()).thenReturn(run.map);
        return reactor;
    }

    private void tick(int dayOfWeek, long nowMs) {
        AgentOpqCoordinator.tickLobbyBoxes(session, entry, mira, session.member(mira.getId()), dayOfWeek, nowMs);
    }

    @Test
    void onWednesdayTheRunnerSaysOnceItCannotReachTheBoxAndWaitsByTheMusicPlayer() {
        Reactor wednesday = box(41, 2_002_007, -357);
        when(run.actions.reactors(mira)).thenReturn(List.of(wednesday));

        tick(Calendar.WEDNESDAY, 10_000L);
        tick(Calendar.WEDNESDAY, 10_500L);

        verify(run.actions, times(2)).navigate(entry, AgentOpqCoordinator.MUSIC_PLAYER_DROP, true);
        verify(run.actions, never()).navigate(entry, wednesday.getPosition(), true);
        assertEquals(0, session.member(mira.getId()).committedReactorObjectId());
        verify(packets, times(1)).broadcastChatText(mira, OUT_OF_REACH, false, 0);
    }

    @Test
    void onWednesdayTheRunnerStillOpensTheRewardBoxEakRaises() {
        Reactor wednesday = box(41, 2_002_007, -357);
        Reactor reward = box(42, AgentOpqDefinition.LOBBY_REWARD_BOX, -1_500);
        when(run.actions.reactors(mira)).thenReturn(List.of(wednesday, reward));

        tick(Calendar.WEDNESDAY, 10_000L);

        assertEquals(42, session.member(mira.getId()).committedReactorObjectId());
        verify(run.actions).navigate(entry, reward.getPosition(), true);
        verify(packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    void onAnyOtherDayTheRunnerGoesForTheDaysBox() {
        Reactor monday = box(43, 2_002_005, -1_717);
        when(run.actions.reactors(mira)).thenReturn(List.of(monday));

        tick(Calendar.MONDAY, 10_000L);

        assertEquals(43, session.member(mira.getId()).committedReactorObjectId());
        verify(run.actions).navigate(entry, monday.getPosition(), true);
        verify(run.actions, never()).navigate(eq(entry), eq(AgentOpqCoordinator.MUSIC_PLAYER_DROP), anyBoolean());
        verify(packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
    }
}
