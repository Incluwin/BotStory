package server.living.resident;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Loadout;
import server.living.config.ContentLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An outfit weapon only replaces weapons the living world handed out. Before 2026-10-02 dressing deleted every
 * other weapon in the bag, so a released companion would lose what it earned (Dorran 1402009, Jinx 1472020,
 * Vex 1472030 at the time).
 */
class ResidentWardrobeWeaponsTest {
    private static final Loadout SAILOR = new Loadout("sailor", List.of(1302008, 1082002), List.of(), Map.of(),
            Map.of("hat", List.of(1002019), "weapon", List.of(1302005, 1402002)));

    @Test
    void handedOutHoldsLoadoutAndOutfitWeaponsAndTheStarterKitsOnly() {
        Set<Integer> ids = ResidentWardrobe.handedOut(List.of(SAILOR));
        assertTrue(ids.containsAll(List.of(1302008, 1302005, 1402002)), ids.toString());
        assertTrue(ids.contains(1302077), "the warrior starter sword: the optimizer would re-equip it over a mop");
        assertFalse(ids.contains(1082002), "gloves are not weapons");
        assertFalse(ids.contains(1002019), "a hat is not a weapon");
    }

    @Test
    void anEarnedWeaponIsNeverReplaced() {
        Set<Integer> ids = ResidentWardrobe.handedOut(List.of(SAILOR));
        assertTrue(ResidentWardrobe.replaces(1302008, 1302005, ids), "the loadout's own sword gives way to the pick");
        assertFalse(ResidentWardrobe.replaces(1302005, 1302005, ids), "the pick itself stays");
        assertFalse(ResidentWardrobe.replaces(1402009, 1302005, ids), "a weapon it earned stays");
    }

    @Test
    void theCompanionsEarnedWeaponsAreNotHandedOutByAnyShippedLoadout() {
        Set<Integer> ids = ResidentWardrobe.handedOut(ContentLoader.load(Path.of("scripts/config/living-world")).loadouts().values());
        for (int earned : List.of(1402009, 1472020, 1472030)) {
            assertFalse(ids.contains(earned), earned + " would be deleted when its companion is dressed");
        }
    }
}
