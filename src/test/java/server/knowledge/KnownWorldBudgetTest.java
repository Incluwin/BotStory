package server.knowledge;

import config.KnowledgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import server.knowledge.ContentAnswers.Ask;
import server.knowledge.ContentAnswers.Asker;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.ContentAnswers.Responder;
import server.knowledge.KnownWorld.Past;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost of personal knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 7.9 acceptance 5): 400 full worlds (every table at its cap, a
 * level-120 past each) retain at most 1.5 MB, and a question to a bot with a full world stays under 50 microseconds
 * (opt-in: {@code -DrunBenchmarks=true}).
 */
class KnownWorldBudgetTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final int WORLDS = 400;
    private static final long HEAP_BUDGET_BYTES = 1_536L * 1024;
    private static final int ATTEMPTS = 3;
    private static final List<String> STYLES = List.of("friendly-casual-v1", "quiet-practical-v1", "playful-social-v1",
            "curious-millennial-v1");

    @Test
    void fourHundredFullWorldsFitTheBudget() {
        WorldKnowledge k = SeedOverlay.knowledge();
        List<Integer> maps = new ArrayList<>();
        for (MapFact map : k.maps()) {
            if (map.region() != null && map.forcedReturn() == 0 && maps.size() < KnownWorld.MAPS * 2) {
                maps.add(map.id());
            }
        }
        long retained = Long.MAX_VALUE;
        PersonalKnowledge registry = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            registry = null;
            long before = usedHeapAfterGc();
            registry = full(k, maps);
            retained = Math.min(retained, usedHeapAfterGc() - before);
        }
        assertTrue(registry.known(WORLDS).encode().split(" ").length == KnownWorld.MAPS + KnownWorld.MOBS + KnownWorld.ITEMS);
        System.out.printf("known worlds: %d full worlds retain %.2f MB (%.1f KB each)%n", WORLDS, retained / 1048576.0,
                retained / 1024.0 / WORLDS);
        assertTrue(retained <= HEAP_BUDGET_BYTES, "retained " + retained + " bytes");
    }

    /**
     * A wall-clock budget, so it runs only on request: a loaded machine measured 141 us here against a quiet 22-42 us.
     * Run with {@code mvn test -DrunBenchmarks=true -Dtest=KnownWorldBudgetTest}.
     */
    @Test
    @EnabledIfSystemProperty(named = "runBenchmarks", matches = "true")
    void aQuestionToAFullWorldStaysUnderFiftyMicroseconds() {
        WorldKnowledge k = SeedOverlay.knowledge();
        List<Integer> maps = new ArrayList<>();
        for (MapFact map : k.maps()) {
            if ("victoria".equals(map.region()) && map.forcedReturn() == 0 && maps.size() < KnownWorld.MAPS) {
                maps.add(map.id());
            }
        }
        KnownWorld world = full(k, maps).known(1);
        ContentAnswers answers = new ContentAnswers(SeedOverlay::knowledge, w -> RATES, item -> null, 10, 70, false);
        List<Ask> asks = new ArrayList<>();
        int i = 0;
        for (KnowledgeProbeTest.Probe probe : KnowledgeProbeTest.POSITIVES) {
            asks.add(new Ask(probe.line(), new Asker(7000 + i, "Tester", probe.map(), probe.level(), probe.job(), 0),
                    new Responder(1, 120, "victoria", STYLES.get(i++ % STYLES.size()), Set.of(), true, world), name -> false, 1_000_000L * i));
        }
        for (String line : KnowledgeProbeTest.NEGATIVES) {
            asks.add(new Ask(line, new Asker(7000, "Tester", 100_000_000, 30, 410, 0),
                    new Responder(1, 120, "victoria", STYLES.get(0), Set.of(), true, world), name -> false, 1_000_000L));
        }
        for (int warm = 0; warm < 20; warm++) {
            asks.forEach(answers::explain);
        }
        int rounds = 50;
        double micros = Double.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) { // the best of three: a busy machine slows one, not all
            long started = System.nanoTime();
            for (int round = 0; round < rounds; round++) {
                asks.forEach(answers::explain);
            }
            micros = Math.min(micros, (System.nanoTime() - started) / 1000d / (rounds * asks.size()));
        }
        System.out.printf("personal answers: %.1f us per question over %d lines with a full world%n", micros, asks.size());
        assertTrue(micros < 50, micros + " us per question");
    }

    /** WORLDS worlds, each with a level-120 past and every table filled to its cap with real ids. */
    private static PersonalKnowledge full(WorldKnowledge k, List<Integer> maps) {
        PersonalKnowledge registry = PersonalKnowledge.inMemory(() -> k, w -> RATES, new KnowledgeConfig());
        List<MobFact> mobs = k.mobs();
        List<ItemFact> items = k.items();
        for (int id = 1; id <= WORLDS; id++) {
            registry.open(id, new PersonalKnowledge.Spec(Past.SYNTHESIZED, id, 120, band -> band < 10 ? "maple-island" : null,
                    IntList.of(100_000_000), List.of(), false, false));
            for (int n = 0; n < KnownWorld.MAPS; n++) {
                registry.visited(id, maps.get((id + n) % maps.size()));
            }
            for (int n = 0; n < KnownWorld.MOBS; n++) {
                registry.killed(id, mobs.get((id * 7 + n) % mobs.size()).id(), 0);
            }
            for (int n = 0; n < KnownWorld.ITEMS; n++) {
                registry.gained(id, items.get((id * 13 + n) % items.size()).id());
            }
        }
        return registry;
    }

    private static long usedHeapAfterGc() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
