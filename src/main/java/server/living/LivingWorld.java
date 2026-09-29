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
import server.living.quest.QuestLog;
import server.living.quest.QuestMarkers;
import server.living.resident.ResidentDialogue;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchLedger;
import server.living.roster.CompanionAuditor;
import server.living.roster.CompanionBudget;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionLeases;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.Commitments;
import server.living.roster.HireOffers;
import server.living.roster.HiredHands;
import server.living.roster.ResidentAllocation;
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
    private final QuestLog questLog;
    private final QuestMarkers markers;
    private final ContractService contracts;
    private final CompanionExecutor executor;
    private final Commitments commitments;
    private final CompanionSlots slots;
    private final CompanionPartySync partySync;
    private final RosterService roster;
    private final HiredHands hiredHands;
    private final HireOffers hireOffers;
    private final ResidentAllocation residentAllocation;
    private final CompanionViews views;
    private final CompanionManager manager;
    private final CompanionLeases leases;
    private final CompanionDirectory directory;
    private final CompanionBudget budget;
    private final CompanionChatVerbs chatVerbs;
    private final CompanionAuditor auditor;
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
        this.questLog = new QuestLog(this);
        this.markers = new QuestMarkers(this);
        this.executor = new CompanionExecutor();
        this.commitments = new Commitments();
        this.slots = new CompanionSlots(this); // before the roster, which rebuilds its reservations
        this.partySync = CompanionPartySync.live(this);
        this.views = new CompanionViews(this); // before the roster, whose every write drops its owner's views
        this.roster = new RosterService(this);
        this.hiredHands = new HiredHands(this);
        this.hireOffers = new HireOffers(this);
        this.residentAllocation = new ResidentAllocation(this);
        this.manager = new CompanionManager(this);
        this.leases = new CompanionLeases();
        this.directory = new CompanionDirectory(this);
        this.budget = new CompanionBudget(this);
        this.chatVerbs = new CompanionChatVerbs(this);
        this.auditor = new CompanionAuditor(this);
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
        world.residents.syncLooks();
        server.agents.auth.AgentAuthorityService.installOperatorGrant(world.roster::hasCompanions);
        server.agents.auth.AgentClaimGuard.install(world.directory);
        server.agents.auth.AgentClaimGuard.installVerbs(world.chatVerbs); // chat dismiss/logout of a companion bench it
        // travelling, held and away companions never follow; the rest come into their owner's new map where they came in
        server.agents.capabilities.follow.AgentFollowGuard.install(new server.living.roster.CompanionFollowGuard(world));
        // a companion out with its owner drinks and loots by its own settings (P3b)
        world.roster.settings().install();
        // hire:<characterId> ids resolve to hired citizens, and a released hire goes back to the population (P4)
        world.hiredHands.install();
        // boot reconciliation (decision 8a) goes first on companion-ops, ahead of any login's restore
        world.executor.submit("roster.boot", world.roster::reconcileAtBoot);
        // decision 18: Residents of owners away too long go home, at boot (after the reconciliation) and daily
        world.executor.submit("residents.allocation", world.residentAllocation::sweep);
        TimerManager timers = TimerManager.getInstance();
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            world.executor.submit("residents.allocation", world.residentAllocation::sweep);
            world.executor.submit("hire.reconcile", world.hiredHands::reconcileFlags); // and the hired flags, daily
        }, ResidentAllocation.SWEEP_INTERVAL_MS, ResidentAllocation.SWEEP_INTERVAL_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.residents::tick,
                config.RESIDENT_TICK_MS, config.RESIDENT_TICK_MS));
        // the ledger, the contract clocks and the bench slices write roster rows: they run on companion-ops, the
        // timer only hands them over
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("bench.tick", world.bench::tick),
                config.BENCH_LEDGER_INTERVAL_MS, config.BENCH_LEDGER_INTERVAL_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.rumors::tick,
                config.RUMOR_TICK_MS, config.RUMOR_TICK_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("contracts.tick", world.contracts::tick), 60_000L, 60_000L));
        // a citizen guest's party minutes, its offer to stay on and the offer's expiry (P4)
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("hire.offers", world.hireOffers::tick), HireOffers.TICK_MS, HireOffers.TICK_MS));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("bench_sessions.tick", world.benchSessions::tick), 120_000L, 120_000L));
        // companions parked with an owner who stays away go to their duty once LEADER_OFFLINE_BENCH_MS has passed
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("roster.park_sweep", world.roster::benchParkedPastGrace), 60_000L, 60_000L));
        // party events that waited for an event instance to end are reconciled on companion-ops
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("companion.resume", world.partySync::resumeDeferred), 30_000L, 30_000L));
        // and at once when a commitment ends (the member left the run, or the run ended)
        world.commitments.addListener(ended -> world.executor.submit("commitment.ended " + ended.key(),
                world.partySync::resumeDeferred));
        // and a contract walk-out that came due during the run goes now ("after this run, i'm heading home")
        world.commitments.addListener(ended -> world.executor.submit("contracts.after_the_run " + ended.key(),
                () -> world.contracts.commitmentEnded(ended)));
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("companion.audit", world.auditor::run),
                CompanionAuditor.INTERVAL_MS, CompanionAuditor.INTERVAL_MS));
        if (config.QUEST_MARKER_PULSE_MS > 0) {
            world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.markers::tick,
                    config.QUEST_MARKER_PULSE_MS, config.QUEST_MARKER_PULSE_MS));
            if (config.QUEST_MARKER_HITBOX_NPC > 0) {
                world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, world.markers::trackHitboxes,
                        config.QUEST_MARKER_HITBOX_TRACK_MS, config.QUEST_MARKER_HITBOX_TRACK_MS));
            }
        }
        instance = world;
        // party changes reach the roster on companion-ops, never inline; a bot joins a human's party only on a seat
        net.server.world.PartyEvents.install(LivingWorldHooks::partyChanged);
        net.server.world.PartySeatGate.install(world.slots::admitsJoin);
        log.info("Living world started: {} (world {} channel {}, content {})", content.census(), config.WORLD,
                config.CHANNEL, Path.of(config.CONTENT_DIR).toAbsolutePath());
        server.living.population.Population.start(world);
        // the roster's hire rows are the truth for the citizens' hired flags: checked once the population is up (P4)
        world.executor.submit("hire.reconcile", world.hiredHands::reconcileFlags);
    }

    public static synchronized void stop() {
        LivingWorld world = instance;
        if (world == null) {
            return;
        }
        instance = null;
        net.server.world.PartyEvents.install(null);
        net.server.world.PartySeatGate.install(null);
        server.agents.auth.AgentClaimGuard.installVerbs(null);
        server.agents.capabilities.follow.AgentFollowGuard.install(null);
        server.living.roster.CompanionSettingsApplier.uninstall();
        HiredHands.uninstall();
        server.living.population.Population.stop();
        for (ScheduledFuture<?> timer : world.timers) {
            timer.cancel(false);
        }
        world.timers.clear();
        // live bench slices unload on companion-ops like every companion unload; shutdown waits for their saves
        try {
            world.executor.submit("bench_sessions.end_all", () -> world.benchSessions.endAll("server shutdown"))
                    .get(CompanionExecutor.DEFAULT_WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            log.warn("live bench slices did not all end before shutdown", failure);
        }
        world.residents.standbyAll("server shutdown");
        world.executor.shutdown();
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

    /** Current hour (0-23) in the zone routines and rumors are authored in. */
    public int routineHour() {
        String zone = config.ROUTINE_ZONE;
        java.time.ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException invalid) {
            zoneId = java.time.ZoneId.systemDefault();
        }
        return java.time.LocalTime.now(zoneId).getHour();
    }

    public java.time.LocalDate routineDate() {
        String zone = config.ROUTINE_ZONE;
        try {
            return java.time.LocalDate.now(zone == null || zone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone));
        } catch (java.time.DateTimeException invalid) {
            return java.time.LocalDate.now();
        }
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

    public QuestLog questLog() {
        return questLog;
    }

    public QuestMarkers markers() {
        return markers;
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

    /** Hired citizens (P4): their synthetic personas, their terms and the hire itself. */
    public HiredHands hiredHands() {
        return hiredHands;
    }

    /** Party-time hire offers (P4): a citizen guest's offer to stay on, open for 10 minutes. */
    public HireOffers hireOffers() {
        return hireOffers;
    }

    /** Decision 18 (P4): the per-owner Resident cap, and Residents of owners away too long going home. */
    public ResidentAllocation residentAllocation() {
        return residentAllocation;
    }

    /** The CompanionView cache the menus render from (no database read per render). */
    public CompanionViews views() {
        return views;
    }

    /** The typed facade the menus call on companion-ops (roster verbs, orders, trade, gear, release). */
    public CompanionManager manager() {
        return manager;
    }

    /** The companion-ops thread every companion mutation runs on. */
    public CompanionExecutor executor() {
        return executor;
    }

    /** Companions tied to an event instance or expedition: frozen for the roster and the party sync meanwhile. */
    public Commitments commitments() {
        return commitments;
    }

    /** The only slot authority. */
    public CompanionSlots slots() {
        return slots;
    }

    /** Keeps the roster in step with the party window (runs on companion-ops). */
    public CompanionPartySync partySync() {
        return partySync;
    }

    public CompanionLeases leases() {
        return leases;
    }

    public CompanionDirectory directory() {
        return directory;
    }

    public CompanionBudget budget() {
        return budget;
    }

    /** The one door from chat into the roster. */
    public CompanionChatVerbs chatVerbs() {
        return chatVerbs;
    }

    /** The zero-drift auditor. */
    public CompanionAuditor auditor() {
        return auditor;
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
