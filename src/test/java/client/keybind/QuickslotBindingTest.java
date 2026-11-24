package client.keybind;

import client.Character;
import client.Client;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.opcodes.SendOpcode;
import net.server.channel.handlers.QuickslotKeyMappedModifiedHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quickslot keys of both bars: the stock 8 slots, and the 26 of BotStory-Kaentake's enlarge_quickslot, whose
 * packets were dropped for their size so none of that bar's key changes were saved (GitHub Incluwin/BotStory#3).
 */
class QuickslotBindingTest {
    private static final byte[] CUSTOM = {0x2A, 0x43, 0x47, 0x49, 2, 3, 4, 5};
    private static final byte[] TAIL = {6, 30, 31, 32, 33, 29, 83, 79, 81, 16, 17, 18, 19, 20, 44, 45, 46, 47};

    private static byte[] encoded(QuickslotBinding binding) {
        ByteBufOutPacket p = new ByteBufOutPacket(SendOpcode.QUICKSLOT_INIT);
        binding.encode(p);
        byte[] all = p.getBytes();
        byte[] body = new byte[all.length - 2];     // without the opcode
        System.arraycopy(all, 2, body, 0, body.length);
        return body;
    }

    private static ByteBufInPacket keys(int... keys) {
        ByteBuf buf = Unpooled.buffer();
        for (int key : keys) {
            buf.writeIntLE(key);
        }
        return new ByteBufInPacket(buf);
    }

    private static int[] ints(byte[]... parts) {
        int n = 0;
        for (byte[] part : parts) {
            n += part.length;
        }
        int[] out = new int[n];
        int i = 0;
        for (byte[] part : parts) {
            for (byte b : part) {
                out[i++] = b;
            }
        }
        return out;
    }

    @Test
    void theStockDefaultsWithNoSavedTailAreSentAsDefault() {
        assertArrayEquals(new byte[]{0}, encoded(new QuickslotBinding(QuickslotBinding.DEFAULT_QUICKSLOTS)));
    }

    @Test
    void eightCustomKeysAreSentAsEightInts() {
        byte[] body = encoded(new QuickslotBinding(CUSTOM));
        assertEquals(1 + 8 * 4, body.length);
        assertEquals(1, body[0]);
        assertEquals(0x43, body[1 + 4], "slot 2, low byte of a little-endian int");
    }

    @Test
    void aSavedTailIsSentAfterTheEightEvenWithTheStockDefaults() {
        byte[] body = encoded(new QuickslotBinding(QuickslotBinding.DEFAULT_QUICKSLOTS, TAIL));
        assertEquals(1 + 26 * 4, body.length);
        assertEquals(1, body[0]);
        assertEquals(6, body[1 + 8 * 4], "slot 9");
        assertEquals(47, body[1 + 25 * 4], "slot 26");
    }

    @Test
    void theHandlerStoresAll26KeysOfTheWideBar() {
        Character chr = mock(Character.class);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);

        new QuickslotKeyMappedModifiedHandler().handlePacket(keys(ints(CUSTOM, TAIL)), c);

        verify(chr).changeQuickslotKeybinding(CUSTOM, TAIL);
    }

    @Test
    void theHandlerStoresTheEightKeysOfTheStockBarAndLeavesTheTailToTheCharacter() {
        Character chr = mock(Character.class);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);

        new QuickslotKeyMappedModifiedHandler().handlePacket(keys(ints(CUSTOM)), c);

        verify(chr).changeQuickslotKeybinding(CUSTOM);
        verify(chr, never()).changeQuickslotKeybinding(any(), any());
    }

    @Test
    void theHandlerDropsAPacketOfAnotherSizeOrWithAKeyThatIsNotAKey() {
        Character chr = mock(Character.class);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        QuickslotKeyMappedModifiedHandler handler = new QuickslotKeyMappedModifiedHandler();

        handler.handlePacket(keys(1, 2, 3), c);
        handler.handlePacket(keys(new int[27]), c);
        int[] pastTheTable = ints(CUSTOM, TAIL);
        pastTheTable[20] = QuickslotBinding.KEY_LIMIT;
        handler.handlePacket(keys(pastTheTable), c);
        int[] negative = ints(CUSTOM);
        negative[0] = -1;
        handler.handlePacket(keys(negative), c);

        verify(chr, never()).changeQuickslotKeybinding(any());
        verify(chr, never()).changeQuickslotKeybinding(any(), any());
    }

    @Test
    void aBindingKeepsItsOwnCopies() {
        byte[] tail = TAIL.clone();
        QuickslotBinding binding = new QuickslotBinding(CUSTOM, tail);
        tail[0] = 1;
        assertArrayEquals(TAIL, binding.GetWideTail());
        assertNull(new QuickslotBinding(CUSTOM).GetWideTail());
    }
}
