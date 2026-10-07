package server.living.probe;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.channel.handlers.PlayerInteractionHandler;
import tools.HexTool;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Names and one-line summaries of the client packets the P0 companion probes watch for
 * (docs/BOT_MANAGEMENT_PLAN.md, P0 f). Pure: it decodes its own wrapper around a copy of the
 * payload that follows the opcode, reading the fields the stock handlers read. It also builds the
 * two probe packets the stock builders cannot express.
 */
public final class CompanionProbePackets {
    private static final Map<Integer, RecvOpcode> BY_VALUE = byValue();
    /** Opcodes with a decoded summary; they are echoed in pink, everything else in blue. */
    private static final Set<RecvOpcode> DECODED = EnumSet.of(
            RecvOpcode.PARTY_OPERATION, RecvOpcode.DENY_PARTY_REQUEST, RecvOpcode.SCRIPTED_ITEM,
            RecvOpcode.USE_ITEM, RecvOpcode.CHAR_INFO_REQUEST, RecvOpcode.BUDDYLIST_MODIFY,
            RecvOpcode.NPC_TALK_MORE, RecvOpcode.PLAYER_INTERACTION, RecvOpcode.CHANGE_KEYMAP,
            RecvOpcode.MESSENGER, RecvOpcode.PARTY_SEARCH_START, RecvOpcode.WHISPER);
    /** Movement, regen and keep-alive traffic that would bury the probe lines. */
    private static final Set<RecvOpcode> QUIET = EnumSet.of(
            RecvOpcode.PONG, RecvOpcode.MOVE_PLAYER, RecvOpcode.HEAL_OVER_TIME, RecvOpcode.MOVE_PET,
            RecvOpcode.MOVE_SUMMON, RecvOpcode.MOVE_DRAGON, RecvOpcode.MOVE_LIFE, RecvOpcode.AUTO_AGGRO,
            RecvOpcode.NPC_ACTION, RecvOpcode.MOB_DAMAGE_MOB, RecvOpcode.MOB_DAMAGE_MOB_FRIENDLY);
    private static final int KEYMAP_ENTRIES_SHOWN = 4;

    private CompanionProbePackets() {
    }

    public static RecvOpcode opcode(int value) {
        return BY_VALUE.get(value);
    }

    /** {@code PARTY_OPERATION(0x7C)}, or {@code UNKNOWN(0x...)}. */
    public static String name(int value) {
        RecvOpcode opcode = opcode(value);
        return (opcode == null ? "UNKNOWN" : opcode.name()) + "(0x" + Integer.toHexString(value).toUpperCase() + ")";
    }

    public static boolean decoded(int value) {
        RecvOpcode opcode = opcode(value);
        return opcode != null && DECODED.contains(opcode);
    }

    public static boolean quiet(int value) {
        RecvOpcode opcode = opcode(value);
        return opcode != null && QUIET.contains(opcode);
    }

    /**
     * The summary of one inbound payload (the bytes after the opcode), or "" for an opcode with no
     * decoder. {@code names} turns a character id into a display name, or returns null.
     */
    public static String summarize(int value, byte[] payload, IntFunction<String> names) {
        if (!decoded(value) || payload == null) {
            return "";
        }
        InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(payload));
        try {
            return switch (opcode(value)) {
                case PARTY_OPERATION -> partyOperation(p, names);
                case DENY_PARTY_REQUEST -> {
                    byte mode = p.readByte();
                    yield "mode=" + mode + " from=" + p.readString();
                }
                case SCRIPTED_ITEM, USE_ITEM -> {
                    p.skip(4); // timestamp
                    short slot = p.readShort();
                    yield "slot=" + slot + " item=" + p.readInt();
                }
                case CHAR_INFO_REQUEST -> {
                    p.skip(4); // timestamp
                    yield "target=" + target(p.readInt(), names);
                }
                case BUDDYLIST_MODIFY -> buddylist(p, names);
                case WHISPER -> {
                    byte request = p.readByte();
                    yield "request=0x" + Integer.toHexString(request & 0xFF).toUpperCase() + " name=" + p.readString();
                }
                case NPC_TALK_MORE -> npcTalkMore(p);
                case PLAYER_INTERACTION -> playerInteraction(p, names);
                case CHANGE_KEYMAP -> keymap(p);
                case MESSENGER -> messenger(p);
                case PARTY_SEARCH_START -> {
                    int min = p.readInt();
                    int max = p.readInt();
                    int members = p.readInt();
                    yield "min=" + min + " max=" + max + " members=" + members
                            + " jobs=0x" + Integer.toHexString(p.readInt()).toUpperCase();
                }
                default -> "";
            };
        } catch (RuntimeException truncated) { // a short or malformed payload: the real handler decides
            return "(undecodable, " + payload.length + " bytes)";
        }
    }

    /**
     * {@code PacketCreator.partyInvite}, but with the party id the invite was registered under
     * instead of a second read of the inviter's (possibly changed) party.
     */
    public static Packet partyInvite(int partyId, String fromName) {
        OutPacket p = OutPacket.create(SendOpcode.PARTY_OPERATION);
        p.writeByte(4);
        p.writeInt(partyId);
        p.writeString(fromName);
        p.writeByte(0);
        return p;
    }

    /**
     * {@code PacketCreator.getNPCTalk} with an int after the speaker byte: later clients read a
     * speaker template id there when the speaker byte has bit 0x04 set (probe 5).
     */
    public static Packet npcTalkWithTemplate(int npcId, int msgType, int speaker, int templateId, String text,
                                             String endBytes) {
        OutPacket p = OutPacket.create(SendOpcode.NPC_TALK);
        p.writeByte(4);
        p.writeInt(npcId);
        p.writeByte(msgType);
        p.writeByte(speaker);
        p.writeInt(templateId);
        p.writeString(text);
        p.writeBytes(HexTool.toBytes(endBytes));
        return p;
    }

    private static String partyOperation(InPacket p, IntFunction<String> names) {
        int op = p.readByte();
        return "op=" + op + switch (op) {
            case 1 -> " create";
            case 2 -> " leave";
            case 3 -> " join party=" + p.readInt();
            case 4 -> " invite name=" + p.readString();
            case 5 -> " expel cid=" + target(p.readInt(), names);
            case 6 -> " leader cid=" + target(p.readInt(), names);
            default -> "";
        };
    }

    private static String buddylist(InPacket p, IntFunction<String> names) {
        int mode = p.readByte();
        return "mode=" + mode + switch (mode) {
            case 1 -> {
                String name = p.readString();
                yield " add name=" + name + " group=" + p.readString();
            }
            case 2 -> " accept cid=" + target(p.readInt(), names);
            case 3 -> " delete cid=" + target(p.readInt(), names);
            default -> "";
        };
    }

    private static String npcTalkMore(InPacket p) {
        byte msgType = p.readByte();
        byte action = p.readByte();
        String head = "msgType=" + msgType + " action=" + action;
        if (msgType == 2) {
            return action != 0 && p.available() > 0 ? head + " text=" + p.readString() : head;
        }
        int selection = -1;
        if (p.available() >= 4) {
            selection = p.readInt();
        } else if (p.available() > 0) {
            selection = p.readUnsignedByte();
        }
        return head + " selection=" + selection;
    }

    private static String playerInteraction(InPacket p, IntFunction<String> names) {
        byte mode = p.readByte();
        String head = "mode=" + mode + " " + interactionName(mode);
        if (mode == PlayerInteractionHandler.Action.CREATE.getCode() && p.available() > 0) {
            return head + " type=" + p.readByte();
        }
        if (mode == PlayerInteractionHandler.Action.INVITE.getCode() && p.available() >= 4) {
            return head + " cid=" + target(p.readInt(), names);
        }
        return head;
    }

    private static String interactionName(byte mode) {
        for (PlayerInteractionHandler.Action action : PlayerInteractionHandler.Action.values()) {
            if (action.getCode() == mode) {
                return action.name();
            }
        }
        return "?";
    }

    private static String keymap(InPacket p) {
        if (p.available() < 8) {
            return "(short)";
        }
        int mode = p.readInt();
        if (mode != 0) {
            return "mode=" + mode + " item=" + p.readInt();
        }
        int changes = p.readInt();
        StringBuilder text = new StringBuilder("mode=0 changes=").append(changes);
        for (int index = 0; index < changes && index < KEYMAP_ENTRIES_SHOWN; index++) {
            int key = p.readInt();
            byte type = p.readByte();
            text.append(" [key=").append(key).append(" type=").append(type).append(" action=").append(p.readInt()).append(']');
        }
        if (changes > KEYMAP_ENTRIES_SHOWN) {
            text.append(" ...");
        }
        return text.toString();
    }

    private static String messenger(InPacket p) {
        byte mode = p.readByte();
        return "mode=" + mode + switch (mode) {
            case 0x00 -> " open id=" + p.readInt();
            case 0x02 -> " close";
            case 0x03 -> " invite name=" + p.readString();
            case 0x05 -> " decline name=" + p.readString();
            case 0x06 -> " chat";
            default -> "";
        };
    }

    private static String target(int id, IntFunction<String> names) {
        String name = names == null ? null : names.apply(id);
        return name == null ? String.valueOf(id) : id + " (" + name + ")";
    }

    private static Map<Integer, RecvOpcode> byValue() {
        Map<Integer, RecvOpcode> map = new HashMap<>();
        for (RecvOpcode opcode : RecvOpcode.values()) {
            map.putIfAbsent(opcode.getValue(), opcode);
        }
        return map;
    }
}
