package net.server.channel.handlers;

import client.Client;
import client.keybind.QuickslotBinding;
import net.AbstractPacketHandler;
import net.packet.InPacket;

/**
 * @author Shavit
 */
public class QuickslotKeyMappedModifiedHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        // 8 keys from the stock bar, or 26 from the 26-slot bar (BotStory-Kaentake's enlarge_quickslot). That
        // bar's packets used to be dropped here for their size, so none of its key changes were ever saved.
        int nKeys = p.available() / Integer.BYTES;
        boolean bWide = nKeys == QuickslotBinding.QUICKSLOT_SIZE + QuickslotBinding.WIDE_TAIL_SIZE;
        if (p.available() % Integer.BYTES != 0 || (nKeys != QuickslotBinding.QUICKSLOT_SIZE && !bWide) ||
                // not logged in-game
                c.getPlayer() == null) {
            return;
        }

        byte[] aQuickslotKeyMapped = new byte[QuickslotBinding.QUICKSLOT_SIZE];
        byte[] aWideTail = bWide ? new byte[QuickslotBinding.WIDE_TAIL_SIZE] : null;

        for (int i = 0; i < nKeys; i++) {
            int nKey = p.readInt();
            if (!QuickslotBinding.isValidKey(nKey)) {
                return; // not a key: the client would read past its function-key table for this slot
            }
            if (i < QuickslotBinding.QUICKSLOT_SIZE) {
                aQuickslotKeyMapped[i] = (byte) nKey;
            } else {
                aWideTail[i - QuickslotBinding.QUICKSLOT_SIZE] = (byte) nKey;
            }
        }

        if (bWide) {
            c.getPlayer().changeQuickslotKeybinding(aQuickslotKeyMapped, aWideTail);
        } else {
            c.getPlayer().changeQuickslotKeybinding(aQuickslotKeyMapped);
        }
    }
}
