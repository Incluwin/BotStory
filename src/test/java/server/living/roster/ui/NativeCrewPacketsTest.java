package server.living.roster.ui;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import server.living.roster.ui.NativeCrewPackets.Action;
import server.living.roster.ui.NativeCrewPackets.Close;
import server.living.roster.ui.NativeCrewPackets.Hello;
import server.living.roster.ui.NativeCrewPackets.Open;
import server.living.roster.ui.NativeCrewPackets.Row;
import server.living.roster.ui.NativeCrewPackets.Snapshot;
import server.living.roster.ui.NativeCrewPackets.Verb;
import testutil.Packets;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The native Crew window's wire format (docs/BOT_MANAGEMENT_P10_PLAN.md, "Protocol"), byte for byte as the P10 DLL reads
 * and writes it: the server's WELCOME, CLOSE, OPEN and SNAPSHOT on Send opcode 0x17A, and the client's HELLO, OPEN,
 * CLOSE and ACTION on Recv opcode 0x117, where anything short or unknown decodes to nothing and never throws.
 */
class NativeCrewPacketsTest {

    @Test
    void theOpcodesAreTheProtocols() {
        assertEquals(0x117, RecvOpcode.CREW_WINDOW.getValue());
        assertEquals(0x17A, SendOpcode.CREW_WINDOW.getValue());
    }

    // ---- server to client ------------------------------------------------------------------------

    @Test
    void welcomeIsTheProtocolAndWhetherTheWindowIsEnabled() {
        assertArrayEquals(bytes(0x7A, 0x01, 0x00, 0x01, 0x00, 0x01), NativeCrewPackets.welcome(true).getBytes());
        assertArrayEquals(bytes(0x7A, 0x01, 0x00, 0x01, 0x00, 0x00), NativeCrewPackets.welcome(false).getBytes());
    }

    @Test
    void closeAndOpenAreTheirTypeAlone() {
        assertArrayEquals(bytes(0x7A, 0x01, 0x02), NativeCrewPackets.close().getBytes());
        assertArrayEquals(bytes(0x7A, 0x01, 0x03), NativeCrewPackets.open().getBytes());
    }

    @Test
    void aSnapshotIsItsHeaderThenThePartyRowsThenTheBenchRows() {
        Snapshot snapshot = new Snapshot(0x01020304, 3, 6, 9, "(1 seat kept)", NativeCrewPackets.USUAL_CALLABLE
                | NativeCrewPackets.SOMEONE_OUT,
                List.of(new Row("hadrik", 7, "Hadrik", "Lv31 Fighter", "Perion - HP 90%", 90,
                        NativeCrewPackets.BENCH | NativeCrewPackets.SWAP_OUT)),
                List.of(new Row("mira", -2, "Mira", "Lv28 Hunter", "train @ Ant Tunnel", NativeCrewPackets.HP_UNKNOWN,
                        NativeCrewPackets.CALL | NativeCrewPackets.SWAP_IN)));

        Wire expected = new Wire()
                .u16(0x17A).u8(1) // SNAPSHOT
                .u32(0x01020304) // ownerVersion
                .u8(3).u8(6).u8(9) // seats, partyCap, benchSeats
                .str("(1 seat kept)")
                .u8(0b11) // usual lineup callable, someone out
                .u8(1) // partyCount
                .str("hadrik").u32(7).str("Hadrik").str("Lv31 Fighter").str("Perion - HP 90%").u8(90).u8(0b0110)
                .u8(1) // benchCount
                .str("mira").u32(-2).str("Mira").str("Lv28 Hunter").str("train @ Ant Tunnel").u8(255).u8(0b1001);
        assertArrayEquals(expected.bytes(), NativeCrewPackets.snapshot(snapshot).getBytes());
        // spelled out once, so the DLL side can read it against its parser
        assertArrayEquals(bytes(0x7A, 0x01, 0x01, 0x04, 0x03, 0x02, 0x01, 0x03, 0x06, 0x09, 0x0D, 0x00),
                java.util.Arrays.copyOf(NativeCrewPackets.snapshot(snapshot).getBytes(), 12));
    }

    @Test
    void anEmptyCrewIsTwoZeroCounts() {
        Snapshot empty = new Snapshot(0, 0, 6, 10, "", 0, List.of(), List.of());

        assertArrayEquals(new Wire().u16(0x17A).u8(1).u32(0).u8(0).u8(6).u8(10).str("").u8(0).u8(0).u8(0).bytes(),
                NativeCrewPackets.snapshot(empty).getBytes());
    }

    // ---- client to server ------------------------------------------------------------------------

    @Test
    void helloOpenCloseAndActionDecode() {
        assertEquals(new Hello(1, 20261012), decode(out -> {
            out.writeByte(0);
            out.writeShort(1);
            out.writeInt(20261012);
        }));
        assertEquals(new Open(), decode(out -> out.writeByte(1)));
        assertEquals(new Close(), decode(out -> out.writeByte(2)));
        assertEquals(new Action(Verb.SWAP, 41, "hadrik", 7, "mira", -2), decode(out -> {
            out.writeByte(3);
            out.writeByte(4);
            out.writeInt(41);
            out.writeString("hadrik");
            out.writeInt(7);
            out.writeString("mira");
            out.writeInt(-2);
        }));
        assertEquals(new Action(Verb.FOLLOW, 0, "", 0, "", 0), decode(action(7, "", "")));
        for (Verb verb : Verb.values()) {
            assertEquals(verb, ((Action) decode(action(verb.code(), "a", "b"))).verb());
        }
    }

    @Test
    void bytesAfterAWellFormedBodyAreIgnored() {
        assertEquals(new Open(), decode(out -> {
            out.writeByte(1);
            out.writeInt(99);
        }));
    }

    @Test
    void malformedPacketsDecodeToNothingAndNeverThrow() {
        assertNull(decode(out -> { }), "empty");
        assertNull(decode(out -> out.writeByte(4)), "an unknown op");
        assertNull(decode(out -> out.writeByte(0xFF)), "an unknown op");
        assertNull(decode(out -> {
            out.writeByte(0);
            out.writeShort(1);
        }), "a HELLO without its DLL build");
        assertNull(decode(out -> out.writeByte(3)), "an ACTION without its body");
        assertNull(decode(action(0, "a", "b")), "verb 0");
        assertNull(decode(action(11, "a", "b")), "verb 11");
        assertNull(decode(out -> {
            out.writeByte(3);
            out.writeByte(2);
            out.writeInt(1);
            out.writeShort(10); // says ten bytes, carries two
            out.writeBytes("ab".getBytes(StandardCharsets.US_ASCII));
        }), "a string cut short");
        assertNull(decode(out -> {
            out.writeByte(3);
            out.writeByte(2);
            out.writeInt(1);
            out.writeShort(-1);
        }), "a negative string length");
        assertNull(decode(action(1, "x".repeat(NativeCrewPackets.MAX_ID_BYTES + 1), "")), "an id longer than any");
        assertEquals("x".repeat(NativeCrewPackets.MAX_ID_BYTES),
                ((Action) decode(action(1, "x".repeat(NativeCrewPackets.MAX_ID_BYTES), ""))).a());
        assertNull(decode(out -> {
            out.writeByte(3);
            out.writeByte(4);
            out.writeInt(1);
            out.writeString("hadrik");
            out.writeInt(7);
            out.writeString("mira");
            out.writeShort(0); // half of bVersion
        }), "the last field cut short");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static Consumer<OutPacket> action(int verb, String a, String b) {
        return out -> {
            out.writeByte(3);
            out.writeByte(verb);
            out.writeInt(0);
            out.writeString(a);
            out.writeInt(0);
            out.writeString(b);
            out.writeInt(0);
        };
    }

    private static NativeCrewPackets.Op decode(Consumer<OutPacket> body) {
        return NativeCrewPackets.decode(Packets.buildInPacket(body));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** Little-endian bytes as the DLL reads them, written by hand: not by the server's OutPacket. */
    private static final class Wire {
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
