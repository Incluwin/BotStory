package server.agents.integration.cosmic;

import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class CosmicPreparedNpcScriptEnginesTest {
    private static final String LAKELIS = "npc/9020000.js";
    private static final String CLOTO = "npc/9020001.js";

    private final Queue<Runnable> background = new ArrayDeque<>();
    private final List<String> evaluated = new ArrayList<>();
    private final List<ScriptEngine> created = new ArrayList<>();
    private final Map<String, Long> modified = new ConcurrentHashMap<>();

    private CosmicPreparedNpcScriptEngines engines(int perScript, int maxScripts) {
        return new CosmicPreparedNpcScriptEngines(path -> {
            evaluated.add(path);
            ScriptEngine engine = mock(ScriptEngine.class);
            created.add(engine);
            return engine;
        }, path -> modified.getOrDefault(path, 1L), background::add, perScript, maxScripts);
    }

    private void runBackground() {
        while (!background.isEmpty()) {
            background.poll().run();
        }
    }

    @Test
    void firstTakeMissesWithoutEvaluatingOnTheCallerAndLaterTakesHit() {
        CosmicPreparedNpcScriptEngines engines = engines(2, 8);

        assertNull(engines.take(LAKELIS));
        assertEquals(List.of(), evaluated, "a take must never evaluate on the calling (tick) thread");

        runBackground();
        assertEquals(List.of(LAKELIS, LAKELIS), evaluated);
        assertSame(created.get(0), engines.take(LAKELIS));
        assertSame(created.get(1), engines.take(LAKELIS));

        runBackground();
        assertEquals(4, evaluated.size(), "each take tops the queue back up");
        assertNotNull(engines.take(LAKELIS));
    }

    @Test
    void prepareFillsAheadSoTheFirstConversationHits() {
        CosmicPreparedNpcScriptEngines engines = engines(2, 8);

        engines.prepare(CLOTO);
        engines.prepare(CLOTO); // a repeated hint does not queue a second fill
        assertEquals(1, background.size());
        runBackground();

        assertSame(created.get(0), engines.take(CLOTO));
        assertEquals(1, engines.snapshot().ready());
    }

    @Test
    void enginesEvaluatedBeforeAScriptEditAreDiscarded() {
        CosmicPreparedNpcScriptEngines engines = engines(2, 8);
        engines.prepare(LAKELIS);
        runBackground();

        modified.put(LAKELIS, 2L);
        assertNull(engines.take(LAKELIS), "a stale engine would run the pre-edit script");
        runBackground();

        assertSame(created.get(2), engines.take(LAKELIS));
    }

    @Test
    void aScriptThatDoesNotEvaluateLeavesTheCallerOnItsOwnPath() {
        CosmicPreparedNpcScriptEngines engines = new CosmicPreparedNpcScriptEngines(path -> {
            evaluated.add(path);
            return null;
        }, path -> -1L, background::add, 2, 8);

        engines.prepare("npc/404.js");
        runBackground();

        assertEquals(List.of("npc/404.js"), evaluated, "one failed evaluation, no retry loop");
        assertNull(engines.take("npc/404.js"));
    }

    @Test
    void scriptsBeyondTheLimitAreNotPrepared() {
        CosmicPreparedNpcScriptEngines engines = engines(1, 1);
        engines.prepare(LAKELIS);
        engines.prepare(CLOTO);
        runBackground();

        assertEquals(List.of(LAKELIS), evaluated);
        assertNull(engines.take(CLOTO));
        assertEquals(1, engines.snapshot().scripts());
    }

    @Test
    void productionPrepThreadResolvesScriptHostClassesWhicheverThreadStartsIt() throws Exception {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        // The KPQ entry script's top level calls Java.type(...) on an Agent class; GraalJS resolves it through the
        // context loader of the thread that evaluates, so a prep thread spawned from a foreign-loader thread must not inherit it.
        Thread foreign = new Thread(() -> CosmicPreparedNpcScriptEngines.INSTANCE.prepare(LAKELIS));
        foreign.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        foreign.start();
        foreign.join();

        ScriptEngine prepared = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (prepared == null && System.nanoTime() < deadline) {
            if (CosmicPreparedNpcScriptEngines.INSTANCE.snapshot().ready() > 0) {
                prepared = CosmicPreparedNpcScriptEngines.INSTANCE.take(LAKELIS);
            } else {
                Thread.sleep(20L);
            }
        }
        assertNotNull(prepared, "the KPQ entry script failed to evaluate on the prep thread");
    }

    @Test
    void runsAreCountedAsHitsOrMisses() {
        CosmicPreparedNpcScriptEngines engines = engines(1, 8);

        engines.recordRun(true, 2_000_000L);
        engines.recordRun(false, 15_000_000L);
        engines.recordRun(false, 5_000_000L);

        CosmicPreparedNpcScriptEngines.Stats stats = engines.snapshot();
        assertEquals(1, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(2_000, stats.hitRunMaxUs());
        assertEquals(15_000, stats.missRunMaxUs());
        assertEquals(20_000, stats.missRunTotalUs());
    }
}
