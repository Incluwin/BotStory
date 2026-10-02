package server.knowledge;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot cost of the knowledge layer (docs/SERVER_KNOWLEDGE_PLAN.md 4.3): reading the file + travel.yaml + aliases.yaml
 * + fields.yaml and building the snapshot (name index included) stays under 500 ms (the DB {@code SELECT}s excluded),
 * and the snapshot, with an overlay the size of the live tables (22,157 drop rows, 3,882 shop rows), retains under
 * 10 MB of heap.
 */
class KnowledgeLoadBudgetTest {
    private static final int DROP_ROWS = 22_157;
    private static final int SHOP_ROWS = 3_882;
    private static final long LOAD_BUDGET_MS = 500;
    private static final long HEAP_BUDGET_BYTES = 10L * 1024 * 1024;
    private static final int ATTEMPTS = 3;

    @Test
    void loadsUnderBudget() throws IOException {
        // At boot config.yaml is parsed (YamlReader warmed up) long before the loader reads travel.yaml.
        assertTrue(YamlConfig.config.knowledge.ENABLED);
        // the best of a few loads: in the broad suite other tests' threads and garbage land in any single GC delta
        long fileMs = Long.MAX_VALUE;
        long buildMs = Long.MAX_VALUE;
        long retained = Long.MAX_VALUE;
        WorldKnowledge knowledge = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            knowledge = null;
            long before = usedHeapAfterGc();
            long started = System.nanoTime();
            KnowledgeFile file = KnowledgeFile.read(KnowledgeFixtures.DIR.resolve(KnowledgeSources.FILE_NAME));
            List<TravelLeg> travel = TravelCatalog.load(KnowledgeFixtures.DIR.resolve("travel.yaml"), file);
            AliasCatalog aliases = AliasCatalog.load(KnowledgeFixtures.DIR.resolve("aliases.yaml"), file);
            Map<Integer, Integer> fields = KnowledgeLoader.readFields(KnowledgeFixtures.FIELDS);
            fileMs = Math.min(fileMs, (System.nanoTime() - started) / 1_000_000);
            KnowledgeOverlay overlay = liveSizedOverlay(file);
            long buildStarted = System.nanoTime();
            knowledge = WorldKnowledge.build(file, travel, aliases, fields, overlay, 1);
            buildMs = Math.min(buildMs, (System.nanoTime() - buildStarted) / 1_000_000);
            retained = Math.min(retained, usedHeapAfterGc() - before);
        }

        long lookupStarted = System.nanoTime();
        int found = 0;
        for (int i = 0; i < 10_000; i++) {
            found += knowledge.dropSources(2070005 + (i % 7)).size() + knowledge.shopsSelling(2000000 + (i % 7)).size()
                    + knowledge.spawnMaps(100100 + (i % 3)).size();
        }
        long lookupNs = (System.nanoTime() - lookupStarted) / 30_000;
        long routeStarted = System.nanoTime();
        found += knowledge.route(100000000, 220000000).size();
        long routeUs = (System.nanoTime() - routeStarted) / 1_000;
        long cachedStarted = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            found += knowledge.route(100000000, 220000000).size();
        }
        long cachedNs = (System.nanoTime() - cachedStarted) / 100;
        System.out.printf("knowledge load: file+travel %d ms, build %d ms, retained %.2f MB (estimate %.2f MB), "
                        + "lookup %d ns, route %d us first / %d ns cached (%d)%n", fileMs, buildMs, retained / 1048576.0,
                knowledge.approxBytes() / 1048576.0, lookupNs, routeUs, cachedNs, found);
        assertTrue(fileMs + buildMs < LOAD_BUDGET_MS, "load took " + (fileMs + buildMs) + " ms");
        assertTrue(retained < HEAP_BUDGET_BYTES, "retained " + retained + " bytes");
        assertTrue(knowledge.ready());
    }

    /** Real mob/item/NPC ids in the live row counts, so the indexes are as big as on the server. */
    private static KnowledgeOverlay liveSizedOverlay(KnowledgeFile file) {
        KnowledgeOverlay.Builder overlay = KnowledgeOverlay.builder();
        for (int i = 0; i < DROP_ROWS; i++) {
            overlay.drop(file.mobs.idAt(i % file.mobs.size()), file.items.idAt((i * 7) % file.items.size()),
                    1000 + i, 1, 1, 0);
        }
        for (int i = 0; i < SHOP_ROWS; i++) {
            overlay.shopItem(1000 + i / 35, file.npcs.idAt(i / 35), file.items.idAt((i * 13) % file.items.size()), 100 + i, 0);
        }
        overlay.globalDrop(-1, 4310000, 2000, 1, 1, 0);
        return overlay.build();
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
