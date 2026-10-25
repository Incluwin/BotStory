package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Companions earn their own Crimsonwood Keystone (docs/CWKPQ_COMPANION_PLAN.md A3, 2026-10-19). The Gate into the
 * Keep's Inner Sanctum lets only holders in, and only three quests give it: The Brewing Storm (8222, ten Stormbreaker
 * Badges for Lukan), Stemming the Tide started at Jack in the Cavern of Pain (8230), then Storming the Castle (8223, 75
 * each of Windraider, Firebrand and Nightshadow for Lukan). A companion out with its owner gets them by the quest
 * mirror when it goes along with its owner; this errand is for one that goes alone, because its owner asked it to
 * ("casse get your keystone"): out with its owner it walks out keeping its seat, as for its next job (JobErrands); a
 * benched one goes between its duties.
 *
 * <p>The errand is a chain of steps, each read from the companion's own quest log and bags whenever it is loaded
 * ({@link #next}), so the quest log is the record and a step is never claimed twice. A talk step is a town-pool bench
 * slice on Lukan's or Jack's map: it walks up, stands a moment and starts or hands in the quest as a player does. A hunt
 * step is a field-pool slice on a hunting ground holding nothing but its marks (Stormhall for the badges, the Cavern of
 * Fortitude for the three kinds), fighting only them; Firebrand is hunted in its first form, which revives into the one
 * the quest counts. Between live slices the ledger carries a hunt on at the kill rates the companion itself measured
 * live there, never at a guessed rate, so a ledgered hunt is never quicker than a live one; what the ledger credits
 * goes into the quest log and the bag the next time the companion is loaded. A restart loses ledger credit not yet
 * written, never quest progress.</p>
 *
 * <p>{@link #ask} answers the owner's chat on companion-ops; the forms and the sweep run there too; what changes a
 * running Agent (a quest, a credit, a field visit) goes through its mailbox.</p>
 */
public final class KeystoneErrands {
    private static final Logger log = LoggerFactory.getLogger(KeystoneErrands.class);
    static final String FLAG_PREFIX = "companion.keystone-errand.";

    public static final int LUKAN_NPC_ID = 9201098;
    public static final int PHANTOM_ROAD_MAP_ID = 610010003;
    public static final int JACK_NPC_ID = 9201096;
    public static final int CAVERN_OF_PAIN_MAP_ID = 610020011;
    public static final int BREWING_STORM_QUEST_ID = 8222;
    public static final int STEMMING_THE_TIDE_QUEST_ID = 8230;
    public static final int STORMING_THE_CASTLE_QUEST_ID = 8223;
    public static final int STORMBREAKER_BADGE_ITEM_ID = 4032006;
    public static final int BADGES_WANTED = 10;
    public static final int KEYSTONE_ITEM_ID = 3992041;
    public static final int WINDRAIDER_MOB_ID = 9400576;
    /** The Firebrand that spawns; it revives into {@link #RISEN_FIREBRAND_MOB_ID}, the one 8223 counts. */
    public static final int FIREBRAND_MOB_ID = 9400577;
    public static final int RISEN_FIREBRAND_MOB_ID = 9400578;
    public static final int NIGHTSHADOW_MOB_ID = 9400579;
    public static final int STORMBREAKER_MOB_ID = 9400581;
    public static final int KILLS_WANTED = 75;
    /** Stormhall: seven Stormbreakers and nothing else. */
    public static final int STORMHALL_MAP_ID = 610030014;
    /** The Cavern of Fortitude: four Windraiders, four Firebrands, three Nightshadows and nothing else. */
    public static final int CAVERN_OF_FORTITUDE_MAP_ID = 610020014;
    /** What 8223 counts, in its quest's order. */
    static final List<Integer> CASTLE_MARKS = List.of(WINDRAIDER_MOB_ID, RISEN_FIREBRAND_MOB_ID, NIGHTSHADOW_MOB_ID);
    private static final double HOUR_MS = 3_600_000.0d;
    /** A live hunt shorter than this measures nothing (a slice that ended as it began). */
    private static final long MEASURE_MIN_MS = 60_000L;
    /** A visit or hunt start that keeps failing gives the errand up after this many tries in a row. */
    static final int TRIES = 3;
    /** Agents one hunting ground's field session takes (as for a bench gather). */
    private static final int FIELD_PARTICIPANTS = 6;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    enum Action { START, COMPLETE, TALK, HUNT, NONE }

    /** The steps, in order; {@link #next} names the one a companion is on. */
    enum Step {
        START_BREWING_STORM(Action.START, BREWING_STORM_QUEST_ID, LUKAN_NPC_ID, PHANTOM_ROAD_MAP_ID),
        HUNT_BADGES(Action.HUNT, BREWING_STORM_QUEST_ID, 0, STORMHALL_MAP_ID),
        FINISH_BREWING_STORM(Action.COMPLETE, BREWING_STORM_QUEST_ID, LUKAN_NPC_ID, PHANTOM_ROAD_MAP_ID),
        START_STEMMING_THE_TIDE(Action.START, STEMMING_THE_TIDE_QUEST_ID, JACK_NPC_ID, CAVERN_OF_PAIN_MAP_ID),
        START_STORMING_THE_CASTLE(Action.START, STORMING_THE_CASTLE_QUEST_ID, LUKAN_NPC_ID, PHANTOM_ROAD_MAP_ID),
        HUNT_CASTLE(Action.HUNT, STORMING_THE_CASTLE_QUEST_ID, 0, CAVERN_OF_FORTITUDE_MAP_ID),
        FINISH_STORMING_THE_CASTLE(Action.COMPLETE, STORMING_THE_CASTLE_QUEST_ID, LUKAN_NPC_ID, PHANTOM_ROAD_MAP_ID),
        /** 8223 is done but the keystone is gone: Lukan crafts another (scripts/npc/9201098.js). */
        REISSUE_KEYSTONE(Action.TALK, 0, LUKAN_NPC_ID, PHANTOM_ROAD_MAP_ID),
        /** 8230 was finished before 8223 began: 8223 can never start (its start needs 8230 in progress). */
        LOCKED_OUT(Action.NONE, 0, 0, 0),
        DONE(Action.NONE, 0, 0, 0);

        final Action action;
        final int questId;
        final int npcId;
        final int mapId;

        Step(Action action, int questId, int npcId, int mapId) {
            this.action = action;
            this.questId = questId;
            this.npcId = npcId;
            this.mapId = mapId;
        }

        boolean talk() {
            return action == Action.START || action == Action.COMPLETE || action == Action.TALK;
        }
    }

    /**
     * What the companion's quest log and bags say: the three quests' states (0 not started, 1 in progress, 2 done), the
     * badges it holds, 8223's count per mark and whether it holds the keystone.
     */
    record Progress(int brewingStorm, int stemmingTheTide, int stormingTheCastle, int badges,
                    Map<Integer, Integer> kills, boolean keystone) {
        Progress {
            kills = Map.copyOf(kills);
        }

        int kills(int mobId) {
            return kills.getOrDefault(mobId, 0);
        }
    }

    /** The step a companion with {@code progress} is on. */
    static Step next(Progress progress) {
        if (progress.keystone()) {
            return Step.DONE;
        }
        if (progress.stormingTheCastle() == 2) {
            return Step.REISSUE_KEYSTONE;
        }
        if (progress.stormingTheCastle() == 1) {
            return CASTLE_MARKS.stream().allMatch(mob -> progress.kills(mob) >= KILLS_WANTED)
                    ? Step.FINISH_STORMING_THE_CASTLE : Step.HUNT_CASTLE;
        }
        if (progress.brewingStorm() == 2) {
            return switch (progress.stemmingTheTide()) {
                case 0 -> Step.START_STEMMING_THE_TIDE;
                case 1 -> Step.START_STORMING_THE_CASTLE;
                default -> Step.LOCKED_OUT;
            };
        }
        if (progress.brewingStorm() == 1) {
            return progress.badges() >= BADGES_WANTED ? Step.FINISH_BREWING_STORM : Step.HUNT_BADGES;
        }
        return Step.START_BREWING_STORM;
    }

    /** The marks a hunt step still needs, by id (a mob id, or the badge's item id), with how many more. */
    static Map<Integer, Integer> wanted(Step step, Progress progress) {
        Map<Integer, Integer> wanted = new HashMap<>();
        if (step == Step.HUNT_BADGES && progress.badges() < BADGES_WANTED) {
            wanted.put(STORMBREAKER_BADGE_ITEM_ID, BADGES_WANTED - progress.badges());
        } else if (step == Step.HUNT_CASTLE) {
            for (int mob : CASTLE_MARKS) {
                if (progress.kills(mob) < KILLS_WANTED) {
                    wanted.put(mob, KILLS_WANTED - progress.kills(mob));
                }
            }
        }
        return wanted;
    }

    /** The mobs a hunt fights for what it still needs: Firebrands in their first form for the risen ones. */
    static Set<Integer> targets(Step step, Progress progress) {
        Set<Integer> targets = new LinkedHashSet<>();
        for (int mark : wanted(step, progress).keySet()) {
            if (mark == STORMBREAKER_BADGE_ITEM_ID) {
                targets.add(STORMBREAKER_MOB_ID);
            } else if (mark == RISEN_FIREBRAND_MOB_ID) {
                targets.add(FIREBRAND_MOB_ID);
                targets.add(RISEN_FIREBRAND_MOB_ID);
            } else {
                targets.add(mark);
            }
        }
        return targets;
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
        /** True once it holds the keystone. */
        volatile boolean done;
        /** True while a hunt slice of it is live (the ledger keeps still meanwhile). */
        volatile boolean live;
        /** Its quest log and bags as last read. */
        volatile Progress seen;
        /** Mark id -> what the ledger credited and the quest log does not hold yet. */
        final Map<Integer, Double> credit = new ConcurrentHashMap<>();
        /** Mark id -> how many an hour it got live on its hunting ground (its last measured hunt). */
        final Map<Integer, Double> perHour = new ConcurrentHashMap<>();
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
        /** Starts, hands in or talks for {@code step} as a player would; true when the quest log took it. */
        boolean act(AgentRuntimeEntry runtime, Step step);

        /** Writes ledger credit into its quest log and bag: whole badges into the bag, whole kills into 8223's count. */
        void credit(AgentRuntimeEntry runtime, Map<Integer, Integer> whole);

        /** Starts its field visit on its hunting ground, fighting only {@code targets}; true when it started. */
        boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, Set<Integer> targets,
                     long exitAtMs);
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Map<String, Errand> errands = new ConcurrentHashMap<>();
    /** True while the Agent goes along with its owner (a test stands in for the Agent's follow state). */
    BiPredicate<AgentRuntimeEntry, Integer> goesAlong = AgentSupplyReadiness::carriedBy;
    /** Its quest log and bags (a test stands in for the character). */
    Function<Character, Progress> progress = KeystoneErrands::read;
    Hands hands = new AgentHands();
    final BenchDutyDispatcher.Form talk = new Talk();
    final BenchDutyDispatcher.Form hunt = new Hunt();

    public KeystoneErrands(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    KeystoneErrands(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** Takes a row ahead of its duty while its errand is out (after the job errands); carries on saved errands. */
    public void install() {
        world.benchDuty().alsoFirst(this::formFor);
        world.executor().submit("keystone_errands.restore", this::restore);
    }

    // ---- the owner's ask (companion-ops) -----------------------------------------------------------------

    /**
     * "casse get your keystone": out with its owner and free, it says so and walks out keeping its seat; benched, it
     * goes between its duties. What the owner reads back.
     */
    public String ask(Character owner, Persona persona) {
        if (!world.config().COMPANION_KEYSTONE_ERRANDS_ENABLED) {
            return persona.name() + " can't go for a keystone right now.";
        }
        RosterEntry row = world.store().rosterEntry(owner.getId(), persona.id());
        if (row == null || row.state() == RosterState.RELEASED) {
            return persona.name() + " is not with you.";
        }
        if (errands.containsKey(persona.id())) {
            return persona.name() + " is already working on a keystone.";
        }
        JobErrands jobs = world.jobErrands();
        if (jobs != null && jobs.underway(persona.id())) {
            return persona.name() + " is off to see an instructor first.";
        }
        TicketErrands tickets = world.ticketErrands();
        if (tickets != null && tickets.underway(persona.id())) {
            return persona.name() + " is out for an Amorian Challenge ticket first.";
        }
        int characterId = world.residents().characterIdOf(persona.id());
        int level = characterId == 0 ? 0 : world.store().characterLevel(characterId);
        if (level < world.config().COMPANION_KEYSTONE_ERRAND_MIN_LEVEL) {
            world.residents().reply(persona, owner, line(persona, "keystoneLow", KeystoneErrandLines.LOW, owner.getId()));
            return persona.name() + " needs Lv" + world.config().COMPANION_KEYSTONE_ERRAND_MIN_LEVEL + " for the Crimsonwood hunts.";
        }
        long now = clock.getAsLong();
        if (row.state() == RosterState.BENCHED) {
            int camp = row.campMapId() != 0 ? row.campMapId() : ResidentSpots.townMapId(persona.homeTown());
            Errand errand = new Errand(persona.id(), owner.getId(), false, Step.START_BREWING_STORM,
                    now + eta(camp, PHANTOM_ROAD_MAP_ID));
            open(errand, "from the bench");
            return persona.name() + " will go for a Crimsonwood Keystone between bench duties.";
        }
        Character bot = row.state() == RosterState.ACTIVE ? world.roster().companion(row) : null;
        if (bot == null || !free(owner, bot, persona)) {
            return persona.name() + " is busy right now.";
        }
        Progress now0 = progress.apply(bot);
        Step step = next(now0);
        if (step == Step.DONE) {
            world.residents().reply(persona, owner, line(persona, "keystoneHave", KeystoneErrandLines.HAVE, owner.getId()));
            return persona.name() + " already holds a Crimsonwood Keystone.";
        }
        if (step == Step.LOCKED_OUT) {
            world.residents().reply(persona, owner, line(persona, "keystoneLocked", KeystoneErrandLines.LOCKED, owner.getId()));
            return persona.name() + " finished Stemming the Tide too early: Storming the Castle can no longer start.";
        }
        RosterResult left = world.roster().leaveOnErrand(owner, persona,
                line(persona, "keystoneErrand", KeystoneErrandLines.GO, owner.getId()));
        if (!left.ok()) {
            return RosterVoice.line(persona, left);
        }
        Errand errand = new Errand(persona.id(), owner.getId(), true, step, now + eta(bot.getMapId(), step.mapId));
        errand.seen = now0;
        open(errand, "from its owner");
        return persona.name() + " leaves to earn a Crimsonwood Keystone, keeping the party slot.";
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
        world.store().event(errand.ownerId, errand.personaId, "keystone.errand", errand.step + " " + how);
    }

    // ---- the bench (companion-ops) ------------------------------------------------------------------------

    /** The form of an errand's step for its row, ahead of its duty; null for none (its duty's own form). */
    BenchDutyDispatcher.Form formFor(RosterEntry entry) {
        if (!world.config().COMPANION_KEYSTONE_ERRANDS_ENABLED) {
            return null;
        }
        Errand errand = errands.get(entry.personaId());
        if (errand == null || errand.phase == Phase.HOMEWARD) {
            return null;
        }
        return errand.step.action == Action.HUNT ? hunt : errand.step.talk() ? talk : null;
    }

    /** Its step's map once its road time is up; 0 (the ledger keeps it) while it is on its way. */
    private int mapFor(RosterEntry entry, Action... actions) {
        Errand errand = errands.get(entry.personaId());
        if (errand == null || errand.phase == Phase.HOMEWARD || clock.getAsLong() < errand.arrivesAtMs) {
            return 0;
        }
        for (Action action : actions) {
            if (errand.step.action == action) {
                return errand.step.mapId;
            }
        }
        return 0;
    }

    /**
     * Loaded for {@code expected}: writes the ledger's credit into its quest log, reads it again and, when it is on
     * another step than its errand thought (a restart, a GM, its owner's mirror), moves the errand on. The errand,
     * when this slice is for its step on this map; null when it should unload again.
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
        if (step == Step.DONE || step == Step.LOCKED_OUT) {
            settle(errand, step == Step.DONE, step == Step.DONE ? null : "locked out of 8223");
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
        world.store().event(errand.ownerId, errand.personaId, "keystone.step", step + " at map " + step.mapId);
    }

    /** A try that did not take (a visit too long, a refused quest, a hunt that would not start): the last one ends it. */
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
            return KeystoneErrands.this.mapFor(entry, Action.START, Action.COMPLETE, Action.TALK);
        }

        /** Loaded at the map's portal: it walks up to Lukan or Jack (it holds still meanwhile, never fights). */
        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            AgentRuntimeEntry runtime = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            Errand errand = runtime == null ? null : arrive(slice, agent, runtime, nowMs);
            if (errand == null) {
                return false;
            }
            MapleMap map = agent.getMap();
            NPC npc = map == null ? null : map.getNPCById(errand.step.npcId);
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
                return "left " + who(errand.step);
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

    /** It looks again after {@code delayMs}: there yet, done talking, then the quest. */
    private void watch(Errand errand, Character agent, AgentRuntimeEntry runtime, long delayMs) {
        world.roster().later("keystone.errand.visit " + errand.personaId, () -> visit(errand, agent, runtime), delayMs);
    }

    private void visit(Errand errand, Character agent, AgentRuntimeEntry runtime) {
        if (errands.get(errand.personaId) != errand || errand.phase != Phase.AT_WORK || !errand.step.talk()) {
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
        Step step = errand.step;
        if (!hands.act(runtime, step)) {
            log.warn("keystone errand: {} could not {} at {}", errand.personaId, step, who(step));
            failed(errand, step + " was refused");
            return;
        }
        world.store().event(errand.ownerId, errand.personaId, "keystone.quest", step.toString());
        Progress seen = progress.apply(agent);
        errand.seen = seen;
        Step following = next(seen);
        if (following == Step.DONE) {
            MapleMap map = agent.getMap();
            if (map != null && ResidentRuntime.humansIn(map) > 0) {
                AgentDialogueTransportRuntime.sayMapNow(agent, KeystoneErrandLines.THANKS);
            }
            homeward(errand, true, null);
        } else if (following == Step.LOCKED_OUT) {
            homeward(errand, false, "locked out of 8223");
        } else if (following.talk() && following.npcId == step.npcId && following.mapId == agent.getMapId()) {
            errand.step = following; // another word with the same NPC: it stays where it stands
            errand.reachedAtMs = now;
            errand.visitStartedAtMs = now;
            watch(errand, agent, runtime, world.config().COMPANION_JOB_ERRAND_TALK_MS);
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
            return KeystoneErrands.this.mapFor(entry, Action.HUNT);
        }

        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            AgentRuntimeEntry runtime = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            Errand errand = runtime == null ? null : arrive(slice, agent, runtime, nowMs);
            if (errand == null) {
                return false;
            }
            if (!startHunt(errand, slice, agent, runtime, nowMs)) {
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
                return "hunted what it needs";
            }
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
            if (runtime != null && !AgentFieldVisitLeaseRuntime.active(runtime)
                    && (ResidentRuntime.humansIn(map) > 0 || nowMs - slice.startedAtMs() < world.config().BENCH_LIVE_SLICE_MS)) {
                // its visit ended while the slice runs on (a player is watching): another visit on the same ground
                return startHunt(errand, slice, agent, runtime, nowMs) ? null : "field visit ended";
            }
            return null;
        }

        /** Its live rates from this hunt; then its next step's road when the hunt is done. */
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

    private boolean startHunt(Errand errand, BenchDutyDispatcher.Slice slice, Character agent, AgentRuntimeEntry runtime,
                              long nowMs) {
        Set<Integer> targets = targets(errand.step, errand.seen);
        return !targets.isEmpty() && hands.hunt(runtime, agent, "keystone-hunt:" + slice.personaId() + ':' + nowMs,
                slice.mapId(), targets, nowMs + world.config().BENCH_LIVE_SLICE_MS);
    }

    /** Mark id -> how many an hour it got between {@code before} and {@code after}, over {@code elapsedMs} live. */
    static void measure(Errand errand, Progress before, Progress after, long elapsedMs) {
        if (before == null || after == null || elapsedMs < MEASURE_MIN_MS) {
            return;
        }
        double hours = elapsedMs / HOUR_MS;
        if (errand.step == Step.HUNT_BADGES) {
            errand.perHour.put(STORMBREAKER_BADGE_ITEM_ID, Math.max(0, after.badges() - before.badges()) / hours);
        } else if (errand.step == Step.HUNT_CASTLE) {
            for (int mob : CASTLE_MARKS) {
                errand.perHour.put(mob, Math.max(0, after.kills(mob) - before.kills(mob)) / hours);
            }
        }
    }

    // ---- the ledger --------------------------------------------------------------------------------------

    /**
     * A hunt between its live slices: each mark it still needs gains its measured rate times the hours since the last
     * look, never past what the step needs; a hunt it never measured live gains nothing. Once the credit covers the
     * step, it heads for its next step (its quest log takes the credit when it is loaded there).
     */
    void accrue(Errand errand, long nowMs) {
        if (errand.phase == Phase.HOMEWARD || errand.live || errand.step.action != Action.HUNT
                || nowMs < errand.arrivesAtMs || errand.seen == null) {
            return;
        }
        double hours = Math.max(0L, nowMs - errand.ledgerAtMs) / HOUR_MS;
        errand.ledgerAtMs = nowMs;
        Map<Integer, Integer> wanted = wanted(errand.step, errand.seen);
        boolean covered = !wanted.isEmpty();
        for (Map.Entry<Integer, Integer> need : wanted.entrySet()) {
            double rate = errand.perHour.getOrDefault(need.getKey(), 0.0d);
            double credited = Math.min(need.getValue(), errand.credit.getOrDefault(need.getKey(), 0.0d) + rate * hours);
            if (credited > 0.0d) {
                errand.credit.put(need.getKey(), credited);
            }
            covered &= credited >= need.getValue();
        }
        if (covered) {
            Step following = errand.step == Step.HUNT_BADGES ? Step.FINISH_BREWING_STORM : Step.FINISH_STORMING_THE_CASTLE;
            world.store().event(errand.ownerId, errand.personaId, "keystone.ledger", errand.step + " covered");
            moveOn(errand, errand.step.mapId, following, nowMs);
        }
    }

    /** The ledger's whole credit, written into the loaded companion's quest log and bag; the fractions stay. */
    private void flush(Errand errand, AgentRuntimeEntry runtime) {
        Map<Integer, Integer> whole = new HashMap<>();
        for (Map.Entry<Integer, Double> credit : errand.credit.entrySet()) {
            int count = (int) Math.floor(credit.getValue());
            if (count > 0) {
                whole.put(credit.getKey(), count);
            }
        }
        if (whole.isEmpty()) {
            return;
        }
        hands.credit(runtime, whole);
        whole.forEach((mark, count) -> errand.credit.computeIfPresent(mark, (id, value) -> value - count > 0 ? value - count : null));
        world.store().event(errand.ownerId, errand.personaId, "keystone.ledger", "credited " + whole);
    }

    // ---- home again, the sweep and a restart (companion-ops) --------------------------------------------------

    /**
     * Done ({@code done}: it holds the keystone) or given up ({@code why}), in the log: a benched one goes back to its
     * duties; an owner's is called back by {@link #homeward} or the sweep (never from a slice starting or ending: a call
     * may be what ends it).
     */
    private void settle(Errand errand, boolean done, String why) {
        errand.done = done;
        errand.phase = Phase.HOMEWARD;
        world.store().event(errand.ownerId, errand.personaId, done ? "keystone.earned" : "keystone.errand.end",
                done ? "Crimsonwood Keystone" : "not earned: " + why);
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

    /** Called back to its owner; with the keystone it says so on arrival. Its owner offline or busy: the sweep tries. */
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
            world.roster().sayOnArrival(persona.id(), line(persona, "keystoneBack", KeystoneErrandLines.BACK, owner.getId()));
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
                end(errand); // called or released meanwhile: its quest log keeps what it got live
            } else if (errand.phase == Phase.HOMEWARD) {
                returnHome(errand);
            } else if (!world.config().COMPANION_KEYSTONE_ERRANDS_ENABLED) {
                homeward(errand, false, "keystone errands switched off");
            } else {
                accrue(errand, now);
            }
        }
    }

    /** The errands a restart cut short: each starts again from Lukan's door, and its first load reads where it stands. */
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
                    Step.START_BREWING_STORM, now));
        }
    }

    // ---- what changes the Agent ---------------------------------------------------------------------------

    /** The Agent's own quest log, bags and field visit, on its mailbox. */
    private static final class AgentHands implements Hands {
        @Override
        public boolean act(AgentRuntimeEntry runtime, Step step) {
            return Boolean.TRUE.equals(BenchDutyDispatcher.onAgent(runtime, entry -> {
                Character agent = entry.bot();
                PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
                return switch (step.action) {
                    case START -> gateway.startQuest(agent, step.questId, step.npcId);
                    case COMPLETE -> gateway.completeQuest(agent, step.questId, step.npcId);
                    case TALK -> gateway.runNpcScript(agent, step.npcId)
                            && gateway.itemCount(agent, KEYSTONE_ITEM_ID) > 0;
                    default -> false;
                };
            }));
        }

        @Override
        public void credit(AgentRuntimeEntry runtime, Map<Integer, Integer> whole) {
            BenchDutyDispatcher.onAgent(runtime, entry -> {
                Character agent = entry.bot();
                PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
                whole.forEach((mark, count) -> {
                    if (mark == STORMBREAKER_BADGE_ITEM_ID) {
                        gateway.grantItem(agent, mark, count);
                    } else if (gateway.questStatus(agent, STORMING_THE_CASTLE_QUEST_ID) == 1) {
                        int kills = gateway.questProgress(agent, STORMING_THE_CASTLE_QUEST_ID, mark);
                        gateway.setQuestProgress(agent, STORMING_THE_CASTLE_QUEST_ID, mark,
                                Math.min(KILLS_WANTED, kills + count));
                    }
                });
                return Boolean.TRUE;
            });
        }

        @Override
        public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, Set<Integer> targets,
                            long exitAtMs) {
            AgentFieldIntent intent = new AgentFieldIntent(AgentFieldIntent.Type.FREE_GRIND, requestId, targets,
                    Map.of(), false);
            AgentFieldVisitLeaseRequest lease = new AgentFieldVisitLeaseRequest(
                    new AgentFieldEntryRequest(requestId, "living-keystone", new AgentFieldVisitRequest(mapId, intent,
                            true, FIELD_PARTICIPANTS, true, null)),
                    AgentFieldAdmissionMode.CREATE_OR_JOIN, exitAtMs, GRACEFUL_EXIT_MS, "keystone hunt slice elapsed");
            AgentFieldSessionResult result = BenchDutyDispatcher.onAgent(runtime,
                    entry -> AgentFieldVisitLeaseRuntime.start(entry, agent, lease, System.currentTimeMillis()));
            return result != null && (result.started()
                    || result.status() == AgentFieldSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST);
        }
    }

    /** The three quests' states, the badges, 8223's counts and the keystone, as the gateway reads them. */
    static Progress read(Character agent) {
        PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        Map<Integer, Integer> kills = new HashMap<>();
        for (int mob : CASTLE_MARKS) {
            kills.put(mob, Math.max(0, gateway.questProgress(agent, STORMING_THE_CASTLE_QUEST_ID, mob)));
        }
        return new Progress(gateway.questStatus(agent, BREWING_STORM_QUEST_ID),
                gateway.questStatus(agent, STEMMING_THE_TIDE_QUEST_ID),
                gateway.questStatus(agent, STORMING_THE_CASTLE_QUEST_ID),
                gateway.itemCount(agent, STORMBREAKER_BADGE_ITEM_ID), kills,
                gateway.itemCount(agent, KEYSTONE_ITEM_ID) > 0);
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

    private static String who(Step step) {
        return step.npcId == JACK_NPC_ID ? "Jack" : "Lukan";
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

    /** True while {@code personaId} is out for its keystone (not yet on its way back). */
    public boolean underway(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        return errand != null && errand.phase != Phase.HOMEWARD;
    }

    /** What it is doing for its keystone, as a player would say it, while {@link #underway}; else null. */
    public String doing(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        if (errand == null || errand.phase == Phase.HOMEWARD) {
            return null;
        }
        return switch (errand.step) {
            case HUNT_BADGES -> "hunting Stormbreakers for Lukan";
            case HUNT_CASTLE -> "hunting in the Cavern of Fortitude for Lukan";
            case START_STEMMING_THE_TIDE -> "gone to see Jack in the Cavern of Pain";
            default -> "gone to see Lukan on Phantom Road";
        };
    }
}
