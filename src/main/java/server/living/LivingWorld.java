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
    private final server.living.bond.Friendship friendship;
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
    private final server.living.roster.BenchDutyDispatcher benchDuty;
    private final server.living.roster.BenchYieldModel benchYields;
    private final ResidentRuntime residents;
    private final ResidentDialogue dialogue;
    private final RumorScheduler rumors;
    private final PhraseMatcher phrases;
    private final server.living.roster.CrewRotation crewRotation;
    private final server.living.roster.BenchCombatLearning benchCombatLearning;
    private final server.living.roster.CompanionPartyQuestService partyQuests;
    private final server.living.roster.CompanionRunLifecycle partyQuestRuns;
    private final server.living.roster.CompanionRestock restock;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();

    private LivingWorld(LivingWorldConfig config, LivingContent content) {
        this.config = config;
        this.content = content;
        this.store = new LivingStore();
        this.bonds = new BondService(this);
        this.friendship = new server.living.bond.Friendship(this);
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
        // the one place a benched companion goes live (P5): the field form above, and the forms the other duties register
        this.benchDuty = new server.living.roster.BenchDutyDispatcher(this, benchSessions);
        this.benchYields = new server.living.roster.BenchYieldModel(this);
        // the visible forms of the other duties (P5): GATHER at its camp, STALL and SELL in the Free Market, REST in town
        server.living.roster.BenchStallForm stall = new server.living.roster.BenchStallForm(this);
        this.benchDuty.register(LivingStore.Assignment.GATHER, new server.living.roster.BenchGatherForm(this));
        this.benchDuty.register(LivingStore.Assignment.STALL, stall);
        this.benchDuty.register(LivingStore.Assignment.SELL, stall);
        this.benchDuty.register(LivingStore.Assignment.REST, new server.living.roster.BenchRestForm(this));
        this.residents = new ResidentRuntime(this);
        this.dialogue = new ResidentDialogue(this);
        this.rumors = new RumorScheduler(this);
        this.phrases = new PhraseMatcher(this);
        this.crewRotation = new server.living.roster.CrewRotation(this); // "Keep my crew fresh" (P5, decision 15 b)
        this.benchCombatLearning = new server.living.roster.BenchCombatLearning(this); // decision 13 a
        this.partyQuests = new server.living.roster.CompanionPartyQuestService(this); // P6: party quests with companions
        this.partyQuestRuns = new server.living.roster.CompanionRunLifecycle(this, partyQuests);
        this.restock = new server.living.roster.CompanionRestock(this); // companions restock their own potions
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
        // a companion a party quest lets go goes back to its owner, not to Kerning TownLife (P6); before any admission,
        // whose own rollback recovers its Agents
        server.agents.capabilities.partyquest.AgentPartyQuestHandback.install(world.partyQuestRuns);
        // and a companion whose owner logs off inside its run sees it through for the friend still in it (decision 8b)
        world.roster.installInstanceDelegation(world.partyQuestRuns);
        // each entry NPC offers a party with companions its lineup (living_world.COMPANION_PQ_ENABLED_KEYS): one desk
        // per entrance, Lakelis's for KPQ and Tory's for HPQ
        server.living.roster.ui.CompanionMenus deskMenus = new server.living.roster.ui.CompanionMenus(world);
        for (String key : server.living.roster.PqEntrance.keys()) {
            world.partyQuests.installDesk(key, new server.living.roster.ui.EntryDesk(world, world.partyQuests,
                    deskMenus, server.living.roster.PqEntrance.of(key)));
        }
        // inside a run, a companion asks its leader in a window of its own whether to re-roll a heavy stage 1
        world.partyQuestRuns.installRerollWindow(new server.living.roster.ui.RerollQuestion());
        // a companion out with its owner drinks and loots by its own settings (P3b)
        world.roster.settings().install();
        // and keeps the party-quest potion standard by itself, walking to a nearby shop when short (2026-10-02)
        world.restock.install();
        // hire:<characterId> ids resolve to hired citizens, and a released hire goes back to the population (P4)
        world.hiredHands.install();
        // boot reconciliation (decision 8a) goes first on companion-ops, ahead of any login's restore
        world.executor.submit("roster.boot", world.roster::reconcileAtBoot);
        // decision 18: Residents of owners away too long go home, at boot (after the reconciliation) and daily
        world.executor.submit("residents.allocation", world.residentAllocation::sweep);
        // the yields live bench slices measured (companion_yield), before the first ledger tick credits them
        world.executor.submit("bench.yields", world.benchYields::load);
        // the companions' KPQ records (companion_performance), which the lineups at Lakelis rank by
        world.executor.submit("pq.records.load", world.partyQuests.records()::load);
        // and KPQ's pace data (the clear-time estimate's monster census, its monsters, the attack timings) the lineups
        // weigh each party member against, read here, not on an I/O thread
        world.executor.submit("pq.pace", world.partyQuests::warmPace);
        // then its clear-time records (pq_run_pace), fitted against that warm data, which the estimate learns from
        world.executor.submit("pq.pace.records", world.partyQuests.paceLedger()::load);
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
                () -> world.executor.submit("bench_duty.tick", world.benchDuty::tick),
                server.living.roster.BenchDutyDispatcher.TICK_MS, server.living.roster.BenchDutyDispatcher.TICK_MS));
        world.executor.submit("bench_duty.your_call", world.benchDuty::loadYourCalls); // the rows on Your call (P5)
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
        // decision 13 a: bench grinders nobody can see learn how to fight into candidate tables, read at boot and
        // written on companion-ops (the combat arena's policy=candidate check is what adopts them for live play)
        world.benchCombatLearning.install();
        world.executor.submit("combat_learning.load", world.benchCombatLearning::load);
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("combat_learning.save", world.benchCombatLearning::save),
                config.BENCH_COMBAT_LEARN_SAVE_MS, config.BENCH_COMBAT_LEARN_SAVE_MS));
        // decision 15 b: the crews of the owners who turned on "Keep my crew fresh" are looked at on companion-ops
        world.executor.submit("crew.fresh.load", world.crewRotation::load);
        world.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor.submit("crew.fresh", world.crewRotation::tick),
                config.AUTO_ROTATE_TICK_MS, config.AUTO_ROTATE_TICK_MS));
        instance = world;
        // party changes reach the roster on companion-ops, never inline; a bot joins a human's party only on a seat
        net.server.world.PartyEvents.install(LivingWorldHooks::partyChanged);
        net.server.world.PartySeatGate.install(world.slots::admitsJoin);
        log.info("Living world started: {} (world {} channel {}, content {})", content.census(), config.WORLD,
                config.CHANNEL, Path.of(config.CONTENT_DIR).toAbsolutePath());
        server.living.population.Population.start(world);
        server.knowledge.PersonalKnowledge.shared().start(); // bots' known worlds: recorded from here, written behind (plan 7.4)
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
        server.agents.capabilities.partyquest.AgentPartyQuestHandback.install(null);
        world.roster.installInstanceDelegation(null);
        server.living.roster.BenchCombatLearning.uninstall();
        server.living.roster.CompanionSettingsApplier.uninstall();
        server.living.roster.CompanionRestock.uninstall();
        HiredHands.uninstall();
        server.living.population.Population.stop();
        server.knowledge.PersonalKnowledge.shared().stop(); // the citizens logged out: their worlds are written now, DB still up
        for (ScheduledFuture<?> timer : world.timers) {
            timer.cancel(false);
        }
        world.timers.clear();
        // live bench slices unload on companion-ops like every companion unload; shutdown waits for their saves
        try {
            world.executor.submit("bench_duty.end_all", () -> world.benchDuty.endAll("server shutdown"))
                    .get(CompanionExecutor.DEFAULT_WATCHDOG_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            log.warn("live bench slices did not all end before shutdown", failure);
        }
        world.executor.submit("combat_learning.save", world.benchCombatLearning::save); // what bench grinders learned
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

    /** Epoch ms of today's midnight in the routine zone: where the friendship daily counts start. */
    public long routineDayStartMs() {
        String zone = config.ROUTINE_ZONE;
        java.time.ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException invalid) {
            zoneId = java.time.ZoneId.systemDefault();
        }
        return java.time.LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli();
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

    public server.living.bond.Friendship friendship() {
        return friendship;
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

    /** Every live bench slice, whatever its duty's form (P5). */
    public server.living.roster.BenchDutyDispatcher benchDuty() {
        return benchDuty;
    }

    /** The bench yields measured on live slices (companion_yield, P5). */
    public server.living.roster.BenchYieldModel benchYields() {
        return benchYields;
    }

    /** "Keep my crew fresh" (P5, decision 15 b): each owner's opt-in and the swaps it makes. */
    public server.living.roster.CrewRotation crewRotation() {
        return crewRotation;
    }

    /** P6: party quests with companions, which start at the quest's entry NPC (Lakelis). */
    /** Companions restocking their own potions: the Agent layer's readiness rule (CompanionRestock). */
    public server.living.roster.CompanionRestock restock() {
        return restock;
    }

    public server.living.roster.CompanionPartyQuestService partyQuests() {
        return partyQuests;
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
