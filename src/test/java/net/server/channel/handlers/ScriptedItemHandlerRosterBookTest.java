package net.server.channel.handlers;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.LivingWorldConfig;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.roster.ui.CompanionMenus;
import testutil.HandlerTest;
import testutil.NpcTalks;
import testutil.Packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Adventurer's Roster intercept (docs/BOT_MANAGEMENT_PLAN.md, "Adventurer's Roster item"): double-clicking
 * ROSTER_BOOK_ITEM_ID opens the Crew window before any item script is looked up and does not use the item up;
 * any other item, the key set to 0 or the living world off leave the stock item-script path to run.
 * (The stock path itself needs the item data, which unit tests do not load: those cases check the hook's answer.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedItemHandlerRosterBookTest extends HandlerTest {
    private static final int ROSTER_BOOK = 2430011;
    private static final short SLOT = 3;

    private final ScriptedItemHandler handler = new ScriptedItemHandler();
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final Inventory use = mock(Inventory.class);

    private MockedStatic<LivingWorld> livingWorld;
    private MockedConstruction<CompanionMenus> menus;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        holding(ROSTER_BOOK);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = mockConstruction(CompanionMenus.class);
    }

    @AfterEach
    void close() {
        menus.close();
        livingWorld.close();
    }

    @Test
    void theRosterBookOpensTheCrewWindowBeforeAnyItemScriptAndIsNotUsedUp() {
        handler.handlePacket(use(ROSTER_BOOK), client); // the stock path would load the item data and fail here

        assertEquals(1, menus.constructed().size());
        verify(menus.constructed().get(0)).rosterBook(chr);
        verify(use, never()).removeItem(anyShort(), anyShort(), anyBoolean());
        verify(use, never()).removeSlot(anyShort());
    }

    @Test
    void aRosterBookNotInThatSlotOpensNothingButReEnablesTheClient() {
        holding(4000000);

        handler.handlePacket(use(ROSTER_BOOK), client);

        assertTrue(menus.constructed().isEmpty());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(chr).sendPacket(sent.capture());
        assertTrue(NpcTalks.isEnableActions(sent.getValue()));
    }

    @Test
    void otherItemsTheKeySetToZeroAndTheWorldOffKeepTheStockPath() {
        holding(2430012);
        assertFalse(LivingWorldHooks.rosterBookUsed(chr, SLOT, 2430012));

        holding(ROSTER_BOOK);
        config.ROSTER_BOOK_ITEM_ID = 0;
        assertFalse(LivingWorldHooks.rosterBookUsed(chr, SLOT, ROSTER_BOOK));

        config.ROSTER_BOOK_ITEM_ID = ROSTER_BOOK;
        livingWorld.when(LivingWorld::get).thenReturn(null);
        assertFalse(LivingWorldHooks.rosterBookUsed(chr, SLOT, ROSTER_BOOK));

        assertTrue(menus.constructed().isEmpty());
        verify(chr, never()).sendPacket(any());
    }

    private void holding(int itemId) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getQuantity()).thenReturn((short) 1);
        when(use.getItem(SLOT)).thenReturn(item);
    }

    private static InPacket use(int itemId) {
        return Packets.buildInPacket(out -> {
            out.writeInt(0);
            out.writeShort(SLOT);
            out.writeInt(itemId);
        });
    }
}
