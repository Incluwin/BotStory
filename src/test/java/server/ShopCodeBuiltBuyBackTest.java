package server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A shop built in code with its own buy-back prices (the casino's chip cashier, docs/SOLOMAPLING_PORT.md) pays them, not
 * the item's stock sell price, and never overflows on a full stack; SoloMapling rewrote the WZ XML for this instead.
 */
class ShopCodeBuiltBuyBackTest {
    private static final int SLIME_STAMP = 4002003;

    @Test
    void aBuyBackPriceIsPaidPerItemAndCappedAtTheMesoLimit() {
        Shop shop = Shop.custom(9_999_001, 9000055);
        shop.stock(SLIME_STAMP, 1_000_000);
        shop.buysBackAt(SLIME_STAMP, 1_000_000);
        assertEquals(1, shop.getItems().size());
        assertEquals(1_000_000, shop.getItems().get(0).getPrice());
        assertEquals(3_000_000, shop.salePrice(SLIME_STAMP, (short) 3));
        assertEquals(Integer.MAX_VALUE, shop.salePrice(SLIME_STAMP, (short) 5_000));
        assertEquals(9_999_001, shop.getId());
    }
}
