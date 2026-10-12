package server.living.roster.ui;

import constants.string.CharsetConstants;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import server.living.resident.NativeTalkPage;

import java.util.List;

/**
 * The native Crew window's wire format (docs/BOT_MANAGEMENT_P10_PLAN.md, "Protocol"): the client sends Recv opcode
 * CREW_WINDOW (0x117), the server answers with Send opcode CREW_WINDOW (0x17A). Little-endian; a string is the Maple
 * string ({@code short} length, then the bytes in the server's charset). Every client packet is decoded with explicit
 * bounds checks: one that is short, names an unknown op or verb, or carries a string longer than any id the server
 * sends decodes to null, and nothing is thrown. Bytes after a well-formed body are ignored (a later DLL may add some).
 */
public final class NativeCrewPackets {
    /**
     * The highest protocol this server speaks. A connection is served at {@code min(its HELLO's protocol, PROTOCOL)},
     * which WELCOME carries: 1 is the crew view, 2 adds native pages (PAGE, PAGE_CLOSE, PAGE_REPLY, verb ACTIONS).
     */
    public static final int PROTOCOL = 2;
    /** The first protocol with native pages (docs/BOT_MANAGEMENT_P10_PLAN.md, "Protocol 2"). */
    public static final int PAGES_PROTOCOL = 2;

    /** Client ops (the first byte of 0x117). */
    static final int OP_HELLO = 0;
    static final int OP_OPEN = 1;
    static final int OP_CLOSE = 2;
    static final int OP_ACTION = 3;
    static final int OP_PAGE_REPLY = 4;

    /** Server types (the first byte of 0x17A). */
    static final int TYPE_WELCOME = 0;
    static final int TYPE_SNAPSHOT = 1;
    static final int TYPE_CLOSE = 2;
    static final int TYPE_OPEN = 3;
    static final int TYPE_PAGE = 4;
    static final int TYPE_PAGE_CLOSE = 5;

    /** Snapshot flags: someone of the usual lineup can be called; someone is out with the owner (orders apply). */
    static final int USUAL_CALLABLE = 1;
    static final int SOMEONE_OUT = 1 << 1;

    /** Row flags: what the companion's card offers now (a guest's row only opens its hire terms). */
    static final int CALL = 1;
    static final int BENCH = 1 << 1;
    static final int SWAP_OUT = 1 << 2;
    static final int SWAP_IN = 1 << 3;
    static final int GUEST = 1 << 4;
    static final int COMMITTED = 1 << 5;
    /** A party row out with the owner: one a bench row's SWAP_IN ("Swap in for...") may take over from. */
    static final int OUT = 1 << 6;
    /** A bench row that can come at once (benched or away, not on its way): one a SWAP_OUT row may make room for. */
    static final int CAN_COME = 1 << 7;

    /** The longest persona id an ACTION may name, in bytes ("hire:2147483647" is 15; a Resident's id is a word). */
    static final int MAX_ID_BYTES = 64;
    /** The hpPercent of a companion that is not loaded (its HP is unknown). */
    static final int HP_UNKNOWN = 255;

    private NativeCrewPackets() {
    }

    /** The ACTION verbs; each does what the NPC dialog does for the same click (NativeCrewWindow). */
    public enum Verb {
        CARD(1), CALL(2), BENCH(3), SWAP(4), USUAL(5), MORE(6), FOLLOW(7), WAIT(8), GATHER(9), REBUFF(10),
        /** Protocol 2: the native actions page (no ids, no stamp). */
        ACTIONS(11);

        private final int code;

        Verb(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Verb of(int code) {
            for (Verb verb : values()) {
                if (verb.code == code) {
                    return verb;
                }
            }
            return null;
        }
    }

    /** A decoded client packet. */
    public sealed interface Op permits Hello, Open, Close, Action, PageReply { }

    /** Sent once per connection after its first SET_FIELD; {@code dllBuild} is yyyymmdd. */
    public record Hello(int protocol, int dllBuild) implements Op { }

    /** The player opened the window: a SNAPSHOT answers, and the crew is watched while it stays open. */
    public record Open() implements Op { }

    /** The player closed the window: watching stops. */
    public record Close() implements Op { }

    /**
     * A click: {@code a} and {@code b} are persona ids ("" when the verb names none), each stamped with the row version
     * the client was shown; {@code ownerVersion} stamps the snapshot itself. Versions are the low 32 bits.
     */
    public record Action(Verb verb, int ownerVersion, String a, int aVersion, String b, int bVersion) implements Op { }

    /**
     * Protocol 2: a click on the native page stamped {@code stamp}. {@code action} (0-255) as the NPC reply's: 1 = OK /
     * Yes / Accept / Next / an option, 0 = No / Decline / Prev, 255 = Close; {@code selection} is the option's selection
     * value, -1 otherwise.
     */
    public record PageReply(int stamp, int action, int selection) implements Op { }

    /** One row of a SNAPSHOT. */
    record Row(String personaId, int rowVersion, String name, String levelJob, String detail, int hpPercent, int flags) { }

    /** A SNAPSHOT: the party block (guests last), then the bench block. */
    record Snapshot(int ownerVersion, int seats, int partyCap, int benchSeats, String seatsNote, int flags,
                    List<Row> party, List<Row> bench) { }

    // ---- client to server ------------------------------------------------------------------------

    /** The packet after its opcode, or null when it is malformed (short, an unknown op or verb, a bad string). */
    public static Op decode(InPacket packet) {
        try {
            Reader in = new Reader(packet);
            int op = in.unsignedByte();
            return switch (op) {
                case OP_HELLO -> {
                    int protocol = in.shortValue();
                    int dllBuild = in.intValue();
                    yield in.ok() ? new Hello(protocol, dllBuild) : null;
                }
                case OP_OPEN -> in.ok() ? new Open() : null;
                case OP_CLOSE -> in.ok() ? new Close() : null;
                case OP_ACTION -> {
                    Verb verb = Verb.of(in.unsignedByte());
                    int ownerVersion = in.intValue();
                    String a = in.string();
                    int aVersion = in.intValue();
                    String b = in.string();
                    int bVersion = in.intValue();
                    yield in.ok() && verb != null ? new Action(verb, ownerVersion, a, aVersion, b, bVersion) : null;
                }
                case OP_PAGE_REPLY -> {
                    int stamp = in.intValue();
                    int action = in.unsignedByte();
                    int selection = in.intValue();
                    yield in.ok() ? new PageReply(stamp, action, selection) : null;
                }
                default -> null;
            };
        } catch (RuntimeException malformed) {
            return null; // a buffer that does not read as it says it is: never thrown into the handler
        }
    }

    /** Reads with a bounds check before every field; once one fails, every later read fails too. */
    private static final class Reader {
        private final InPacket packet;
        private boolean ok = true;

        Reader(InPacket packet) {
            this.packet = packet;
        }

        boolean ok() {
            return ok;
        }

        private boolean has(int bytes) {
            ok = ok && packet.available() >= bytes;
            return ok;
        }

        int unsignedByte() {
            return has(1) ? packet.readUnsignedByte() : -1;
        }

        int shortValue() {
            return has(2) ? packet.readShort() : 0;
        }

        int intValue() {
            return has(4) ? packet.readInt() : 0;
        }

        String string() {
            if (!has(2)) {
                return "";
            }
            int length = packet.readShort();
            if (length < 0 || length > MAX_ID_BYTES || !has(length)) {
                ok = false;
                return "";
            }
            return new String(packet.readBytes(length), CharsetConstants.CHARSET);
        }
    }

    // ---- server to client ------------------------------------------------------------------------

    /** WELCOME: {@code short protocol} (the one this connection is served at), {@code byte enabled}. */
    public static Packet welcome(int protocol, boolean enabled) {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_WELCOME);
        out.writeShort(protocol);
        out.writeBool(enabled);
        return out;
    }

    /** CLOSE: the switch was turned off, or the character may no longer use the window. */
    public static Packet close() {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_CLOSE);
        return out;
    }

    /** OPEN: the server asks the client to open its window (the Adventurer's Roster); the client answers with OPEN. */
    public static Packet open() {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_OPEN);
        return out;
    }

    /**
     * PAGE (protocol 2): {@code int stamp}, {@code byte kind}, {@code byte flags}, {@code string text}, {@code short
     * entryCount} (a MENU's lines, 0 for any other kind), then each entry: {@code byte type} (0 heading, 1 option),
     * {@code int selection} (an option's NPC selection value, 0 for a heading), {@code string label}.
     */
    public static Packet page(NativeTalkPage page) {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_PAGE);
        out.writeInt(page.stamp());
        out.writeByte(page.kind().code());
        out.writeByte(unsignedByte(page.flags()));
        out.writeString(page.text());
        List<NativeTalkPage.Entry> entries = page.kind() == NativeTalkPage.Kind.MENU ? page.entries() : List.of();
        int count = Math.min(entries.size(), Short.MAX_VALUE);
        out.writeShort(count);
        for (NativeTalkPage.Entry entry : entries.subList(0, count)) {
            out.writeByte(entry.option() ? 1 : 0);
            out.writeInt(entry.option() ? entry.selection() : 0);
            out.writeString(entry.label());
        }
        return out;
    }

    /** PAGE_CLOSE (protocol 2): {@code int stamp}, the page to close (0: whatever page is shown). */
    public static Packet pageClose(int stamp) {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_PAGE_CLOSE);
        out.writeInt(stamp);
        return out;
    }

    /** SNAPSHOT: the header, then the party rows and the bench rows, each block led by its count. */
    static Packet snapshot(Snapshot snapshot) {
        OutPacket out = OutPacket.create(SendOpcode.CREW_WINDOW);
        out.writeByte(TYPE_SNAPSHOT);
        out.writeInt(snapshot.ownerVersion());
        out.writeByte(unsignedByte(snapshot.seats()));
        out.writeByte(unsignedByte(snapshot.partyCap()));
        out.writeByte(unsignedByte(snapshot.benchSeats()));
        out.writeString(snapshot.seatsNote());
        out.writeByte(unsignedByte(snapshot.flags()));
        rows(out, snapshot.party());
        rows(out, snapshot.bench());
        return out;
    }

    private static void rows(OutPacket out, List<Row> rows) {
        int count = Math.min(rows.size(), 255);
        out.writeByte(count);
        for (Row row : rows.subList(0, count)) {
            out.writeString(row.personaId());
            out.writeInt(row.rowVersion());
            out.writeString(row.name());
            out.writeString(row.levelJob());
            out.writeString(row.detail());
            out.writeByte(unsignedByte(row.hpPercent()));
            out.writeByte(unsignedByte(row.flags()));
        }
    }

    private static int unsignedByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
