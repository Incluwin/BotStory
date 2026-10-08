package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.capabilities.expedition.boss.AgentBossRunService.Enlisted;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Bench allies in a boss run (P7 Part B, flow 4; Mu Young's Easy Balrog): an owner on the entry NPC's list calls
 * benched companions of theirs down to fight beside them. Each ally's trip is kept here in memory, from the call until
 * it is unloaded again: CALLING (reserved on the owner's I/O thread), TRAVELLING (its EXPEDITION lease held, enlisted
 * in the Agent layer's run as a member that leads itself, committed under its owner's run key, not loaded), HERE
 * (loaded in her room or in the fight) and LEAVING. A call is admitted by the budget before anything changes, then it
 * ends the ally's live bench slice and the ally whispers the roster's own recall ETA; at that ETA it loads at the
 * room's arrival portal, where players coming down land, as an idle Agent that leads itself, and the run's tick walks
 * it up to her. A call that fails undoes itself, last step first, and the ally whispers that it cannot come. While a
 * trip lasts, bench duty and the bench ledger leave its persona alone. Calls and arrivals run on companion-ops; the
 * picker (who can come, and how many) reads the crew cache and memory on the owner's I/O thread. It writes no roster
 * state. Owned by CompanionBossRuns; nothing survives a restart (the rows stayed BENCHED).
 */
final class CompanionBossAllies {
    private static final Logger log = LoggerFactory.getLogger(CompanionBossAllies.class);

    /** Where an ally's trip is. */
    enum State {
        /** Reserved on the owner's I/O thread; its call waits on companion-ops. */
        CALLING,
        /** Lease held, enlisted and committed; on its way, not loaded. */
        TRAVELLING,
        /** Loaded in the entry NPC's room or in the fight. */
        HERE,
        /** Walking out, or waiting to unload. */
        LEAVING
    }

    /** Why a benched companion of the owner's cannot be called now. */
    enum NotOffered {
        /** It cannot reach the entry NPC before the list closes ("cannot reach me before the list closes"). */
        TOO_FAR,
        /** Outside the expedition's level range ("is not ready for this battle"). */
        NOT_READY,
        /** It has a trip already ("is already on the way"). */
        ON_THE_WAY,
        /** Tied to a run, in a boss run, loaded for something else than its live slice, or walking out ("busy"). */
        BUSY
    }

    /** One benched companion of the owner's as the entry NPC's picker shows it; {@code why} null: it can be called. */
    record Row(String personaId, String name, int level, String job, long etaMs, NotOffered why) {
        boolean offered() {
            return why == null;
        }
    }

    /**
     * What the entry NPC's picker shows the owner: their benched companions in roster order, and how many more of
     * theirs she can still take ({@code left}: the least of the list's seat share, the server's permit share and the
     * allies an owner may bring to one run). {@code deadlineMs} is when the run stops taking sign-ups.
     */
    record Picker(AgentBossRunDefinition definition, Expedition expedition, long deadlineMs, int left, List<Row> rows) {
        Picker {
            rows = List.copyOf(rows);
        }

        /** The row of {@code personaId}, or null when it is not one of these. */
        Row row(String personaId) {
            for (Row row : rows) {
                if (row.personaId().equals(personaId)) {
                    return row;
                }
            }
            return null;
        }
    }

    /** One ally's trip, from its call until it is unloaded again. */
    static final class Trip {
        final String personaId;
        final int ownerId;
        final String bossKey;
        final Expedition expedition;
        /** Its character (0 until its call on companion-ops resolves it). */
        volatile int characterId;
        volatile State state = State.CALLING;
        /** The Agent layer's run it is enlisted in (0 until its enlist). */
        volatile long sessionId;
        /** Bumped by each step that ends a wait: a timer of an older generation does nothing. */
        volatile int generation;

        Trip(String personaId, int ownerId, String bossKey, Expedition expedition) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.bossKey = bossKey;
            this.expedition = expedition;
        }
    }

    /** What a trip asks of the game (the live adapters below); tests use fakes. */
    interface Ports {
        /**
         * Loads the offline character on {@code map} at {@code at} on {@code channel} and registers it as an idle Agent
         * that leads itself; null when it did not load.
         */
        Character load(int characterId, int worldId, int channel, MapleMap map, Point at) throws Exception;

        /** True while any Agent runtime holds the character. */
        boolean loaded(int characterId);

        /** True when the Agent carries less than {@code bossKey}'s potion standard (AgentSupplyReadiness). */
        boolean shortOfPotions(Character agent, String bossKey);

        Ports LIVE = new Ports() {
            @Override
            public Character load(int characterId, int worldId, int channel, MapleMap map, Point at) throws Exception {
                Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, worldId, channel, map, at);
                if (agent != null) {
                    try { // loaded: from here on only its unload lets its lease go
                        AgentInteractionRuntime.registerDirectorIdleAgent(agent);
                    } catch (RuntimeException failure) {
                        log.warn("boss ally {} loaded but did not register", characterId, failure);
                    }
                }
                return agent;
            }

            @Override
            public boolean loaded(int characterId) {
                return AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId);
            }

            @Override
            public boolean shortOfPotions(Character agent, String bossKey) {
                return !AgentSupplyReadiness.shortfall(agent, AgentSupplyReadiness.Activity.partyQuest(bossKey)).none();
            }
        };
    }

    /** Where a called ally's live bench slice ends, as the bench's event log says it. */
    static final String CALLED = "called to the temple";

    private final LivingWorld world;
    private final CompanionBossRuns.OpenRuns open;
    /** CompanionBossRuns' owners' parts of their runs, by run key: an ally's lines rotate their wordings there. */
    private final Map<String, CompanionBossRun> runs;
    private final Ports ports;
    private final LongSupplier clock;
    /** Persona id -> its trip; one trip per persona. Reserved under this object's lock, moved on companion-ops. */
    private final Map<String, Trip> trips = new ConcurrentHashMap<>();
    private final AtomicInteger turns = new AtomicInteger();

    /** The boss and the expedition an owner on its entry NPC's list may call allies to now. */
    private record Context(AgentBossRunDefinition definition, Expedition expedition) { }

    CompanionBossAllies(LivingWorld world, CompanionBossRuns.OpenRuns open, Map<String, CompanionBossRun> runs) {
        this(world, open, runs, Ports.LIVE, System::currentTimeMillis);
    }

    CompanionBossAllies(LivingWorld world, CompanionBossRuns.OpenRuns open, Map<String, CompanionBossRun> runs,
                        Ports ports, LongSupplier clock) {
        this.world = world;
        this.open = open;
        this.runs = runs;
        this.ports = ports;
        this.clock = clock;
    }

    /** True while {@code personaId} has a trip, in any state (memory, any thread): bench duty and its ledger wait. */
    boolean onTrip(String personaId) {
        return personaId != null && trips.containsKey(personaId);
    }

    /** The persona's trip, or null. */
    Trip trip(String personaId) {
        return personaId == null ? null : trips.get(personaId);
    }

    // ---- the picker and the reservation (the owner's I/O thread) -------------------------------

    /**
     * The entry NPC's picker for {@code clicker} (memory and the crew cache): null unless both switches list the boss,
     * the clicker is a human with companions, the channel's expedition of the boss takes sign-ups, a human leads it,
     * the clicker is on it, and no other owner's companions are (Part A's one-owner rule). Each BENCHED row of theirs
     * is offered when it has no trip, is tied to no run, is in no boss run, is loaded by nothing but its own live
     * slice, is within the expedition's level range, and its recall ETA (from where its slice stands, else its camp)
     * plus the widest arrival stagger reaches her before the run stops taking sign-ups.
     */
    Picker picker(Character clicker, String bossKey, long nowMs) {
        Context context = context(clicker, bossKey);
        if (context == null) {
            return null;
        }
        AgentBossRunDefinition definition = context.definition();
        long deadline = AgentBossRunService.signUpDeadlineMs(context.expedition());
        List<Row> rows = new ArrayList<>();
        CompanionViews views = world.views();
        for (CompanionView view : views == null ? List.<CompanionView>of() : views.crew(clicker)) {
            if (view.state() != RosterState.BENCHED) {
                continue;
            }
            Persona persona = world.content().persona(view.personaId());
            RosterEntry entry = views.row(clicker.getId(), view.personaId());
            if (persona == null || entry == null) {
                continue;
            }
            long eta = etaMs(entry, persona, definition);
            rows.add(new Row(persona.id(), view.name(), view.level(), view.job(), eta,
                    whyNot(entry, persona, view.level(), definition, nowMs + eta, deadline)));
        }
        return new Picker(definition, context.expedition(), deadline, left(clicker.getId(), context.expedition()),
                rows);
    }

    /**
     * The owner picked allies at the entry NPC (their I/O thread): those of {@code personaIds} her picker offers now
     * are reserved, in order and as many as she can still take, and each one's call goes to companion-ops. The names
     * of those reserved, in order; empty when nobody was.
     */
    List<String> send(Character owner, String bossKey, List<String> personaIds, long nowMs) {
        List<Trip> reserved = reserve(owner, bossKey, personaIds, nowMs);
        List<String> names = new ArrayList<>();
        for (Trip trip : reserved) {
            Persona persona = world.content().persona(trip.personaId);
            names.add(persona == null ? trip.personaId : persona.name());
            world.executor().submit("boss.ally_call " + trip.personaId, () -> call(trip));
        }
        return names;
    }

    /** The picker asked again and the trips made in one step, so two quick picks never take more than the share. */
    private synchronized List<Trip> reserve(Character owner, String bossKey, List<String> personaIds, long nowMs) {
        Picker picker = owner == null || personaIds == null ? null : picker(owner, bossKey, nowMs);
        if (picker == null) {
            return List.of();
        }
        int left = picker.left();
        List<Trip> reserved = new ArrayList<>();
        for (String personaId : personaIds) {
            Row row = picker.row(personaId);
            if (left <= 0) {
                break;
            }
            if (row == null || !row.offered()) {
                continue;
            }
            Trip trip = new Trip(personaId, owner.getId(), picker.definition().key(), picker.expedition());
            if (trips.putIfAbsent(personaId, trip) == null) {
                reserved.add(trip);
                left--;
            }
        }
        return reserved;
    }

    // ---- the call and the arrival (companion-ops) ---------------------------------------------

    /**
     * On companion-ops: the ally's call. An owner gone offline, an offer gone (the list closed, the row changed) drops
     * the trip with no line. The budget admits it first (EXPEDITION_BURST_MAX, and the owner's even share of it), so a
     * denial changes nothing and the ally whispers there is no room; then its live slice ends, its EXPEDITION lease is
     * taken, it is enlisted in the run as a member that leads itself, and it is committed under its owner's run key.
     * It whispers its recall ETA and arrives at that ETA plus a stagger. Any later failure undoes, last step first.
     */
    void call(Trip trip) {
        if (trips.get(trip.personaId) != trip || trip.state != State.CALLING) {
            return;
        }
        long now = clock.getAsLong();
        Character owner = world.roster().onlineCharacter(trip.ownerId);
        Context context = owner == null ? null : context(owner, trip.bossKey);
        Persona persona = world.content().persona(trip.personaId);
        RosterEntry entry = persona == null ? null : world.store().rosterEntry(trip.ownerId, persona.id());
        if (context == null || context.expedition() != trip.expedition || entry == null
                || entry.state() != RosterState.BENCHED || busy(entry, persona)) {
            log.info("boss ally call of {} for owner {} dropped: the offer is gone", trip.personaId, trip.ownerId);
            trips.remove(trip.personaId, trip);
            return;
        }
        try {
            long eta = etaMs(entry, persona, context.definition()); // read before the call ends its slice
            CompanionBudget.Admission admission = world.budget().admit(CompanionBudget.Kind.EXPEDITION, trip.ownerId,
                    now);
            if (!admission.allowed() || permitsLeft(trip.ownerId, trip) < 1) {
                log.info("boss ally call of {} for owner {} denied: {}", persona.id(), trip.ownerId,
                        admission.allowed() ? "owner's permit share used" : admission.reason());
                trips.remove(trip.personaId, trip);
                whisper(trip, persona, BossRunLines.ALLY_NO_ROOM, null);
                return;
            }
            world.benchDuty().endFor(persona.id(), CALLED);
            int id = world.residents().ensureCharacter(persona);
            trip.characterId = id;
            if (id == 0 || !world.leases().tryAcquire(id, CompanionLeases.Kind.EXPEDITION, trip.ownerId)) {
                cantCome(trip, persona, "its lease is held");
                return;
            }
            Enlisted enlisted = open.enlistSelfLed(owner, context.definition(), trip.expedition, List.of(id), now);
            if (enlisted.sessionId() == 0L || !enlisted.accepted().contains(id)) {
                cantCome(trip, persona, "the run refused it");
                return;
            }
            trip.sessionId = enlisted.sessionId();
            String key = CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId);
            Commitments commitments = world.commitments();
            if (commitments == null || !commitments.commit(key, trip.ownerId, id)) {
                cantCome(trip, persona, "it is tied to another run");
                return;
            }
            CompanionBossRun run = runs.computeIfAbsent(key,
                    unused -> new CompanionBossRun(trip.bossKey, trip.sessionId, trip.ownerId));
            int generation = ++trip.generation;
            trip.state = State.TRAVELLING;
            log.info("Boss ally called: session={} boss={} owner={} ally={} eta={}", trip.sessionId, trip.bossKey,
                    trip.ownerId, id, eta);
            world.roster().later("boss.ally_arrive " + persona.id(), () -> arrive(trip, generation),
                    eta + ArrivalPlacement.staggerMs()); // first: it never whispers an ETA nothing will keep
            whisper(trip, persona, BossRunLines.ALLY_ON_MY_WAY, RecallTravel.describe(eta), run);
        } catch (RuntimeException failure) {
            log.warn("boss ally call of {} for owner {} failed", trip.personaId, trip.ownerId, failure);
            cantCome(trip, persona, "the call failed");
        }
    }

    /**
     * On companion-ops, at the ally's ETA: it loads at the entry NPC room's arrival portal of the expedition's own copy
     * of the room, on the expedition's channel, as an idle Agent that leads itself; it comes back whole, drinks by its
     * own settings, takes its banked EXP and any stall stock back (the order a live slice loads in), says it is here,
     * and says once when it is short of the boss's potions. A stale timer (another generation, a trip no longer on its
     * way) does nothing. A member no longer waiting to sign up, or a run no longer taking sign-ups, turns it back with
     * a whisper; one loaded elsewhere whispers that it cannot come; both let everything go.
     */
    void arrive(Trip trip, int generation) {
        if (trip.generation != generation || trip.state != State.TRAVELLING || trips.get(trip.personaId) != trip) {
            return;
        }
        int id = trip.characterId;
        Persona persona = world.content().persona(trip.personaId);
        if (open.phaseOf(trip.sessionId, id) != AgentBossRunMember.Phase.WAITING || !open.registering(trip.sessionId)) {
            log.info("Boss ally turned back: session={} ally={} (no longer waiting to sign up)", trip.sessionId, id);
            undo(trip);
            whisper(trip, persona, BossRunLines.ALLY_TURNED_BACK, null);
            return;
        }
        if (ports.loaded(id)) {
            cantCome(trip, persona, "it is loaded elsewhere");
            return;
        }
        MapleMap map = trip.expedition.getRecruitingMap();
        BossEntrance entrance = BossEntrance.of(trip.bossKey);
        Point at = entrance == null ? null : entrance.arrivalAt(map);
        Character agent;
        try {
            agent = map == null ? null : ports.load(id, world.config().WORLD, channelOf(map),
                    map, at != null ? at : ArrivalPlacement.portalNear(map, null));
        } catch (Exception failure) {
            log.warn("boss ally {} failed to load", id, failure);
            agent = null;
        }
        if (agent == null) {
            cantCome(trip, persona, "it did not load");
            return;
        }
        trip.state = State.HERE;
        log.info("Boss ally here: session={} ally={} map={}", trip.sessionId, id, map.getId());
        settle(trip, persona, agent);
        CompanionBossRun run = runs.get(CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId));
        BossRunLines.tell(world, agent, BossRunLines.ALLY_HERE, null, null, run, trip.ownerId);
        if (ports.shortOfPotions(agent, trip.bossKey)) {
            BossRunLines.tell(world, agent, BossRunLines.ALLY_LOW_POTS, null, null, run, trip.ownerId);
        }
    }

    /** A loaded ally as a live slice is (BenchDutyDispatcher.begin): each step on its own, never undoing the load. */
    private void settle(Trip trip, Persona persona, Character agent) {
        try {
            agent.healHpMp(); // it rested on the bench: it comes back whole
        } catch (RuntimeException failure) {
            log.warn("boss ally {} was not healed", persona.id(), failure);
        }
        try {
            CompanionSettingsApplier settings = world.roster().settings();
            if (settings != null) {
                settings.bind(agent, trip.ownerId, persona.id()); // it drinks by its own potion percentages
            }
        } catch (RuntimeException failure) {
            log.warn("boss ally {} kept no settings", persona.id(), failure);
        }
        try {
            RosterEntry entry = world.store().rosterEntry(trip.ownerId, persona.id());
            BenchLedger ledger = world.bench();
            if (entry != null && ledger != null) {
                ledger.applyAccrued(entry, agent);
            }
        } catch (RuntimeException failure) {
            log.warn("boss ally {} took no banked EXP", persona.id(), failure);
        }
        try {
            world.benchDuty().reclaimStall(trip.ownerId, persona.id(), agent);
        } catch (RuntimeException failure) {
            log.warn("boss ally {} took no stall stock back", persona.id(), failure);
        }
    }

    // ---- shares ----------------------------------------------------------------------------------

    /** How many more allies the owner may call to {@code expedition} now: the least of the three shares, never < 0. */
    private int left(int ownerId, Expedition expedition) {
        LivingWorldConfig config = world.config();
        int calling = 0;
        int callingMine = 0;
        int mineInThisRun = 0;
        for (Trip trip : trips.values()) {
            if (trip.expedition != expedition) {
                continue;
            }
            if (trip.state == State.CALLING) { // not enlisted yet: the run's list does not count it
                calling++;
                callingMine += trip.ownerId == ownerId ? 1 : 0;
            }
            mineInThisRun += trip.ownerId == ownerId && trip.state != State.LEAVING ? 1 : 0;
        }
        CompanionBossRuns.Seats seats = open.seats(expedition, ownerId);
        int seatsLeft = CompanionBossRuns.seatsLeft(expedition.getMaxSize(), config.COMPANION_BOSS_HUMAN_SEATS,
                seats.humansListed(), seats.botsListedOrPending() + calling, seats.owners(),
                seats.held() + callingMine);
        int alliesLeft = Math.max(0, perOwnerCap(config) - mineInThisRun);
        return Math.min(seatsLeft, Math.min(permitsLeft(ownerId, null), alliesLeft));
    }

    /** The owner's EXPEDITION permits left by the even share of the burst over every trip but {@code except}. */
    private int permitsLeft(int ownerId, Trip except) {
        Map<Integer, Integer> held = new HashMap<>();
        for (Trip trip : trips.values()) {
            if (trip != except) {
                held.merge(trip.ownerId, 1, Integer::sum);
            }
        }
        LivingWorldConfig config = world.config();
        return CompanionBudget.expeditionShareLeft(config.EXPEDITION_BURST_MAX, perOwnerCap(config), held, ownerId);
    }

    /** Allies one owner may bring: what their roster holds beyond a full party (ROSTER_MAX - 5). */
    private static int perOwnerCap(LivingWorldConfig config) {
        return Math.max(0, config.ROSTER_MAX - CompanionBossRuns.MOST_PER_OWNER);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** What {@code clicker} may call allies to now (see {@link #picker}); null: nothing. */
    private Context context(Character clicker, String bossKey) {
        AgentBossRunDefinition definition = AgentBossRunCatalog.of(bossKey);
        LivingWorldConfig config = world.config();
        if (clicker == null || definition == null || BossEntrance.of(bossKey) == null || config == null
                || !config.companionBossAlliesEnabled(definition.key()) || AgentPresence.isAgent(clicker)
                || !world.roster().hasCompanions(clicker)) {
            return null;
        }
        Expedition expedition = CompanionBossRuns.expeditionOf(clicker, definition);
        Character leader = expedition == null ? null : expedition.getLeader();
        if (leader == null || !expedition.isRegistering() || AgentPresence.isAgent(leader)
                || !expedition.contains(clicker) || open.heldByAnother(expedition, clicker.getId())) {
            return null;
        }
        return new Context(definition, expedition);
    }

    /** Why the row cannot be called now (null: it can), were it to set off now and arrive at {@code arrivesAtMs}. */
    private NotOffered whyNot(RosterEntry entry, Persona persona, int level, AgentBossRunDefinition definition,
                              long arrivesAtMs, long deadlineMs) {
        Trip trip = trips.get(persona.id());
        if (trip != null) {
            return trip.state == State.LEAVING ? NotOffered.BUSY : NotOffered.ON_THE_WAY;
        }
        if (busy(entry, persona)) {
            return NotOffered.BUSY;
        }
        if (level < definition.type().getMinLevel() || level > definition.type().getMaxLevel()) {
            return NotOffered.NOT_READY;
        }
        return arrivesAtMs + ArrivalPlacement.STAGGER_MAX_MS > deadlineMs ? NotOffered.TOO_FAR : null;
    }

    /** Tied to a run, in a boss run, or loaded for anything but its own live bench slice. */
    private boolean busy(RosterEntry entry, Persona persona) {
        int id = world.residents().characterIdOf(persona.id());
        Commitments commitments = world.commitments();
        if (id != 0 && (commitments != null && commitments.isCommitted(id) || open.inRun(id))) {
            return true;
        }
        BenchDutyDispatcher bench = world.benchDuty();
        return world.roster().companion(entry) != null && (bench == null || !bench.live(persona.id()));
    }

    /** The roster's recall ETA from where the row sets off (its live slice's map, else its camp) to the NPC's room. */
    private long etaMs(RosterEntry entry, Persona persona, AgentBossRunDefinition definition) {
        return RecallTravel.etaMs(world.roster().departure(entry, persona), definition.recruitMapId(),
                world.config().RECALL_MAX_ETA_MS);
    }

    /** The trip ends with the ally whispering it cannot come; everything it took is let go first. */
    private void cantCome(Trip trip, Persona persona, String why) {
        log.info("boss ally call of {} for owner {} undone: {}", trip.personaId, trip.ownerId, why);
        undo(trip);
        whisper(trip, persona, BossRunLines.ALLY_CANT_COME, null);
    }

    /**
     * Undoes a trip that never loaded, last step first: its commitment to the run key, its place in the run (the run's
     * next tick refuses it WITHDRAWN), its EXPEDITION lease; then the trip is gone and any timer of it is stale.
     */
    private void undo(Trip trip) {
        trip.generation++;
        int id = trip.characterId;
        if (id != 0) {
            Commitments commitments = world.commitments();
            String key = CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId);
            if (trip.sessionId != 0L && commitments != null && key.equals(commitments.keyOf(id))) {
                commitments.releaseMember(id);
            }
            if (trip.sessionId != 0L) {
                open.withdraw(trip.sessionId, id);
            }
            if (new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, trip.ownerId)
                    .equals(world.leases().holder(id))) {
                world.leases().release(id, CompanionLeases.Kind.EXPEDITION);
            }
        }
        trips.remove(trip.personaId, trip);
    }

    /** A whisper to the owner from an ally that is not loaded (its persona speaks), its wordings rotated per run. */
    private void whisper(Trip trip, Persona persona, String intent, String eta) {
        CompanionBossRun run = trip.sessionId == 0L ? null
                : runs.get(CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId));
        whisper(trip, persona, intent, eta, run);
    }

    private void whisper(Trip trip, Persona persona, String intent, String eta, CompanionBossRun run) {
        Character owner = persona == null ? null : world.roster().onlineCharacter(trip.ownerId);
        List<String> lines = owner == null ? List.of() : BossRunLines.lines(world, persona, intent);
        if (lines.isEmpty()) {
            return;
        }
        int turn = turns.getAndIncrement();
        String wording = run == null ? lines.get(Math.floorMod(turn, lines.size())) : run.wording(intent, lines, turn);
        world.residents().whisper(persona, owner, wording.replace("{eta}", eta == null ? "" : eta));
    }

    /** The channel {@code map}'s copy belongs to; the living channel when it names none. */
    private int channelOf(MapleMap map) {
        Channel channel = map.getChannelServer();
        return channel == null ? world.config().CHANNEL : channel.getId();
    }
}
