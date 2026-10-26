package server.agents.runtime.scheduler;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

/**
 * Offline tick-lag and CPU bench for the Agent scheduler modes (docs/SCALE_PLATFORM_PLAN.md section 5). Opt-in:
 * {@code -DrunBenchmarks=true -Dtest=AgentSchedulerLagBench}. Real AgentTickScheduler shards run on real executors;
 * each synthetic Agent burns a log-normal ordinary tick cost and, at the live rate, a slow tick drawn from the live
 * slow-tick distribution (logs/cosmic-log.log, 2026-10-25 20:06-21:12, 406 ticks of 250 ms or more over 66 minutes
 * with 248 Agents). A probe timer shares the Cosmic CORE-like pool, and an outside thread posts mailbox actions.
 * Results print and go to target/scale-bench/agent-scheduler-lag.txt.
 *
 * <p>Knobs: bench.seconds (per run, default 60), bench.scenarios (comma list of live,players,stress), bench.modes
 * (comma list of sequential,sharded-shared,sharded-dedicated), bench.slowFactor (multiplies the live slow-tick
 * rate, default 1), bench.repeats (interleaved rounds, default 2).
 */
@EnabledIfSystemProperty(named = "runBenchmarks", matches = "true")
class AgentSchedulerLagBench {
    private static final int CORE_THREADS = 4;
    private static final int SHARDS = 4;
    private static final long PROBE_PERIOD_MS = 50L;
    private static final long MAILBOX_PERIOD_US = 5_000L;
    /** Live slow ticks per Agent-hour: 406 over 66 min with 248 Agents. */
    private static final double SLOW_PER_AGENT_HOUR = 406.0 / (66.0 / 60.0) / 248.0;
    /** Live slow-tick quantiles (every 2% from 2% to 98%), ms. */
    private static final int[] SLOW_QUANTILES_MS = {
            253, 256, 259, 265, 273, 276, 280, 285, 292, 300, 311, 317, 327, 332, 342, 350, 363, 371, 397, 409,
            419, 434, 443, 450, 457, 465, 467, 473, 477, 483, 487, 491, 497, 503, 511, 522, 533, 539, 550, 564,
            586, 609, 626, 657, 677, 709, 757, 869, 1118};
    /** The ten longest live slow ticks, ms: each weighs 1/406. */
    private static final int[] SLOW_TOP_MS = {1118, 1258, 1485, 1793, 2060, 2080, 2653, 6508, 7511, 13432};
    /** Ordinary tick cost: log-normal, median 150 us, sigma 1.1 (July server-only gates: work p50 93-165 us). */
    private static final double BASE_MEDIAN_US = 150.0;
    private static final double BASE_SIGMA = 1.1;

    private record Scenario(String name, int agents, int presentationAgents) {
    }

    private record Result(String scenario,
                          String mode,
                          int agents,
                          long seconds,
                          long ticks,
                          long[] overrunMs,
                          double lateOver250Percent,
                          long[] probeLateMs,
                          long[] mailboxUs,
                          int slowInjected,
                          double processCores,
                          double burnedCores,
                          double overheadCores,
                          double gcPercent) {
    }

    @AfterEach
    void tearDown() {
        AgentRuntimeRegistry.clear();
    }

    @Test
    void measureSchedulerModes() throws Exception {
        long seconds = Long.getLong("bench.seconds", 60L);
        int repeats = Integer.getInteger("bench.repeats", 2);
        double slowFactor = Double.parseDouble(System.getProperty("bench.slowFactor", "1"));
        List<Scenario> scenarios = new ArrayList<>();
        for (String name : System.getProperty("bench.scenarios", "live,players").split(",")) {
            scenarios.add(switch (name.trim()) {
                case "live" -> new Scenario("live", 248, 0);
                case "players" -> new Scenario("players", 600, 120);
                case "stress" -> new Scenario("stress", 1_000, 200);
                default -> throw new IllegalArgumentException("unknown scenario " + name);
            });
        }
        List<String> modes = Arrays.stream(System.getProperty(
                        "bench.modes", "sequential,sharded-shared,sharded-dedicated").split(","))
                .map(String::trim).toList();
        List<Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            for (int round = 0; round < repeats; round++) {
                for (String mode : modes) {
                    Result result = run(scenario, mode, seconds, slowFactor, 0x5ca1eL + round);
                    results.add(result);
                    System.out.println(line(result));
                }
            }
        }
        write(results, seconds, slowFactor);
        assertTrue(results.stream().allMatch(result -> result.ticks() > 0));
    }

    private Result run(Scenario scenario, String mode, long seconds, double slowFactor, long seed) throws Exception {
        AgentRuntimeRegistry.clear();
        ScheduledThreadPoolExecutor core = pool("bench-core", CORE_THREADS);
        ScheduledThreadPoolExecutor agentLane = mode.equals("sharded-dedicated") ? pool("bench-agent", SHARDS) : null;
        ScheduledExecutorService driver = Executors.newScheduledThreadPool(2);
        ScheduledThreadPoolExecutor loops = agentLane != null ? agentLane : core;
        BiFunction<Runnable, Long, ScheduledFuture<?>> loop =
                (task, periodMs) -> loops.scheduleAtFixedRate(task, periodMs, periodMs, TimeUnit.MILLISECONDS);
        BiFunction<Runnable, Long, ScheduledFuture<?>> wake =
                (task, delayMs) -> loops.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        List<AgentTickScheduler> shards = new ArrayList<>();
        if (mode.equals("sequential")) {
            shards.add(new AgentTickScheduler(System::currentTimeMillis, System::nanoTime, loop, wake,
                    config(AgentSchedulerMode.CENTRAL_SEQUENTIAL, 1), 0));
        } else {
            for (int shardId = 0; shardId < SHARDS; shardId++) {
                shards.add(new AgentTickScheduler(System::currentTimeMillis, System::nanoTime, loop, wake,
                        config(AgentSchedulerMode.CENTRAL_SHARDED, SHARDS), shardId));
            }
        }
        AgentShardedTickScheduler sharded = new AgentShardedTickScheduler(shards);

        int agents = scenario.agents();
        long[] lastStartNs = new long[agents + 1];
        long[] periodNs = new long[agents + 1];
        AtomicLongArray slowPendingNs = new AtomicLongArray(agents + 1);
        AtomicLongArray overrun = new AtomicLongArray(20_001);
        LongAdder ticks = new LongAdder();
        LongAdder late = new LongAdder();
        LongAdder burnedNs = new LongAdder();
        AtomicLongArray probe = new AtomicLongArray(20_001);
        long[] mailbox = new long[(int) (seconds * 1_000_000L / MAILBOX_PERIOD_US) + 16];
        AtomicInteger mailboxCount = new AtomicInteger();
        List<AgentRuntimeEntry> entries = new ArrayList<>(agents);
        List<AgentScheduleHandle> handles = new ArrayList<>(agents);
        SplittableRandom setup = new SplittableRandom(seed);
        long measureFromNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        for (int agentId = 1; agentId <= agents; agentId++) {
            int id = agentId;
            boolean presentation = agentId <= scenario.presentationAgents();
            long periodMs = presentation ? 50L : 250L;
            periodNs[id] = TimeUnit.MILLISECONDS.toNanos(periodMs);
            AgentRuntimeEntry entry = entry(agentId);
            AgentRuntimeRegistry.registerEntry(agentId, entry);
            entries.add(entry);
            long baseSeed = setup.nextLong();
            SplittableRandom costs = new SplittableRandom(baseSeed);
            Runnable tick = () -> {
                long started = System.nanoTime();
                AgentMailboxRuntime.drain(entry);
                long previous = lastStartNs[id];
                lastStartNs[id] = started;
                if (previous != 0L && started >= measureFromNs) {
                    long overrunMs = Math.max(0L, started - previous - periodNs[id]) / 1_000_000L;
                    overrun.incrementAndGet((int) Math.min(20_000L, overrunMs));
                    ticks.increment();
                    if (overrunMs > 250L) {
                        late.increment();
                    }
                }
                long cost = slowPendingNs.getAndSet(id, 0L);
                if (cost == 0L) { // one Agent's ticks never overlap, so its own random needs no lock
                    cost = (long) (BASE_MEDIAN_US * Math.exp(BASE_SIGMA * gaussian(costs)) * 1_000.0);
                }
                burn(started, cost);
                burnedNs.add(cost);
            };
            AgentScheduleHandle handle = presentation
                    ? sharded.register(entry, tick, periodMs)
                    : registerBackground(shards, entry, tick, periodMs);
            entry.scheduledTaskState().attachScheduledTask(handle);
            handles.add(handle);
        }

        // probe: a Cosmic core timer on the shared pool; how late does it run?
        long probeStart = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_PERIOD_MS);
        AtomicLongArray probeRuns = new AtomicLongArray(1);
        ScheduledFuture<?> probeTask = core.scheduleAtFixedRate(() -> {
            long run = probeRuns.getAndIncrement(0);
            long now = System.nanoTime();
            if (now >= measureFromNs) {
                long lateMs = Math.max(0L, now - (probeStart + run * TimeUnit.MILLISECONDS.toNanos(PROBE_PERIOD_MS)))
                        / 1_000_000L;
                probe.incrementAndGet((int) Math.min(20_000L, lateMs));
            }
        }, PROBE_PERIOD_MS, PROBE_PERIOD_MS, TimeUnit.MILLISECONDS);

        // slow ticks at the live rate (Poisson), each on a random Agent's next tick
        SplittableRandom slow = new SplittableRandom(seed ^ 0x51ab);
        double perSecond = SLOW_PER_AGENT_HOUR * agents / 3_600.0 * slowFactor;
        AtomicInteger injected = new AtomicInteger();
        Runnable[] inject = new Runnable[1];
        inject[0] = () -> {
            int victim = 1 + slow.nextInt(agents);
            slowPendingNs.set(victim, TimeUnit.MILLISECONDS.toNanos(slowTickMs(slow)));
            injected.incrementAndGet();
            driver.schedule(inject[0], nextGapMs(slow, perSecond), TimeUnit.MILLISECONDS);
        };
        driver.schedule(inject[0], nextGapMs(slow, perSecond), TimeUnit.MILLISECONDS);

        // mailbox: an outside thread posts one action every 5 ms to a random Agent
        ScheduledFuture<?> mailboxTask = driver.scheduleAtFixedRate(() -> {
            AgentRuntimeEntry target = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
            long submitted = System.nanoTime();
            if (submitted < measureFromNs) {
                return;
            }
            AgentMailboxRuntime.submit(target, ignored -> {
                int slot = mailboxCount.getAndIncrement();
                if (slot < mailbox.length) {
                    mailbox[slot] = (System.nanoTime() - submitted) / 1_000L;
                }
                return null;
            });
        }, MAILBOX_PERIOD_US, MAILBOX_PERIOD_US, TimeUnit.MICROSECONDS);

        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Thread.sleep(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(measureFromNs - System.nanoTime())));
        long gcStart = gcMillis();
        long cpuStart = os.getProcessCpuTime();
        long burnedStart = burnedNs.sum();
        long wallStart = System.nanoTime();
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        long wall = System.nanoTime() - wallStart;
        long cpu = os.getProcessCpuTime() - cpuStart;
        long burned = burnedNs.sum() - burnedStart;
        long gc = gcMillis() - gcStart;

        mailboxTask.cancel(false);
        probeTask.cancel(false);
        driver.shutdownNow();
        handles.forEach(handle -> handle.cancel(false));
        shards.forEach(AgentTickScheduler::tickAll);
        core.shutdownNow();
        if (agentLane != null) {
            agentLane.shutdownNow();
        }
        core.awaitTermination(30, TimeUnit.SECONDS);
        if (agentLane != null) {
            agentLane.awaitTermination(30, TimeUnit.SECONDS);
        }

        int delivered = Math.min(mailboxCount.get(), mailbox.length);
        long[] mailboxSorted = Arrays.copyOf(mailbox, delivered);
        Arrays.sort(mailboxSorted);
        long tickCount = ticks.sum();
        return new Result(scenario.name(), mode, agents, seconds, tickCount,
                percentiles(overrun),
                tickCount == 0 ? 0.0 : 100.0 * late.sum() / tickCount,
                percentiles(probe),
                new long[]{at(mailboxSorted, 0.50), at(mailboxSorted, 0.95), at(mailboxSorted, 0.99),
                        delivered == 0 ? 0L : mailboxSorted[delivered - 1]},
                injected.get(),
                (double) cpu / wall,
                (double) burned / wall,
                (double) (cpu - burned) / wall,
                100.0 * gc / TimeUnit.NANOSECONDS.toMillis(wall));
    }

    private static AgentScheduleHandle registerBackground(List<AgentTickScheduler> shards,
                                                          AgentRuntimeEntry entry,
                                                          Runnable tick,
                                                          long periodMs) {
        AgentTickScheduler shard = shards.get(shards.size() == 1
                ? 0 : AgentShardedTickScheduler.shardIndex(AgentSessionId.from(entry), shards.size()));
        return shard.register(entry, tick, periodMs, AgentWorkClass.BACKGROUND_GAMEPLAY,
                AgentPriorityClass.BACKGROUND_ACTIVE);
    }

    private static AgentSchedulerConfig config(AgentSchedulerMode mode, int shardCount) {
        // The shipped scheduler values (agent-engine.yaml AgentSchedulerConfig.DEFAULT_*), simulation tiers off: the
        // bench gives each Agent its tier's cadence directly.
        return new AgentSchedulerConfig(mode, 50L, false, 250L, 0, 4_096, 10L, 256, 40, 10, 2_000L, shardCount);
    }

    private static ScheduledThreadPoolExecutor pool(String name, int threads) {
        AtomicInteger number = new AtomicInteger(1);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, runnable -> {
            Thread thread = new Thread(runnable, name + "-" + number.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static AgentRuntimeEntry entry(int agentId) {
        // stub-only: a recording mock keeps every getId/getMap call the scheduler makes and the heap fills (GC thrash)
        Character agent = mock(Character.class, withSettings().stubOnly());
        when(agent.getId()).thenReturn(agentId);
        return new AgentRuntimeEntry(agent, null, null);
    }

    private static long gcMillis() {
        long total = 0L;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, collector.getCollectionTime());
        }
        return total;
    }

    private static void burn(long startedNs, long costNs) {
        long until = startedNs + costNs;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    private static double gaussian(SplittableRandom random) {
        double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private static long slowTickMs(SplittableRandom random) {
        double u = random.nextDouble();
        double topShare = SLOW_TOP_MS.length / 406.0;
        if (u >= 1.0 - topShare) {
            return SLOW_TOP_MS[random.nextInt(SLOW_TOP_MS.length)];
        }
        double position = u / (1.0 - topShare) * (SLOW_QUANTILES_MS.length - 1);
        int low = (int) position;
        int high = Math.min(SLOW_QUANTILES_MS.length - 1, low + 1);
        double fraction = position - low;
        return Math.round(SLOW_QUANTILES_MS[low] + fraction * (SLOW_QUANTILES_MS[high] - SLOW_QUANTILES_MS[low]));
    }

    private static long nextGapMs(SplittableRandom random, double perSecond) {
        return Math.max(1L, Math.round(-Math.log(1.0 - random.nextDouble()) / perSecond * 1_000.0));
    }

    /** p50, p95, p99, p99.9, max of a 1 ms histogram. */
    private static long[] percentiles(AtomicLongArray histogram) {
        long total = 0L;
        for (int i = 0; i < histogram.length(); i++) {
            total += histogram.get(i);
        }
        double[] quantiles = {0.50, 0.95, 0.99, 0.999};
        long[] out = new long[quantiles.length + 1];
        long seen = 0L;
        int next = 0;
        for (int i = 0; i < histogram.length(); i++) {
            seen += histogram.get(i);
            while (next < quantiles.length && total > 0 && seen >= Math.ceil(quantiles[next] * total)) {
                out[next++] = i;
            }
            if (histogram.get(i) > 0) {
                out[quantiles.length] = i;
            }
        }
        return out;
    }

    private static long at(long[] sorted, double quantile) {
        if (sorted.length == 0) {
            return 0L;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private static String line(Result result) {
        return String.format(Locale.ROOT,
                "%-8s %-17s agents=%4d ticks=%8d overrunMs p50/p95/p99/p99.9/max=%d/%d/%d/%d/%d late>250ms=%.3f%%"
                        + " probeLateMs p50/p95/p99/p99.9/max=%d/%d/%d/%d/%d mailboxUs p50/p95/p99/max=%d/%d/%d/%d"
                        + " slow=%d cpuCores=%.3f burned=%.3f overhead=%.3f gc=%.1f%%",
                result.scenario(), result.mode(), result.agents(), result.ticks(),
                result.overrunMs()[0], result.overrunMs()[1], result.overrunMs()[2], result.overrunMs()[3],
                result.overrunMs()[4], result.lateOver250Percent(),
                result.probeLateMs()[0], result.probeLateMs()[1], result.probeLateMs()[2], result.probeLateMs()[3],
                result.probeLateMs()[4],
                result.mailboxUs()[0], result.mailboxUs()[1], result.mailboxUs()[2], result.mailboxUs()[3],
                result.slowInjected(), result.processCores(), result.burnedCores(), result.overheadCores(),
                result.gcPercent());
    }

    private static void write(List<Result> results, long seconds, double slowFactor) throws IOException {
        Path out = Path.of("target", "scale-bench", "agent-scheduler-lag.txt");
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# AgentSchedulerLagBench seconds=" + seconds + " slowFactor=" + slowFactor
                + " cpus=" + Runtime.getRuntime().availableProcessors());
        results.forEach(result -> lines.add(line(result)));
        Files.write(out, lines);
    }
}
