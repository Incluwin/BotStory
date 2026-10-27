package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Decision D6 (docs/BOT_CONCEPT_PLAN.md F8): no citizen AFK on a field runs past 2 min, and one starts only where it is
 * safe and ends early when it is not; towns, Free Market rooms and lobbies keep the 5-15 min AFK.
 */
class FieldPauseTest {
    @Test
    void noFieldAfkRunsPastTwoMinutesWhileTownsKeepTheLongOne() {
        Random random = new Random(11);
        int fieldMax = new PopulationConfig().FIELD_AFK_MAX_MS;
        assertTrue(fieldMax <= FieldPause.HARD_MAX_MS);
        for (int i = 0; i < 2_000; i++) {
            long field = FieldPause.durationMs("field", fieldMax, random);
            assertTrue(field >= FieldPause.MIN_MS && field <= 120_000L, "field " + field);
            long top = FieldPause.durationMs("field", 120_000, random);
            assertTrue(top <= 120_000L, "even at the top of the config: " + top);
            for (String where : List.of("town", "fm", "lobby")) {
                long town = FieldPause.durationMs(where, fieldMax, random);
                assertTrue(town >= 5 * 60_000L && town <= 15 * 60_000L, where + " " + town);
            }
        }
        long old = FieldPause.durationMs("field", 0, random);
        assertTrue(old >= 5 * 60_000L, "0 restores the old field AFK");
    }

    @Test
    void theFieldCapIsValidated() {
        PopulationConfig config = new PopulationConfig();
        config.FIELD_AFK_MAX_MS = 300_000;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.FIELD_AFK_MAX_MS = 0;
        config.validate();
        assertEquals(110_000, new PopulationConfig().FIELD_AFK_MAX_MS);
    }

    @Test
    void aPauseStartsOnlyWhereItIsSafeAndEndsWhenItIsNot() {
        assertTrue(FieldPause.safeToStart(citizen(100, 2_000)));
        assertFalse(FieldPause.safeToStart(citizen(79, 2_000)), "hurt");
        assertFalse(FieldPause.safeToStart(citizen(100, 300)), "a mob within 400 px");
        assertTrue(FieldPause.safeToStart(null), "a ledger-only citizen stands nowhere");

        assertFalse(FieldPause.endsEarly(citizen(100, 300)));
        assertTrue(FieldPause.endsEarly(citizen(59, 2_000)), "hit under 60%");
        assertTrue(FieldPause.endsEarly(citizen(100, 100)), "a mob within 150 px");
        assertTrue(FieldPause.endsEarly(citizen(0, 2_000)), "dead");
    }

    private static Character citizen(int hp, int mobX) {
        Monster mob = mock(Monster.class);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getPosition()).thenReturn(new Point(mobX, 0));
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of(mob));
        Character citizen = mock(Character.class);
        when(citizen.getMap()).thenReturn(map);
        when(citizen.getPosition()).thenReturn(new Point(0, 0));
        when(citizen.getHp()).thenReturn(hp);
        when(citizen.getCurrentMaxHp()).thenReturn(100);
        when(citizen.isAlive()).thenReturn(hp > 0);
        return citizen;
    }
}
