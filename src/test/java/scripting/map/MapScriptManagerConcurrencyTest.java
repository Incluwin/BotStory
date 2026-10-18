package scripting.map;

import org.junit.jupiter.api.Test;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.SimpleBindings;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One map script engine is shared by every player entering maps that use it. A GraalJS context throws when a second
 * thread enters it, and that exception used to escape into the map change; the engine is now serialized and script
 * failures stay inside the map script manager.
 */
class MapScriptManagerConcurrencyTest {
    private static final String SCRIPT = "onUserEnter/explorationPoint";

    @Test
    void aCachedScriptIsNeverEnteredByTwoThreadsAtOnce() throws Exception {
        FakeEngineManager manager = new FakeEngineManager(false, null);
        assertTrue(manager.runMapScript(null, SCRIPT, false));

        List<Boolean> results = runConcurrently(12, () -> manager.runMapScript(null, SCRIPT, false));

        assertEquals(List.of(true, true, true, true, true, true, true, true, true, true, true, true), results);
        assertEquals(1, manager.created.size());
        assertEquals(13, manager.created.get(0).calls.get());
        assertEquals(1, manager.created.get(0).maximumConcurrent.get());
    }

    @Test
    void aFailureInsideTheScriptDoesNotEscapeTheMapChange() {
        FakeEngineManager manager = new FakeEngineManager(true, null);

        assertFalse(manager.runMapScript(null, SCRIPT, false));    // first load
        assertFalse(manager.runMapScript(null, SCRIPT, false));    // cached engine
    }

    @Test
    void playersLoadingTheSameScriptAtOnceShareOneEngine() throws Exception {
        FakeEngineManager manager = new FakeEngineManager(false, new CyclicBarrier(2));

        List<Boolean> results = runConcurrently(2, () -> manager.runMapScript(null, SCRIPT, false));
        assertEquals(List.of(true, true), results);
        assertTrue(manager.runMapScript(null, SCRIPT, false));

        assertEquals(2, manager.created.size());
        int first = manager.created.get(0).calls.get();
        int second = manager.created.get(1).calls.get();
        assertEquals(3, first + second);
        assertTrue(first == 0 || second == 0, "both loaders must run the one cached engine, got " + first + "/" + second);
    }

    private static List<Boolean> runConcurrently(int callers, java.util.concurrent.Callable<Boolean> call)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakeEngineManager extends MapScriptManager {
        private final boolean failing;
        private final CyclicBarrier loadBarrier;
        private final List<FakeEngine> created = new CopyOnWriteArrayList<>();

        private FakeEngineManager(boolean failing, CyclicBarrier loadBarrier) {
            this.failing = failing;
            this.loadBarrier = loadBarrier;
        }

        @Override
        protected ScriptEngine getInvocableScriptEngine(String path) {
            FakeEngine engine = new FakeEngine(failing);
            created.add(engine);
            if (loadBarrier != null) {
                try {
                    loadBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return engine;
        }
    }

    /** Behaves like a GraalJS context: entering it from a second thread while one is inside throws. */
    private static final class FakeEngine extends AbstractScriptEngine implements Invocable {
        private final boolean failing;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumConcurrent = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        private FakeEngine(boolean failing) {
            this.failing = failing;
        }

        @Override
        public Object invokeFunction(String name, Object... args) {
            int now = active.incrementAndGet();
            maximumConcurrent.accumulateAndGet(now, Math::max);
            calls.incrementAndGet();
            try {
                if (failing) {
                    throw new IllegalStateException("script failed");
                }
                Thread.sleep(10L);
                if (now > 1) {
                    throw new IllegalStateException("Multi threaded access requested by thread "
                            + Thread.currentThread() + " but is not allowed for language(s) js.");
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public Object invokeMethod(Object thiz, String name, Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getInterface(Class<T> clasz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getInterface(Object thiz, Class<T> clasz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object eval(String script, ScriptContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object eval(Reader reader, ScriptContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bindings createBindings() {
            return new SimpleBindings();
        }

        @Override
        public ScriptEngineFactory getFactory() {
            throw new UnsupportedOperationException();
        }
    }
}
