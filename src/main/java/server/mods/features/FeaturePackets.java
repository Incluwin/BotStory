package server.mods.features;

import constants.string.CharsetConstants;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import server.mods.features.FeaturePolicy.Decision;

import java.util.ArrayList;
import java.util.List;

/**
 * The login feature handshake's wire format (docs/CLIENT_FEATURE_HANDSHAKE.md, "Protocol"): the kaentake client sends
 * Recv opcode CLIENT_FEATURES (0x118), the server answers with Send opcode CLIENT_FEATURES (0x17B). Little-endian; a
 * string is the Maple string ({@code short} length, then the bytes). Each packet leads with its protocol, so a later
 * one can change the layout after it; bytes after a well-formed body are ignored. A client packet that is short, names
 * an unknown op, or carries a string or list longer than any client sends decodes to null, and nothing is thrown.
 */
public final class FeaturePackets {
    /** The highest protocol this server speaks; an answer carries {@code min(the report's, PROTOCOL)}. */
    public static final int PROTOCOL = 1;

    /** Client ops (the first byte of 0x118). */
    static final int OP_REPORT = 0;
    static final int OP_APPLIED = 1;

    /** Server types (the first byte of 0x17B). */
    static final int TYPE_POLICY = 0;

    /** feature_list.h flags, as the client sends them. */
    public static final int FLAG_CORE = 0x01;
    public static final int FLAG_SERVER_GATED = 0x02;
    public static final int FLAG_LIVE = 0x04;

    /** A feature's state on the client. */
    public static final int STATE_ATTACHED = 0x01;
    public static final int STATE_ACTIVE = 0x02;
    /** config.ini [features] inert= names it: a veto the client keeps for its next start. */
    public static final int STATE_VETOED = 0x04;

    static final int MAX_FEATURES = 255;
    static final int MAX_ID_BYTES = 64;
    static final int MAX_TITLE_BYTES = 128;

    private FeaturePackets() {
    }

    /** One line of the client's feature list (feature_list.h). */
    public record Feature(String id, String title, int version, int flags, int state) {
        public boolean core() {
            return (flags & FLAG_CORE) != 0;
        }

        public boolean serverGated() {
            return (flags & FLAG_SERVER_GATED) != 0;
        }

        public boolean live() {
            return (flags & FLAG_LIVE) != 0;
        }

        public boolean attached() {
            return (state & STATE_ATTACHED) != 0;
        }

        /** Attached and not inert. */
        public boolean active() {
            return attached() && (state & STATE_ACTIVE) != 0;
        }

        public boolean vetoed() {
            return (state & STATE_VETOED) != 0;
        }
    }

    /**
     * The client's feature list: REPORT once per connection after its first SET_FIELD (answered with a POLICY),
     * APPLIED after it applied a POLICY (never answered). {@code dllBuild} is yyyymmdd.
     */
    public record Report(boolean applied, int protocol, int dllBuild, List<Feature> features) {
        public Report {
            features = List.copyOf(features);
        }
    }

    /** The server's answer for one feature the client reported. */
    public record Answer(String id, Decision decision) {
    }

    // ---- client to server ------------------------------------------------------------------------

    /**
     * REPORT / APPLIED after the opcode: {@code byte op}, {@code short protocol}, {@code int dllBuild}, {@code short
     * count}, then each feature: {@code string id}, {@code string title}, {@code short version}, {@code byte flags},
     * {@code byte state}. Null when malformed.
     */
    public static Report decode(InPacket packet) {
        try {
            Reader in = new Reader(packet);
            int op = in.unsignedByte();
            if (op != OP_REPORT && op != OP_APPLIED) {
                return null;
            }
            int protocol = in.unsignedShort();
            int dllBuild = in.intValue();
            int count = in.unsignedShort();
            if (!in.ok() || protocol < 1 || count > MAX_FEATURES) {
                return null;
            }
            List<Feature> features = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = in.string(MAX_ID_BYTES);
                String title = in.string(MAX_TITLE_BYTES);
                int version = in.unsignedShort();
                int flags = in.unsignedByte();
                int state = in.unsignedByte();
                if (!in.ok() || id.isEmpty()) {
                    return null;
                }
                features.add(new Feature(id, title, version, flags, state));
            }
            return new Report(op == OP_APPLIED, protocol, dllBuild, features);
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

        int unsignedShort() {
            return has(2) ? Short.toUnsignedInt(packet.readShort()) : 0;
        }

        int intValue() {
            return has(4) ? packet.readInt() : 0;
        }

        String string(int maxBytes) {
            if (!has(2)) {
                return "";
            }
            int length = Short.toUnsignedInt(packet.readShort());
            if (length > maxBytes || !has(length)) {
                ok = false;
                return "";
            }
            return new String(packet.readBytes(length), CharsetConstants.CHARSET);
        }
    }

    // ---- server to client ------------------------------------------------------------------------

    /**
     * POLICY: {@code byte type} (0), {@code short protocol}, {@code short count}, then each answer: {@code string id},
     * {@code byte decision} (0 allow, 1 deny, 2 require).
     */
    public static Packet policy(int protocol, List<Answer> answers) {
        OutPacket out = OutPacket.create(SendOpcode.CLIENT_FEATURES);
        out.writeByte(TYPE_POLICY);
        out.writeShort(protocol);
        int count = Math.min(answers.size(), MAX_FEATURES);
        out.writeShort(count);
        for (Answer answer : answers.subList(0, count)) {
            out.writeString(answer.id());
            out.writeByte(answer.decision().code());
        }
        return out;
    }
}
