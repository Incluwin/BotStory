package server.living.probe;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompanionProbeObserverTest {
    private static final int GM_ID = 7;

    @AfterEach
    void stopObserving() {
        CompanionProbeObserver.setObserving(GM_ID, false);
    }

    @Test
    void withNobodyObservingTheHookTouchesNeitherClientNorPacket() {
        Client client = mock(Client.class);
        InPacket packet = mock(InPacket.class);

        CompanionProbeObserver.recordInbound(client, (short) RecvOpcode.PARTY_OPERATION.getValue(), packet);

        verifyNoInteractions(client, packet);
    }

    @Test
    void observationTogglesPerCharacter() {
        assertFalse(CompanionProbeObserver.isObserving(GM_ID));
        assertTrue(CompanionProbeObserver.setObserving(GM_ID, true));
        assertFalse(CompanionProbeObserver.setObserving(GM_ID, true));
        assertTrue(CompanionProbeObserver.isObserving(GM_ID));
        assertFalse(CompanionProbeObserver.isObserving(GM_ID + 1));
        assertTrue(CompanionProbeObserver.setObserving(GM_ID, false));
        assertFalse(CompanionProbeObserver.isObserving(GM_ID));
    }

    @Test
    void anObservedPacketIsEchoedWithoutConsumingIt() {
        CompanionProbeObserver.setObserving(GM_ID, true);
        Character gm = character(GM_ID);
        Client client = client(gm);
        InPacket packet = inbound(RecvOpcode.PARTY_OPERATION, new CompanionProbePacketsTest.Bytes().b(4).str("Hadrik").array());
        short opcode = packet.readShort();
        int position = packet.getPosition();
        int available = packet.available();

        CompanionProbeObserver.recordInbound(client, opcode, packet);

        verify(gm).dropMessage(5, "[probe] PARTY_OPERATION(0x7C) op=4 invite name=Hadrik");
        assertEquals(position, packet.getPosition());
        assertEquals(available, packet.available());
        assertEquals(4, packet.readByte()); // the real handler still reads the op byte and the name
        assertEquals("Hadrik", packet.readString());
    }

    @Test
    void otherCharactersAndQuietOpcodesAreNotEchoed() {
        CompanionProbeObserver.setObserving(GM_ID, true);
        Character other = character(GM_ID + 1);
        InPacket fromOther = inbound(RecvOpcode.PARTY_OPERATION, new byte[]{1});
        CompanionProbeObserver.recordInbound(client(other), fromOther.readShort(), fromOther);

        Character gm = character(GM_ID);
        InPacket movement = inbound(RecvOpcode.MOVE_PLAYER, new byte[]{0, 0, 0, 0});
        CompanionProbeObserver.recordInbound(client(gm), movement.readShort(), movement);

        verify(other, never()).dropMessage(anyInt(), anyString());
        verify(gm, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void anUndecodedOpcodeIsEchoedByNameInBlue() {
        CompanionProbeObserver.setObserving(GM_ID, true);
        Character gm = character(GM_ID);
        InPacket chat = inbound(RecvOpcode.GENERAL_CHAT, new byte[]{0, 0, 0});

        CompanionProbeObserver.recordInbound(client(gm), chat.readShort(), chat);

        verify(gm).dropMessage(eq(6), eq("[probe] GENERAL_CHAT(0x31)"));
    }

    private static Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("Gm" + id);
        return chr;
    }

    private static Client client(Character chr) {
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        return client;
    }

    private static InPacket inbound(RecvOpcode opcode, byte[] payload) {
        byte[] bytes = new byte[payload.length + 2];
        bytes[0] = (byte) opcode.getValue();
        bytes[1] = (byte) (opcode.getValue() >> 8);
        System.arraycopy(payload, 0, bytes, 2, payload.length);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
    }
}
