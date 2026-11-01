package server.maps;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.MonsterDropEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Family drop buff scales the rows the killer's drop rate scales, and no others. */
class MapleMapFamilyDropRateTest {
    private static final MonsterDropEntry MESO_ROW = new MonsterDropEntry(0, 400_000, 30, 60, (short) 0);
    private static final MonsterDropEntry ITEM_ROW = new MonsterDropEntry(4_000_000, 600_000, 1, 1, (short) 0);
    private final boolean shipped = YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE;

    @AfterEach
    void restore() {
        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = shipped;
    }

    @Test
    void itemRowsTakeTheBuff() {
        assertEquals(2.0f, MapleMap.familyEntryRate(ITEM_ROW, 1, 2.0f));
        assertEquals(1.5f, MapleMap.familyEntryRate(ITEM_ROW, 3, 1.5f));
    }

    @Test
    void aCertainDropAndAMesoRowOnItsOwnChanceDoNot() {
        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = true;
        assertEquals(1.0f, MapleMap.familyEntryRate(ITEM_ROW, 1_000_000, 2.0f));
        assertEquals(1.0f, MapleMap.familyEntryRate(MESO_ROW, 3, 2.0f));

        YamlConfig.config.economy.MESO_CHANCE_IGNORES_DROP_RATE = false;
        assertEquals(2.0f, MapleMap.familyEntryRate(MESO_ROW, 3, 2.0f), "the meso row follows the drop rate again");
    }
}
