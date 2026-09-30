package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The one place a benched companion is made live (docs/BOT_MANAGEMENT_PLAN.md P5, "Living bench", flow 2). Each bench
 * duty has a {@link Form} that shows it in the world: TRAIN, FARM_MESO and FARM_ITEM grind at a field camp
 * ({@link BenchSessions}); GATHER, STALL, SELL and REST get theirs through {@link #register}; a duty with no form stays
 * in the ledger alone (BenchLedger). Each slice is charged to a CompanionBudget pool and held by the CompanionLeases
 * lease of the same name: BENCH_LIVE for the field forms, BENCH_TOWN (cheap town presence) and COMMERCE (Free Market
 * stalls), each with its own cap per owner and server-wide. When a pool is contended, owners online go first, and each
 * owner's bench takes turns (the longest since its last slice first), one of each owner's per round. Every slice loads
 * on the living channel, at a portal when it is a town slice or a player is on the map (it walks in, never pops in),
 * else at one of the map's own spots, never two on the same one. A row on "Your call" picks its duty and camp again
 * (BenchDutyChooser) each time its turn comes, so the bandit learns from each slice it measured.
 *
 * <p>End rules. Every slice ends when its row stops being bench-governed or changes duty or camp, when its owner calls
 * it or it is released ({@link #endFor}), when its character is gone or off the living channel, after its pool's slice
 * length unless a player is watching it (a watched field slice runs on; a watched town or stall slice twice its length
 * at most, so the small pools still rotate; neither is ended by being in a town), and by its form's own rules (a field
 * slice back in town, at its pace target, at the daily farming cap or with what it was asked for). Flow 12: a death
 * ends it once the companion has revived by stock rules in its camp's return town, and it rests one field slice
 * ("resting after a rough hunt") while the ledger resumes; out of supplies, its hunt ends, the Agent's own shop
 * resupply runs, and only then does the slice unload and free its seat for the next one. A slice ends clean, each step
 * on its own so one failing never skips the rest: its form's last harvest, the hand-back on the Agent's own mailbox
 * (each primary activity asked to stop, the visit-lease checkpoints cleared), the field yield recorded
 * (BenchYieldModel) and the call-back report, then the unload, the save and the lease. A companion still online after
 * a failed unload keeps its lease and its pool slot (nothing can load a second copy of it) and is unloaded again on
 * the next tick.</p>
 *
 * <p>Every load, tick and unload runs on companion-ops; asked from another thread, the work is handed over there. What
 * changes a running Agent's activity (a visit lease, the hand-back) goes through its mailbox and is waited for at most
 * {@link #ON_AGENT_WAIT_MS}. The pickup and death hooks ({@link #looted}, {@link #died}) come from the Agent's own tick
 * and only count.</p>
 */
public final class BenchDutyDispatcher {
    private static final Logger log = LoggerFactory.getLogger(BenchDutyDispatcher.class);
    /** The card's line for a companion resting after a death on a live slice (flow 12). */
    public static final String ROUGH_HUNT = "resting after a rough hunt";
    /** The dispatcher's look at every live slice (LivingWorld's timer), and so the most a form's own rule waits. */
    public static final long TICK_MS = 120_000L;
    /** The most companion-ops waits for an action on a running Agent's own mailbox (it runs on its next tick). */
    static final long ON_AGENT_WAIT_MS = 2_000L;
    /** The primary activities a form may start, stopped at their boundary when a slice ends. */
    private static final AgentActivityKind[] HAND_BACK = {AgentActivityKind.HUNTING, AgentActivityKind.TOWN_LIFE,
            AgentActivityKind.COMMERCE};

    /**
     * How one duty shows in the world. The dispatcher chooses who goes live and when, takes the lease and the budget,
     * loads the companion at the form's map and keeps the common end rules; the form starts its activity, keeps its own
     * rules and takes its harvest. Every call is on companion-ops.
     */
    public interface Form {
        /** The pool its slices are charged to: BENCH_LIVE (a field form), BENCH_TOWN or COMMERCE. */
        CompanionBudget.Kind pool();

        /**
         * Where a slice of this row loads now, on the living channel, or 0 while it should stay in the ledger (its turn
         * passes). {@code characterId} is the companion's own character.
         */
        int mapFor(RosterEntry entry, Persona persona, int characterId);

        /** Registers the loaded companion and starts its duty's activity; false unloads it again. */
        boolean begin(Slice slice, Character agent, long nowMs);

        /** Its own rules each dispatcher tick (its harvest too): why the slice ends now, or null while it runs on. */
        String check(Slice slice, Character agent, RosterEntry entry, long nowMs);

        /** The slice ends, for {@code reason}: its last harvest and its own tidy-up ({@code agent} null when it is gone). */
        void finish(Slice slice, Character agent, String reason);
    }

    /** One live slice: who, for which duty, where, since when, and what it picked up and suffered there. */
    public static final class Slice {
        private final String personaId;
        private final int ownerId;
        private final int characterId;
        private final Assignment duty;
        private final Form form;
        private final CompanionBudget.Kind pool;
        private final int mapId;
        /** The camp its row named when the slice began (its map may be the field chosen for a town camp). */
        private final int rowCampMapId;
        private final long startedAtMs;
        /** What it picked up since the last harvest, fed by the pickup hook. */
        final HarvestDiff loot = new HarvestDiff();
        private final AtomicInteger deaths = new AtomicInteger();
        private volatile boolean died;
        private volatile long huntEndedAtMs;
        private int startLevel;
        private long startExp;
        private int jobId = -1;
        /** Set once its end ran its tidy-up (companion-ops): only the unload is left, retried each tick until it is done. */
        private boolean closing;
        private boolean handedBack;
        private Character leaving;
        private String endReason;

        Slice(RosterEntry entry, int characterId, Form form, int mapId, long startedAtMs) {
            this.personaId = entry.personaId();
            this.ownerId = entry.characterId();
            this.characterId = characterId;
            this.duty = entry.assignment();
            this.form = form;
            this.pool = form.pool();
            this.mapId = mapId;
            this.rowCampMapId = entry.campMapId();
            this.startedAtMs = startedAtMs;
        }

        public String personaId() {
            return personaId;
        }

        public int ownerId() {
            return ownerId;
        }

        public int characterId() {
            return characterId;
        }

        public Assignment duty() {
            return duty;
        }

        public CompanionBudget.Kind pool() {
            return pool;
        }

        public int mapId() {
            return mapId;
        }

        public long startedAtMs() {
            return startedAtMs;
        }

        public int deaths() {
            return deaths.get();
        }

        /** True once it ran out of supplies: its hunt is over and it restocks at a shop before it unloads. */
        public boolean resupplying() {
            return huntEndedAtMs > 0L;
        }

        private void snapshot(Character agent) {
            startLevel = agent.getLevel();
            startExp = agent.getExp();
            jobId = agent.getJob() == null ? -1 : agent.getJob().getId();
        }

        private CompanionLeases.Kind lease() {
            return BenchDutyDispatcher.lease(pool);
        }
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Map<Assignment, Form> forms = new ConcurrentHashMap<>();
    /** persona id -> its live slice. */
    private final Map<String, Slice> live = new ConcurrentHashMap<>();
    /** character id -> its live slice, for the pickup and death hooks. */
    private final Map<Integer, Slice> byCharacter = new ConcurrentHashMap<>();
    /** persona id -> when its last slice ended (or its turn last passed): the rotation. */
    private final Map<String, Long> lastLiveAtMs = new ConcurrentHashMap<>();
    /** persona id -> until when it rests after a death on a live slice. */
    private final Map<String, Long> restingUntilMs = new ConcurrentHashMap<>();
    /** "owner:persona" of the rows on "Your call" (living_roster.your_call), read at boot. */
    private final Set<String> yourCall = ConcurrentHashMap.newKeySet();
    /** The camps Your call chooses among for a level near a camp (BenchDutyChooser.camps; a test gives its own). */
    BiFunction<Integer, Integer, List<BenchDutyChooser.Camp>> yourCallCamps = BenchDutyChooser::camps;
    /**
     * The purse a companion with no HP potion needs to go and fight: enough for a stack of the cheapest potions. Below
     * it, and with no HP potion in its saved USE tab, a field slice cannot resupply and ends in a death every time (found
     * live 2026-09-30: a companion with 0 meso loaded, failed to buy, died and rested every 20 minutes for hours).
     */
    static final long FIELD_SUPPLY_MIN_MESO = 1_000L;
    public static final String NO_SUPPLIES = "out of potions and meso";
    /** Character id -> true when its saved state can neither heal nor buy potions (a test gives its own). */
    IntPredicate cannotSupplyItself = this::savedStateCannotSupply;
    /** persona ids held in the ledger because they could not supply a field slice (the card says so). */
    private final Set<String> unsupplied = ConcurrentHashMap.newKeySet();

    /** The dispatcher with {@code field} as the form of TRAIN, FARM_MESO and FARM_ITEM. */
    public BenchDutyDispatcher(LivingWorld world, BenchSessions field) {
        this(world, field, System::currentTimeMillis);
    }

    BenchDutyDispatcher(LivingWorld world, BenchSessions field, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
        for (Assignment duty : List.of(Assignment.TRAIN, Assignment.FARM_MESO, Assignment.FARM_ITEM)) {
            register(duty, field);
        }
    }

    /** Gives {@code duty} its form (null: none, so the ledger alone keeps it); a slice already live keeps its own. */
    public void register(Assignment duty, Form form) {
        if (form == null) {
            forms.remove(duty);
        } else {
            forms.put(duty, form);
        }
    }

    /** The form of {@code duty}, or null when the ledger alone keeps it. */
    public Form form(Assignment duty) {
        return duty == null ? null : forms.get(duty);
    }

    /** The CompanionLeases kind that holds a slice of {@code pool}. */
    static CompanionLeases.Kind lease(CompanionBudget.Kind pool) {
        return CompanionLeases.Kind.valueOf(pool.name());
    }

    /** Slices of {@code pool} live at once server-wide (0: that pool is off). */
    static int cap(CompanionBudget.Kind pool, LivingWorldConfig config) {
        return switch (pool) {
            case BENCH_LIVE -> Math.min(config.BENCH_LIVE_MAX, config.BENCH_LIVE_GLOBAL);
            case BENCH_TOWN -> config.BENCH_TOWN_MAX;
            case COMMERCE -> config.COMMERCE_LIVE_MAX;
            default -> 0;
        };
    }

    /** Slices of {@code pool} one owner may have live at once. */
    static int perOwner(CompanionBudget.Kind pool, LivingWorldConfig config) {
        return switch (pool) {
            case BENCH_LIVE -> config.BENCH_LIVE_PER_OWNER;
            case BENCH_TOWN -> config.BENCH_TOWN_PER_OWNER;
            case COMMERCE -> config.COMMERCE_PER_OWNER;
            default -> 0;
        };
    }

    /**
     * The end rule on time (EndRulesTest): a slice of {@code pool} that ran {@code ranMs} ends after its pool's slice
     * length, a field slice after BENCH_LIVE_SLICE_MS and town and stall slices after their own longer ones. A player
     * watching keeps a field slice on; a town or stall slice (towns and the Free Market nearly always have a player)
     * runs on to twice its length at most, so the small pools still rotate. Null while it runs on.
     */
    static String timeUp(CompanionBudget.Kind pool, long ranMs, boolean watched, LivingWorldConfig config) {
        long limit = switch (pool) {
            case BENCH_TOWN -> config.BENCH_TOWN_SLICE_MS;
            case COMMERCE -> config.COMMERCE_SLICE_MS;
            default -> config.BENCH_LIVE_SLICE_MS;
        };
        if (watched && pool != CompanionBudget.Kind.BENCH_TOWN && pool != CompanionBudget.Kind.COMMERCE) {
            return null;
        }
        return ranMs > (watched ? 2L * limit : limit) ? "slice elapsed" : null;
    }

    /**
     * The order benched companions go live in (BenchDutyDispatcherTest): owners online first; within each group one of
     * each owner's per round, the owner whose next companion waited longest first; and each owner's bench by turn, the
     * longest since its last slice first ({@code lastLiveAtMs} by persona id, 0 = never).
     */
    static List<RosterEntry> order(List<RosterEntry> rows, IntPredicate online, ToLongFunction<String> lastLiveAtMs) {
        Comparator<RosterEntry> turn = Comparator.comparingLong((RosterEntry row) -> lastLiveAtMs.applyAsLong(row.personaId()))
                .thenComparing(RosterEntry::personaId);
        Map<Integer, List<RosterEntry>> byOwner = new LinkedHashMap<>();
        for (RosterEntry row : rows) {
            byOwner.computeIfAbsent(row.characterId(), owner -> new ArrayList<>()).add(row);
        }
        List<List<RosterEntry>> onlineOwners = new ArrayList<>();
        List<List<RosterEntry>> offlineOwners = new ArrayList<>();
        for (List<RosterEntry> bench : byOwner.values()) {
            bench.sort(turn);
            (online.test(bench.get(0).characterId()) ? onlineOwners : offlineOwners).add(bench);
        }
        List<RosterEntry> out = new ArrayList<>(rows.size());
        for (List<List<RosterEntry>> group : List.of(onlineOwners, offlineOwners)) {
            group.sort(Comparator.comparing((List<RosterEntry> bench) -> bench.get(0), turn)
                    .thenComparingInt(bench -> bench.get(0).characterId()));
            int longest = group.stream().mapToInt(List::size).max().orElse(0);
            for (int round = 0; round < longest; round++) {
                for (List<RosterEntry> bench : group) {
                    if (round < bench.size()) {
                        out.add(bench.get(round));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Where a field duty of this row runs (the default camp): its camp when that is a field, else (no camp, or a town:
     * a row from before P5, or a hire camping where it was hired) the FieldCatalog's top field for the companion's level
     * near that town or its home town. Never a town; 0 when no field fits.
     */
    public static int fieldCamp(LivingWorld world, RosterEntry entry, Persona persona) {
        int camp = entry.campMapId() != 0 ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        if (!town(world, camp)) {
            return camp;
        }
        RosterService roster = world.roster();
        return roster == null ? 0 : roster.fieldCampFor(persona, camp);
    }

    /**
     * Whether a camp is a town (or no map at all): by the population's field catalog when it lists the map, so the
     * ledger never loads a camp nobody is at; else by the map itself on the living channel.
     */
    private static boolean town(LivingWorld world, int mapId) {
        Population population = Population.get();
        if (population != null && population.content().field(mapId) != null) {
            return population.fields().isTown(mapId);
        }
        MapleMap map = world.residents().map(mapId);
        return map == null || map.isTown();
    }

    /** EXP gained from {@code fromExp} at {@code fromLevel} to {@code toExp} at {@code toLevel} (0 for a loss). */
    static long expGained(int fromLevel, long fromExp, int toLevel, long toExp) {
        if (toLevel <= fromLevel) {
            return Math.max(0L, toExp - fromExp);
        }
        long gained = Math.max(0L, ExpTable.getExpNeededForLevel(fromLevel) - fromExp);
        for (int level = fromLevel + 1; level < toLevel; level++) {
            gained += ExpTable.getExpNeededForLevel(level);
        }
        return gained + Math.max(0L, toExp);
    }

    // ---- what is live ----------------------------------------------------------------------

    /** True while the persona is on a live slice, whatever its form. */
    public boolean live(String personaId) {
        return personaId != null && live.containsKey(personaId);
    }

    /** The persona's live slice, or null. */
    public Slice slice(String personaId) {
        return personaId == null ? null : live.get(personaId);
    }

    /** The live slice of this loaded character, or null (any thread: the combat learner asks, BenchCombatLearning). */
    public Slice sliceOf(int characterId) {
        return byCharacter.get(characterId);
    }

    public int liveCount() {
        return live.size();
    }

    /** Live slices charged to {@code pool} server-wide. */
    public int liveCount(CompanionBudget.Kind pool) {
        return count(pool, 0);
    }

    /** Live slices of {@code pool} charged to this owner, for the per-owner BENCH_LIVE quota. */
    public int liveCountFor(int ownerId, CompanionBudget.Kind pool) {
        return count(pool, ownerId);
    }

    private int count(CompanionBudget.Kind pool, int ownerId) {
        int count = 0;
        for (Slice slice : live.values()) {
            if (slice.pool == pool && (ownerId == 0 || slice.ownerId == ownerId)) {
                count++;
            }
        }
        return count;
    }

    /** Character ids of every live slice (a copy), for the operator budget. */
    public Set<Integer> liveCharacterIds() {
        return new HashSet<>(byCharacter.keySet());
    }

    /**
     * What the card says of the companion's bench: {@link #ROUGH_HUNT} while it rests after a death, {@link #NO_SUPPLIES}
     * while it cannot supply a field slice, else "".
     */
    public String note(String personaId) {
        Long until = personaId == null ? null : restingUntilMs.get(personaId);
        if (until != null && until > clock.getAsLong()) {
            return ROUGH_HUNT;
        }
        return personaId != null && unsupplied.contains(personaId) ? NO_SUPPLIES : "";
    }

    /** Its saved USE tab holds no HP potion and its saved purse cannot buy a stack (a failed read: it may try). */
    private boolean savedStateCannotSupply(int characterId) {
        long meso = world.store().characterMeso(characterId);
        return meso >= 0 && meso < FIELD_SUPPLY_MIN_MESO && CrewRotation.savedTabHasNoHpPotion(world.store(), characterId);
    }

    // ---- Your call (a standing intent) -------------------------------------------------------

    private static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }

    /** Reads which rows are on "Your call" (LivingWorld.start, on companion-ops); a failed read leaves them off. */
    public void loadYourCalls() {
        try {
            world.store().yourCalls().forEach((ownerId, personas) -> personas.forEach(persona -> yourCall.add(key(ownerId, persona))));
        } catch (LivingStore.ReadFailed failure) {
            log.warn("your call: the rows on it could not be read", failure);
        }
    }

    /** True while the companion picks its own duty and camp before each slice. */
    public boolean yourCall(int ownerId, String personaId) {
        return personaId != null && yourCall.contains(key(ownerId, personaId));
    }

    /**
     * Turns "Your call" on (the duty picker's pick) or off (any duty or camp its owner gives it, RosterService) for the
     * row; written only when it changes. False when the write failed (it stays as it was).
     */
    public boolean setYourCall(int ownerId, String personaId, boolean on) {
        if (personaId == null || yourCall(ownerId, personaId) == on) {
            return true;
        }
        if (!world.store().saveYourCall(ownerId, personaId, on)) {
            return false;
        }
        if (on) {
            yourCall.add(key(ownerId, personaId));
        } else {
            yourCall.remove(key(ownerId, personaId));
        }
        return true;
    }

    /**
     * Before a slice of a row on Your call: the companion picks again (BenchDutyChooser, on what the last slices
     * measured) and its new duty or camp is set as its own pick. The row as it stands now.
     */
    private RosterEntry pickAgain(RosterEntry entry) {
        if (!yourCall(entry.characterId(), entry.personaId())) {
            return entry;
        }
        Persona persona = world.content().persona(entry.personaId());
        int characterId = persona == null ? 0 : world.residents().characterIdOf(persona.id());
        int[] levelJob = characterId == 0 ? null : world.store().characterLevelAndJob(characterId);
        if (levelJob == null || levelJob.length < 2 || levelJob[0] <= 0) {
            return entry;
        }
        int camp = entry.campMapId() != 0 ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        BondService bonds = world.bonds();
        Bond bond = bonds == null ? null : bonds.peek(entry.characterId(), persona.id());
        BenchDutyChooser.Pick pick = new BenchDutyChooser(world).choose(entry, persona, bond == null ? null : bond.stage(),
                levelJob[0], levelJob[1], world.store().characterLevel(entry.characterId()),
                yourCallCamps.apply(levelJob[0], camp));
        int pickedCamp = pick == null || pick.campMapId() == 0 ? entry.campMapId() : pick.campMapId();
        if (pick == null || pick.duty() == entry.assignment() && pickedCamp == entry.campMapId()) {
            return entry;
        }
        RosterEntry now = world.roster().applyYourCall(entry.characterId(), persona.id(), pick.duty(), pickedCamp);
        if (now == null) {
            return entry;
        }
        world.store().event(entry.characterId(), persona.id(), "bench.your_call", pick.duty() + " at " + pickedCamp + " ("
                + pick.why() + String.format(java.util.Locale.ROOT, ", score %.2f + %.2f)", pick.score(), pick.bonus()));
        CompanionViews.invalidate(world, entry.characterId(), persona.id());
        return now;
    }

    // ---- hooks (any thread) ------------------------------------------------------------------

    /** The character picked up an item stack or meso (LivingWorldHooks.looted): counted when it is on a live slice. */
    public void looted(int characterId, int itemId, int quantity, int meso) {
        Slice slice = byCharacter.get(characterId);
        if (slice != null) {
            slice.loot.item(itemId, quantity);
            slice.loot.meso(meso);
        }
    }

    /** The character died (LivingWorldHooks.playerDied): a live slice ends once it has revived (flow 12). */
    public void died(int characterId) {
        Slice slice = byCharacter.get(characterId);
        if (slice != null) {
            slice.deaths.incrementAndGet();
            slice.died = true;
        }
    }

    // ---- ticks, starts and ends (companion-ops) ---------------------------------------------

    /**
     * Character loads and unloads run on companion-ops like every companion load (plan "CompanionExecutor"): called
     * from any other thread (a timer, a shutdown), the work is handed over there and this returns true.
     */
    private boolean handedOver(String name, Runnable work) {
        CompanionExecutor ops = world.executor();
        if (ops == null || ops.onExecutorThread()) {
            return false;
        }
        ops.submit(name, work);
        return true;
    }

    /**
     * Runs {@code action} on the Agent's own mailbox (its next tick; at once where the Agent has no mailbox) and waits
     * for it at most {@link #ON_AGENT_WAIT_MS}: its result, or null when it failed, was refused or did not run in time.
     */
    static <R> R onAgent(AgentRuntimeEntry entry, AgentMailboxAction<R> action) {
        if (entry == null) {
            return null;
        }
        CompletableFuture<R> result = AgentMailboxRuntime.dispatch(entry, action);
        try {
            return result == null ? null : result.get(ON_AGENT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | CancellationException failure) {
            log.debug("an action on an Agent's mailbox did not complete", failure);
            return null;
        }
    }

    /** Keeps or ends every live slice by the end rules, then starts the slices that are due and fit their pools. */
    public void tick() {
        if (handedOver("bench_duty.tick", this::tick)) {
            return;
        }
        long now = clock.getAsLong();
        for (Slice slice : new ArrayList<>(live.values())) {
            try {
                String reason;
                if (slice.closing) {
                    reason = slice.endReason; // an unload that failed: once more
                } else {
                    try {
                        reason = check(slice, now);
                    } catch (RuntimeException failure) {
                        log.warn("bench slice of {} failed its check", slice.personaId, failure);
                        reason = "check failed";
                    }
                }
                if (reason != null) {
                    end(slice, reason, now);
                }
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed to end", slice.personaId, failure);
            }
        }
        startDue(now);
    }

    /** Ends a live slice before the character is needed elsewhere (a call, a release, a shutdown). */
    public void endFor(String personaId, String reason) {
        if (handedOver("bench_duty.end " + personaId, () -> endFor(personaId, reason))) {
            return;
        }
        Slice slice = slice(personaId);
        if (slice != null) {
            end(slice, reason, clock.getAsLong());
        }
    }

    public void endAll(String reason) {
        if (handedOver("bench_duty.end_all", () -> endAll(reason))) {
            return;
        }
        long now = clock.getAsLong();
        for (Slice slice : new ArrayList<>(live.values())) {
            try {
                end(slice, reason, now);
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed to end", slice.personaId, failure);
            }
        }
    }

    /** The end rules every slice keeps (see the class comment), then its form's own; null while it runs on. */
    private String check(Slice slice, long now) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        RosterEntry roster = world.store().rosterEntry(slice.ownerId, slice.personaId);
        if (agent == null) {
            return "gone";
        }
        if (!BenchSessions.benchGoverned(roster) || roster.assignment() != slice.duty) {
            return "assignment changed";
        }
        if (roster.campMapId() != slice.rowCampMapId) {
            return "camp changed";
        }
        if (agent.getClient() != null && agent.getClient().getChannel() != world.config().CHANNEL) {
            return "off the living channel";
        }
        if (slice.died) {
            return agent.getHp() > 0 ? ROUGH_HUNT : null; // it revives by stock rules in its camp's return town first
        }
        if (slice.resupplying()) {
            if (!resupplying(entry)) {
                return "resupplied";
            }
            return now - slice.huntEndedAtMs >= world.config().BENCH_RESUPPLY_MAX_MS ? "resupply took too long" : null;
        }
        if (slice.pool == CompanionBudget.Kind.BENCH_LIVE && resupplying(entry)) {
            slice.huntEndedAtMs = now; // out of supplies: the hunt is over, its own shop visit runs, then it unloads
            world.store().event(slice.ownerId, slice.personaId, "bench.resupply", "out of supplies at map " + slice.mapId);
            return null;
        }
        String own = slice.form.check(slice, agent, roster, now);
        if (own != null) {
            return own;
        }
        return timeUp(slice.pool, now - slice.startedAtMs, agent.getMap() != null && ResidentRuntime.humansIn(agent.getMap()) > 0,
                world.config());
    }

    /** True while the Agent's own supply procurement (the existing shop resupply) runs. */
    private static boolean resupplying(AgentRuntimeEntry entry) {
        return entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false);
    }

    private void startDue(long now) {
        LivingWorldConfig config = world.config();
        boolean room = false;
        for (CompanionBudget.Kind pool : List.of(CompanionBudget.Kind.BENCH_LIVE, CompanionBudget.Kind.BENCH_TOWN,
                CompanionBudget.Kind.COMMERCE)) {
            room |= count(pool, 0) < cap(pool, config);
        }
        if (!room) {
            return;
        }
        List<RosterEntry> due = new ArrayList<>();
        Set<Integer> owners = new HashSet<>();
        for (RosterEntry entry : world.store().allBenched()) {
            if (BenchSessions.benchGoverned(entry) && form(entry.assignment()) != null && !live.containsKey(entry.personaId())
                    && restingUntilMs.getOrDefault(entry.personaId(), 0L) <= now
                    && world.roster().companion(entry) == null && world.residents().agentOf(entry.personaId()) == null) {
                due.add(entry);
                owners.add(entry.characterId());
            }
        }
        Set<Integer> online = new HashSet<>();
        for (int owner : owners) {
            if (world.roster().onlineCharacter(owner) != null) {
                online.add(owner);
            }
        }
        for (RosterEntry turn : order(due, online::contains, persona -> lastLiveAtMs.getOrDefault(persona, 0L))) {
            RosterEntry entry;
            try {
                entry = pickAgain(turn); // Your call: it picks its duty and camp again first
            } catch (RuntimeException failure) {
                log.warn("your call for {} failed; it keeps its duty", turn.personaId(), failure);
                entry = turn;
            }
            Form form = form(entry.assignment());
            if (form == null) {
                continue;
            }
            CompanionBudget.Kind pool = form.pool();
            if (count(pool, 0) >= cap(pool, config) || count(pool, entry.characterId()) >= perOwner(pool, config)) {
                continue;
            }
            begin(entry, form, now);
        }
    }

    /**
     * Where a slice of {@code pool} loads on {@code map}: at a portal for a town slice, or on a map a player is on, so it
     * walks in (TownLife or its hunt takes it from there); else at one of the map's own spots, by persona, so two
     * companions never stand on the same one.
     */
    static Point arrival(MapleMap map, CompanionBudget.Kind pool, String personaId) {
        if (pool == CompanionBudget.Kind.BENCH_TOWN || ResidentRuntime.humansIn(map) > 0) {
            Point portal = ArrivalPlacement.portalNear(map, null);
            if (portal != null) {
                return portal;
            }
        }
        return ResidentSpots.fallback(map, personaId.hashCode());
    }

    private void begin(RosterEntry entry, Form form, long now) {
        Persona persona = world.content().persona(entry.personaId());
        int characterId = persona == null ? 0 : world.residents().characterIdOf(persona.id());
        if (characterId == 0) {
            return;
        }
        if (form.pool() == CompanionBudget.Kind.BENCH_LIVE && cannotSupplyItself.test(characterId)) {
            if (unsupplied.add(persona.id())) { // the ledger keeps it until its purse or bag can (coin for supplies)
                CompanionViews.invalidate(world, entry.characterId(), persona.id());
                world.store().event(entry.characterId(), persona.id(), "bench.unsupplied", NO_SUPPLIES);
            }
            lastLiveAtMs.put(persona.id(), now); // its turn passes
            return;
        }
        if (unsupplied.remove(persona.id())) {
            CompanionViews.invalidate(world, entry.characterId(), persona.id());
        }
        int mapId = form.mapFor(entry, persona, characterId);
        MapleMap map = mapId == 0 ? null : world.residents().map(mapId);
        if (map == null) {
            lastLiveAtMs.put(persona.id(), now); // not now: the ledger keeps it, and its turn passes
            return;
        }
        CompanionLeases.Kind lease = lease(form.pool());
        // never a second copy of a character that is loaded, or leased for something else
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)
                || !world.leases().tryAcquire(characterId, lease, entry.characterId())) {
            return;
        }
        if (!world.budget().admit(form.pool(), entry.characterId(), now).allowed()) {
            world.leases().release(characterId, lease);
            return;
        }
        Character agent = null;
        boolean begun = false;
        Slice slice = new Slice(entry, characterId, form, mapId, now);
        try {
            agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL,
                    map, arrival(map, form.pool(), persona.id()));
            if (agent == null) {
                world.leases().release(characterId, lease);
                return;
            }
            live.put(persona.id(), slice);
            byCharacter.put(characterId, slice);
            // it has rested off-screen since its last slice (a player idling in town regenerates): it comes back whole.
            // Found live 2026-09-30: a companion saved at its stock-revive HP (50-70) loaded onto its field that low and
            // died within seconds, slice after slice (Jinx 15 deaths in 16 slices).
            agent.healHpMp();
            world.bench().applyAccrued(entry, agent);
            slice.snapshot(agent);
            if (!(form instanceof BenchStallForm)) {
                reclaimStall(entry.characterId(), persona.id(), agent); // a stall's own begin takes its stock back itself
            }
            if (!form.begin(slice, agent, now)) {
                close(slice, agent, "not begun", false, now); // its form left nothing running
                return;
            }
            begun = true;
            CompanionSettingsApplier settings = world.roster().settings();
            if (settings != null) { // a test's roster may have none
                settings.bind(agent, entry.characterId(), persona.id()); // it drinks by its own potion % here too (P3b)
            }
            lastLiveAtMs.put(persona.id(), now);
            if (restingUntilMs.remove(persona.id()) != null) {
                CompanionViews.invalidate(world, entry.characterId(), persona.id());
            }
            world.store().event(entry.characterId(), persona.id(), "bench.live",
                    world.roster().describeAssignment(entry) + " live at map " + mapId);
            log.info("bench slice: {} {} live at {} for {}", persona.name(), world.roster().describeAssignment(entry), mapId,
                    entry.characterId());
        } catch (Exception failure) {
            log.warn("bench slice for {} failed", persona.id(), failure);
            if (agent == null) {
                forget(slice);
                world.leases().release(characterId, lease);
            } else if (begun) {
                end(slice, "begin failed", now); // its form began: its tidy-up and the hand-back run as for any end
            } else {
                close(slice, agent, "begin failed", true, now); // its form may have started something first
            }
        }
    }

    /** A slice whose form never began: the hand-back when {@code handBack}, then the unload; nothing to record. */
    private void close(Slice slice, Character agent, String reason, boolean handBack, long now) {
        slice.closing = true;
        slice.endReason = reason;
        slice.leaving = agent;
        slice.handedBack = !handBack || handBack(slice, agent, reason, now);
        if (unload(slice)) {
            forget(slice);
        }
    }

    /**
     * Ends a slice. Each step of its tidy-up runs on its own, so one failing never skips the others; the unload comes
     * last, and a companion still online after it keeps its lease and its slot and is unloaded again next tick.
     */
    private void end(Slice slice, String reason, long now) {
        if (!slice.closing) {
            slice.closing = true;
            slice.endReason = reason;
            lastLiveAtMs.put(slice.personaId, now);
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId);
            Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
            slice.leaving = agent;
            try {
                slice.form.finish(slice, agent, reason); // its last harvest
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed to finish", slice.personaId, failure);
            }
            slice.handedBack = handBack(slice, agent, reason, now);
            try {
                record(slice, agent, now);
            } catch (RuntimeException failure) {
                log.warn("bench yield of {} was not recorded", slice.personaId, failure);
            }
            try {
                BenchLedger ledger = world.bench();
                if (ledger != null) { // the call-back report: the EXP it gained here and the deaths it took
                    ledger.reportSlice(slice.ownerId, slice.personaId, slice.mapId, agent == null ? 0L
                            : expGained(slice.startLevel, slice.startExp, agent.getLevel(), agent.getExp()), slice.startLevel,
                            slice.deaths.get());
                }
            } catch (RuntimeException failure) {
                log.warn("bench report of {} was not kept", slice.personaId, failure);
            }
            if (ROUGH_HUNT.equals(reason)) {
                restingUntilMs.put(slice.personaId, now + world.config().BENCH_LIVE_SLICE_MS);
                CompanionViews.invalidate(world, slice.ownerId, slice.personaId);
            }
        }
        if (!unload(slice)) {
            return; // still online: it keeps its lease and its slot, and the next tick unloads it again
        }
        forget(slice);
        world.store().event(slice.ownerId, slice.personaId, "bench.live-end", slice.endReason);
    }

    private void forget(Slice slice) {
        live.remove(slice.personaId, slice);
        byCharacter.remove(slice.characterId, slice);
    }

    /**
     * Unloads the slice's companion: out of the registry, then saved and disconnected. Its lease is released once it is
     * offline (the save first, so the next load sees it); true then. While it is still online its lease stays, so
     * nothing can load a second copy of it. A hand-back that never ran on its own tick is finished here: once it is
     * out of the registry nothing ticks it any more.
     */
    private boolean unload(Slice slice) {
        Character agent = slice.leaving;
        try {
            AgentRuntimeCleanupService.removeAgentByCharacterId(slice.characterId);
        } catch (RuntimeException failure) {
            log.warn("bench slice of {} failed to leave the registry", slice.personaId, failure);
        }
        if (!slice.handedBack && agent != null) {
            try {
                AgentFieldVisitLeaseRuntime.clear(null, agent);
                AgentTownLifeVisitLeaseRuntime.clear(null, agent);
                slice.handedBack = true;
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed to clear its visit checkpoints", slice.personaId, failure);
            }
        }
        boolean offline = agent == null || agent.getClient() == null;
        if (!offline) {
            try {
                agent.saveCharToDB();
                agent.getClient().forceDisconnect();
                offline = true;
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed to unload; it keeps its lease and is unloaded again next tick",
                        slice.personaId, failure);
            }
        }
        if (offline) {
            world.leases().release(slice.characterId, slice.lease());
        }
        return offline;
    }

    /** A field slice's yield, for the model: how long it hunted, and the EXP, meso, items and deaths it took there. */
    private void record(Slice slice, Character agent, long now) {
        BenchYieldModel yields = world.benchYields();
        // only the grinders' slices (TRAIN, FARM_MESO, FARM_ITEM): a GATHER visit fights only what drops its items
        if (yields == null || agent == null || !(slice.form instanceof BenchSessions)) { // a test's world may have none
            return;
        }
        long huntedMs = (slice.resupplying() ? slice.huntEndedAtMs : now) - slice.startedAtMs;
        yields.record(BenchYieldModel.Key.of(slice.jobId, slice.startLevel, slice.mapId), new BenchYieldModel.Sample(
                huntedMs / 3_600_000.0d, expGained(slice.startLevel, slice.startExp, agent.getLevel(), agent.getExp()),
                slice.loot.mesoTotal(), slice.loot.itemsTotal(), slice.deaths.get()));
    }

    /**
     * A companion just loaded (a slice of any duty, or a call): what an interrupted stall of its left in its bag or in a
     * shop escrow goes back to the haul first (BenchStallForm), whatever its duty is now.
     */
    public void reclaimStall(int ownerId, String personaId, Character agent) {
        for (Form form : forms.values()) {
            if (form instanceof BenchStallForm stall) {
                stall.reclaim(ownerId, personaId, agent);
                return;
            }
        }
    }

    /**
     * Hands a slice's Agent back clean before it unloads, on its own mailbox (its tick may be running): each primary
     * activity a form may have started (a hunt, a town visit, a stall's commerce) is asked to stop at its boundary, a
     * hunt or town visit still running is stopped (it unloads now), and the field and town visit-lease checkpoints are
     * cleared, so its next load (a call from its owner) restores no bench activity. A stall's commerce is closed by its
     * form first and is never abandoned here. False when it did not run in time (the unload clears the checkpoints).
     */
    private boolean handBack(Slice slice, Character agent, String reason, long nowMs) {
        AgentRuntimeEntry entry = agent == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId);
        if (entry == null) {
            return agent == null;
        }
        String why = "bench slice ended: " + reason;
        return Boolean.TRUE.equals(onAgent(entry, runtime -> {
            for (AgentActivityKind kind : HAND_BACK) {
                if (!AgentActivityBootstrap.requestPrimaryStop(runtime, agent, kind, why, nowMs) && kind != AgentActivityKind.COMMERCE) {
                    AgentActivityBootstrap.forcePrimaryStop(runtime, agent, kind, why, nowMs);
                }
            }
            AgentFieldVisitLeaseRuntime.clear(runtime, agent);
            AgentTownLifeVisitLeaseRuntime.clear(runtime, agent);
            return Boolean.TRUE;
        }));
    }
}
