package server.living.population.craft;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.population.SocialFabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Citizens' trades (docs/WORLD_SYSTEMS_PLAN.md N8.1): about one in ten has one, by career and personality and stable; it
 * is one flag the column has room for; and nothing reads or writes one while PROFESSIONS_ENABLED is off.
 */
class ProfessionsTest {
    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final SocialFabric social = mock(SocialFabric.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final Professions professions = new Professions(population, new RecipeBook(List.of()));

    @BeforeEach
    void setUp() {
        config.PROFESSIONS_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.social()).thenReturn(social);
        when(social.sociability(any())).thenReturn(50);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.citizens()).thenAnswer(call -> new ArrayList<>(registry.values()));
        when(population.citizen(anyInt())).thenAnswer(call -> registry.get(call.<Integer>getArgument(0)));
        when(population.update(anyInt(), any())).thenAnswer(call -> {
            UnaryOperator<Citizen> change = call.getArgument(1);
            return registry.computeIfPresent(call.getArgument(0), (id, current) -> change.apply(current));
        });
    }

    static Citizen citizen(int id, Set<String> flags) {
        return new Citizen(id, "Kim" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    @Test
    void aboutOneInTenHasATradeAndFriendlyPeopleSooner() {
        int plain = 0;
        int shy = 0;
        int friendly = 0;
        for (int id = 1; id <= 20_000; id++) {
            plain += Profession.drawn(id, 1, 50) == null ? 0 : 1;
            shy += Profession.drawn(id, 1, 0) == null ? 0 : 1;
            friendly += Profession.drawn(id, 1, 100) == null ? 0 : 1;
        }
        assertTrue(plain > 1_800 && plain < 2_200, plain + " of 20,000");
        assertTrue(shy < plain && plain < friendly, shy + " " + plain + " " + friendly);
    }

    @Test
    void theCareerLeansOnIt() {
        int[][] counts = new int[6][Profession.values().length];
        for (int branch = 1; branch <= 5; branch++) {
            for (int id = 1; id <= 20_000; id++) {
                Profession drawn = Profession.drawn(id, branch, 50);
                if (drawn != null) {
                    counts[branch][drawn.ordinal()]++;
                }
            }
        }
        assertTrue(counts[1][Profession.SMITH.ordinal()] > counts[2][Profession.SMITH.ordinal()] * 4, "warriors smith");
        assertTrue(counts[2][Profession.ALCHEMIST.ordinal()] > counts[1][Profession.ALCHEMIST.ordinal()] * 2, "mages brew");
        assertTrue(counts[4][Profession.TAILOR.ordinal()] > counts[1][Profession.TAILOR.ordinal()] * 2, "thieves sew");
    }

    @Test
    void theMinuteWritesTheFlagsAFewAtATimeAndNeverTwice() {
        for (int id = 1; id <= 1_000; id++) {
            registry.put(id, citizen(id, Set.of()));
        }
        registry.put(2_000_000, citizen(2_000_000, Set.of(Profession.TAILOR.flag())));
        professions.minute();
        assertEquals(Professions.WRITES_A_MINUTE + 1L, crafters());
        for (int i = 0; i < 20; i++) {
            professions.minute();
        }
        long settled = crafters();
        assertTrue(settled > 70 && settled < 130, settled + " of 1,000");
        professions.minute();
        assertEquals(settled, crafters());
        assertEquals(Profession.TAILOR, professions.of(2_000_000), "what it had is kept");
    }

    private long crafters() {
        return registry.values().stream().filter(citizen -> Profession.of(citizen) != null).count();
    }

    @Test
    void offNobodyHasATradeAndNothingIsWritten() {
        config.PROFESSIONS_ENABLED = false;
        registry.put(1, citizen(1, Set.of(Profession.SMITH.flag())));
        for (int id = 2; id <= 300; id++) {
            registry.put(id, citizen(id, Set.of()));
        }
        professions.minute();
        assertEquals(1L, crafters());
        assertNull(professions.of(1));
        assertEquals("plain", professions.stallTitle(registry.get(1), "plain", "steel plate"));
        assertFalse(professions.says(registry.get(1), "trade.smith.town"));
        assertTrue(professions.says(registry.get(1), "town.idle"));
    }

    @Test
    void itsLinesAndStallAreItsTrades() {
        registry.put(1, citizen(1, Set.of(Profession.SMITH.flag())));
        assertTrue(professions.says(registry.get(1), "trade.smith.town"));
        assertFalse(professions.says(registry.get(1), "trade.tailor.town"));
        assertEquals("smith here: steel plate", professions.stallTitle(registry.get(1), "plain", "steel plate"));
        professions.set(1, Profession.ALCHEMIST);
        assertEquals(Profession.ALCHEMIST, professions.of(1));
        professions.set(1, null);
        assertNull(professions.of(1));
        registry.put(3, citizen(3, Set.of(Profession.SMITH.flag(), Citizen.FLAG_HIRED)));
        assertNull(professions.of(3), "a hired hand works for its player");
    }

    @Test
    void everyFlagFitsTheColumn() {
        String all = String.join(",", Citizen.FLAG_MERCHANT, Citizen.FLAG_FAMER, Citizen.FLAG_PQ_REGULAR,
                Citizen.FLAG_AFK_TOWNIE, Citizen.FLAG_RECRUITER, Citizen.FLAG_HIRED, Citizen.FLAG_JOB_BACKLOG,
                Citizen.FLAG_LORE_SPOUSE, Profession.ALCHEMIST.flag());
        assertTrue(all.length() <= 255, all.length() + " of living_citizen.flags VARCHAR(255)");
    }
}
