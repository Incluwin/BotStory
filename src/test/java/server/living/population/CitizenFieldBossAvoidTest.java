package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.living.population.PopulationContent.Field;
import server.maps.MapleMap;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-10-19, for the field groups on by default: citizens never attack a listed field boss but take its hits while a
 * player fights it. One that died beside it trains elsewhere while the boss stands there (at most FIELD_BOSS_AVOID_MS),
 * instead of the re-plan sending it straight back to Mano's thicket to die again.
 */
class CitizenFieldBossAvoidTest {
    private static final int MANO_THICKET = 104_000_400;
    private static final int DRAWS = 400;

    private final MockedStatic<AgentBossField> field = mockStatic(AgentBossField.class);

    @AfterEach
    void close() {
        field.close();
    }

    @Test
    void thePickerNeverOffersAnAvoidedField() {
        FieldCatalog catalog = ShippedFields.population().fields();
        Set<Integer> picked = new LinkedHashSet<>();
        Set<Integer> avoiding = new LinkedHashSet<>();
        for (int level = 1; level <= 6; level++) {
            Random random = new Random(level);
            Random again = new Random(level);
            for (int i = 0; i < DRAWS; i++) {
                Field free = catalog.trainingField(citizen(), level, random, false);
                Field away = catalog.trainingField(citizen(), level, again, false, mapId -> mapId == MANO_THICKET);
                if (free != null) picked.add(free.mapId());
                if (away != null) avoiding.add(away.mapId());
            }
        }
        assertTrue(picked.contains(MANO_THICKET), "Mano's thicket is a field for these levels: " + picked);
        assertFalse(avoiding.contains(MANO_THICKET), "avoided: " + avoiding);
        assertFalse(avoiding.isEmpty(), "it still trains somewhere");
    }

    @Test
    void itAvoidsTheFieldWhileTheBossStandsWithinTheLimit() {
        Population population = mock(Population.class);
        PopulationConfig config = new PopulationConfig();
        when(population.config()).thenReturn(config);
        MapleMap thicket = mock(MapleMap.class);
        when(population.map(1, MANO_THICKET)).thenReturn(thicket);
        PresenceScheduler presence = new PresenceScheduler(population, new Random(1));
        CitizenSession session = new CitizenSession(citizen(), 0L, 3_600_000L, false);
        session.channel = 1;
        session.avoidMapId = MANO_THICKET;
        session.avoidUntilMs = 1_000_000L;
        field.when(() -> AgentBossField.near(thicket)).thenReturn(true);

        assertTrue(presence.avoiding(session, MANO_THICKET, 500_000L), "Mano still stands");
        assertFalse(presence.avoiding(session, 104_000_300, 500_000L), "only that field");

        field.when(() -> AgentBossField.near(thicket)).thenReturn(false);
        assertFalse(presence.avoiding(session, MANO_THICKET, 500_000L), "Mano fell: the field is free again");
        assertEquals(0, session.avoidMapId, "forgotten");

        session.avoidMapId = MANO_THICKET;
        field.when(() -> AgentBossField.near(thicket)).thenReturn(true);
        assertFalse(presence.avoiding(session, MANO_THICKET, 1_000_000L), "the limit is up");
    }

    private static Citizen citizen() {
        return new Citizen(5, "Mira", "evening", 0, 0, "victoria", "magician-fire", "relaxed-v1", "t", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
