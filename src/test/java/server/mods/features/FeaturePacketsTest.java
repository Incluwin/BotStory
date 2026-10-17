package server.mods.features;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import server.mods.features.FeaturePackets.Answer;
import server.mods.features.FeaturePackets.Feature;
import server.mods.features.FeaturePackets.Report;
import server.mods.features.FeaturePolicy.Decision;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login feature handshake's wire format (docs/CLIENT_FEATURE_HANDSHAKE.md, "Protocol"), byte for byte as the
 * kaentake DLL writes and reads it (its test/featureproto_test.cpp checks the same bytes): the client's REPORT and
 * APPLIED on Recv opcode 0x118, the server's POLICY on Send opcode 0x17B. Anything short, unknown or oversized decodes
 * to nothing and never throws.
 */
class FeaturePacketsTest {

    @Test
    void theOpcodesFollowTheCrewWindows() {
        assertEquals(0x118, RecvOpcode.CLIENT_FEATURES.getValue());
        assertEquals(0x17B, SendOpcode.CLIENT_FEATURES.getValue());
        assertEquals(1, FeaturePackets.PROTOCOL);
    }

    /** The REPORT the DLL's own test builds (featureproto_test.cpp SampleReport), after the opcode. */
    static Wire sampleReport(int op) {
        return new Wire()
                .u8(op).u16(1).u32(20261017).u16(3)
                .str("mob_hp_tag").str("Mob HP bar with numbers").u16(1).u8(0x04).u8(0x03)
                .str("crew_window").str("Crew window").u16(1).u8(0x00).u8(0x03)
                .str("free_addy").str("Gameplay patches (damage/speed caps)").u16(2).u8(0x06).u8(0x01);
    }

    @Test
    void aReportIsItsHeaderAndOneLinePerFeature() {
        Report report = FeaturePackets.decode(in(sampleReport(0).bytes()));
        assertEquals(new Report(false, 1, 20261017, List.of(
                new Feature("mob_hp_tag", "Mob HP bar with numbers", 1, 0x04, 0x03),
                new Feature("crew_window", "Crew window", 1, 0x00, 0x03),
                new Feature("free_addy", "Gameplay patches (damage/speed caps)", 2, 0x06, 0x01))), report);

        Feature hpTag = report.features().get(0);
        assertTrue(hpTag.live() && hpTag.attached() && hpTag.active());
        assertFalse(hpTag.core() || hpTag.serverGated() || hpTag.vetoed());
        Feature addy = report.features().get(2);
        assertTrue(addy.serverGated() && addy.live() && addy.attached());
        assertFalse(addy.active()); // attached but inert
    }

    @Test
    void appliedIsTheSameListAfterTheClientCarriedOutAnAnswer() {
        Report applied = FeaturePackets.decode(in(sampleReport(1).bytes()));
        assertTrue(applied.applied());
        assertEquals(3, applied.features().size());
    }

    @Test
    void anEmptyListAndTrailingBytesFromALaterProtocolDecode() {
        assertEquals(new Report(false, 1, 0, List.of()),
                FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(0).bytes())));
        byte[] longer = new Wire().u8(0).u16(2).u32(7).u16(0).u8(0xEE).u32(-1).bytes();
        assertEquals(new Report(false, 2, 7, List.of()), FeaturePackets.decode(in(longer)));
    }

    @Test
    void shortUnknownOrOversizedPacketsDecodeToNothing() {
        byte[] whole = sampleReport(0).bytes();
        for (int cut = 0; cut < whole.length; cut++) {
            assertNull(FeaturePackets.decode(in(java.util.Arrays.copyOf(whole, cut))), "cut at " + cut);
        }
        assertNull(FeaturePackets.decode(in(new Wire().u8(2).u16(1).u32(0).u16(0).bytes())), "unknown op");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(0).u32(0).u16(0).bytes())), "protocol 0");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(256).bytes())), "256 features");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(1)
                .str("x".repeat(65)).str("").u16(1).u8(0).u8(0).bytes())), "id over 64 bytes");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(1)
                .str("x").str("t".repeat(129)).u16(1).u8(0).u8(0).bytes())), "title over 128 bytes");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(1)
                .str("").str("t").u16(1).u8(0).u8(0).bytes())), "empty id");
        assertNull(FeaturePackets.decode(in(new Wire().u8(0).u16(1).u32(0).u16(1)
                .u16(0xFFFF).bytes())), "string length 65535 with no bytes");
    }

    @Test
    void aPolicyIsItsHeaderAndOneDecisionPerFeature() {
        byte[] policy = FeaturePackets.policy(1, List.of(
                new Answer("mob_hp_tag", Decision.DENY),
                new Answer("crew_window", Decision.ALLOW),
                new Answer("free_addy", Decision.REQUIRE))).getBytes();
        Wire expected = new Wire()
                .u16(0x17B).u8(0).u16(1).u16(3)
                .str("mob_hp_tag").u8(1)
                .str("crew_window").u8(0)
                .str("free_addy").u8(2);
        assertArrayEquals(expected.bytes(), policy);
        // spelled out once, so the DLL side can read it against its parser
        assertArrayEquals(bytes(0x7B, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x0A, 0x00, 'm', 'o', 'b'),
                java.util.Arrays.copyOf(policy, 12));
    }

    @Test
    void anEmptyPolicyIsJustItsHeader() {
        assertArrayEquals(bytes(0x7B, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00), FeaturePackets.policy(1, List.of()).getBytes());
    }

    private static InPacket in(byte[] body) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(body));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** Little-endian bytes as the DLL writes and reads them, written by hand: not by the server's OutPacket. */
    static final class Wire {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Wire u8(int value) {
            out.write(value & 0xFF);
            return this;
        }

        Wire u16(int value) {
            return u8(value).u8(value >>> 8);
        }

        Wire u32(int value) {
            return u16(value).u16(value >>> 16);
        }

        Wire str(String value) {
            byte[] text = value.getBytes(StandardCharsets.US_ASCII);
            u16(text.length);
            out.writeBytes(text);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
