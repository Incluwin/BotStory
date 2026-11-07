package tools;

import client.BuddylistEntry;
import net.opcodes.SendOpcode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketCreatorBuddylistTest {
    private static final int HEADER = 4; // opcode, mode, count
    private static final int ENTRY = 39; // cid, name, status, channel, group, map
    private static final int TRAILER = 4;

    @Test
    void aRequestStillOpenIsNeitherCountedNorWritten() {
        byte[] packet = PacketCreator.updateBuddylist(List.of(
                new BuddylistEntry("Dorran", "Crew", 101, 1, true),
                new BuddylistEntry("xlsrax", "Default Group", 102, 1, false))).getBytes();

        assertEquals(SendOpcode.BUDDYLIST.getValue(), (packet[0] & 0xFF) | (packet[1] & 0xFF) << 8);
        assertEquals(7, packet[2]);
        assertEquals(1, packet[3], "the client reads this many entries");
        assertEquals(HEADER + ENTRY + TRAILER, packet.length);
    }

    @Test
    void aListOfOnlyOpenRequestsIsEmpty() {
        byte[] packet = PacketCreator.updateBuddylist(List.of(
                new BuddylistEntry("xlsrax", "Default Group", 102, 1, false))).getBytes();

        assertEquals(0, packet[3]);
        assertEquals(HEADER, packet.length);
    }

    @Test
    void everyVisibleBuddyIsWritten() {
        byte[] packet = PacketCreator.updateBuddylist(List.of(
                new BuddylistEntry("Dorran", "Crew", 101, 1, true),
                new BuddylistEntry("Lioren", "Crew", 103, 2, true))).getBytes();

        assertEquals(2, packet[3]);
        assertEquals(HEADER + 2 * (ENTRY + TRAILER), packet.length);
    }
}
