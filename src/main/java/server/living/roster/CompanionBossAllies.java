package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyEvents;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.agents.capabilities.expedition.boss.AgentBossRunService.Enlisted;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * it up to her. A call that fails undoes itself, last step first, and the ally whispers that it cannot come. Once on
 * her list, an owner's allies form one party of bots, the highest level leading. However the run ends for an ally (a
 * refusal, a missed start, the fight, a death it waits out beside her, the run's end, a lost run), it says how it went
 * where its owner hears it, leaves its party, walks to the room's way out and is unloaded; only then do its lease, its
 * commitment and its trip go, and its BENCHED row takes up its bench duty again. While a trip lasts, bench duty, the
 * bench ledger and the roster's call leave its persona alone. Calls, arrivals and the way home run on companion-ops;
 * the entry NPC's window (her offer, the picker of who can come and how many, the owner's pick) reads the crew cache
 * and memory on the owner's I/O thread. It writes no roster state. Owned by CompanionBossRuns; nothing survives a
 * restart (the rows stayed BENCHED).
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
        /** The loaded ally (null until it arrives). */
        volatile Character agent;
        /** It went down in the fight and waits beside the entry NPC for its owner (or the run's end), still tied. */
        volatile boolean waitingOutside;
        /** When it set off for the way out (LEAVING). */
        volatile long leftAtMs;
        /** When the audit first found its run gone (0: it has not); companion-ops only. */
        long goneSinceMs;

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

        /** The ally joins {@code lead}'s party; a lead in no party makes one first. */
        JoinOutcome join(Character lead, Character ally);

        /** The ally leaves the party it is in, if any. */
        void leaveParty(Character ally);

        /** The id of the leader of the party the ally is in, as the game keeps it; 0 when it is in none. */
        int partyLeaderOf(Character ally);

        /** The ally walks to {@code to} on its map; false when no Agent runtime holds it. */
        boolean walkTo(Character ally, Point to);

        /**
         * Unloads the ally as a live bench slice leaves: out of the Agent registry, then saved and disconnected. True
         * once it is offline; false (or a failure) while it is still online.
         */
        boolean unload(Character ally);

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

            @Override
            public JoinOutcome join(Character lead, Character ally) {
                return AgentPartyLifecycleService.joinAgentToLeaderParty(lead, ally);
            }

            @Override
            public void leaveParty(Character ally) {
                AgentPartyLifecycleService.leaveAgentParty(ally);
            }

            @Override
            public int partyLeaderOf(Character ally) {
                Party party = ally.getParty();
                return party == null ? 0 : party.getLeaderId();
            }

            @Override
            public boolean walkTo(Character ally, Point to) {
                AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(ally.getId());
                if (runtime == null) {
                    return false;
                }
                AgentMovementCommandRuntime.moveTo(runtime, to, false);
                return true;
            }

            @Override
            public boolean unload(Character ally) {
                AgentRuntimeCleanupService.removeAgentByCharacterId(ally.getId());
                if (ally.getClient() == null) {
                    return true;
                }
                ally.saveCharToDB(); // first, so its next load (a bench slice, a call) sees what it gained here
                ally.getClient().forceDisconnect();
                return true;
            }
        };
    }

    /** Where a called ally's live bench slice ends, as the bench's event log says it. */
    static final String CALLED = "called to the temple";
    /**
     * The cause token of every join and leave of an owner's party of allies: the party sync takes it for companion
     * code's own echo (CompanionPartySync.CAUSE_PREFIX) and never pulls an ally out of a party its owner is not in.
     */
    static final String CAUSE = "companion.boss";
    /** The picker window's choice of the first ally it offers; each next one it offers takes the next number. */
    private static final int FIRST_CHOICE = 100;
    /** The picker window's "Everyone who can make it." */
    private static final int EVERYONE = 98;
    /** The picker window's "That's all for now." (the entry NPC's script ends the talk on it). */
    private static final int THATS_ALL = 99;
    /** The picker window's minutes. */
    private static final long MINUTE_MS = 60_000L;

    private final LivingWorld world;
    private final CompanionBossRuns.OpenRuns open;
    /** CompanionBossRuns' owners' parts of their runs, by run key: an ally's lines rotate their wordings there. */
    private final Map<String, CompanionBossRun> runs;
    private final Ports ports;
    private final LongSupplier clock;
    /** Persona id -> its trip; one trip per persona. Reserved under this object's lock, moved on companion-ops. */
    private final Map<String, Trip> trips = new ConcurrentHashMap<>();
    /** Run key -> that owner's party of allies in the run, as formed here; companion-ops only. */
    private final Map<String, Overflow> parties = new HashMap<>();
    private final AtomicInteger turns = new AtomicInteger();
    /** Owner id -> the picker their entry NPC window last showed (their I/O thread); dropped once that list closed. */
    private final Map<Integer, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** The boss and the expedition an owner on its entry NPC's list may call allies to now. */
    private record Context(AgentBossRunDefinition definition, Expedition expedition) { }

    /** The entry NPC's picker as its window last showed it to an owner: for which list, whom it offered, in order. */
    private record Snapshot(Expedition expedition, List<String> offered) {
        /** The allies a pick of {@code selection} names: one by its choice, everyone, or none (a choice not shown). */
        List<String> picked(int selection) {
            if (selection == EVERYONE) {
                return offered;
            }
            int index = selection - FIRST_CHOICE;
            return index >= 0 && index < offered.size() ? List.of(offered.get(index)) : List.of();
        }
    }

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

    // ---- the entry NPC's window (the owner's I/O thread) ------------------------------------------

    /**
     * The entry NPC's Easy window for {@code clicker} (memory and the crew cache): true when she offers "Call allies
     * from my bench...": the terms of her picker hold ({@link #picker}: both switches, a human owner on a list a human
     * leads that still takes sign-ups, no other owner's companions on it) and a BENCHED row of theirs has no trip.
     */
    boolean offers(Character clicker, String bossKey) {
        if (context(clicker, bossKey) == null) {
            return false;
        }
        CompanionViews views = world.views();
        for (CompanionView view : views == null ? List.<CompanionView>of() : views.crew(clicker)) {
            if (view.state() == RosterState.BENCHED && !onTrip(view.personaId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The picker's window, in the entry NPC's words: when she closes the list (whole minutes, rounded down), how many
     * more of theirs she takes, each benched companion of theirs as a choice from 100 on when she offers it (level,
     * job, its ETA in whole minutes rounded up) or a plain line saying why not, "Everyone who can make it." with two or
     * more to call, and "That's all for now." last. Whom it offers is kept for the owner's pick ({@link #callFromNpc}).
     * Null when there is nothing to show: no picker, or no benched companion.
     */
    String pickerWindow(Character clicker, String bossKey, long nowMs) {
        Picker picker = clicker == null ? null : picker(clicker, bossKey, nowMs);
        if (picker == null || picker.rows().isEmpty()) {
            if (clicker != null) {
                snapshots.remove(clicker.getId());
            }
            return null;
        }
        int left = picker.left();
        StringBuilder text = new StringBuilder("Who should I send word to? I close the list in about #b")
                .append(Math.max(1L, (picker.deadlineMs() - nowMs) / MINUTE_MS))
                .append(" min#k, so they must reach me before then.\r\n")
                .append(left == 0 ? "There is no room on my list for more of yours."
                        : left == 1 ? "There is #b1#k seat left for you."
                        : "There are #b" + left + "#k seats left for you.")
                .append("\r\n");
        List<String> offered = new ArrayList<>();
        for (Row row : picker.rows()) {
            text.append("\r\n");
            if (row.offered()) {
                text.append("#L").append(FIRST_CHOICE + offered.size()).append('#').append(row.name()).append(" (Lv. ")
                        .append(row.level()).append(row.job() == null || row.job().isEmpty() ? "" : " " + row.job())
                        .append(") - about ").append(Math.max(1L, Math.ceilDiv(row.etaMs(), MINUTE_MS)))
                        .append(" min away#l");
                offered.add(row.personaId());
            } else {
                text.append(switch (row.why()) {
                    case TOO_FAR -> row.name() + " (Lv. " + row.level() + ") cannot reach me before the list closes.";
                    case NOT_READY -> row.name() + " is not ready for this battle.";
                    case ON_THE_WAY -> row.name() + " is already on the way.";
                    case BUSY -> row.name() + " is busy right now.";
                });
            }
        }
        if (offered.size() >= 2) {
            text.append("\r\n#L").append(EVERYONE).append("#Everyone who can make it.#l");
        }
        text.append("\r\n#L").append(THATS_ALL).append("#That's all for now.#l");
        snapshots.put(clicker.getId(), new Snapshot(picker.expedition(), List.copyOf(offered)));
        return text.toString();
    }

    /**
     * The owner picked {@code selection} in the picker's window (an ally's choice, or "Everyone who can make it."):
     * those it names are called as {@link #send} calls them (the offer asked again under this object's lock, as many as
     * she still takes), and she says whom she sent word to, then shows the picker again. A pick from a stale window
     * (none shown, another list, a choice it never had, nobody it names still offered) only shows the picker as it is
     * now.
     * Null when there is nothing to show (see {@link #pickerWindow}).
     */
    String callFromNpc(Character clicker, String bossKey, int selection, long nowMs) {
        Snapshot shown = clicker == null ? null : snapshots.get(clicker.getId());
        Context context = shown == null ? null : context(clicker, bossKey);
        List<String> picked = context == null || context.expedition() != shown.expedition() ? List.of()
                : shown.picked(selection);
        List<String> names = picked.isEmpty() ? List.of() : send(clicker, bossKey, picked, nowMs);
        String picker = pickerWindow(clicker, bossKey, nowMs);
        if (names.isEmpty()) {
            return picker;
        }
        String sent = "I have sent word to #b" + spoken(names) + "#k.";
        return picker == null ? sent : sent + "\r\n\r\n" + picker;
    }

    /** "Orrin", "Orrin and Magna", "Orrin, Magna and Gunnar". */
    private static String spoken(List<String> names) {
        int last = names.size() - 1;
        return last == 0 ? names.get(0) : String.join(", ", names.subList(0, last)) + " and " + names.get(last);
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
        trip.agent = agent;
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

    // ---- the run's reports (companion-ops, through CompanionBossRuns) -----------------------------

    /**
     * On companion-ops, the ally of {@code seat} is on the entry NPC's list: once none of its owner's allies still
     * waits to sign up, they form their party ({@link #formParty}). Any other seat: nothing.
     */
    void registered(Seat seat) {
        Trip trip = tripOf(seat);
        if (trip != null) {
            formParty(trip.ownerId, trip.sessionId, trip.bossKey);
        }
    }

    /**
     * On companion-ops, the run refused {@code seat} (the list closed or was full, it came too late, the leader took it
     * off, it was withdrawn): false when it is no ally's (a party companion says its own line). A loaded ally says it
     * could not get its name down, or that the fight is off when it was withdrawn, and goes back to its bench duty; one
     * still on its way turns back with a whisper (none when withdrawn: whoever withdrew it let it know). Its owner's
     * other allies form their party again.
     */
    boolean refused(Seat seat, Refusal refusal, CompanionBossRun run) {
        Trip trip = tripOf(seat);
        if (trip == null) {
            return false;
        }
        boolean withdrawn = refusal == Refusal.WITHDRAWN;
        log.info("Boss ally refused: session={} ally={} refusal={}", trip.sessionId, trip.characterId, refusal);
        sendHome(trip, trip.state != State.HERE ? (withdrawn ? null : BossRunLines.ALLY_TURNED_BACK)
                : withdrawn ? BossRunLines.ALLY_CALLED_OFF : BossRunLines.ALLY_NOT_LISTED, run);
        return true;
    }

    /**
     * On companion-ops, the ally of {@code seat} left the run ({@code out}): false when it is no ally's. Down in the
     * fight, it waits outside ({@link #waitOutside}). Having missed the start, with no human left in the fight, or out
     * of it alive, it says so and goes back to its bench duty; one whose runtime went away goes with no line. Its
     * owner's other allies form their party again.
     */
    boolean out(Seat seat, Out out, CompanionBossRun run) {
        Trip trip = tripOf(seat);
        if (trip == null) {
            return false;
        }
        switch (out) {
            case DIED -> waitOutside(trip, run);
            case MISSED_START -> sendHome(trip, BossRunLines.ALLY_MISSED, run);
            case NO_HUMAN_LEFT -> sendHome(trip, BossRunLines.ALLY_NEXT_TIME, run);
            case EXITED -> sendHome(trip, BossRunLines.ALLY_GOOD_FIGHT, run);
            case GONE -> sendHome(trip, null, run);
        }
        return true;
    }

    /**
     * On companion-ops, the run ended ({@code taken}: its owners' parts of it, by owner, as CompanionBossRuns took them
     * out): every ally of it not on its way out yet says how it went (a clear "good fight", a fight lost "next time", a
     * list that never started "called off") and goes back to its bench duty; one still on its way down turns back with
     * a whisper. Its owners' parties are forgotten.
     */
    void runEnded(RunEnded ended, Map<Integer, CompanionBossRun> taken) {
        String intent = !ended.started() ? BossRunLines.ALLY_CALLED_OFF
                : ended.cleared() ? BossRunLines.ALLY_GOOD_FIGHT : BossRunLines.ALLY_NEXT_TIME;
        int sent = 0;
        for (Trip trip : List.copyOf(trips.values())) {
            if (trip.sessionId != ended.sessionId() || !trip.bossKey.equals(ended.bossKey())
                    || trip.state == State.LEAVING) {
                continue;
            }
            dismiss(trip, trip.state == State.HERE ? intent : BossRunLines.ALLY_TURNED_BACK, taken.get(trip.ownerId));
            sent++;
        }
        parties.values().removeIf(party -> party.sessionId == ended.sessionId());
        if (sent > 0) {
            log.info("Boss allies: session={} boss={} reason={} sent home={}", ended.sessionId(), ended.bossKey(),
                    ended.reason(), sent);
        }
    }

    /**
     * On companion-ops, the owner came into a boss's entry NPC room ({@code mapId}, never an event's copy): each ally
     * of theirs down from the fight and waiting there on the owner's channel says goodbye and goes back to its bench
     * duty.
     */
    void ownerArrived(int ownerId, int mapId) {
        Character owner = null;
        for (Trip trip : List.copyOf(trips.values())) {
            Character agent = trip.agent;
            if (trip.ownerId != ownerId || !trip.waitingOutside || trip.state != State.HERE || agent == null
                    || agent.getMapId() != mapId) {
                continue;
            }
            owner = owner != null ? owner : world.roster().onlineCharacter(ownerId);
            if (owner != null && RosterService.sameChannel(owner, agent)) {
                sendHome(trip, BossRunLines.ALLY_GOODBYE, run(trip));
            }
        }
    }

    /**
     * On companion-ops, from the audit (CompanionBossRuns.sweepLostRuns): an ally whose unload left it online (or whose
     * walk out lost its timer) is unloaded again once its walk out is over; one here whose runtime went away goes home;
     * one whose run is gone without an end goes home once it has been gone LOST_RUN_AFTER_MS. The picker windows kept
     * for lists that closed are dropped.
     */
    void sweep(long nowMs) {
        for (Trip trip : List.copyOf(trips.values())) {
            switch (trip.state) {
                case LEAVING -> {
                    if (nowMs - trip.leftAtMs >= walkOutMs()) {
                        leave(trip);
                    }
                }
                case TRAVELLING, HERE -> {
                    if (open.open(trip.sessionId)) {
                        trip.goneSinceMs = 0L;
                        if (trip.state == State.HERE && !ports.loaded(trip.characterId)) {
                            log.info("Boss ally lost its runtime: session={} ally={}", trip.sessionId,
                                    trip.characterId);
                            sendHome(trip, null, run(trip));
                        }
                    } else if (trip.goneSinceMs == 0L) {
                        trip.goneSinceMs = Math.max(1L, nowMs);
                    } else if (nowMs - trip.goneSinceMs >= CompanionPartyQuestService.LOST_RUN_AFTER_MS) {
                        log.warn("boss ally {} lost its run {}; sending it home", trip.personaId, trip.sessionId);
                        dismiss(trip, trip.state == State.HERE ? BossRunLines.ALLY_GOODBYE
                                : BossRunLines.ALLY_TURNED_BACK, run(trip));
                    }
                }
                case CALLING -> {
                }
            }
        }
        parties.values().removeIf(party -> !open.open(party.sessionId));
        snapshots.values().removeIf(shown -> !shown.expedition().isRegistering()); // a closed list's window is stale
    }

    /** True while a call of the owner's still waits on companion-ops (CALLING): not all of theirs are signed up. */
    boolean calling(int ownerId) {
        for (Trip trip : trips.values()) {
            if (trip.ownerId == ownerId && trip.state == State.CALLING) {
                return true;
            }
        }
        return false;
    }

    // ---- the overflow party (companion-ops) ------------------------------------------------------

    /**
     * The owner's allies in run {@code sessionId} form one party of bots, once none of the owner's allies still waits
     * to sign up or is still being called: those on the entry NPC's list or in the fight, loaded, two at least. A party
     * already formed keeps its lead, so a late one joins it: the ally of theirs in this run, still here, that leads its
     * party as the game keeps it, even one down outside (the stock leave ends a party only as its leader leaves). With
     * none, the highest level leads (the lower ordinal on a tie). Every other one joins the lead's party, six in all at
     * most, under the boss cause token (the party sync takes the joins for companion code's own echo). Idempotent;
     * again after an ally is refused or leaves the run.
     */
    private void formParty(int ownerId, long sessionId, String bossKey) {
        List<Trip> mine = new ArrayList<>();
        List<Trip> here = new ArrayList<>(); // mine, and those down from the fight waiting outside
        Map<Integer, Integer> ordinals = new HashMap<>();
        for (Trip trip : trips.values()) {
            if (trip.ownerId != ownerId) {
                continue;
            }
            if (trip.state == State.CALLING) {
                return; // a call of theirs still to come: it signs up too
            }
            AgentBossRunMember.Phase phase = trip.sessionId == sessionId
                    ? open.phaseOf(sessionId, trip.characterId) : null;
            if (phase == AgentBossRunMember.Phase.WAITING) {
                return;
            }
            if (trip.state != State.HERE || trip.agent == null || trip.sessionId != sessionId) {
                continue;
            }
            boolean listedOrInside = phase == AgentBossRunMember.Phase.REGISTERED
                    || phase == AgentBossRunMember.Phase.INSIDE;
            if (listedOrInside || trip.waitingOutside) {
                here.add(trip);
                if (listedOrInside) {
                    mine.add(trip);
                }
                int ordinal = open.ordinalOf(sessionId, trip.characterId);
                ordinals.put(trip.characterId, ordinal < 0 ? Integer.MAX_VALUE : ordinal);
            }
        }
        if (mine.size() < 2) {
            return;
        }
        mine.sort(Comparator.comparingInt(trip -> ordinals.get(trip.characterId)));
        here.sort(Comparator.comparingInt(trip -> ordinals.get(trip.characterId)));
        Overflow party = parties.computeIfAbsent(CompanionBossRun.key(bossKey, sessionId, ownerId),
                unused -> new Overflow(sessionId));
        Trip lead = null;
        for (Trip trip : here) { // the game's party, the one formed here first
            if (leadsItsParty(trip.agent) && (lead == null || trip.characterId == party.leadId)) {
                lead = trip;
            }
        }
        boolean kept = lead != null;
        if (!kept) { // none formed yet, or its lead left (which ended it): a new one
            for (Trip trip : mine) { // in ordinal order: a tie keeps the first
                lead = lead == null || trip.agent.getLevel() > lead.agent.getLevel() ? trip : lead;
            }
        }
        if (!kept || lead.characterId != party.leadId) { // the joins tell who is in it already (ALREADY_MEMBER)
            party.leadId = lead.characterId;
            party.members.clear();
        }
        List<Integer> joined = new ArrayList<>();
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            for (Trip trip : mine) {
                if (trip == lead || party.members.contains(trip.characterId)) {
                    continue;
                }
                if (party.members.size() + 1 >= CompanionSlots.PARTY_CAP) {
                    break;
                }
                JoinOutcome outcome;
                try {
                    outcome = ports.join(lead.agent, trip.agent);
                } catch (RuntimeException failure) {
                    log.warn("boss ally {} failed to join its party", trip.personaId, failure);
                    continue;
                }
                if (outcome == JoinOutcome.JOINED || outcome == JoinOutcome.ALREADY_MEMBER) {
                    party.members.add(trip.characterId);
                    joined.add(trip.characterId);
                } else {
                    log.info("boss ally {} did not join its party: {}", trip.personaId, outcome);
                }
            }
        }
        if (!joined.isEmpty()) {
            log.info("Boss allies party: session={} owner={} lead={} joined={} members={}", sessionId, ownerId,
                    lead.characterId, joined, party.members.size() + 1);
        }
    }

    /** One owner's party of allies in one run, as it was formed here: its lead, and the others that joined it. */
    private static final class Overflow {
        final long sessionId;
        int leadId;
        final Set<Integer> members = new LinkedHashSet<>();

        Overflow(long sessionId) {
            this.sessionId = sessionId;
        }
    }

    // ---- the way home (companion-ops) ------------------------------------------------------------

    /**
     * The ally went down in the fight and came to outside: it tells its owner, walks to its own spot beside the entry
     * NPC (by its ordinal in the run) and waits there, still tied to the run, until its owner comes out to the room
     * ({@link #ownerArrived}) or the run ends. With its owner standing there already, it says goodbye and goes.
     */
    private void waitOutside(Trip trip, CompanionBossRun run) {
        Character agent = trip.agent;
        if (trip.state != State.HERE || agent == null) {
            sendHome(trip, null, run);
            return;
        }
        Character owner = world.roster().onlineCharacter(trip.ownerId);
        if (owner != null && owner.getEventInstance() == null && owner.getMapId() == agent.getMapId()
                && RosterService.sameChannel(owner, agent)) {
            sendHome(trip, BossRunLines.ALLY_GOODBYE, run);
            return;
        }
        trip.waitingOutside = true;
        BossRunLines.tell(world, agent, BossRunLines.DOWN, null, null, run, trip.ownerId);
        BossEntrance entrance = BossEntrance.of(trip.bossKey);
        MapleMap map = agent.getMap();
        int ordinal = open.ordinalOf(trip.sessionId, trip.characterId);
        Point spot = entrance == null || map == null || ordinal < 0
                || map.getId() != entrance.definition().recruitMapId() ? null : entrance.spot(map, ordinal);
        try {
            if (spot != null) {
                ports.walkTo(agent, spot);
            }
        } catch (RuntimeException failure) {
            log.warn("boss ally {} did not walk to its spot", trip.personaId, failure);
        }
    }

    /** {@link #dismiss}, then the owner's other allies in the run form their party again (its leave may end it). */
    private void sendHome(Trip trip, String intent, CompanionBossRun run) {
        dismiss(trip, intent, run);
        formParty(trip.ownerId, trip.sessionId, trip.bossKey);
    }

    /**
     * The ally's trip ends and it goes back to its bench duty, saying {@code intent} (null: nothing). One never loaded
     * lets everything go at once and whispers it. A loaded one says it where its owner hears it, leaves its party, and
     * walks to the room's way out (its exit portal on the entry NPC's map, else the portal nearest it) while a human
     * there sees it; it is unloaded once there, after COMPANION_BOSS_ALLY_WALK_OUT_MS, or when nobody is left to see
     * it, and at once inside an event or with nowhere to walk. Its lease, its commitment and its trip go only once it
     * is offline. Once per trip.
     */
    private void dismiss(Trip trip, String intent, CompanionBossRun run) {
        if (trip.state == State.LEAVING || trips.get(trip.personaId) != trip) {
            return;
        }
        Character agent = trip.agent;
        if (trip.state != State.HERE || agent == null) {
            letGo(trip);
            if (intent != null) {
                whisper(trip, world.content().persona(trip.personaId), intent, null, run);
            }
            return;
        }
        if (intent != null) {
            BossRunLines.tell(world, agent, intent, null, null, run, trip.ownerId);
        }
        trip.state = State.LEAVING;
        trip.waitingOutside = false;
        int generation = ++trip.generation;
        long now = clock.getAsLong();
        trip.leftAtMs = now;
        leaveParty(trip, agent);
        Point target = null;
        try {
            target = wayOut(trip, agent);
            if (target != null && !ports.walkTo(agent, target)) {
                target = null;
            }
        } catch (RuntimeException failure) {
            log.warn("boss ally {} did not walk out", trip.personaId, failure);
            target = null;
        }
        if (target == null) {
            leave(trip);
            return;
        }
        watchWalkOut(trip, generation, target, now + walkOutMs());
    }

    /**
     * Out of its party of allies under the boss cause token. The leave of the party's leader, as the game keeps it,
     * ends the party (the stock leave), so the next formation makes a new one.
     */
    private void leaveParty(Trip trip, Character agent) {
        Overflow party = parties.get(CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId));
        if (party != null && (party.leadId == trip.characterId || leadsItsParty(agent))) {
            party.leadId = 0;
            party.members.clear();
        } else if (party != null) {
            party.members.remove(trip.characterId);
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            ports.leaveParty(agent);
        } catch (RuntimeException failure) {
            log.warn("boss ally {} did not leave its party", trip.personaId, failure);
        }
    }

    /** True when the ally leads the party it is in, as the game keeps it (false when that cannot be read). */
    private boolean leadsItsParty(Character agent) {
        try {
            return agent != null && ports.partyLeaderOf(agent) == agent.getId();
        } catch (RuntimeException failure) {
            log.warn("boss ally {}'s party could not be read", agent.getId(), failure);
            return false;
        }
    }

    /**
     * Where the ally walks to go: the entrance's exit portal on its entry NPC's map, else the portal nearest it; null
     * (it goes where it stands) inside an event, or with no human on its map to see it go.
     */
    private Point wayOut(Trip trip, Character agent) {
        MapleMap map = agent.getMap();
        if (map == null || agent.getEventInstance() != null || ResidentRuntime.humansIn(map) == 0) {
            return null;
        }
        BossEntrance entrance = BossEntrance.of(trip.bossKey);
        Point exit = entrance != null && map.getId() == entrance.definition().recruitMapId() ? entrance.exitAt(map)
                : null;
        return exit != null ? exit : ArrivalPlacement.portalNear(map, agent.getPosition());
    }

    private void watchWalkOut(Trip trip, int generation, Point target, long deadlineMs) {
        world.roster().later("boss.ally_walk_out " + trip.personaId, () -> {
            if (trip.generation != generation || trip.state != State.LEAVING || trips.get(trip.personaId) != trip) {
                return; // gone meanwhile
            }
            Character agent = trip.agent;
            Point at = agent.getPosition();
            MapleMap map = agent.getMap();
            if (at != null && at.distance(target) > ArrivalPlacement.WALK_OUT_REACH_PX && clock.getAsLong() < deadlineMs
                    && map != null && ResidentRuntime.humansIn(map) > 0) {
                watchWalkOut(trip, generation, target, deadlineMs);
            } else {
                leave(trip);
            }
        }, ArrivalPlacement.WALK_OUT_CHECK_MS);
    }

    /**
     * The ally on its way out is unloaded; once it is offline everything it took goes. Still online (its save or
     * disconnect failed), it stays LEAVING and keeps its lease, so nothing loads a second copy of it, and the audit
     * unloads it again ({@link #sweep}).
     */
    private void leave(Trip trip) {
        Character agent = trip.agent;
        boolean offline;
        try {
            offline = agent == null || ports.unload(agent);
        } catch (RuntimeException failure) {
            log.warn("boss ally {} failed to unload; it keeps its lease and is unloaded again", trip.personaId,
                    failure);
            offline = false;
        }
        if (!offline) {
            return;
        }
        log.info("Boss ally home: session={} ally={}", trip.sessionId, trip.characterId);
        letGo(trip);
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
            releaseCommitment(trip);
            if (trip.sessionId != 0L) {
                open.withdraw(trip.sessionId, id);
            }
            releaseLease(trip);
        }
        trips.remove(trip.personaId, trip);
    }

    /**
     * The trip is over (the run is done with the ally, and it is offline or never loaded): its commitment to the run
     * key, if it is still tied to it, and its EXPEDITION lease go; then the trip, and any timer of it is stale.
     */
    private void letGo(Trip trip) {
        trip.generation++;
        if (trip.characterId != 0) {
            releaseCommitment(trip);
            releaseLease(trip);
        }
        trips.remove(trip.personaId, trip);
    }

    private void releaseCommitment(Trip trip) {
        Commitments commitments = world.commitments();
        String key = CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId);
        if (trip.sessionId != 0L && commitments != null && key.equals(commitments.keyOf(trip.characterId))) {
            commitments.releaseMember(trip.characterId);
        }
    }

    private void releaseLease(Trip trip) {
        if (new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, trip.ownerId)
                .equals(world.leases().holder(trip.characterId))) {
            world.leases().release(trip.characterId, CompanionLeases.Kind.EXPEDITION);
        }
    }

    /** The trip of the ally {@code seat} names (its run, its owner), or null: the seat is a party companion's. */
    private Trip tripOf(Seat seat) {
        for (Trip trip : trips.values()) {
            if (trip.characterId == seat.agentCharacterId() && trip.sessionId == seat.sessionId()
                    && trip.ownerId == seat.enlistedBy() && trip.bossKey.equals(seat.bossKey())) {
                return trip;
            }
        }
        return null;
    }

    /** Its owner's part of its run while CompanionBossRuns keeps it (an ally's lines rotate their wordings there). */
    private CompanionBossRun run(Trip trip) {
        return runs.get(CompanionBossRun.key(trip.bossKey, trip.sessionId, trip.ownerId));
    }

    private long walkOutMs() {
        return world.config().COMPANION_BOSS_ALLY_WALK_OUT_MS;
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
