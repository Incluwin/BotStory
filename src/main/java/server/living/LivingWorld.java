package server.living;

import config.LivingWorldConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.living.arc.ArcService;
import server.living.arc.RumorScheduler;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.phrases.PhraseMatcher;
import server.living.quest.QuestLedger;
import server.living.resident.ResidentDialogue;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchLedger;
import server.living.roster.RosterService;
import server.living.store.LivingStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Bootstrap and service registry for the living world (docs/LIVING_RPG_PLAN.md). Started from
 * {@code Server} after the Agent runtimes; everything is off when {@code living_world.ENABLED}
 * is false and {@link #get()} returns null.
 */
public final class LivingWorld {
    private static final Logger log = LoggerFactory.getLogger(LivingWorld.class);
    private static volatile LivingWorld instance;

    private final LivingWorldConfig config;
    private volatile LivingContent content;
    private final LivingStore store;
    private final BondService bonds;
    private final ArcService arcs;
    private final QuestLedger quests;
    private final ContractService contracts;
    private final RosterService roster;
    private final BenchLedger bench;
    private final server.living.roster.BenchSessions benchSessions;
    private final ResidentRuntime residents;
    private final ResidentDialogue dialogue;
    private final RumorScheduler rumors;
    private final PhraseMatcher phrases;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();

    private LivingWorld(LivingWorldConfig config, LivingContent content) {
        this.config = config;
        this.content = content;
        this.store = new LivingStore();
        this.bonds = new BondService(this);
        this.arcs = new ArcService(this);
        this.quests = new QuestLedger(this);
        this.roster = new RosterService(this);
        this.contracts = new ContractService(this);
        this.bench = new BenchLedger(this);
        this.benchSessions = new server.living.roster.BenchSessions(this);
        this.residents = new ResidentRuntime(this);
        this.dialogue = new ResidentDialogue(this);
        this.rumors = new RumorScheduler(this);
        this.phrases = new PhraseMatcher(this);
    }

    public static LivingWorld get() {
        return instance;
    }

    public static boolean enabled() {
        return instance != null;
    }

    public static synchronized void start() {
        LivingWorldConfig config = YamlConfig.config.living_world;
        if (config == null || !config.ENABLED) {
            log.info("Living world disabled (living_world.ENABLED=false).");
            return;
        }
        if (instance != null) {
            return;
        }
        LivingContent content;
        try {
            content = ContentLoader.load(Path.of(config.CONTENT_DIR));
        } catch (ContentException failure) {
            log.error("Living world content failed to load; feature stays off: {}", failure.getMessage());
            return;
        }
        for (String warning : content.warnings()) {
            if (warning.startsWith("ERROR")) {
                log.warn("Living world content: {}", warning);
            } else {
                log.debug("Living world content: {}", warning);
            }
        }
        LivingWorld world = new LivingWorld(config, content);
        world.phrases.reload();
        world.arcs.loadState();
        server.agents.auth.AgentAuthorityService.installOperatorGrant(world.roster::hasCompanions);
        TimerManager timers = TimerManager.getInstance();
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.residents::tick,
                config.RESIDENT_TICK_MS, config.RESIDENT_TICK_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.bench::tick,
                config.BENCH_LEDGER_INTERVAL_MS, config.BENCH_LEDGER_INTERVAL_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.rumors::tick,
                config.RUMOR_TICK_MS, config.RUMOR_TICK_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.contracts::tick,
                60_000L, 60_000L));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.benchSessions::tick,
                120_000L, 120_000L));
        instance = world;
        log.info("Living world started: {} (world {} channel {}, content {})", content.census(), config.WORLD,
                config.CHANNEL, Path.of(config.CONTENT_DIR).toAbsolutePath());
    }

    public static synchronized void stop() {
        LivingWorld world = instance;
        if (world == null) {
            return;
        }
        instance = null;
        for (ScheduledFuture<?> timer : world.timers) {
            timer.cancel(false);
        }
        world.timers.clear();
        world.benchSessions.endAll("server shutdown");
        world.residents.standbyAll("server shutdown");
        log.info("Living world stopped.");
    }

    /** Re-reads the content directory; residents pick the new definitions up on their next tick. */
    public List<String> reload() {
        LivingContent reloaded = ContentLoader.load(Path.of(config.CONTENT_DIR));
        this.content = reloaded;
        phrases.reload();
        arcs.loadState();
        residents.contentChanged();
        return reloaded.warnings();
    }

    public LivingWorldConfig config() {
        return config;
    }

    public LivingContent content() {
        return content;
    }

    public LivingStore store() {
        return store;
    }

    public BondService bonds() {
        return bonds;
    }

    public ArcService arcs() {
        return arcs;
    }

    public QuestLedger quests() {
        return quests;
    }

    public ContractService contracts() {
        return contracts;
    }

    public RosterService roster() {
        return roster;
    }

    public BenchLedger bench() {
        return bench;
    }

    public server.living.roster.BenchSessions benchSessions() {
        return benchSessions;
    }

    public ResidentRuntime residents() {
        return residents;
    }

    public ResidentDialogue dialogue() {
        return dialogue;
    }

    public RumorScheduler rumors() {
        return rumors;
    }

    public PhraseMatcher phrases() {
        return phrases;
    }
}
