package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Family window's EXP and drop buffs (FamilyUseHandler) used to work out a rate and stop at "not implemented".
 * They are now kept by character id so a channel change keeps them, and they end on time.
 */
class FamilyBuffsTest {
    private static final int CHR = 4_242;

    @AfterEach
    void clear() {
        FamilyBuffs.clear();
    }

    @Test
    void aBuffAppliesUntilItEnds() {
        long now = System.currentTimeMillis();
        FamilyBuffs.grantExp(CHR, 2.0f, now + 60_000);

        assertEquals(2.0f, FamilyBuffs.expRate(CHR, now));
        assertEquals(1.0f, FamilyBuffs.dropRate(CHR, now), "an EXP buff leaves drops alone");
        assertEquals(1.0f, FamilyBuffs.expRate(CHR, now + 60_000), "ended");
        assertEquals(1.0f, FamilyBuffs.expRate(CHR + 1, now), "someone else");
    }

    @Test
    void aSecondBuffKeepsTheHigherRateAndTheLaterEnd() {
        long now = System.currentTimeMillis();
        FamilyBuffs.grantDrop(CHR, 2.0f, now + 15 * 60_000);
        FamilyBuffs.grantDrop(CHR, 1.5f, now + 30 * 60_000);

        assertEquals(2.0f, FamilyBuffs.dropRate(CHR, now + 20 * 60_000));
        assertEquals(1.0f, FamilyBuffs.dropRate(CHR, now + 30 * 60_000));
    }

    @Test
    void ratesReadTheWayTheFamilyWindowWritesThem() {
        assertEquals("2", FamilyBuffs.formatRate(2.0f));
        assertEquals("1.5", FamilyBuffs.formatRate(1.5f));
    }
}
