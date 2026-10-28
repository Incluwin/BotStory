package server.maps;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.MonsterDropEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decision D2 (docs/ECONOMY_PLAN.md section 7): a mob's meso row drops at its own chance. At drop_rate 3 a 40 % meso
 * row had become a certain drop, ~7.5x vanilla meso a kill with meso_rate 3 on top. Item rows keep the drop rate,
 * and a certain drop stays certain; with the switch off the meso row is multiplied again.
 */
class MapleMapMesoChanceTest {
    private static final MonsterDropEntry MESO_ROW = new MonsterDropEntry(0, 400_000, 30, 60, (short) 0);
    private static final MonsterDropEntry ITEM_ROW = new MonsterDropEntry(4_000_000, 600_000, 1, 1, (short) 0);
    private final boolean shipped = YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE;

    @AfterEach
    void restore() {
        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = shipped;
    }

    @Test
    void aMesoRowDropsAtItsOwnChanceAndItemsKeepTheDropRate() {
        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = true;
        assertEquals(1, MapleMap.entryRate(MESO_ROW, 3));
        assertEquals(1, MapleMap.entryRate(MESO_ROW, 6), "a coupon's doubled drop rate is ignored too");
        assertEquals(3, MapleMap.entryRate(ITEM_ROW, 3));
        assertEquals(1_000_000, MapleMap.entryRate(MESO_ROW, 1_000_000), "a certain drop stays certain");
    }

    @Test
    void offTheDropRateMultipliesTheMesoRowAgain() {
        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = false;
        assertEquals(3, MapleMap.entryRate(MESO_ROW, 3));
    }
}
