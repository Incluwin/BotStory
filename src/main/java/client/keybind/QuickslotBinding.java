package client.keybind;

import net.packet.OutPacket;

import java.util.Arrays;

/**
 * @author Shavit
 */
public class QuickslotBinding {
    public static final int QUICKSLOT_SIZE = 8;
    public static final byte[] DEFAULT_QUICKSLOTS = {0x2A, 0x52, 0x47, 0x49, 0x1D, 0x53, 0x4F, 0x51};
    /** Slots 9 to 26 of the 26-slot bar (BotStory-Kaentake's enlarge_quickslot): 18 more keys. */
    public static final int WIDE_TAIL_SIZE = 18;
    /** The client's function-key table has 89 entries; a quickslot key past it reads beyond the table. */
    public static final int KEY_LIMIT = 89;

    private final byte[] m_aQuickslotKeyMapped;
    private final byte[] m_aWideTail;   // null: no client with the 26-slot bar has saved its keys

    // Initializes quickslot object for the user.
    // aKeys' length has to be 8.
    public QuickslotBinding(byte[] aKeys) {
        this(aKeys, null);
    }

    /** {@code aWideTail} is the 18 keys of slots 9 to 26, or null. */
    public QuickslotBinding(byte[] aKeys, byte[] aWideTail) {
        if (aKeys.length != QUICKSLOT_SIZE) {
            throw new IllegalArgumentException(String.format("aKeys' size should be %d", QUICKSLOT_SIZE));
        }
        if (aWideTail != null && aWideTail.length != WIDE_TAIL_SIZE) {
            throw new IllegalArgumentException(String.format("aWideTail's size should be %d", WIDE_TAIL_SIZE));
        }

        this.m_aQuickslotKeyMapped = aKeys.clone();
        this.m_aWideTail = aWideTail == null ? null : aWideTail.clone();
    }

    /** True for a key the client can look up in its function-key table. */
    public static boolean isValidKey(int nKey) {
        return nKey >= 0 && nKey < KEY_LIMIT;
    }

    public void encode(OutPacket p) {
        // Quickslots are default.
        // The client will skip them and call CQuickslotKeyMappedMan::DefaultQuickslotKeyMap.
        if (this.m_aWideTail == null && Arrays.equals(this.m_aQuickslotKeyMapped, DEFAULT_QUICKSLOTS)) {
            p.writeBool(false);
            return;
        }

        p.writeBool(true);

        for (byte nKey : this.m_aQuickslotKeyMapped) {
            // For some reason Nexon sends these as integers, similar to CFuncKeyMappedMan.
            // However there's no evidence any key can be above 0xFF anyhow.
            // Regardless, we need to encode an integer to avoid an error 38 crash; as CFuncKeyMapped::m_aQuickslotKeyMapped is int[8].
            p.writeInt(nKey);
        }

        // Slots 9 to 26, when a client with the 26-slot bar has saved them. That client reads them when they are
        // there; a client with the 8-slot bar reads its 8 keys and leaves the rest of the packet unread.
        if (this.m_aWideTail != null) {
            for (byte nKey : this.m_aWideTail) {
                p.writeInt(nKey);
            }
        }
    }

    public byte[] GetKeybindings() {
        return m_aQuickslotKeyMapped.clone();
    }

    /** The 18 keys of slots 9 to 26, or null when none are saved. */
    public byte[] GetWideTail() {
        return m_aWideTail == null ? null : m_aWideTail.clone();
    }

}
