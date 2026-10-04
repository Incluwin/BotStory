package server.living.roster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The companion restock duty asks which quest's desk its owner stands at ({@link PqEntrance#keyAt}): the map an
 * entrance's NPC stands on (its recruit map) names that quest, and any other map names none.
 */
class PqEntranceKeyAtTest {

    @Test
    void kerningCityIsKpqsDeskAndHenesysParkIsHpqs() {
        assertEquals("kpq", PqEntrance.keyAt(103_000_000));
        assertEquals("hpq", PqEntrance.keyAt(100_000_200));
    }

    @Test
    void everyRegisteredEntranceIsFoundOnItsOwnRecruitMap() {
        for (String key : PqEntrance.keys()) {
            int recruitMap = PqEntrance.of(key).definition().recruitMapId();
            assertEquals(key, PqEntrance.keyAt(recruitMap), "the desk map of " + key);
        }
    }

    @Test
    void aMapWithNoDeskNamesNoQuest() {
        assertNull(PqEntrance.keyAt(100_000_000)); // Henesys town, not Henesys Park
        assertNull(PqEntrance.keyAt(103_000_800)); // KPQ's first stage, inside the run
        assertNull(PqEntrance.keyAt(0));
    }
}
