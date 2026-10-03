package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.PacketGateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A companion run's lines are map chat in the client's charset (and logged as 'HPQ line:'); no speaker, no line. */
class AgentHpqDialogueTest {
    @Test
    void aLineIsSaidAsSanitizedMapChat() {
        PacketGateway packets = mock(PacketGateway.class);
        Character mira = mock(Character.class);
        when(mira.getName()).thenReturn("Mira");
        try (MockedStatic<AgentPacketGatewayRuntime> gateway = mockStatic(AgentPacketGatewayRuntime.class)) {
            gateway.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);

            AgentHpqDialogue.sayMapNow("s-1", "bunny-out", mira, "the bunny\u2019s out! keep the monsters off it.");
            AgentHpqDialogue.sayMapNow("s-1", "none", null, "nobody says this");

            verify(packets).broadcastChatText(mira, "the bunny's out! keep the monsters off it.", false, 0);
            verify(packets, times(1)).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        }
    }
}
