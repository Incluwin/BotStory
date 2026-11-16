package server.pvp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpGrindMinutesTest {
    private static final PvpGrindMinutes TABLE = PvpGrindMinutes.parse(List.of(
            "# a comment",
            "exp_per_minute:   # band -> EXP",
            "  30: 1000",
            "  40: 3629",
            "mob_meso_per_hour:",
            "  30: 8916",
            "  100: 4739",
            "meso_rule:",
            "  measured_multiplier: 5",
            "  floor_per_level_hour: 1782"));

    @Test
    void expIsTheBandsAndBelowTheTableItScalesWithTheLevel() {
        assertEquals(1_000d, TABLE.expPerMinute(35), 1e-9);
        assertEquals(3_629d, TABLE.expPerMinute(45), 1e-9);
        assertEquals(3_629d, TABLE.expPerMinute(130), 1e-9);
        double low = TABLE.expPerMinute(20);
        assertTrue(low > 0 && low < 1_000d, "Lv 20 earns less than the 30s band: " + low);
    }

    @Test
    void mesoIsTheLargerOfFiveTimesMeasuredAndTheLevelFloor() {
        assertEquals(62_370d / 60, TABLE.mesoPerMinute(35), 1e-9);   // floor 1782 x 35 beats 5 x 8916
        assertEquals(133_650d / 60, TABLE.mesoPerMinute(75), 1e-9);  // band 70 falls back to the 30s row
        assertEquals(187_110d / 60, TABLE.mesoPerMinute(105), 1e-9); // floor beats 5 x 4739
    }

    @Test
    void aMissingTablePaysNothing() {
        PvpGrindMinutes empty = PvpGrindMinutes.parse(List.of());
        assertEquals(0d, empty.expPerMinute(50), 1e-9);
        assertEquals(0d, empty.mesoPerMinute(50), 1e-9);
    }

    @Test
    void theShippedTableHoldsTheV06Measurements() {
        PvpGrindMinutes shipped = PvpGrindMinutes.load(PvpGrindMinutes.FILE);
        assertEquals(13_735d, shipped.expPerMinute(75), 1e-9);
        assertEquals(77_803d, shipped.expPerMinute(105), 1e-9);
        assertEquals(5d * 62_879 / 60, shipped.mesoPerMinute(70), 1e-9);
    }
}
