package server.living.roster.ui;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import server.living.resident.NativeTalkPage;
import server.living.roster.ui.NativeCrewPackets.Action;
import server.living.roster.ui.NativeCrewPackets.Close;
import server.living.roster.ui.NativeCrewPackets.Hello;
import server.living.roster.ui.NativeCrewPackets.Open;
import server.living.roster.ui.NativeCrewPackets.PageReply;
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
        assertArrayEquals(bytes(0x7A, 0x01, 0x00, 0x01, 0x00, 0x01), NativeCrewPackets.welcome(1, true).getBytes());
        assertArrayEquals(bytes(0x7A, 0x01, 0x00, 0x01, 0x00, 0x00), NativeCrewPackets.welcome(1, false).getBytes());
        assertArrayEquals(bytes(0x7A, 0x01, 0x00, 0x02, 0x00, 0x01), NativeCrewPackets.welcome(2, true).getBytes());
        assertEquals(2, NativeCrewPackets.PROTOCOL);
    }

    @Test
    void aMenuPageIsItsHeaderTextAndEntriesInTheNpcWindowsOrder() {
        NativeTalkPage menu = new NativeTalkPage(0x01020304, NativeTalkPage.Kind.MENU, 0, "#eWho?#n\r\n", List.of(
                NativeTalkPage.Entry.heading("(after the run)"),
                NativeTalkPage.Entry.option(1_020_304_000 + 1, "Hadrik"),
                NativeTalkPage.Entry.option(1_020_304_000 + 998, "Back")));

        Wire expected = new Wire()
                .u16(0x17A).u8(4) // PAGE
                .u32(0x01020304) // stamp
                .u8(3) // kind MENU
                .u8(0) // flags
                .str("#eWho?#n\r\n")
                .u16(3) // entryCount
                .u8(0).u32(0).str("(after the run)")
                .u8(1).u32(1_020_304_001).str("Hadrik")
                .u8(1).u32(1_020_304_998).str("Back");
        assertArrayEquals(expected.bytes(), NativeCrewPackets.page(menu).getBytes());
        // spelled out once, so the DLL side can read it against its parser
        assertArrayEquals(bytes(0x7A, 0x01, 0x04, 0x04, 0x03, 0x02, 0x01, 0x03, 0x00, 0x0A, 0x00),
                java.util.Arrays.copyOf(NativeCrewPackets.page(menu).getBytes(), 11));
    }

    @Test
    void anOkYesNoOrAcceptPageCarriesItsKindFlagsAndNoEntries() {
        NativeTalkPage ok = new NativeTalkPage(7, NativeTalkPage.Kind.OK, NativeTalkPage.HAS_PREV | NativeTalkPage.HAS_NEXT,
                "page one", List.of());
        assertArrayEquals(new Wire().u16(0x17A).u8(4).u32(7).u8(0).u8(0b11).str("page one").u16(0).bytes(),
                NativeCrewPackets.page(ok).getBytes());

        NativeTalkPage yesNo = new NativeTalkPage(8, NativeTalkPage.Kind.YES_NO, 0, "sure?", List.of());
        assertArrayEquals(new Wire().u16(0x17A).u8(4).u32(8).u8(1).u8(0).str("sure?").u16(0).bytes(),
                NativeCrewPackets.page(yesNo).getBytes());

        NativeTalkPage accept = new NativeTalkPage(9, NativeTalkPage.Kind.ACCEPT_DECLINE, 0, "hire?",
                List.of(NativeTalkPage.Entry.option(1, "ignored: not a menu")));
        assertArrayEquals(new Wire().u16(0x17A).u8(4).u32(9).u8(2).u8(0).str("hire?").u16(0).bytes(),
                NativeCrewPackets.page(accept).getBytes());
    }

    @Test
    void pageCloseIsItsStamp() {
        assertArrayEquals(bytes(0x7A, 0x01, 0x05, 0x04, 0x03, 0x02, 0x01), NativeCrewPackets.pageClose(0x01020304).getBytes());
        assertArrayEquals(bytes(0x7A, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00), NativeCrewPackets.pageClose(0).getBytes());
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
        assertEquals(new Action(Verb.ACTIONS, 0, "", 0, "", 0), decode(action(11, "", "")));
        for (Verb verb : Verb.values()) {
            assertEquals(verb, ((Action) decode(action(verb.code(), "a", "b"))).verb());
        }
    }

    @Test
    void aPageReplyDecodesItsStampActionAndSelection() {
        assertEquals(new PageReply(0x01020304, 1, 123_456_789), decode(pageReply(0x01020304, 1, 123_456_789)));
        assertEquals(new PageReply(42, 0, -1), decode(pageReply(42, 0, -1)));
        assertEquals(new PageReply(42, 255, -1), decode(pageReply(42, 0xFF, -1)), "Close is 255, unsigned");
        // the same bytes, spelled out
        assertEquals(new PageReply(7, 1, 7_000_003), NativeCrewPackets.decode(Packets.buildInPacket(out -> out.writeBytes(
                bytes(0x04, 0x07, 0x00, 0x00, 0x00, 0x01, 0xC3, 0xCF, 0x6A, 0x00)))));
    }

    @Test
    void aMalformedPageReplyDecodesToNothing() {
        assertNull(decode(out -> out.writeByte(4)), "a PAGE_REPLY without its body");
        assertNull(decode(out -> {
            out.writeByte(4);
            out.writeInt(7);
        }), "without its action");
        assertNull(decode(out -> {
            out.writeByte(4);
            out.writeInt(7);
            out.writeByte(1);
            out.writeShort(3); // half of the selection
        }), "the selection cut short");
        assertEquals(new PageReply(7, 1, 3), decode(out -> {
            out.writeByte(4);
            out.writeInt(7);
            out.writeByte(1);
            out.writeInt(3);
            out.writeInt(99); // a later DLL's extra bytes
        }));
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
        assertNull(decode(out -> out.writeByte(5)), "an unknown op");
        assertNull(decode(out -> out.writeByte(0xFF)), "an unknown op");
        assertNull(decode(out -> {
            out.writeByte(0);
            out.writeShort(1);
        }), "a HELLO without its DLL build");
        assertNull(decode(out -> out.writeByte(3)), "an ACTION without its body");
        assertNull(decode(action(0, "a", "b")), "verb 0");
        assertNull(decode(action(12, "a", "b")), "verb 12");
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

    private static Consumer<OutPacket> pageReply(int stamp, int action, int selection) {
        return out -> {
            out.writeByte(4);
            out.writeInt(stamp);
            out.writeByte(action);
            out.writeInt(selection);
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
