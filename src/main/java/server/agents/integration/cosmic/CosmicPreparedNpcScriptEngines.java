package server.agents.integration.cosmic;

import scripting.npc.NPCScriptManager;
import server.agents.runtime.async.AgentAsyncExecutorRegistry;
import server.agents.runtime.async.AgentAsyncWorkKind;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Keeps freshly evaluated NPC script engines ready for headless Agent conversations, so the central Agent
 * tick thread no longer creates and evaluates a GraalJS context per conversation (10-20 ms warm, several
 * hundred ms on a script's first parse). Engines are evaluated on a background thread but never invoked, so
 * a conversation on one behaves exactly like one that evaluated its own. A miss returns null and the caller
 * evaluates synchronously, as before; every take tops the script's queue back up.
 */
public final class CosmicPreparedNpcScriptEngines {
    private static final int ENGINES_PER_SCRIPT = config.AgentTuning.intValue(
            "server.agents.integration.cosmic.CosmicPreparedNpcScriptEngines.ENGINES_PER_SCRIPT");
    private static final int MAX_SCRIPTS = config.AgentTuning.intValue(
            "server.agents.integration.cosmic.CosmicPreparedNpcScriptEngines.MAX_SCRIPTS");

    static final CosmicPreparedNpcScriptEngines INSTANCE = new CosmicPreparedNpcScriptEngines(
            CosmicPreparedNpcScriptEngines::evaluateOnPrepThread,
            CosmicPreparedNpcScriptEngines::lastModifiedMs,
            task -> AgentAsyncExecutorRegistry.runtime().execute(AgentAsyncWorkKind.NPC_SCRIPT_PREPARATION, task),
            ENGINES_PER_SCRIPT,
            MAX_SCRIPTS);

    public record Stats(long hits, long misses, long hitRunMaxUs, long missRunMaxUs, long hitRunTotalUs,
                        long missRunTotalUs, long evaluations, long evaluationMaxUs, int scripts, int ready) {
    }

    private record Prepared(ScriptEngine engine, long scriptModifiedMs) {
    }

    private final Function<String, ScriptEngine> evaluator;
    private final ToLongFunction<String> scriptModifiedMs;
    private final Executor preparer;
    private final int enginesPerScript;
    private final int maxScripts;
    private final Map<String, Queue<Prepared>> ready = new ConcurrentHashMap<>();
    private final Set<String> refilling = ConcurrentHashMap.newKeySet();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong hitRunMaxNs = new AtomicLong();
    private final AtomicLong missRunMaxNs = new AtomicLong();
    private final AtomicLong hitRunTotalNs = new AtomicLong();
    private final AtomicLong missRunTotalNs = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong evaluationMaxNs = new AtomicLong();

    CosmicPreparedNpcScriptEngines(Function<String, ScriptEngine> evaluator, ToLongFunction<String> scriptModifiedMs,
                                   Executor preparer, int enginesPerScript, int maxScripts) {
        this.evaluator = evaluator;
        this.scriptModifiedMs = scriptModifiedMs;
        this.preparer = preparer;
        this.enginesPerScript = enginesPerScript;
        this.maxScripts = maxScripts;
    }

    public static Stats stats() {
        return INSTANCE.snapshot();
    }

    /** Returns an evaluated, never-invoked engine for {@code path}, or null; either way queues a refill. */
    ScriptEngine take(String path) {
        Queue<Prepared> queue = queue(path);
        if (queue == null) {
            return null;
        }
        Prepared prepared = queue.poll();
        if (prepared != null && prepared.scriptModifiedMs() != scriptModifiedMs.applyAsLong(path)) {
            queue.clear(); // the script was edited after these were evaluated
            prepared = null;
        }
        refill(path, queue);
        return prepared == null ? null : prepared.engine();
    }

    /** Starts evaluating engines for {@code path} ahead of its first conversation. */
    void prepare(String path) {
        Queue<Prepared> queue = queue(path);
        if (queue != null) {
            refill(path, queue);
        }
    }

    void recordRun(boolean prepared, long elapsedNs) {
        (prepared ? hits : misses).incrementAndGet();
        (prepared ? hitRunTotalNs : missRunTotalNs).addAndGet(elapsedNs);
        (prepared ? hitRunMaxNs : missRunMaxNs).accumulateAndGet(elapsedNs, Math::max);
    }

    Stats snapshot() {
        int readyEngines = 0;
        for (Queue<Prepared> queue : ready.values()) {
            readyEngines += queue.size();
        }
        return new Stats(hits.get(), misses.get(), hitRunMaxNs.get() / 1_000L, missRunMaxNs.get() / 1_000L,
                hitRunTotalNs.get() / 1_000L, missRunTotalNs.get() / 1_000L, evaluations.get(),
                evaluationMaxNs.get() / 1_000L, ready.size(), readyEngines);
    }

    private Queue<Prepared> queue(String path) {
        Queue<Prepared> queue = ready.get(path);
        if (queue != null || ready.size() >= maxScripts) {
            return queue;
        }
        return ready.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>());
    }

    private void refill(String path, Queue<Prepared> queue) {
        if (queue.size() >= enginesPerScript || !refilling.add(path)) {
            return;
        }
        try {
            preparer.execute(() -> fill(path, queue));
        } catch (RejectedExecutionException rejected) {
            refilling.remove(path);
        }
    }

    private void fill(String path, Queue<Prepared> queue) {
        boolean evaluated = true;
        try {
            while (evaluated && queue.size() < enginesPerScript) {
                long modifiedMs = scriptModifiedMs.applyAsLong(path);
                long startedNs = System.nanoTime();
                ScriptEngine engine = evaluator.apply(path);
                long elapsedNs = System.nanoTime() - startedNs;
                evaluations.incrementAndGet();
                evaluationMaxNs.accumulateAndGet(elapsedNs, Math::max);
                evaluated = engine != null; // a missing or broken script falls back to the caller's evaluation
                if (evaluated) {
                    queue.add(new Prepared(engine, modifiedMs));
                }
            }
        } finally {
            refilling.remove(path);
        }
        if (evaluated && queue.size() < enginesPerScript) {
            refill(path, queue); // a take raced the end of this fill
        }
    }

    private static ScriptEngine evaluateOnPrepThread(String path) {
        // GraalJS resolves a script's Java.type(...) through the evaluating thread's context loader, and the lane's
        // worker inherits it from whichever thread first started the lane, so pin the server's loader.
        Thread.currentThread().setContextClassLoader(CosmicPreparedNpcScriptEngines.class.getClassLoader());
        return NPCScriptManager.getInstance().prepareScriptEngine(path);
    }

    private static long lastModifiedMs(String path) {
        try {
            return Files.getLastModifiedTime(Path.of("scripts", path)).toMillis();
        } catch (IOException missing) {
            return -1L;
        }
    }
}
