package net.server.channel.handlers;

import client.autoban.AutobanManager;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.living.LivingWorld;
import testutil.HandlerTest;
import testutil.NpcTalks;
import testutil.Packets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Adventurer's Roster stays with its owner: dropping it on the ground (ITEM_MOVE to slot 0) is refused with a pink
 * line before the stock drop would erase it (its tradeBlock), while moving it between slots, dropping anything else,
 * the key set to 0 and the living world off keep the stock path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemMoveHandlerRosterBookTest extends HandlerTest {
    private static final int ROSTER_BOOK = 2430011;
    private static final short SLOT = 3;
    private static final short DROP = 0;

    private final ItemMoveHandler handler = new ItemMoveHandler();
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final Inventory use = mock(Inventory.class);

    private MockedStatic<LivingWorld> livingWorld;
    private MockedStatic<Server> server;
    private MockedStatic<InventoryManipulator> inventory;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        when(chr.getAutobanManager()).thenReturn(mock(AutobanManager.class));
        holding(ROSTER_BOOK);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        Server running = mock(Server.class);
        when(running.getCurrentTime()).thenReturn(60_000L);
        server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(running);
        inventory = mockStatic(InventoryManipulator.class);
    }

    @AfterEach
    void close() {
        inventory.close();
        server.close();
        livingWorld.close();
    }

    @Test
    void droppingTheRosterBookIsRefusedWithAPinkLineAndTheClientReEnabled() {
        handler.handlePacket(move(SLOT, DROP), client);

        inventory.verifyNoInteractions();
        verify(chr).dropMessage(5, "The Adventurer's Roster stays with you.");
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertTrue(NpcTalks.isEnableActions(sent.getValue()));
    }

    @Test
    void movingTheBookAndDroppingOtherItemsKeepTheStockPath() {
        handler.handlePacket(move(SLOT, (short) 7), client);
        inventory.verify(() -> InventoryManipulator.handleItemMove(client, InventoryType.USE, SLOT, (short) 7, (short) 1));

        holding(2000000);
        handler.handlePacket(move(SLOT, DROP), client);
        inventory.verify(() -> InventoryManipulator.handleItemMove(client, InventoryType.USE, SLOT, DROP, (short) 1));

        verify(chr, never()).dropMessage(anyInt(), anyString());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void theKeySetToZeroAndTheWorldOffKeepTheStockDrop() {
        config.ROSTER_BOOK_ITEM_ID = 0;
        handler.handlePacket(move(SLOT, DROP), client);

        config.ROSTER_BOOK_ITEM_ID = ROSTER_BOOK;
        livingWorld.when(LivingWorld::get).thenReturn(null);
        handler.handlePacket(move(SLOT, DROP), client);

        inventory.verify(() -> InventoryManipulator.handleItemMove(any(), any(), anyShort(), anyShort(), anyShort()),
                times(2));
        verify(chr, never()).dropMessage(anyInt(), anyString());
    }

    private void holding(int itemId) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getQuantity()).thenReturn((short) 1);
        when(use.getItem(SLOT)).thenReturn(item);
    }

    private static InPacket move(short src, short action) {
        return Packets.buildInPacket(out -> {
            out.writeInt(0);
            out.writeByte(InventoryType.USE.getType());
            out.writeShort(src);
            out.writeShort(action);
            out.writeShort(1);
        });
    }
}
