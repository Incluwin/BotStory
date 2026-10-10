package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.PopulationContent.Field;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.population.ShippedFields.FOREST_BEFORE_THE_GATE;
import static server.living.population.ShippedFields.PAST_THE_CASTLE_GATES;

/**
 * Nobody trains past the Mushroom Castle quest gates (user decision 2026-10-10): citizens were placed straight onto those
 * fields, gates they never passed, and after a potion trip to Mushroom Forest Field (106020000, the only shop) no route
 * led back; 28 stuck trips from 2026-10-06. The nine fields ship with capacity 0, so neither the citizen picker (login
 * placement and the 25-45 minute re-plan) nor the bench camp list offers them; the three forest fields before the gate
 * still take citizens and camps.
 */
class FieldCatalogQuestGateTest {
    private static final int DRAWS_PER_LEVEL = 300;
    private static final int MUSHROOM_FOREST_FIELD = 106020000;

    @Test
    void theNineFieldsPastTheGatesShipWithNoRoom() {
        PopulationContent content = ShippedFields.content();
        List<String> open = new ArrayList<>();
        for (int mapId : PAST_THE_CASTLE_GATES) {
            Field field = content.field(mapId);
            if (field == null || field.town() || field.capacity() != 0) {
                open.add(mapId + " " + field);
            }
        }
        assertEquals(List.of(), open, "listed non-town fields with capacity 0");
    }

    @Test
    void noCitizenOfLevel30To36IsSentPastTheGates() {
        FieldCatalog catalog = ShippedFields.population().fields();
        Set<Integer> picked = new LinkedHashSet<>();
        for (int level = 30; level <= 36; level++) {
            Random random = new Random(level);
            for (int i = 0; i < DRAWS_PER_LEVEL; i++) {
                Field field = catalog.trainingField(citizen(), level, random, false);
                if (field != null) {
                    picked.add(field.mapId());
                }
            }
        }
        assertTrue(picked.stream().noneMatch(PAST_THE_CASTLE_GATES::contains), "picked " + picked);
        assertTrue(picked.containsAll(FOREST_BEFORE_THE_GATE), "the forest before the gate still takes citizens: " + picked);
    }

    @Test
    void theBenchCampListNeverOffersTheFieldsPastTheGates() {
        FieldCatalog catalog = ShippedFields.population().fields();
        Set<Integer> listed = new LinkedHashSet<>();
        for (int level = 30; level <= 36; level++) {
            for (int near : List.of(MUSHROOM_FOREST_FIELD, 106021300)) {
                for (Field field : catalog.fieldsFor(level, near, 1_000)) {
                    listed.add(field.mapId());
                }
            }
        }
        assertTrue(listed.stream().noneMatch(PAST_THE_CASTLE_GATES::contains), "listed " + listed);
        assertTrue(listed.containsAll(FOREST_BEFORE_THE_GATE), "the forest before the gate is still a camp: " + listed);
    }

    private static Citizen citizen() {
        return new Citizen(5, "Mira", "evening", 0, 0, "victoria", "magician-fire", "relaxed-v1", "t", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
