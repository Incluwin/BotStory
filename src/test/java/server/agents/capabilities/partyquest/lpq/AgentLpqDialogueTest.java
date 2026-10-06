package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.PacketGateway;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P6g LPQ C2.1: a companion line is sanitized map chat, logged once as 'LPQ line:'. */
class AgentLpqDialogueTest {

    @Test
    void aLineIsSanitizedLoggedAndSaidOnTheMap() {
        Character mira = mock(Character.class);
        when(mira.getName()).thenReturn("Mira");
        PacketGateway packets = mock(PacketGateway.class);
        String text = "trap's clear, let's go in.";
        String said = AgentChatTextSanitizer.sanitize(text);
        try (MockedStatic<AgentPacketGatewayRuntime> gateway = mockStatic(AgentPacketGatewayRuntime.class);
             LpqLogLines log = new LpqLogLines(AgentLpqDialogue.class)) {
            gateway.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);

            AgentLpqDialogue.sayMapNow("run-1", "s2-clear", mira, text);

            verify(packets).broadcastChatText(mira, said, false, 0);
            assertEquals(List.of("LPQ line: session=run-1 key=s2-clear speaker=Mira text=" + said),
                    log.startingWith("LPQ line: "));
        }
    }

    @Test
    void nothingIsSaidWithoutASpeakerOrALine() {
        PacketGateway packets = mock(PacketGateway.class);
        try (MockedStatic<AgentPacketGatewayRuntime> gateway = mockStatic(AgentPacketGatewayRuntime.class)) {
            gateway.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);

            AgentLpqDialogue.sayMapNow("run-1", "k", null, "hello");
            AgentLpqDialogue.sayMapNow("run-1", "k", mock(Character.class), null);

            verify(packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        }
    }
}
