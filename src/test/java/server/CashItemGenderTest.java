package server;

import org.junit.jupiter.api.Test;
import server.CashShop.CashItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A gift checks the recipient against Commodity.img's Gender: 0 male, 1 female, 2 either. */
class CashItemGenderTest {
    private static CashItem item(int gender) {
        return new CashItem(1, 1_002_000, 1_000, 90, (short) 1, true, gender);
    }

    @Test
    void aGenderedItemOnlyFitsThatGender() {
        assertTrue(item(1).fitsGender(1));
        assertFalse(item(1).fitsGender(0));
        assertTrue(item(0).fitsGender(0));
        assertFalse(item(0).fitsGender(1));
    }

    @Test
    void anUnisexItemFitsEveryone() {
        assertTrue(item(2).fitsGender(0));
        assertTrue(item(2).fitsGender(1));
    }
}
