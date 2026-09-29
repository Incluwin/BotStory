package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The one place a benched companion is made live (docs/BOT_MANAGEMENT_PLAN.md P5, "Living bench", flow 2). Each bench
 * duty has a {@link Form} that shows it in the world: TRAIN, FARM_MESO and FARM_ITEM grind at a field camp
 * ({@link BenchSessions}); GATHER, STALL, SELL and REST get theirs through {@link #register}; a duty with no form stays
 * in the ledger alone (BenchLedger). Each slice is charged to a CompanionBudget pool and held by the CompanionLeases
 * lease of the same name: BENCH_LIVE (per owner and server-wide) for the field forms, BENCH_TOWN (cheap town presence)
 * and COMMERCE (Free Market stalls), each with its own server-wide cap. When a pool is contended, owners online go
 * first, and each owner's bench takes turns (the longest since its last slice first), one of each owner's per round.
 * Every slice loads on the living channel.
 *
 * <p>End rules. Every slice ends when its row stops being bench-governed or changes duty or camp, when its owner calls
 * it or it is released ({@link #endFor}), when its character is gone or off the living channel, after its pool's slice
 * length unless a player is watching it (town and stall slices run longer, and are never ended by being in a town), and
 * by its form's own rules (a field slice back in town, at its pace target, at the daily farming cap or with what it was
 * asked for). Flow 12: a death ends it once the companion has revived by stock rules in its camp's return town, and it
 * rests one field slice ("resting after a rough hunt") while the ledger resumes; out of supplies, its hunt ends, the
 * Agent's own shop resupply runs, and only then does the slice unload and free its seat for the next one. A slice
 * ends clean: its form's last harvest, each primary activity asked to stop (AgentActivityBootstrap.requestPrimaryStop),
 * the visit-lease checkpoints cleared, the field yield recorded (BenchYieldModel), then the unload, the save and the
 * lease.</p>
 *
 * <p>Every load, tick and unload runs on companion-ops; asked from another thread, the work is handed over there. The
 * pickup and death hooks ({@link #looted}, {@link #died}) come from the Agent's own tick and only count.</p>
 */
public final class BenchDutyDispatcher {
    private static final Logger log = LoggerFactory.getLogger(BenchDutyDispatcher.class);
    /** The card's line for a companion resting after a death on a live slice (flow 12). */
    public static final String ROUGH_HUNT = "resting after a rough hunt";
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

    /**
     * The end rule on time (EndRulesTest): a slice of {@code pool} that ran {@code ranMs} ends after its pool's slice
     * length, a field slice after BENCH_LIVE_SLICE_MS and town and stall slices after their own longer ones, unless a
     * player is watching it. Null while it runs on.
     */
    static String timeUp(CompanionBudget.Kind pool, long ranMs, boolean watched, LivingWorldConfig config) {
        long limit = switch (pool) {
            case BENCH_TOWN -> config.BENCH_TOWN_SLICE_MS;
            case COMMERCE -> config.COMMERCE_SLICE_MS;
            default -> config.BENCH_LIVE_SLICE_MS;
        };
        return !watched && ranMs > limit ? "slice elapsed" : null;
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

    /** What the card adds to the companion's duty: {@link #ROUGH_HUNT} while it rests after a death, else "". */
    public String note(String personaId) {
        Long until = personaId == null ? null : restingUntilMs.get(personaId);
        return until != null && until > clock.getAsLong() ? ROUGH_HUNT : "";
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

    /** Keeps or ends every live slice by the end rules, then starts the slices that are due and fit their pools. */
    public void tick() {
        if (handedOver("bench_duty.tick", this::tick)) {
            return;
        }
        long now = clock.getAsLong();
        for (Slice slice : new ArrayList<>(live.values())) {
            String reason;
            try {
                reason = check(slice, now);
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed its check", slice.personaId, failure);
                reason = "check failed";
            }
            if (reason != null) {
                end(slice, reason, now);
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
            end(slice, reason, now);
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
        for (RosterEntry entry : order(due, online::contains, persona -> lastLiveAtMs.getOrDefault(persona, 0L))) {
            Form form = form(entry.assignment());
            CompanionBudget.Kind pool = form.pool();
            if (count(pool, 0) >= cap(pool, config)
                    || pool == CompanionBudget.Kind.BENCH_LIVE && count(pool, entry.characterId()) >= config.BENCH_LIVE_PER_OWNER) {
                continue;
            }
            begin(entry, form, now);
        }
    }

    private void begin(RosterEntry entry, Form form, long now) {
        Persona persona = world.content().persona(entry.personaId());
        int characterId = persona == null ? 0 : world.residents().characterIdOf(persona.id());
        if (characterId == 0) {
            return;
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
        Slice slice = new Slice(entry, characterId, form, mapId, now);
        try {
            agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL,
                    map, ResidentSpots.fallback(map, 0));
            if (agent == null) {
                world.leases().release(characterId, lease);
                return;
            }
            world.bench().applyAccrued(entry, agent);
            slice.snapshot(agent);
            live.put(persona.id(), slice);
            byCharacter.put(characterId, slice);
            if (!form.begin(slice, agent, now)) {
                forget(slice);
                unload(characterId, agent, lease);
                return;
            }
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
            forget(slice);
            if (agent != null) {
                unload(characterId, agent, lease);
            } else {
                world.leases().release(characterId, lease);
            }
            log.warn("bench slice for {} failed", persona.id(), failure);
        }
    }

    private void end(Slice slice, String reason, long now) {
        forget(slice);
        lastLiveAtMs.put(slice.personaId, now);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        try {
            slice.form.finish(slice, agent, reason); // its last harvest
        } catch (RuntimeException failure) {
            log.warn("bench slice of {} failed to finish", slice.personaId, failure);
        }
        if (entry != null && agent != null) {
            try {
                handBack(entry, agent, reason, now);
            } catch (RuntimeException failure) {
                log.warn("bench slice of {} failed its hand-back", slice.personaId, failure);
            }
        }
        record(slice, agent, now);
        BenchLedger ledger = world.bench();
        if (ledger != null) { // the call-back report: the EXP it gained here and the deaths it took
            ledger.reportSlice(slice.ownerId, slice.personaId, slice.mapId, agent == null ? 0L
                    : expGained(slice.startLevel, slice.startExp, agent.getLevel(), agent.getExp()), slice.startLevel, slice.deaths.get());
        }
        if (ROUGH_HUNT.equals(reason)) {
            restingUntilMs.put(slice.personaId, now + world.config().BENCH_LIVE_SLICE_MS);
            CompanionViews.invalidate(world, slice.ownerId, slice.personaId);
        }
        unload(slice.characterId, agent, slice.lease());
        world.store().event(slice.ownerId, slice.personaId, "bench.live-end", reason);
    }

    private void forget(Slice slice) {
        live.remove(slice.personaId, slice);
        byCharacter.remove(slice.characterId, slice);
    }

    private void unload(int characterId, Character agent, CompanionLeases.Kind lease) {
        AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
        if (agent != null && agent.getClient() != null) {
            agent.saveCharToDB();
            agent.getClient().forceDisconnect();
        }
        world.leases().release(characterId, lease); // saved first, so the next load sees it
    }

    /** A field slice's yield, for the model: how long it hunted, and the EXP, meso, items and deaths it took there. */
    private void record(Slice slice, Character agent, long now) {
        BenchYieldModel yields = world.benchYields();
        if (yields == null || agent == null || slice.pool != CompanionBudget.Kind.BENCH_LIVE) { // a test's world may have none
            return;
        }
        long huntedMs = (slice.resupplying() ? slice.huntEndedAtMs : now) - slice.startedAtMs;
        try {
            yields.record(BenchYieldModel.Key.of(slice.jobId, slice.startLevel, slice.mapId), new BenchYieldModel.Sample(
                    huntedMs / 3_600_000.0d, expGained(slice.startLevel, slice.startExp, agent.getLevel(), agent.getExp()),
                    slice.loot.mesoTotal(), slice.loot.itemsTotal(), slice.deaths.get()));
        } catch (RuntimeException failure) {
            log.warn("bench yield of {} was not recorded", slice.personaId, failure);
        }
    }

    /**
     * Hands a slice's Agent back clean before it unloads: each primary activity a form may have started (a hunt, a town
     * visit, a stall's commerce) is asked to stop at its boundary, a hunt or town visit still running is stopped (it
     * unloads now), and the field and town visit-lease checkpoints are cleared, so its next load (a call from its owner)
     * restores no bench activity. A stall's commerce is closed by its form first and is never abandoned here.
     */
    public static void handBack(AgentRuntimeEntry entry, Character agent, String reason, long nowMs) {
        String why = "bench slice ended: " + reason;
        for (AgentActivityKind kind : HAND_BACK) {
            if (!AgentActivityBootstrap.requestPrimaryStop(entry, agent, kind, why, nowMs) && kind != AgentActivityKind.COMMERCE) {
                AgentActivityBootstrap.forcePrimaryStop(entry, agent, kind, why, nowMs);
            }
        }
        AgentFieldVisitLeaseRuntime.clear(entry, agent);
        AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
    }
}
