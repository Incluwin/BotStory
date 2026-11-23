package net.server.handlers.login;

import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The client's own crash report (C->S 0x19) is logged, bounded: anyone can reach the login port. */
class ClientStartErrorHandlerTest {
    @Test
    void aReportKeepsItsLinesAndLosesEverythingUnprintable() {
        String raw = "ver.83 CharacterName(Admin), FieldID(10000)\r\nException code: C0000005 ACCESS_VIOLATION\r\n"
                + "Fault Address:  00428743\u0000\u0007\u001B[31m";
        assertEquals("ver.83 CharacterName(Admin), FieldID(10000)\nException code: C0000005 ACCESS_VIOLATION\n"
                + "Fault Address:  00428743[31m", ClientStartErrorHandler.clean(raw));
        assertEquals("", ClientStartErrorHandler.clean(null));
    }

    @Test
    void aReportIsCutToTheCap() {
        assertEquals(ClientStartErrorHandler.MAX_CHARS,
                ClientStartErrorHandler.clean("x".repeat(ClientStartErrorHandler.MAX_CHARS * 3)).length());
    }

    @Test
    void oneReportPerAddressPerCooldown() {
        ClientStartErrorHandler handler = new ClientStartErrorHandler();
        assertTrue(handler.accept("10.0.0.5", 1_000));
        assertFalse(handler.accept("10.0.0.5", 1_000 + ClientStartErrorHandler.COOLDOWN_MILLIS - 1));
        assertTrue(handler.accept("10.0.0.6", 1_500), "another address is not held back");
        assertTrue(handler.accept("10.0.0.5", 1_000 + ClientStartErrorHandler.COOLDOWN_MILLIS));
    }

    @Test
    void aReportPacketIsReadAsOneStringAndAShortOneIsIgnored() {
        ClientStartErrorHandler handler = new ClientStartErrorHandler();
        Client client = mock(Client.class);
        when(client.getRemoteAddress()).thenReturn("127.0.0.1");
        assertTrue(handler.validateState(client), "a client sends it before it logs in");

        byte[] text = "ver.83 CharacterName(Admin), WorldID(0), ChID(0), FieldID(10000)".getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[2 + text.length];
        body[0] = (byte) text.length;
        System.arraycopy(text, 0, body, 2, text.length);
        ByteBufInPacket report = new ByteBufInPacket(Unpooled.wrappedBuffer(body));
        handler.handlePacket(report, client);
        assertEquals(0, report.available(), "the whole string was read");

        handler.handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{1})), mock(Client.class));
        // a length that runs past the packet is dropped, not thrown at the network thread
        Client other = mock(Client.class);
        when(other.getRemoteAddress()).thenReturn("127.0.0.2");
        handler.handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{50, 0, 65})), other);
    }
}
