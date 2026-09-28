package testutil;

import net.opcodes.SendOpcode;
import net.packet.Packet;
import tools.PacketCreator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the NPC_TALK packets a test captured: {@code opcode, 4, npc id, msgType, speaker, text, end bytes}. */
public final class NpcTalks {
    private static final Pattern OPTION = Pattern.compile("#L(\\d+)#(.*?)#l");

    private NpcTalks() {
    }

    public record Talk(int npcId, int msgType, int speaker, String text) {
        /** The menu's option labels, in order. */
        public List<String> labels() {
            List<String> labels = new ArrayList<>();
            Matcher matcher = OPTION.matcher(text);
            while (matcher.find()) {
                labels.add(matcher.group(2));
            }
            return labels;
        }

        /** The selection number the client sends back for the option with this label. */
        public int selection(String label) {
            Matcher matcher = OPTION.matcher(text);
            while (matcher.find()) {
                if (matcher.group(2).equals(label)) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
            throw new AssertionError("no option '" + label + "' in: " + text);
        }
    }

    public static boolean isTalk(Packet packet) {
        byte[] bytes = packet.getBytes();
        int opcode = (bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8;
        return opcode == SendOpcode.NPC_TALK.getValue();
    }

    public static boolean isEnableActions(Packet packet) {
        return Arrays.equals(PacketCreator.enableActions().getBytes(), packet.getBytes());
    }

    public static Talk read(Packet packet) {
        if (!isTalk(packet)) {
            throw new AssertionError("not an NPC_TALK packet");
        }
        byte[] bytes = packet.getBytes();
        int npcId = (bytes[3] & 0xFF) | (bytes[4] & 0xFF) << 8 | (bytes[5] & 0xFF) << 16 | (bytes[6] & 0xFF) << 24;
        int length = (bytes[9] & 0xFF) | (bytes[10] & 0xFF) << 8;
        return new Talk(npcId, bytes[7], bytes[8] & 0xFF, new String(bytes, 11, length, StandardCharsets.US_ASCII));
    }

    /** Every NPC_TALK among the packets, in order. */
    public static List<Talk> talks(List<Packet> packets) {
        return packets.stream().filter(NpcTalks::isTalk).map(NpcTalks::read).toList();
    }
}
