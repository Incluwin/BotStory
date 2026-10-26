package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.field.AgentFieldIntent;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.field.AgentFieldAdmissionMode;
import server.agents.runtime.field.AgentFieldEntryRequest;
import server.agents.runtime.field.AgentFieldSessionResult;
import server.agents.runtime.field.AgentFieldVisitLeaseRequest;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.field.AgentFieldVisitRequest;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Companions earn their own Entrance Ticket to the Amorian Challenge (docs/APQ_GPQ_COMPANION_PLAN.md A2, decision 2,
 * 2026-10-25). Amos the Strong (9201043, Amos' Training Ground) lets in only a player who hands him a ticket, and trades
 * a ticket for ten Lip Lock Keys, which Purplewood Forest's boars and eyes drop (9400515, 9400516). A companion out with
 * its owner loots keys like any drop; this errand is for one its owner sends ("casse get your ticket"): out with its
 * owner it walks out keeping its seat, as for its keystone (KeystoneErrands, whose shape this is); a benched one goes
 * between its duties.
 *
 * <p>Two steps, read from the companion's bags whenever it is loaded ({@link #next}): a hunt, a field-pool slice on
 * Purplewood Forest 2 fighting only the key droppers, then the trade, a town-pool slice on Amos' Training Ground where
 * it walks up to Amos the Strong and trades as a player does (his own script takes the keys). Between live slices the
 * ledger carries the hunt on at the key rate the companion itself measured live there, never a guessed one; the keys it
 * credits go into its bag the next time it is loaded. A restart loses ledger credit not yet written, never keys.</p>
 *
 * <p>{@link #ask} answers the owner's chat on companion-ops; the forms and the sweep run there too; what changes a
 * running Agent (a trade, a credit, a field visit) goes through its mailbox.</p>
 */
public final class TicketErrands {
    private static final Logger log = LoggerFactory.getLogger(TicketErrands.class);
    static final String FLAG_PREFIX = "companion.ticket-errand.";

    public static final int AMOS_THE_STRONG_NPC_ID = AgentApqDefinition.GATE_NPC;
    public static final int TRAINING_GROUND_MAP_ID = AgentApqDefinition.GATE_MAP;
    public static final int TICKET_ITEM_ID = AgentApqDefinition.ENTRANCE_TICKET;
    public static final int KEY_ITEM_ID = AgentApqDefinition.LIP_LOCK_KEY;
    public static final int KEYS_WANTED = AgentApqDefinition.KEYS_PER_TICKET;
    /** Purplewood Forest 2, next to Amos' Training Ground: ten Indigo Eyes, eleven Crystal Boars, six Sakura Cellions. */
    public static final int PURPLEWOOD_FOREST_2_MAP_ID = 670_000_200;
    /** What drops the keys (152-drop-data.sql: 4%, times the server's drop rate); the Sakura Cellions drop none. */
    public static final int INDIGO_EYE_MOB_ID = 9_400_515;
    public static final int CRYSTAL_BOAR_MOB_ID = 9_400_516;
    static final Set<Integer> KEY_DROPPERS = Set.of(INDIGO_EYE_MOB_ID, CRYSTAL_BOAR_MOB_ID);
    /** 9201043.js's menu: "Trade 10 Keys for a Ticket!". */
    static final int TRADE_SELECTION = 1;
    private static final double HOUR_MS = 3_600_000.0d;
    /** A live hunt shorter than this measures nothing (a slice that ended as it began). */
    private static final long MEASURE_MIN_MS = 60_000L;
    /** A visit or hunt start that keeps failing gives the errand up after this many tries in a row. */
    static final int TRIES = 3;
    private static final int FIELD_PARTICIPANTS = 6;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    /** The steps, in order; {@link #next} names the one a companion is on. */
    enum Step {
        HUNT_KEYS(PURPLEWOOD_FOREST_2_MAP_ID),
        TRADE(TRAINING_GROUND_MAP_ID),
        DONE(0);

        final int mapId;

        Step(int mapId) {
            this.mapId = mapId;
        }
    }

    /** What the companion's bags say: its Lip Lock Keys and whether it holds a ticket. */
    record Progress(int keys, boolean ticket) {
    }

    /** The step a companion with {@code progress} is on. */
    static Step next(Progress progress) {
        if (progress.ticket()) {
            return Step.DONE;
        }
        return progress.keys() >= KEYS_WANTED ? Step.TRADE : Step.HUNT_KEYS;
    }

    /** On its way to its next step's map, there, or done and heading back to its owner or duty. */
    enum Phase { ON_THE_WAY, AT_WORK, HOMEWARD }

    /** One companion's errand. */
    static final class Errand {
        final String personaId;
        final int ownerId;
        /** It left its owner for this and comes back to them; a benched one goes back to its duties. */
        final boolean returns;
        volatile Step step;
        volatile Phase phase = Phase.ON_THE_WAY;
        /** When it reaches its step's map. */
        volatile long arrivesAtMs;
        /** True once it holds the ticket. */
        volatile boolean done;
        /** True while a hunt slice of it is live (the ledger keeps still meanwhile). */
        volatile boolean live;
        /** Its bags as last read. */
        volatile Progress seen;
        /** Keys the ledger credited and its bag does not hold yet. */
        volatile double credit;
        /** Keys an hour it got live in Purplewood (its last measured hunt). */
        volatile double perHour;
        volatile long ledgerAtMs;
        int tries;
        Point spot;
        long visitStartedAtMs;
        long reachedAtMs;
        long huntStartedAtMs;
        Progress huntStart;

        Errand(String personaId, int ownerId, boolean returns, Step step, long arrivesAtMs) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.returns = returns;
            this.step = step;
            this.arrivesAtMs = arrivesAtMs;
            this.ledgerAtMs = arrivesAtMs;
        }
    }

    /** What changes a loaded companion, on its own mailbox (a test stands in for the Agent). */
    interface Hands {
        /** Trades ten keys at Amos the Strong as a player would; true when it holds a ticket after. */
        boolean trade(AgentRuntimeEntry runtime);

        /** Writes ledger credit into its bag: {@code keys} whole Lip Lock Keys. */
        void credit(AgentRuntimeEntry runtime, int keys);

        /** Starts its field visit in Purplewood, fighting only the key droppers; true when it started. */
        boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, long exitAtMs);
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Map<String, Errand> errands = new ConcurrentHashMap<>();
    /** True while the Agent goes along with its owner (a test stands in for the Agent's follow state). */
    BiPredicate<AgentRuntimeEntry, Integer> goesAlong = AgentSupplyReadiness::carriedBy;
    /** Its bags (a test stands in for the character). */
    Function<Character, Progress> progress = TicketErrands::read;
    Hands hands = new AgentHands();
    final BenchDutyDispatcher.Form talk = new Talk();
    final BenchDutyDispatcher.Form hunt = new Hunt();

    public TicketErrands(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    TicketErrands(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** Takes a row ahead of its duty while its errand is out (after the keystone errands); carries on saved errands. */
    public void install() {
        world.benchDuty().alsoFirst(this::formFor);
        world.executor().submit("ticket_errands.restore", this::restore);
    }

    // ---- the owner's ask (companion-ops) -----------------------------------------------------------------

    /**
     * "casse get your ticket": out with its owner and free, it says so and walks out keeping its seat; benched, it goes
     * between its duties. What the owner reads back.
     */
    public String ask(Character owner, Persona persona) {
        if (!world.config().COMPANION_TICKET_ERRANDS_ENABLED) {
            return persona.name() + " can't go for a ticket right now.";
        }
        RosterEntry row = world.store().rosterEntry(owner.getId(), persona.id());
        if (row == null || row.state() == RosterState.RELEASED) {
            return persona.name() + " is not with you.";
        }
        if (CompanionLoans.lent(world, persona.id())) {
            return persona.name() + " is out with a friend right now.";
        }
        if (errands.containsKey(persona.id())) {
            return persona.name() + " is already working on a ticket.";
        }
        JobErrands jobs = world.jobErrands();
        if (jobs != null && jobs.underway(persona.id())) {
            return persona.name() + " is off to see an instructor first.";
        }
        KeystoneErrands keystones = world.keystoneErrands();
        if (keystones != null && keystones.underway(persona.id())) {
            return persona.name() + " is out for a Crimsonwood Keystone first.";
        }
        int characterId = world.residents().characterIdOf(persona.id());
        int level = characterId == 0 ? 0 : world.store().characterLevel(characterId);
        if (level < world.config().COMPANION_TICKET_ERRAND_MIN_LEVEL) {
            world.residents().reply(persona, owner, line(persona, "ticketLow", TicketErrandLines.LOW, owner.getId()));
            return persona.name() + " needs Lv" + world.config().COMPANION_TICKET_ERRAND_MIN_LEVEL + " for the Amorian Challenge.";
        }
        long now = clock.getAsLong();
        if (row.state() == RosterState.BENCHED) {
            int camp = row.campMapId() != 0 ? row.campMapId() : ResidentSpots.townMapId(persona.homeTown());
            Errand errand = new Errand(persona.id(), owner.getId(), false, Step.HUNT_KEYS,
                    now + eta(camp, PURPLEWOOD_FOREST_2_MAP_ID));
            open(errand, "from the bench");
            return persona.name() + " will go for an Entrance Ticket between bench duties.";
        }
        Character bot = row.state() == RosterState.ACTIVE ? world.roster().companion(row) : null;
        if (bot == null || !free(owner, bot, persona)) {
            return persona.name() + " is busy right now.";
        }
        Progress now0 = progress.apply(bot);
        Step step = next(now0);
        if (step == Step.DONE) {
            world.residents().reply(persona, owner, line(persona, "ticketHave", TicketErrandLines.HAVE, owner.getId()));
            return persona.name() + " already holds an Entrance Ticket.";
        }
        RosterResult left = world.roster().leaveOnErrand(owner, persona,
                line(persona, "ticketErrand", step == Step.TRADE ? TicketErrandLines.TRADE : TicketErrandLines.GO,
                        owner.getId()));
        if (!left.ok()) {
            return RosterVoice.line(persona, left);
        }
        Errand errand = new Errand(persona.id(), owner.getId(), true, step, now + eta(bot.getMapId(), step.mapId));
        errand.seen = now0;
        open(errand, "from its owner");
        return persona.name() + " leaves to earn an Entrance Ticket, keeping the party slot.";
    }

    /** Free to go: no run or event, alive, following its owner, not held at a GM level. */
    private boolean free(Character owner, Character bot, Persona persona) {
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        GmLevelHolds holds = world.gmLevelHolds();
        return !world.commitments().isCommitted(bot.getId())
                && AgentPartyQuestEngagementRegistry.forMember(bot.getId()) == null
                && owner.getEventInstance() == null && bot.getEventInstance() == null
                && !(bot.getHp() <= 0 && bot.getCurrentMaxHp() > 0)
                && !world.roster().keepsOffFollow(persona.id())
                && goesAlong.test(runtime, owner.getId())
                && (holds == null || !holds.held(bot.getId(), persona.id()));
    }

    private void open(Errand errand, String how) {
        errands.put(errand.personaId, errand);
        world.store().setWorldFlag(flag(errand.ownerId, errand.personaId), errand.ownerId);
        world.store().event(errand.ownerId, errand.personaId, "ticket.errand", errand.step + " " + how);
    }

    // ---- the bench (companion-ops) ------------------------------------------------------------------------

    /** The form of an errand's step for its row, ahead of its duty; null for none (its duty's own form). */
    BenchDutyDispatcher.Form formFor(RosterEntry entry) {
        if (!world.config().COMPANION_TICKET_ERRANDS_ENABLED) {
            return null;
        }
        Errand errand = errands.get(entry.personaId());
        if (errand == null || errand.phase == Phase.HOMEWARD) {
            return null;
        }
        return errand.step == Step.HUNT_KEYS ? hunt : errand.step == Step.TRADE ? talk : null;
    }

    /** Its step's map once its road time is up; 0 (the ledger keeps it) while it is on its way. */
    private int mapFor(RosterEntry entry, Step wanted) {
        Errand errand = errands.get(entry.personaId());
        if (errand == null || errand.phase == Phase.HOMEWARD || clock.getAsLong() < errand.arrivesAtMs
                || errand.step != wanted) {
            return 0;
        }
        return errand.step.mapId;
    }

    /**
     * Loaded for a slice: writes the ledger's keys into its bag, reads it again and, when it is on another step than
     * its errand thought (a restart, a GM, keys looted out with its owner), moves the errand on. The errand, when this
     * slice is for its step on this map; null when it should unload again.
     */
    private Errand arrive(BenchDutyDispatcher.Slice slice, Character agent, AgentRuntimeEntry runtime, long nowMs) {
        Errand errand = errands.get(slice.personaId());
        if (errand == null || errand.phase == Phase.HOMEWARD) {
            return null;
        }
        flush(errand, runtime);
        Progress seen = progress.apply(agent);
        errand.seen = seen;
        Step step = next(seen);
        if (step == Step.DONE) {
            settle(errand, true, null);
            return null;
        }
        if (step != errand.step || step.mapId != slice.mapId()) {
            moveOn(errand, slice.mapId(), step, nowMs);
            return null;
        }
        errand.phase = Phase.AT_WORK;
        return errand;
    }

    /** On to {@code step}: the road from {@code fromMapId} to its map. */
    private void moveOn(Errand errand, int fromMapId, Step step, long nowMs) {
        errand.step = step;
        errand.phase = Phase.ON_THE_WAY;
        errand.arrivesAtMs = nowMs + eta(fromMapId, step.mapId);
        errand.ledgerAtMs = errand.arrivesAtMs;
        errand.tries = 0;
        world.store().event(errand.ownerId, errand.personaId, "ticket.step", step + " at map " + step.mapId);
    }

    /** A try that did not take (a visit too long, a refused trade, a hunt that would not start): the last one ends it. */
    private void failed(Errand errand, String why) {
        if (++errand.tries >= TRIES) {
            settle(errand, false, why);
        } else {
            errand.phase = Phase.ON_THE_WAY; // it tries again on its next turn
        }
    }

    private final class Talk implements BenchDutyDispatcher.Form {
        @Override
        public CompanionBudget.Kind pool() {
            return CompanionBudget.Kind.BENCH_TOWN;
        }

        @Override
        public int mapFor(RosterEntry entry, Persona persona, int characterId) {
            return TicketErrands.this.mapFor(entry, Step.TRADE);
        }

        /** Loaded at the map's portal: it walks up to Amos the Strong (it holds still meanwhile, never fights). */
        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            AgentRuntimeEntry runtime = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            Errand errand = runtime == null ? null : arrive(slice, agent, runtime, nowMs);
            if (errand == null) {
                return false;
            }
            MapleMap map = agent.getMap();
            NPC npc = map == null ? null : map.getNPCById(AMOS_THE_STRONG_NPC_ID);
            errand.spot = npc == null ? null : npc.getPosition();
            errand.visitStartedAtMs = nowMs;
            errand.reachedAtMs = 0L;
            if (errand.spot != null) {
                AgentMovementCommandRuntime.moveTo(runtime, errand.spot, false);
            }
            watch(errand, agent, runtime, ArrivalPlacement.WALK_OUT_CHECK_MS);
            return true;
        }

        @Override
        public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
            Errand errand = errands.get(slice.personaId());
            if (errand == null || errand.phase != Phase.AT_WORK) {
                return errand != null && errand.phase == Phase.ON_THE_WAY ? "on to its next step" : "errand over";
            }
            if (agent.getMapId() != slice.mapId()) {
                return "left Amos the Strong";
            }
            long visitMaxMs = (long) world.config().COMPANION_JOB_ERRAND_WALK_MAX_MS
                    + world.config().COMPANION_JOB_ERRAND_TALK_MS + BenchDutyDispatcher.TICK_MS;
            return nowMs - errand.visitStartedAtMs > visitMaxMs ? "visit took too long" : null;
        }

        @Override
        public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
            Errand errand = errands.get(slice.personaId());
            if (errand != null && errand.phase == Phase.AT_WORK) {
                failed(errand, reason);
            }
        }
    }

    /** It looks again after {@code delayMs}: there yet, done reading his menu, then the trade. */
    private void watch(Errand errand, Character agent, AgentRuntimeEntry runtime, long delayMs) {
        world.roster().later("ticket.errand.visit " + errand.personaId, () -> visit(errand, agent, runtime), delayMs);
    }

    private void visit(Errand errand, Character agent, AgentRuntimeEntry runtime) {
        if (errands.get(errand.personaId) != errand || errand.phase != Phase.AT_WORK || errand.step != Step.TRADE) {
            return; // its slice ended meanwhile
        }
        long now = clock.getAsLong();
        Point at = agent.getPosition();
        boolean there = errand.spot == null || at != null && at.distance(errand.spot) <= ArrivalPlacement.WALK_OUT_REACH_PX;
        if (!there && now - errand.visitStartedAtMs < world.config().COMPANION_JOB_ERRAND_WALK_MAX_MS) {
            watch(errand, agent, runtime, ArrivalPlacement.WALK_OUT_CHECK_MS);
            return;
        }
        if (errand.reachedAtMs == 0L) {
            errand.reachedAtMs = now;
        }
        long talkLeft = errand.reachedAtMs + world.config().COMPANION_JOB_ERRAND_TALK_MS - now;
        if (talkLeft > 0L) {
            watch(errand, agent, runtime, talkLeft);
            return;
        }
        if (!hands.trade(runtime)) {
            log.warn("ticket errand: {} could not trade its keys at Amos the Strong", errand.personaId);
            failed(errand, "the trade was refused");
            return;
        }
        world.store().event(errand.ownerId, errand.personaId, "ticket.trade", KEYS_WANTED + " keys");
        Progress seen = progress.apply(agent);
        errand.seen = seen;
        Step following = next(seen);
        if (following == Step.DONE) {
            MapleMap map = agent.getMap();
            if (map != null && ResidentRuntime.humansIn(map) > 0) {
                AgentDialogueTransportRuntime.sayMapNow(agent, TicketErrandLines.THANKS);
            }
            homeward(errand, true, null);
        } else {
            moveOn(errand, agent.getMapId(), following, now); // its slice ends at the next look
        }
    }

    private final class Hunt implements BenchDutyDispatcher.Form {
        @Override
        public CompanionBudget.Kind pool() {
            return CompanionBudget.Kind.BENCH_LIVE;
        }

        @Override
        public int mapFor(RosterEntry entry, Persona persona, int characterId) {
            return TicketErrands.this.mapFor(entry, Step.HUNT_KEYS);
        }

        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            AgentRuntimeEntry runtime = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            Errand errand = runtime == null ? null : arrive(slice, agent, runtime, nowMs);
            if (errand == null) {
                return false;
            }
            if (!startHunt(slice, agent, runtime, nowMs)) {
                failed(errand, "its hunt would not start");
                return false;
            }
            errand.live = true;
            errand.huntStartedAtMs = nowMs;
            errand.huntStart = errand.seen;
            return true;
        }

        @Override
        public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
            Errand errand = errands.get(slice.personaId());
            if (errand == null || errand.phase != Phase.AT_WORK) {
                return "errand over";
            }
            MapleMap map = agent.getMap();
            if (map == null || map.isTown()) {
                return "back in town"; // died and respawned, or wandered off the field
            }
            if (map.getId() != slice.mapId()) {
                return "left its hunting ground";
            }
            Progress seen = progress.apply(agent);
            errand.seen = seen;
            if (next(seen) != errand.step) {
                return "has its keys";
            }
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
            if (runtime != null && !AgentFieldVisitLeaseRuntime.active(runtime)
                    && (ResidentRuntime.humansIn(map) > 0 || nowMs - slice.startedAtMs() < world.config().BENCH_LIVE_SLICE_MS)) {
                // its visit ended while the slice runs on (a player is watching): another visit on the same ground
                return startHunt(slice, agent, runtime, nowMs) ? null : "field visit ended";
            }
            return null;
        }

        /** Its live key rate from this hunt; then the trade's road when it has ten. */
        @Override
        public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
            Errand errand = errands.get(slice.personaId());
            if (errand == null) {
                return;
            }
            errand.live = false;
            long now = clock.getAsLong();
            errand.ledgerAtMs = now;
            if (agent == null || errand.phase != Phase.AT_WORK) {
                return;
            }
            Progress seen = progress.apply(agent);
            errand.seen = seen;
            measure(errand, errand.huntStart, seen, now - errand.huntStartedAtMs);
            Step following = next(seen);
            if (following == errand.step) {
                errand.phase = Phase.ON_THE_WAY; // its next field slice carries on here; the ledger meanwhile
                errand.arrivesAtMs = now;
            } else if (following == Step.DONE) {
                settle(errand, true, null);
            } else {
                moveOn(errand, slice.mapId(), following, now);
            }
        }
    }

    private boolean startHunt(BenchDutyDispatcher.Slice slice, Character agent, AgentRuntimeEntry runtime, long nowMs) {
        return hands.hunt(runtime, agent, "ticket-hunt:" + slice.personaId() + ':' + nowMs, slice.mapId(),
                nowMs + world.config().BENCH_LIVE_SLICE_MS);
    }

    /** Keys an hour it got between {@code before} and {@code after}, over {@code elapsedMs} live. */
    static void measure(Errand errand, Progress before, Progress after, long elapsedMs) {
        if (before == null || after == null || elapsedMs < MEASURE_MIN_MS) {
            return;
        }
        errand.perHour = Math.max(0, after.keys() - before.keys()) / (elapsedMs / HOUR_MS);
    }

    // ---- the ledger --------------------------------------------------------------------------------------

    /**
     * A hunt between its live slices: the keys it still needs gain its measured rate times the hours since the last
     * look, never past ten; a hunt it never measured live gains nothing. Once the credit covers the ten, it heads for
     * Amos the Strong (its bag takes the credit when it is loaded there).
     */
    void accrue(Errand errand, long nowMs) {
        if (errand.phase == Phase.HOMEWARD || errand.live || errand.step != Step.HUNT_KEYS
                || nowMs < errand.arrivesAtMs || errand.seen == null) {
            return;
        }
        double hours = Math.max(0L, nowMs - errand.ledgerAtMs) / HOUR_MS;
        errand.ledgerAtMs = nowMs;
        int needed = Math.max(0, KEYS_WANTED - errand.seen.keys());
        errand.credit = Math.min(needed, errand.credit + errand.perHour * hours);
        if (needed > 0 && errand.credit >= needed) {
            world.store().event(errand.ownerId, errand.personaId, "ticket.ledger", "keys covered");
            moveOn(errand, errand.step.mapId, Step.TRADE, nowMs);
        }
    }

    /** The ledger's whole keys, written into the loaded companion's bag; the fraction stays. */
    private void flush(Errand errand, AgentRuntimeEntry runtime) {
        int keys = (int) Math.floor(errand.credit);
        if (keys <= 0) {
            return;
        }
        hands.credit(runtime, keys);
        errand.credit -= keys;
        world.store().event(errand.ownerId, errand.personaId, "ticket.ledger", "credited " + keys + " keys");
    }

    // ---- home again, the sweep and a restart (companion-ops) --------------------------------------------------

    /**
     * Done ({@code done}: it holds the ticket) or given up ({@code why}), in the log: a benched one goes back to its
     * duties; an owner's is called back by {@link #homeward} or the sweep (never from a slice starting or ending: a call
     * may be what ends it).
     */
    private void settle(Errand errand, boolean done, String why) {
        errand.done = done;
        errand.phase = Phase.HOMEWARD;
        world.store().event(errand.ownerId, errand.personaId, done ? "ticket.earned" : "ticket.errand.end",
                done ? "Entrance Ticket" : "not earned: " + why);
        if (!errand.returns) {
            end(errand);
        }
    }

    /** {@link #settle}, then an owner's companion heads back to them at once. */
    private void homeward(Errand errand, boolean done, String why) {
        settle(errand, done, why);
        if (errand.returns) {
            returnHome(errand);
        }
    }

    /** Called back to its owner; with the ticket it says so on arrival. Its owner offline or busy: the sweep tries. */
    private void returnHome(Errand errand) {
        RosterEntry row = world.store().rosterEntry(errand.ownerId, errand.personaId);
        if (row == null || row.state() != RosterState.AWAY) {
            end(errand);
            return;
        }
        Character owner = world.roster().onlineCharacter(errand.ownerId);
        Persona persona = world.content().persona(errand.personaId);
        if (owner == null || persona == null || owner.getEventInstance() != null || !world.roster().canSeat(owner, row)) {
            return;
        }
        if (errand.done) {
            world.roster().sayOnArrival(persona.id(), line(persona, "ticketBack", TicketErrandLines.BACK, owner.getId()));
        }
        if (world.roster().call(owner, persona).ok()) {
            end(errand);
        } else {
            world.roster().sayOnArrival(persona.id(), null);
        }
    }

    /**
     * The errands out: the ledger moves each hunt on; one whose row is no longer away (called back, benched, released)
     * or no longer benched is over; one done is called back once its owner can take it; the kill switch off calls one
     * on its way back.
     */
    public void sweep() {
        long now = clock.getAsLong();
        for (Errand errand : List.copyOf(errands.values())) {
            RosterEntry row = world.store().rosterEntry(errand.ownerId, errand.personaId);
            boolean stays = errand.returns ? row != null && row.state() == RosterState.AWAY : BenchSessions.benchGoverned(row);
            if (!stays) {
                end(errand); // called or released meanwhile: its bag keeps the keys it got live
            } else if (errand.phase == Phase.HOMEWARD) {
                returnHome(errand);
            } else if (!world.config().COMPANION_TICKET_ERRANDS_ENABLED) {
                homeward(errand, false, "ticket errands switched off");
            } else {
                accrue(errand, now);
            }
        }
    }

    /** The errands a restart cut short: each starts again in Purplewood, and its first load reads where it stands. */
    void restore() {
        long now = clock.getAsLong();
        for (String flag : world.store().worldFlags()) {
            if (!flag.startsWith(FLAG_PREFIX)) {
                continue;
            }
            String[] parts = flag.substring(FLAG_PREFIX.length()).split("\\.", 2);
            int ownerId = parts.length == 2 && parts[0].matches("\\d{1,9}") ? Integer.parseInt(parts[0]) : 0;
            RosterEntry row = ownerId == 0 ? null : world.store().rosterEntry(ownerId, parts[1]);
            if (row == null || row.state() != RosterState.AWAY && row.state() != RosterState.BENCHED) {
                world.store().clearWorldFlag(flag);
                continue;
            }
            errands.put(row.personaId(), new Errand(row.personaId(), ownerId, row.state() == RosterState.AWAY,
                    Step.HUNT_KEYS, now));
        }
    }

    // ---- what changes the Agent ---------------------------------------------------------------------------

    /** The Agent's own bags, Amos the Strong's script and its field visit, on its mailbox. */
    private static final class AgentHands implements Hands {
        @Override
        public boolean trade(AgentRuntimeEntry runtime) {
            return Boolean.TRUE.equals(BenchDutyDispatcher.onAgent(runtime, entry -> {
                PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
                return gateway.runNpcScript(entry.bot(), AMOS_THE_STRONG_NPC_ID, TRADE_SELECTION)
                        && gateway.itemCount(entry.bot(), TICKET_ITEM_ID) > 0;
            }));
        }

        @Override
        public void credit(AgentRuntimeEntry runtime, int keys) {
            BenchDutyDispatcher.onAgent(runtime, entry ->
                    AgentPrimitiveCapabilityGatewayRuntime.gateway().grantItem(entry.bot(), KEY_ITEM_ID, keys));
        }

        @Override
        public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, long exitAtMs) {
            AgentFieldIntent intent = new AgentFieldIntent(AgentFieldIntent.Type.FREE_GRIND, requestId, KEY_DROPPERS,
                    Map.of(), false);
            AgentFieldVisitLeaseRequest lease = new AgentFieldVisitLeaseRequest(
                    new AgentFieldEntryRequest(requestId, "living-ticket", new AgentFieldVisitRequest(mapId, intent,
                            true, FIELD_PARTICIPANTS, true, null)),
                    AgentFieldAdmissionMode.CREATE_OR_JOIN, exitAtMs, GRACEFUL_EXIT_MS, "ticket hunt slice elapsed");
            AgentFieldSessionResult result = BenchDutyDispatcher.onAgent(runtime,
                    entry -> AgentFieldVisitLeaseRuntime.start(entry, agent, lease, System.currentTimeMillis()));
            return result != null && (result.started()
                    || result.status() == AgentFieldSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST);
        }
    }

    /** Its Lip Lock Keys and whether it holds a ticket, as the gateway reads them. */
    static Progress read(Character agent) {
        PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return new Progress(gateway.itemCount(agent, KEY_ITEM_ID), gateway.itemCount(agent, TICKET_ITEM_ID) > 0);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private long eta(int fromMapId, int toMapId) {
        return RecallTravel.etaMs(fromMapId, toMapId, world.config().RECALL_MAX_ETA_MS);
    }

    private void end(Errand errand) {
        if (errands.remove(errand.personaId, errand)) {
            world.store().clearWorldFlag(flag(errand.ownerId, errand.personaId));
        }
    }

    private String line(Persona persona, String intent, List<String> fallback, int seed) {
        List<String> own = world.arcs().lines(persona, intent);
        List<String> lines = own == null || own.isEmpty() ? fallback : own;
        return lines.get(Math.floorMod(seed, lines.size()));
    }

    static String flag(int ownerId, String personaId) {
        return FLAG_PREFIX + ownerId + "." + personaId;
    }

    /** The errand out for {@code personaId}, if any (tests and the crew view). */
    Errand errand(String personaId) {
        return errands.get(personaId);
    }

    /** True while {@code personaId} is out for its ticket (not yet on its way back). */
    public boolean underway(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        return errand != null && errand.phase != Phase.HOMEWARD;
    }

    /** What it is doing for its ticket, as a player would say it, while {@link #underway}; else null. */
    public String doing(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        if (errand == null || errand.phase == Phase.HOMEWARD) {
            return null;
        }
        return errand.step == Step.TRADE ? "gone to see Amos the Strong" : "hunting Lip Lock Keys in Purplewood";
    }
}
