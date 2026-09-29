package server.admin;

import client.BotClient;
import client.Character;
import net.server.Server;
import net.server.world.World;
import server.TimerManager;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicPreparedNpcScriptEngines;
import server.agents.monitoring.AgentSchedulerMetrics;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.scheduler.AgentLoadSheddingLevel;
import server.agents.runtime.scheduler.AgentLoadSheddingState;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.agents.runtime.scheduler.AgentShardedTickScheduler;
import server.agents.runtime.scheduler.AgentTickScheduler;
import server.living.LivingWorld;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.resident.ResidentRuntime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * GET /runtime/budget: runtime numbers for sizing companion budgets (BOT_MANAGEMENT_PLAN.md P0).
 * Only what the JVM, the Agent registry and scheduler, the timers and the living world actually
 * expose; nothing is estimated. {@code ?gc=1} runs System.gc() first so the heap reads as live
 * data: that is a full stop-the-world pause, for measurement runs only.
 */
final class AdminBudgetReader {
    private static final long MB = 1024L * 1024L;

    private AdminBudgetReader() {}

    /** True for {@code gc=1} or {@code gc=true} in the raw query string. */
    static boolean gcRequested(String rawQuery) {
        if (rawQuery == null) return false;
        for (String pair : rawQuery.split("&")) {
            if (pair.equals("gc=1") || pair.equalsIgnoreCase("gc=true")) return true;
        }
        return false;
    }

    static String budget(boolean gc) {
        AdminTimerLagProbe.LOW_PRIORITY.ensureStarted();
        long gcPauseMs = -1L;
        if (gc) {
            long startedNs = System.nanoTime();
            System.gc();
            gcPauseMs = (System.nanoTime() - startedNs) / 1_000_000L;
        }
        return new AdminJson.Obj()
                .bool("ok", true)
                .num("sampledAtMs", System.currentTimeMillis())
                .num("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime())
                .raw("heap", heap(ManagementFactory.getMemoryMXBean(), ManagementFactory.getMemoryPoolMXBeans(),
                        ManagementFactory.getGarbageCollectorMXBeans(), gcPauseMs).finished())
                .raw("cpu", cpu(ManagementFactory.getOperatingSystemMXBean()).finished())
                .raw("threads", threads(ManagementFactory.getThreadMXBean()).finished())
                .raw("agents", section(AdminBudgetReader::agents))
                .raw("scheduler", section(AdminBudgetReader::scheduler))
                .raw("headlessNpcScripts", section(AdminBudgetReader::headlessNpcScripts))
                .raw("timers", timers(AdminTimerLagProbe.LOW_PRIORITY, System.nanoTime()).finished())
                .finished();
    }

    // --- JVM sections (no server needed) --------------------------------

    /** {@code gcPauseMs} is -1 unless ?gc=1 ran a collection just before this read. */
    static AdminJson.Obj heap(MemoryMXBean memory, List<MemoryPoolMXBean> pools,
                              List<GarbageCollectorMXBean> collectors, long gcPauseMs) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        AdminJson.Obj o = new AdminJson.Obj()
                .num("usedMb", heap.getUsed() / MB)
                .num("committedMb", heap.getCommitted() / MB)
                .num("maxMb", heap.getMax() < 0 ? -1 : heap.getMax() / MB)
                .bool("afterGc", gcPauseMs >= 0);
        if (gcPauseMs >= 0) o.num("gcPauseMs", gcPauseMs);
        MemoryPoolMXBean oldGen = oldGenPool(pools);
        MemoryUsage afterGc = oldGen == null ? null : oldGen.getCollectionUsage();
        o.str("oldGenPool", oldGen == null ? null : oldGen.getName());
        // Usage right after the last collection that covered this pool (for G1: a mixed or full GC).
        o.raw("oldGenAfterLastGcMb", afterGc == null ? "null" : String.valueOf(afterGc.getUsed() / MB));
        AdminJson.Arr gcs = new AdminJson.Arr();
        for (GarbageCollectorMXBean gcBean : collectors) {
            gcs.obj().str("name", gcBean.getName()).num("count", gcBean.getCollectionCount()).num("timeMs", gcBean.getCollectionTime());
        }
        return o.raw("collectors", gcs.toString());
    }

    /** The tenured heap pool ("G1 Old Gen", "PS Old Gen", "Tenured Gen", ...), or null. */
    static MemoryPoolMXBean oldGenPool(List<MemoryPoolMXBean> pools) {
        for (MemoryPoolMXBean pool : pools) {
            String name = pool.getName();
            if (pool.getType() == MemoryType.HEAP && name != null && (name.contains("Old") || name.contains("Tenured"))) {
                return pool;
            }
        }
        return null;
    }

    /** Loads are 0..1 over the interval since the previous read (negative: unavailable); processCpuTimeMs is cumulative. */
    static AdminJson.Obj cpu(OperatingSystemMXBean os) {
        AdminJson.Obj o = new AdminJson.Obj().num("availableProcessors", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            o.num("processLoad", round3(sun.getProcessCpuLoad()))
                    .num("systemLoad", round3(sun.getCpuLoad()))
                    .num("processCpuTimeMs", sun.getProcessCpuTime() / 1_000_000L);
        }
        return o;
    }

    static AdminJson.Obj threads(ThreadMXBean threads) {
        return new AdminJson.Obj()
                .num("live", threads.getThreadCount())
                .num("peak", threads.getPeakThreadCount())
                .num("daemon", threads.getDaemonThreadCount());
    }

    static AdminJson.Obj timers(AdminTimerLagProbe probe, long nowNanos) {
        AdminJson.Obj lowLag = new AdminJson.Obj()
                .num("lastMs", probe.lastLagMs())
                .num("maxLastMinuteMs", probe.maxLagMs(nowNanos))
                .num("overdueMs", probe.overdueMs(nowNanos))
                .num("samples", probe.samples())
                .num("periodMs", AdminTimerLagProbe.PERIOD_MS)
                .num("rearms", probe.rearms())
                .bool("running", probe.running());
        String diagnostics;
        try {
            diagnostics = TimerManager.getInstance().diagnostics();
        } catch (RuntimeException failure) {
            diagnostics = null;
        }
        return new AdminJson.Obj().raw("lowPriorityLag", lowLag.finished()).str("lanes", diagnostics);
    }

    private static double round3(double value) {
        return Double.isFinite(value) ? Math.round(value * 1000.0) / 1000.0 : -1.0;
    }

    // --- server sections --------------------------------------------------

    /** A failing section reports its error instead of failing the whole read. */
    private static String section(Supplier<String> section) {
        try {
            return section.get();
        } catch (RuntimeException failure) {
            return "{\"error\":" + AdminJson.str(failure.getClass().getSimpleName() + ": " + failure.getMessage()) + "}";
        }
    }

    /**
     * Registered Agents by kind. Each Agent is counted once, first match wins in field order;
     * "other" is every registered Agent none of the sets claims (GM-spawned bots, PQ populations...).
     */
    record AgentKinds(int active, int companions, int benchLive, int residents, int citizens, int other) {
        static AgentKinds classify(Collection<Integer> activeAgentIds, Set<Integer> companionIds, Set<Integer> benchLiveIds,
                                   Set<Integer> residentIds, Set<Integer> citizenIds) {
            int companions = 0, benchLive = 0, residents = 0, citizens = 0, other = 0;
            for (int id : activeAgentIds) {
                if (companionIds.contains(id)) companions++;
                else if (benchLiveIds.contains(id)) benchLive++;
                else if (residentIds.contains(id)) residents++;
                else if (citizenIds.contains(id)) citizens++;
                else other++;
            }
            return new AgentKinds(activeAgentIds.size(), companions, benchLive, residents, citizens, other);
        }
    }

    /**
     * Starts from the registry, so the work is bounded by live Agents and needs no roster query:
     * a companion is a persona character registered under an online human (the persona lookup is
     * an in-memory cache that only goes to the database on its first miss for an id).
     */
    private static String agents() {
        Set<Integer> humans = new HashSet<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (!(chr.getClient() instanceof BotClient)) humans.add(chr.getId());
            }
        }
        Set<Integer> benchLiveIds = new HashSet<>();
        Set<Integer> residentIds = new HashSet<>();
        Set<Integer> citizenIds = new HashSet<>();
        int benchLiveSessions = 0;
        int residentsMaterialized = 0;
        int citizensLoaded = 0;
        int companionOpsQueue = 0;
        LivingWorld world = LivingWorld.get();
        if (world != null) {
            companionOpsQueue = world.executor().queueDepth();
            benchLiveIds.addAll(world.benchSessions().liveCharacterIds());
            benchLiveSessions = world.benchSessions().liveCount();
            for (ResidentRuntime.Session session : world.residents().sessions()) {
                if (session.materialized()) residentIds.add(session.characterId);
            }
            residentsMaterialized = residentIds.size();
        }
        Population population = Population.get();
        if (population != null) {
            for (CitizenSession session : population.presence().sessions()) {
                if (!session.virtual) citizenIds.add(session.characterId);
            }
            citizensLoaded = population.presence().loadedCount();
        }
        List<Integer> active = new ArrayList<>();
        Set<Integer> companionIds = new HashSet<>();
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.activeEntriesSnapshot()) {
            int id = AgentRuntimeIdentityRuntime.botId(entry);
            if (id < 0) continue;
            active.add(id);
            if (world == null || benchLiveIds.contains(id) || residentIds.contains(id) || citizenIds.contains(id)) continue;
            // The registry cohort is the leader's id for leader-registered Agents (other cohorts may exceed int range).
            long leader = AgentRuntimeRegistry.cohortIdForAgentCharacter(id);
            if (leader > 0 && leader <= Integer.MAX_VALUE && humans.contains((int) leader)
                    && world.residents().personaOfCharacterId(id) != null) {
                companionIds.add(id); // a persona following an online human
            }
        }
        AgentKinds kinds = AgentKinds.classify(active, companionIds, benchLiveIds, residentIds, citizenIds);
        return new AdminJson.Obj()
                .num("active", kinds.active())
                .num("companions", kinds.companions())
                .num("benchLive", kinds.benchLive())
                .num("residents", kinds.residents())
                .num("citizens", kinds.citizens())
                .num("other", kinds.other())
                .num("humansOnline", humans.size())
                .bool("livingWorld", world != null)
                .bool("population", population != null)
                // The subsystems' own counters, to cross-check the registry-based split above.
                .num("benchLiveSessions", benchLiveSessions)
                .num("residentsMaterialized", residentsMaterialized)
                .num("citizensLoaded", citizensLoaded)
                // commands queued on the companion-ops thread (plan "CompanionAuditor + ops endpoints")
                .num("companionOpsQueue", companionOpsQueue)
                .finished();
    }

    /** Scheduler registrations vs capacity plus the metrics AgentSchedulerMetrics keeps (cycle p95 is not tracked; avg and max are). */
    private static String scheduler() {
        AgentSchedulerConfig config = AgentSchedulerConfig.fromSystemProperties();
        AdminJson.Arr perShard = new AdminJson.Arr();
        int registrations = 0;
        int shards = 0;
        switch (config.mode()) {
            case LEGACY_PER_AGENT -> { } // every Agent owns its own timer task; nothing is centrally registered
            case CENTRAL_SEQUENTIAL -> {
                shards = 1;
                registrations = AgentTickScheduler.instance().registrationCount();
                perShard.rawEl(String.valueOf(registrations));
            }
            case CENTRAL_SHARDED -> {
                AgentShardedTickScheduler sharded = AgentShardedTickScheduler.instance();
                shards = sharded.shardCount();
                for (int count : sharded.registrationCounts()) {
                    registrations += count;
                    perShard.rawEl(String.valueOf(count));
                }
            }
        }
        AgentSchedulerMetrics.Snapshot m = AgentSchedulerMetrics.snapshot();
        AgentSchedulerMetrics.LoadSheddingSnapshot shedding = AgentSchedulerMetrics.loadSheddingSnapshot();
        AgentLoadSheddingLevel level = AgentLoadSheddingLevel.NORMAL;
        for (AgentLoadSheddingState state : shedding.shardStates().values()) {
            if (state.level().ordinal() > level.ordinal()) level = state.level();
        }
        return new AdminJson.Obj()
                .str("mode", config.mode().name())
                .num("shards", shards)
                .num("registrations", registrations)
                .raw("registrationsPerShard", perShard.toString())
                // Registration is refused once one shard holds this many.
                .num("capacityPerShard", config.ingressCapacityPerShard())
                .num("cycles", m.cycles())
                .num("cycleAvgUs", m.cycles() == 0 ? 0 : m.totalCycleNs() / m.cycles() / 1_000L)
                .num("cycleMaxUs", m.maxCycleNs() / 1_000L)
                .num("cycleBudgetMs", config.cycleBudgetMs())
                .num("queueLagP50Ms", m.queueLagP50Ms())
                .num("queueLagP95Ms", m.queueLagP95Ms())
                .num("queueLagP99Ms", m.queueLagP99Ms())
                .num("queueLagMaxMs", m.maxQueueLagMs())
                .num("tickWorkP50Us", m.workDurationP50Ns() / 1_000L)
                .num("tickWorkP95Us", m.workDurationP95Ns() / 1_000L)
                .num("tickWorkP99Us", m.workDurationP99Ns() / 1_000L)
                .num("slowAgents", m.slowAgents())
                .num("failedAgents", m.failedAgents())
                .num("budgetExhaustions", m.budgetExhaustions())
                .num("deferredWork", m.deferredWork())
                .num("ingressDepth", m.ingressDepth())
                .num("ingressHighWaterMark", m.ingressHighWaterMark())
                .num("dueDepth", m.dueHeapDepth())
                .num("readyDepth", m.readyDepth())
                .str("loadShedding", level.name())
                .num("rejectedAdmissions", shedding.rejectedAdmissions())
                .finished();
    }

    /**
     * Headless Agent NPC conversations, split by whether a pre-evaluated engine was ready (hit) or the
     * conversation had to evaluate its script on the calling Agent tick thread (miss). Run times include
     * the whole conversation; evaluations happen on the background prep thread.
     */
    private static String headlessNpcScripts() {
        CosmicPreparedNpcScriptEngines.Stats s = CosmicPreparedNpcScriptEngines.stats();
        return new AdminJson.Obj()
                .num("hits", s.hits())
                .num("misses", s.misses())
                .num("hitRunAvgUs", s.hits() == 0 ? 0 : s.hitRunTotalUs() / s.hits())
                .num("hitRunMaxUs", s.hitRunMaxUs())
                .num("missRunAvgUs", s.misses() == 0 ? 0 : s.missRunTotalUs() / s.misses())
                .num("missRunMaxUs", s.missRunMaxUs())
                .num("backgroundEvaluations", s.evaluations())
                .num("backgroundEvaluationMaxUs", s.evaluationMaxUs())
                .num("scripts", s.scripts())
                .num("readyEngines", s.ready())
                .finished();
    }
}
