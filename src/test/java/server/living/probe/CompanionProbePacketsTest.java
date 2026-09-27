package server.living.probe;

import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProbePacketsTest {
    private static final int PARTY_OPERATION = RecvOpcode.PARTY_OPERATION.getValue();
    private static final int SCRIPTED_ITEM = RecvOpcode.SCRIPTED_ITEM.getValue();
    private static final IntFunction<String> NO_NAMES = id -> null;

    @Test
    void partyOperationInviteShowsTheOpByteAndTargetName() {
        byte[] payload = new Bytes().b(4).str("Hadrik").array();

        assertEquals("op=4 invite name=Hadrik", CompanionProbePackets.summarize(PARTY_OPERATION, payload, NO_NAMES));
    }

    @Test
    void partyOperationJoinShowsThePartyId() {
        byte[] payload = new Bytes().b(3).i(77).array();

        assertEquals("op=3 join party=77", CompanionProbePackets.summarize(PARTY_OPERATION, payload, NO_NAMES));
    }

    @Test
    void partyOperationExpelAndLeaderResolveTheCharacterId() {
        IntFunction<String> names = id -> id == 1234 ? "Hadrik[bot]" : null;

        assertEquals("op=5 expel cid=1234 (Hadrik[bot])",
                CompanionProbePackets.summarize(PARTY_OPERATION, new Bytes().b(5).i(1234).array(), names));
        assertEquals("op=6 leader cid=99",
                CompanionProbePackets.summarize(PARTY_OPERATION, new Bytes().b(6).i(99).array(), names));
        assertEquals("op=1 create", CompanionProbePackets.summarize(PARTY_OPERATION, new Bytes().b(1).array(), names));
    }

    @Test
    void scriptedItemShowsSlotAndItemId() {
        byte[] payload = new Bytes().i(0x1234_5678).s(3).i(2430011).array();

        assertEquals("slot=3 item=2430011", CompanionProbePackets.summarize(SCRIPTED_ITEM, payload, NO_NAMES));
    }

    @Test
    void aTruncatedPayloadIsReportedNotThrown() {
        byte[] payload = new Bytes().i(1).array(); // stamp only

        assertEquals("(undecodable, 4 bytes)", CompanionProbePackets.summarize(SCRIPTED_ITEM, payload, NO_NAMES));
        assertEquals("(undecodable, 1 bytes)",
                CompanionProbePackets.summarize(PARTY_OPERATION, new Bytes().b(4).array(), NO_NAMES));
    }

    @Test
    void summarizingDoesNotTouchTheCallersArray() {
        byte[] payload = new Bytes().b(4).str("Ann").array();
        byte[] before = payload.clone();

        CompanionProbePackets.summarize(PARTY_OPERATION, payload, NO_NAMES);

        assertArrayEquals(before, payload);
    }

    @Test
    void npcTalkMoreShowsTypeActionAndSelection() {
        int npcTalkMore = RecvOpcode.NPC_TALK_MORE.getValue();

        assertEquals("msgType=4 action=1 selection=1",
                CompanionProbePackets.summarize(npcTalkMore, new Bytes().b(4).b(1).i(1).array(), NO_NAMES));
        assertEquals("msgType=0 action=0 selection=-1",
                CompanionProbePackets.summarize(npcTalkMore, new Bytes().b(0).b(0).array(), NO_NAMES));
    }

    @Test
    void namesQuietAndDecodedOpcodes() {
        assertEquals("PARTY_OPERATION(0x7C)", CompanionProbePackets.name(PARTY_OPERATION));
        assertEquals("UNKNOWN(0xFFF0)", CompanionProbePackets.name(0xFFF0));
        assertTrue(CompanionProbePackets.decoded(SCRIPTED_ITEM));
        assertFalse(CompanionProbePackets.decoded(RecvOpcode.GENERAL_CHAT.getValue()));
        assertTrue(CompanionProbePackets.quiet(RecvOpcode.MOVE_PLAYER.getValue()));
        assertFalse(CompanionProbePackets.quiet(PARTY_OPERATION));
        assertEquals("", CompanionProbePackets.summarize(RecvOpcode.GENERAL_CHAT.getValue(), new byte[]{1, 2}, NO_NAMES));
    }

    /** Little-endian payload builder, like the v83 client writes. */
    static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes b(int value) {
            out.write(value);
            return this;
        }

        Bytes s(int value) {
            return b(value & 0xFF).b((value >> 8) & 0xFF);
        }

        Bytes i(int value) {
            return s(value & 0xFFFF).s((value >>> 16) & 0xFFFF);
        }

        Bytes str(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            s(bytes.length);
            out.writeBytes(bytes);
            return this;
        }

        byte[] array() {
            return out.toByteArray();
        }
    }
}
