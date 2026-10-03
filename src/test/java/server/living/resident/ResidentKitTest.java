package server.living.resident;

import org.junit.jupiter.api.Test;
import server.living.config.Content;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of a Resident's own quest gear that need no live character. */
class ResidentKitTest {
    private static final Set<Integer> QUEST_IDS = Set.of(1302009, 1302010, 1002004);
    private static final Set<Integer> LOADOUT_IDS = Set.of(1302020, 1302008, 1002025);

    @Test
    void piecesGoToTheirEquipSlots() {
        assertEquals(-11, ResidentKit.slotOf(1302011), "a sword");
        assertEquals(-11, ResidentKit.slotOf(1492007), "a gun");
        assertEquals(-1, ResidentKit.slotOf(1002004));
        assertEquals(-4, ResidentKit.slotOf(1032004));
        assertEquals(-5, ResidentKit.slotOf(1040087), "a top");
        assertEquals(-5, ResidentKit.slotOf(1050045), "an overall");
        assertEquals(-6, ResidentKit.slotOf(1060000));
        assertEquals(-7, ResidentKit.slotOf(1072002));
        assertEquals(-8, ResidentKit.slotOf(1082009));
        assertEquals(-9, ResidentKit.slotOf(1102002));
        assertEquals(-10, ResidentKit.slotOf(1092004));
        assertEquals(0, ResidentKit.slotOf(2000000), "not equipment");
    }

    @Test
    void theKitTakesOffItsOwnPieces() {
        assertTrue(replaceable(1302009, "Garrick", 7), "its earlier quest weapon");
        assertTrue(replaceable(1302008, "", 0), "its plain loadout weapon");
        assertTrue(replaceable(1002025, null, 0), "its plain loadout helmet");
    }

    @Test
    void theKitLeavesWhatItWasGiven() {
        assertFalse(replaceable(1302011, "", 0), "a weapon it was given or looted");
        assertFalse(replaceable(1302008, "Brannock", 0), "a loadout id another Resident made: a gift, not its own");
        assertFalse(replaceable(1302010, "", 7), "a quest-tier id without its tag: the player's own reward handed back");
        assertFalse(replaceable(1302020, "", 5), "a scrolled Maple Sword: loadout pieces are made plain, so a gift");
        assertFalse(replaceable(1302020, "Garrick", 0), "a loadout id with its own tag is not a quest piece");
    }

    @Test
    void loadoutPiecesAreItsEquipsAndOutfitPools() {
        Content.Loadout loadout = new Content.Loadout("perion-warrior-l30", List.of(1302020, 1002025), List.of(), Map.of(),
                Map.of("weapon", List.of(1312003, 1302008), "hat", List.of(0, 1002011)));
        assertEquals(Set.of(1302020, 1002025, 1312003, 1302008, 0, 1002011), ResidentKit.loadoutIds(loadout));
        assertEquals(Set.of(), ResidentKit.loadoutIds(null));
    }

    private static boolean replaceable(int itemId, String owner, int scrolls) {
        return ResidentKit.replaceable("Garrick", itemId, owner, scrolls, QUEST_IDS, LOADOUT_IDS);
    }
}
