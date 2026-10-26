package server.living.quest;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.CashShop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** A Resident's reward pet is a real pet, made like an iTCG code's; other rewards still go through addById. */
class RewardItemsTest {
    private static final int BLUE_HUSKY = 5_000_037;
    private static final int TARU_TOTEM = 4_031_755;

    private Character chr;
    private Client client;

    @BeforeEach
    void setUp() {
        chr = mock(Character.class);
        client = mock(Client.class);
        when(chr.getClient()).thenReturn(client);
    }

    /** The coupon item: a mock, since a real Item with a pet id loads its pet from the database. */
    private static Item pet(int petId) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(BLUE_HUSKY);
        when(item.getPetId()).thenReturn(petId);
        return item;
    }

    @Test
    void aPetRewardCreatesOnePetPerCountAndAddsItWithItsPetId() {
        Item first = pet(901);
        Item second = pet(902);
        try (MockedStatic<CashShop> cash = mockStatic(CashShop.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            cash.when(() -> CashShop.generateCouponItem(BLUE_HUSKY, (short) 1)).thenReturn(first, second);
            inventory.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), anyBoolean(), anyInt()))
                    .thenReturn(true);

            assertTrue(RewardItems.give(chr, BLUE_HUSKY, 2));

            cash.verify(() -> CashShop.generateCouponItem(BLUE_HUSKY, (short) 1), times(2));
            inventory.verify(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(true), eq(901)));
            inventory.verify(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(true), eq(902)));
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
    }

    @Test
    void aPetRowTheDatabaseRefusedGivesNothing() {
        Item refused = pet(-1);
        try (MockedStatic<CashShop> cash = mockStatic(CashShop.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            cash.when(() -> CashShop.generateCouponItem(BLUE_HUSKY, (short) 1)).thenReturn(refused);

            assertFalse(RewardItems.give(chr, BLUE_HUSKY, 1));

            inventory.verify(() -> InventoryManipulator.addFromDrop(any(), any(Item.class), anyBoolean(), anyInt()), never());
        }
    }

    @Test
    void anyOtherItemStillGoesThroughAddById() {
        try (MockedStatic<CashShop> cash = mockStatic(CashShop.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.addById(client, TARU_TOTEM, (short) 3)).thenReturn(true);

            assertTrue(RewardItems.give(chr, TARU_TOTEM, 3));

            inventory.verify(() -> InventoryManipulator.addById(client, TARU_TOTEM, (short) 3));
            cash.verify(() -> CashShop.generateCouponItem(anyInt(), anyShort()), never());
        }
    }
}
