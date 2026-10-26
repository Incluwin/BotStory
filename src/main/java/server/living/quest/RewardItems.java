package server.living.quest;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.CashShop;

/**
 * Puts a quest reward's items into the player's inventory. A pet is created the way an iTCG code creates one
 * ({@link CashShop#generateCouponItem}: its {@code pets} row and 30 days of magic time), one pet per count, so a
 * Resident can hand out the iTCG pets; {@link InventoryManipulator#addById} alone would give a cash item with no pet
 * behind it. Everything else goes through {@code addById} as before. The caller checks room first.
 */
final class RewardItems {
    private RewardItems() {
    }

    /** Gives {@code count} of {@code itemId}; false when nothing could be given (a pet row the database refused). */
    static boolean give(Character chr, int itemId, int count) {
        if (!ItemConstants.isPet(itemId)) {
            return InventoryManipulator.addById(chr.getClient(), itemId, (short) count);
        }
        boolean any = false;
        for (int i = 0; i < count; i++) {
            Item pet = CashShop.generateCouponItem(itemId, (short) 1);
            if (pet == null || pet.getPetId() < 0) {
                break;
            }
            any |= InventoryManipulator.addFromDrop(chr.getClient(), pet, true, pet.getPetId());
        }
        return any;
    }
}
