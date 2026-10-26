package server.agents.runtime.activity.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentFileWorldDirectiveInbox;
import server.agents.runtime.activity.world.AgentFileWorldDirectorSessionStore;
import server.agents.runtime.activity.world.AgentWorldActivityRequestType;
import server.agents.runtime.activity.world.AgentWorldCompletionPolicy;
import server.agents.runtime.activity.world.AgentWorldContext;
import server.agents.runtime.activity.world.AgentWorldDirective;
import server.agents.runtime.activity.world.AgentWorldDirectiveSource;
import server.agents.runtime.activity.world.AgentWorldDirectiveStatus;
import server.agents.runtime.activity.world.AgentWorldDirectiveType;
import server.agents.runtime.activity.world.AgentWorldDirectorMode;
import server.agents.runtime.activity.world.AgentWorldDirectorSession;
import server.agents.runtime.activity.world.AgentWorldInterruptionPolicy;
import server.agents.runtime.activity.world.AgentWorldShadowEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Offline cost of the World Director per Agent (docs/SCALE_PLATFORM_PLAN.md section 5). Opt-in:
 * {@code -DrunBenchmarks=true -Dtest=AgentWorldDirectorCostBench}. Times the parts that run on an Agent's tick once
 * a Director session is on: the Observe evaluation (every 5 s in OBSERVE, ASSISTED and AUTONOMOUS), the directive
 * poll's file reads (every 1 s in MANUAL, ASSISTED and AUTONOMOUS: session load plus inbox scan), and the evidence
 * checkpoint write (every 60 samples). The Cosmic context capture needs a live Character and is not in here.
 * Results print and go to target/scale-bench/world-director-cost.txt with the projected cores for 248 Agents.
 */
@EnabledIfSystemProperty(named = "runBenchmarks", matches = "true")
class AgentWorldDirectorCostBench {
    private static final int AGENTS = 248;
    private static final int ROUNDS = 40;

    @TempDir Path directory;

    @Test
    void measureDirectorCostPerAgent() throws IOException {
        AgentWorldShadowEvaluator evaluator = AgentWorldShadowEvaluator.baseline();
        AgentFileWorldDirectorSessionStore sessions = new AgentFileWorldDirectorSessionStore(directory.resolve("s"));
        AgentFileWorldDirectiveInbox inbox = new AgentFileWorldDirectiveInbox(directory.resolve("d"));
        AgentWorldDirectorObserveEvidenceStore evidence =
                new AgentWorldDirectorObserveEvidenceStore(directory.resolve("e"));
        List<AgentWorldContext> contexts = new ArrayList<>();
        for (int agent = 1; agent <= AGENTS; agent++) {
            contexts.add(context(agent));
            sessions.save(AgentWorldDirectorSession.create(agent, AgentWorldDirectorMode.AUTONOMOUS, 1_000L));
            for (int old = 0; old < 4; old++) { // a few finished directives in each inbox, as a live Agent keeps
                AgentWorldDirective directive = directive(agent, "old-" + old);
                inbox.submit(directive, 1_000L + old);
                inbox.resolve(agent, directive.directiveId(), AgentWorldDirectiveStatus.COMPLETED, "done", 2_000L);
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# AgentWorldDirectorCostBench agents=" + AGENTS + " rounds=" + ROUNDS);
        long[] observe = time(index -> evaluator.evaluate(contexts.get(index)));
        long[] poll = time(index -> {
            sessions.load(index + 1);
            inbox.nextPending(index + 1, 10_000L);
        });
        long[] stamp = time(index -> {
            if (31L * inbox.changeStamp(index + 1) + sessions.changeStamp(index + 1) == 42L) {
                throw new AssertionError();
            }
        });
        long[] checkpoint = time(index -> evidence.save(index + 1, 60L * (index + 1)));
        // per Agent per second: one Observe sample every 5 s, one poll every 1 s, one checkpoint every 300 s
        double observeCores = AGENTS * (median(observe) / 1e9) / 5.0;
        double pollCores = AGENTS * (median(poll) / 1e9);
        // idle Agents after the change stamp: a stamp check every 1 s, a full read every 30 s (IDLE_FULL_POLL_MS)
        double stampCores = AGENTS * (median(stamp) / 1e9 + median(poll) / 1e9 / 30.0);
        double checkpointCores = AGENTS * (median(checkpoint) / 1e9) / 300.0;
        lines.add(row("observe-evaluate", observe, observeCores));
        lines.add(row("directive-poll-io", poll, pollCores));
        lines.add(row("poll-change-stamp", stamp, stampCores));
        lines.add(row("evidence-checkpoint", checkpoint, checkpointCores));
        lines.add(String.format(Locale.ROOT,
                "projected cores for %d idle Agents in AUTONOMOUS (sample 5 s, poll 1 s): before %.4f, with the change"
                        + " stamp %.4f (excludes context capture)",
                AGENTS, observeCores + pollCores + checkpointCores, observeCores + stampCores + checkpointCores));
        lines.forEach(System.out::println);
        Path out = Path.of("target", "scale-bench", "world-director-cost.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    /** Runs {@code step} for every Agent, ROUNDS times (first round warms up), and returns sorted ns per step. */
    private static long[] time(IntConsumer step) {
        long[] samples = new long[AGENTS * (ROUNDS - 1)];
        int at = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (int index = 0; index < AGENTS; index++) {
                long started = System.nanoTime();
                step.accept(index);
                long took = System.nanoTime() - started;
                if (round > 0) {
                    samples[at++] = took;
                }
            }
        }
        Arrays.sort(samples);
        return samples;
    }

    private static long median(long[] sorted) {
        return sorted[sorted.length / 2];
    }

    private static String row(String name, long[] sorted, double cores) {
        return String.format(Locale.ROOT, "%-20s us p50/p95/p99/max=%.1f/%.1f/%.1f/%.1f cores@%d=%.4f", name,
                sorted[sorted.length / 2] / 1e3, sorted[(int) (sorted.length * 0.95)] / 1e3,
                sorted[(int) (sorted.length * 0.99)] / 1e3, sorted[sorted.length - 1] / 1e3, AGENTS, cores);
    }

    private static AgentWorldContext context(int agent) {
        int level = 1 + (agent * 7) % 120;
        int mapId = new int[]{100000000, 101000000, 102000000, 103000000, 104000000, 910000000}[agent % 6];
        return new AgentWorldContext(agent, 1_000L, agent, "Agent" + agent, level, level < 10 ? 0 : 100 + agent % 5 * 100,
                mapId, 500, 500, 300, 300, 50_000L * level, true, false, Set.of(), Set.of(),
                agent % 3 == 0 ? AgentActivityKind.HUNTING : null, "", "", "",
                level < 10 ? "BEGINNER" : level < 30 ? "FIRST_JOB" : "SECOND_JOB", Map.of());
    }

    private static AgentWorldDirective directive(int agent, String id) {
        return new AgentWorldDirective(1, id, agent, AgentWorldDirectiveType.START_ACTIVITY,
                AgentWorldDirectiveSource.OPERATOR, null, AgentActivityKind.HUNTING,
                AgentWorldActivityRequestType.AUTHORED_PLAN, "plan-" + id, Map.of(),
                AgentWorldInterruptionPolicy.WAIT_FOR_SAFE_BOUNDARY, AgentWorldCompletionPolicy.REQUEST_NEXT_DECISION,
                100, 1_000L, 0L, "bench");
    }
}
