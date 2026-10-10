package server.living.population;

import server.living.config.ContentLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shipped scripts/config/population content behind a mocked Population with a real FieldCatalog and nobody online,
 * for tests outside this package (FieldCatalog's constructor is package-private). The content loads once per test JVM;
 * each call builds a fresh mock, since inline mocks are freed after each test class.
 */
public final class ShippedFields {
    /**
     * The Mushroom Castle fields past its quest gates (scripts/portal/obstacle.js quest 30000 or a Poison Spore, then
     * gotocastle.js quest 2324): capacity 0 in fields.yaml, so nobody trains or camps there.
     */
    public static final Set<Integer> PAST_THE_CASTLE_GATES = Set.of(106020400, 106020401, 106020402, 106020700,
            106020800, 106021000, 106021100, 106021200, 106021300);
    /** Secluded, Isolated and Deep Inside Mushroom Forest: before the gate, walkable from the Mushroom Forest Field shop. */
    public static final List<Integer> FOREST_BEFORE_THE_GATE = List.of(106020100, 106020200, 106020300);

    private static PopulationContent content;

    private ShippedFields() {
    }

    public static synchronized PopulationContent content() {
        if (content == null) {
            content = PopulationLoader.load(Path.of("scripts/config/population"),
                    ContentLoader.load(Path.of("scripts/config/living-world")));
        }
        return content;
    }

    /** A Population over the shipped content: content(), a real fields() and an empty presence(). */
    public static Population population() {
        Population population = mock(Population.class);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(population.content()).thenReturn(content());
        when(population.presence()).thenReturn(presence);
        when(presence.sessions()).thenReturn(List.of());
        FieldCatalog fields = new FieldCatalog(population);
        when(population.fields()).thenReturn(fields);
        return population;
    }
}
