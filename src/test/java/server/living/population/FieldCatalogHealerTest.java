package server.living.population;

import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.knowledge.WorldKnowledge;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Field;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Healer fields (docs/SERVER_KNOWLEDGE_PLAN.md 8.5): a cleric-line citizen of level 30+ mostly trains where undead
 * monsters hold most spawn points, because Heal hurts them; every other career draws as before.
 */
class FieldCatalogHealerTest {
    private static final int ANT_TUNNEL_II = 105050100;
    private static final int HILL_EAST_OF_HENESYS = 100010000;
    private static final int DRAWS = 200;

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void healerFieldsKeepOnlyTheUndeadMajority() throws Exception {
        List<Field> mixed = List.of(content.field(ANT_TUNNEL_II), content.field(HILL_EAST_OF_HENESYS));

        assertTrue(FieldCatalog.healerFields(mixed, null).isEmpty(), "no knowledge: no preference");
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertEquals(List.of(content.field(ANT_TUNNEL_II)), FieldCatalog.healerFields(mixed, WorldKnowledge.current()));
        }
    }

    @Test
    void aLevel40ClericMostlyPicksUndeadFieldsAndAWizardDoesNot() throws Exception {
        FieldCatalog catalog = new FieldCatalog(population());
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = WorldKnowledge.current();
            int cleric = undeadDraws(catalog, k, "magician-cleric");
            int wizard = undeadDraws(catalog, k, "magician-fire");

            assertTrue(cleric >= DRAWS * 60 / 100, "cleric " + cleric + "/" + DRAWS);
            assertTrue(wizard < DRAWS * 45 / 100, "wizard " + wizard + "/" + DRAWS + ": the base rate (6 of 24 lv40 fields)");
        }
        // unpublished: the cleric draws exactly as the wizard does, the same seeded stream
        assertEquals(draws(catalog, "magician-fire"), draws(catalog, "magician-cleric"));
    }

    private int undeadDraws(FieldCatalog catalog, WorldKnowledge k, String career) {
        int undead = 0;
        for (int mapId : draws(catalog, career)) {
            undead += k.undeadShare(mapId) >= 0.5 ? 1 : 0;
        }
        return undead;
    }

    private static List<Integer> draws(FieldCatalog catalog, String career) {
        Random random = new Random(42);
        Citizen citizen = new Citizen(5, "Mira", "evening", 0, 0, "victoria", career, "relaxed-v1", "t", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of()), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < DRAWS; i++) {
            out.add(catalog.trainingField(citizen, 40, random, false).mapId());
        }
        return out;
    }

    private Population population() {
        Population population = mock(Population.class);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(population.content()).thenReturn(content);
        when(population.presence()).thenReturn(presence);
        when(presence.sessions()).thenReturn(List.of());
        return population;
    }
}
