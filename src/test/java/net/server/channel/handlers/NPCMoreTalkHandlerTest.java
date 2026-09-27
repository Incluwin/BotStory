package net.server.channel.handlers;

import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import server.living.probe.CompanionProbeService;
import testutil.HandlerTest;
import testutil.Packets;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A reply with no NPC or quest conversation behind it: the orphan path the probe windows use. */
@ExtendWith(MockitoExtension.class)
class NPCMoreTalkHandlerTest extends HandlerTest {
    private static final int GM_ID = 5150;
    private final NPCMoreTalkHandler handler = new NPCMoreTalkHandler();

    @AfterEach
    void closeProbeWindows() {
        CompanionProbeService.forget(GM_ID);
    }

    private static InPacket reply(int lastMsg, int action, int selection) {
        return Packets.buildInPacket(out -> {
            out.writeByte(lastMsg);
            out.writeByte(action);
            out.writeInt(selection);
        });
    }

    @Test
    void theReplyToAProbeWindowReEnablesActionsOnce() {
        when(chr.getId()).thenReturn(GM_ID);
        CompanionProbeService.windowOpened(GM_ID);

        handler.handlePacket(reply(0, 1, 0), client);
        handler.handlePacket(reply(0, 1, 0), client); // that window is answered now

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, times(1)).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.enableActions().getBytes(), sent.getValue().getBytes());
    }

    @Test
    void anOrphanReplyWithNoProbeWindowSendsNothingAndReadsOnlyTheHeader() {
        InPacket packet = reply(4, 1, 1);

        handler.handlePacket(packet, client);

        verify(client, never()).sendPacket(any(Packet.class));
        verify(client, never()).getPlayer();
        assertEquals(4, packet.available());
    }
}
