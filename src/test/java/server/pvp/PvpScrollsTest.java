package server.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PvpScrollsTest {
    @Test
    void sixtyAndTenPercentScrollsSitOneAndTwoPastTheWeaponTypesBlock() {
        assertEquals(2043001, PvpScrolls.attack(1302000, 60)); // one-handed sword (Item.wz success 60)
        assertEquals(2043002, PvpScrolls.attack(1302000, 10)); // (success 10)
        assertEquals(2043701, PvpScrolls.attack(1372005, 60)); // wand, magic attack
        assertEquals(2044801, PvpScrolls.attack(1482000, 60)); // knuckle
        assertEquals(2044902, PvpScrolls.attack(1492000, 10)); // gun
    }

    @Test
    void noScrollForAnUnlistedWeaponOrPercent() {
        assertEquals(0, PvpScrolls.attack(1342000, 60)); // katara
        assertEquals(0, PvpScrolls.attack(0, 60));
        assertEquals(0, PvpScrolls.attack(1302000, 100));
    }
}
