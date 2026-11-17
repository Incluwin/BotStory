package server.living.population.coliseum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuartermasterTest {
    @Test
    void theShippedStockReadsInWindowOrderAtThePlansPrices() {
        List<Quartermaster.Ware> stock = Quartermaster.load(Quartermaster.FILE);
        assertEquals(List.of(
                new Quartermaster.Ware("2000004", 10, 3),
                new Quartermaster.Ware("2000005", 5, 5),
                new Quartermaster.Ware("2050004", 5, 2),
                new Quartermaster.Ware("weapon60", 1, 15),
                new Quartermaster.Ware("weapon10", 1, 40),
                new Quartermaster.Ware("3010064", 1, 30),
                new Quartermaster.Ware("1142084", 1, 60)), stock);
    }

    @Test
    void onlyTheStockSectionsReadableLinesCount() {
        List<Quartermaster.Ware> stock = Quartermaster.parse(List.of(
                "# a comment",
                "other:",
                "  2000004: 10, 3",
                "stock:",
                "  2000000: 1, 2   # a note",
                "  nonsense: 1, 2",
                "  2000001: 1",
                "  2000002: 0, 2",
                "  2000003: 2, 0",
                "  weapon60: 1, 15"));
        assertEquals(List.of(new Quartermaster.Ware("2000000", 1, 2), new Quartermaster.Ware("weapon60", 1, 15)), stock);
        assertTrue(Quartermaster.load(java.nio.file.Path.of("no", "such", "file.yaml")).isEmpty());
    }

    @Test
    void aWeaponWareGivesTheScrollForTheWeaponHeld() {
        Quartermaster.Ware sixty = new Quartermaster.Ware("weapon60", 1, 15);
        Quartermaster.Ware ten = new Quartermaster.Ware("weapon10", 1, 40);
        assertEquals(2043001, sixty.itemFor(1302000));   // one-handed sword
        assertEquals(2043002, ten.itemFor(1302000));
        assertEquals(2043702, ten.itemFor(1372005));     // wand: magic attack
        assertEquals(0, sixty.itemFor(0));               // nothing held
        assertEquals(0, sixty.itemFor(1342000));         // a katara has no scroll here
        assertEquals(3010064, new Quartermaster.Ware("3010064", 1, 30).itemFor(1302000));
    }

    @Test
    void aTradeIsRefusedForEachMissingThing() {
        Quartermaster.Ware scroll = new Quartermaster.Ware("weapon60", 1, 15);
        Quartermaster.Ware medal = new Quartermaster.Ware("1142084", 1, 60);
        assertTrue(Quartermaster.refusal(scroll, 0, 99, true, false).startsWith("Hold the weapon"));
        assertEquals("That's 60 Event Trophies, and you have 59.", Quartermaster.refusal(medal, 1142084, 59, true, false));
        assertTrue(Quartermaster.refusal(medal, 1142084, 60, false, true).startsWith("You already have one"));
        assertTrue(Quartermaster.refusal(medal, 1142084, 60, false, false).startsWith("You've no room"));
        assertNull(Quartermaster.refusal(medal, 1142084, 60, true, false));
        assertNull(Quartermaster.refusal(scroll, 2043001, 15, true, false));
    }
}
