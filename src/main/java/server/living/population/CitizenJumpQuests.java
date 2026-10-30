package server.living.population;

import client.Character;
import client.QuestStatus;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.behavior.AgentJumpNoise;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.population.CitizenSession.Activity;
import server.living.population.JumpQuestCourses.Course;
import server.living.population.JumpQuestCourses.Leg;
import server.living.population.JumpQuestCourses.Stages;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Jump quests (docs/BOT_CONCEPT_PLAN.md F5b, {@code JQ_ENABLED}). A cohort weight JQ sends a Victoria citizen up
 * Pet-Walking Road (for fun: Frod pays only pet owners) or, from Lv25 with Shane's fee, through the Forest of Patience,
 * whose pile gives the prize and Sabitrama's quest item (Sabitrama in Sleepywood gives and takes quests 2050 and 2051:
 * the citizen walks up to him first, and takes the item back to him after). It climbs stage by stage (JumpQuestCourses)
 * with its jumps' noise on (AgentJumpNoise): its skill is how often it clears a stage, 30-70% by citizen and a little
 * better for every stage it clears this session; a missed jump drops it to a lower foothold and it climbs again. After
 * JQ_FALLS_MAX falls or JQ_ATTEMPT_MAX_MS it gives up and leaves the stock way (the Forest's exit NPC; the road's own
 * paths). It never idles in the quest longer than {@link #STILL_MAX_MS}. A fall below the map with a player watching logs
 * it off (a disconnect) instead of the recovery teleport. Ticked on the party quests' 10 s lane; no timer of its own.
 */
public final class CitizenJumpQuests {
    private static final Logger log = LoggerFactory.getLogger(CitizenJumpQuests.class);
    /** At a checkpoint, an NPC or a portal this close. */
    static final int REACH_PX = 45;
    /** Close enough to an NPC to talk (its spot may be on a ledge above the path). */
    static final int TALK_PX = 90;
    /** Standing at a top this long before it goes on: a breath, a look around. */
    static final long TOP_STAND_MIN_MS = 3_000L;
    static final int TOP_STAND_SPAN_MS = 5_000;
    /** The user's no-long-idling rule: standing still this long in a jump quest ends it. */
    static final long STILL_MAX_MS = 120_000L;
    /** A walk to an NPC, a portal or the way out gives up (or goes through anyway) after this long. */
    static final long WALK_MAX_MS = 180_000L;
    /** It stands with Sabitrama this long before he hands the errand over or takes the item. */
    static final long TALK_MS = 3_000L;
    /** Moved less than this between ticks: standing still. */
    static final int STILL_PX = 8;
    /** The noise's skill never reaches more than this, however much it practises. */
    static final double SKILL_CEILING = 0.85d;
    /** Shane's warp lands on the course; checked this many ticks before the gate counts as refused. */
    static final int GATE_TRIES = 2;
    /** Clicks or steps through its way out before it takes another way (the exit NPC), or logs off. */
    static final int EXIT_TRIES = 3;
    private static final int RECENT = 10;

    /** What it is doing in an attempt. */
    enum Phase {
        /** Walking up to Sabitrama in Sleepywood for the course's quest. */
        QUEST_START,
        /** Taking the pile's item back to Sabitrama. */
        QUEST_TURN_IN,
        /** Walking up to Shane in Ellinia, then his warp. */
        GATE,
        CLIMB,
        /** Standing at a leg's top a moment. */
        TOP,
        /** Walking to the leg's portal or pile and through it. */
        EXIT,
        /** Walking down to the exit NPC, or away. */
        GIVE_UP
    }

    /** How the engine is reached; tests bring their own. */
    interface Engine {
        void moveTo(CitizenSession session, Point spot, boolean precise);

        /** True while its last move has not arrived (or been dropped by navigation). */
        boolean moving(CitizenSession session);

        /** Clicks the NPC as a player does: its script runs (it may warp). */
        void npc(CitizenSession session, int npcId);

        void startQuest(CitizenSession session, int questId, int npcId);

        void completeQuest(CitizenSession session, int questId, int npcId);

        /** Through the named portal of its map (the next map, or up the same one). */
        void portal(CitizenSession session, String portal);

        Point npcSpot(CitizenSession session, int npcId);

        Point portalSpot(CitizenSession session, String portal);

        Stages stages(Course course, int leg, CitizenSession session);

        void installNoise(CitizenSession session, AgentJumpNoise.Plan plan, long seed);

        void clearNoise(CitizenSession session);

        AgentJumpNoise.Counts noise(CitizenSession session);
    }

    /** What its JQ plan chose before it travelled: the course and where it starts. */
    record Planned(Course course, Phase start) { }

    /** One citizen's attempt. */
    static final class Attempt {
        final CitizenSession session;
        final Course course;
        Phase phase;
        long phaseAtMs;
        final long startedAtMs;
        int leg = -1;
        Stages stages;
        int stage;
        int stageMisses;
        int falls;
        int seenMisses;
        double skill;
        long standUntilMs;
        Point lastPos;
        long stillSinceMs;
        int gateTries;
        boolean toldGiveUp;
        boolean exitAsked;
        int exitTries;
        int stagesClimbed;
        int stagesClean;

        Attempt(CitizenSession session, Course course, Phase phase, long nowMs, double skill) {
            this.session = session;
            this.course = course;
            this.phase = phase;
            this.phaseAtMs = nowMs;
            this.startedAtMs = nowMs;
            this.stillSinceMs = nowMs;
            this.skill = skill;
        }

        void to(Phase next, long nowMs) {
            phase = next;
            phaseAtMs = nowMs;
            exitAsked = false;
            exitTries = 0;
        }
    }

    private final Population population;
    private final Engine engine;
    private final Random random;
    private final Map<Integer, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<Integer, Planned> planned = new ConcurrentHashMap<>();
    private final Map<Integer, Long> coolUntil = new ConcurrentHashMap<>();
    /** Citizens to log off: their fall went below the map in front of a player (AgentJumpNoise.Plan.belowMap). */
    private final Queue<Integer> disconnects = new ConcurrentLinkedQueue<>();
    /** Course key -> {attempts, clears, give-ups, falls, stages climbed, stages climbed with no fall}. */
    private final Map<String, int[]> tally = new ConcurrentHashMap<>();
    private volatile int belowMapLogoffs;
    private volatile int belowMapTeleports;
    private volatile int stuckLogoffs;
    private final Deque<String> recent = new ArrayDeque<>();

    CitizenJumpQuests(Population population) {
        this(population, new LiveEngine(), new Random(population.config().SEED ^ 0x7a9b31L));
    }

    CitizenJumpQuests(Population population, Engine engine, Random random) {
        this.population = population;
        this.engine = engine;
        this.random = random;
    }

    private PopulationConfig config() {
        return population.config();
    }

    /** The courses {@code JQ_COURSES} names, while {@code JQ_ENABLED}. */
    List<Course> courses() {
        if (!config().JQ_ENABLED || config().JQ_COURSES == null) {
            return List.of();
        }
        Set<String> keys = Arrays.stream(config().JQ_COURSES.split(",")).map(key -> key.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return JumpQuestCourses.SHIPPED.stream().filter(course -> keys.contains(course.key())).toList();
    }

    // ---- planning (PresenceScheduler) -------------------------------------------------------

    /** Whether a JQ plan may be rolled for it: the switch, a Victoria citizen, not just back from one, a course fits. */
    boolean plays(CitizenSession session, int level, long nowMs) {
        Long until = coolUntil.get(session.characterId);
        return "victoria".equals(session.citizen.homeRegion()) && (until == null || nowMs >= until)
                && !fitting(session, level).isEmpty();
    }

    /** The courses its level and purse fit: the road at any level, the Forest from its band with the fee and a reserve. */
    private List<Course> fitting(CitizenSession session, int level) {
        Character agent = session.virtual ? null : session.agent();
        List<Course> out = new ArrayList<>();
        for (Course course : courses()) {
            boolean purse = course.fee() == 0 || agent == null || agent.getMeso() >= course.fee() + config().JQ_FEE_RESERVE;
            boolean questFits = agent != null && course.questId() > 0
                    && agent.getQuestStatus(course.questId()) == QuestStatus.Status.STARTED.getId();
            if (purse && (course.fits(level) || questFits)) {
                out.add(course);
            }
        }
        return out;
    }

    /**
     * Where its JQ plan starts: Sleepywood when it carries a pile's item for Sabitrama or its course's quest can start,
     * else the course's own map (the road) or Ellinia (Shane's gate). 0 when no course fits after all.
     */
    int mapFor(CitizenSession session, int level) {
        Character agent = session.virtual ? null : session.agent();
        for (Course course : courses()) {
            if (agent != null && course.questItemId() > 0 && agent.haveItem(course.questItemId())
                    && agent.getQuestStatus(course.questId()) == QuestStatus.Status.STARTED.getId()) {
                planned.put(session.characterId, new Planned(course, Phase.QUEST_TURN_IN));
                return JumpQuestCourses.SLEEPYWOOD;
            }
        }
        List<Course> fitting = fitting(session, level);
        if (fitting.isEmpty()) {
            planned.remove(session.characterId);
            return 0;
        }
        List<Course> forest = fitting.stream().filter(course -> course.gateNpcId() > 0).toList();
        Course course = !forest.isEmpty() && (fitting.size() == forest.size() || random.nextInt(100) < 70)
                ? forest.get(random.nextInt(forest.size())) : fitting.get(0);
        if (agent != null && questStartable(agent, course)) {
            planned.put(session.characterId, new Planned(course, Phase.QUEST_START));
            return JumpQuestCourses.SLEEPYWOOD;
        }
        planned.put(session.characterId, new Planned(course, course.gateNpcId() > 0 ? Phase.GATE : Phase.CLIMB));
        return course.entryMapId();
    }

    /** Sabitrama gives the course's quest: its level, not taken or done yet, 2051 only after 2050. */
    private static boolean questStartable(Character agent, Course course) {
        if (course.questId() <= 0 || agent.getLevel() < course.questLevel()
                || agent.getQuestStatus(course.questId()) != QuestStatus.Status.NOT_STARTED.getId()) {
            return false;
        }
        return course.questId() != JumpQuestCourses.FOP_B.questId()
                || agent.getQuestStatus(JumpQuestCourses.FOP_A.questId()) == QuestStatus.Status.COMPLETED.getId();
    }

    /** Its JQ session starts on {@code map}; false when there is nothing to do here (an ordinary visit instead). */
    boolean arrive(CitizenSession session, MapleMap map, long nowMs) {
        Planned plan = planned.remove(session.characterId);
        Character agent = session.agent();
        if (plan == null || agent == null || map == null || courses().isEmpty()) {
            return false;
        }
        Phase phase = switch (plan.start()) {
            case QUEST_START, QUEST_TURN_IN -> map.getId() == JumpQuestCourses.SLEEPYWOOD ? plan.start() : null;
            case GATE -> map.getId() == JumpQuestCourses.ELLINIA ? Phase.GATE
                    : plan.course().onCourse(map.getId()) ? Phase.CLIMB : null; // already in (a GM sent it straight there)
            default -> plan.course().onCourse(map.getId()) ? Phase.CLIMB : null;
        };
        if (phase == null) {
            return false;
        }
        Attempt attempt = new Attempt(session, plan.course(), phase, nowMs, skillOf(session));
        attempts.put(session.characterId, attempt);
        if (phase == Phase.CLIMB) {
            tally(plan.course())[0]++;
        }
        log.info("citizen {} starts {} ({})", session.name(), plan.course().spoken(), phase);
        step(attempt, nowMs);
        return true;
    }

    /** True while it is on an attempt: presence leaves its session alone, as for a party quest. */
    public boolean holds(CitizenSession session) {
        return session != null && attempts.containsKey(session.characterId);
    }

    /** Its skill: how often it clears a stage, stable for the citizen within JQ_STAGE_SUCCESS_MIN..MAX. */
    double skillOf(CitizenSession session) {
        long mixed = session.characterId * 0x9E3779B97F4A7C15L;
        double unit = ((mixed ^ (mixed >>> 31)) >>> 11) * 0x1.0p-53;
        return config().JQ_STAGE_SUCCESS_MIN + (config().JQ_STAGE_SUCCESS_MAX - config().JQ_STAGE_SUCCESS_MIN) * unit;
    }

    // ---- the attempt ----------------------------------------------------------------------

    public void tick() {
        tick(System.currentTimeMillis());
    }

    synchronized void tick(long nowMs) {
        try {
            Integer gone;
            while ((gone = disconnects.poll()) != null) {
                Attempt attempt = attempts.remove(gone);
                if (attempt != null) {
                    engine.clearNoise(attempt.session);
                    population.presence().logout(attempt.session, "disconnected in a jump quest");
                }
            }
            for (Attempt attempt : new ArrayList<>(attempts.values())) {
                step(attempt, nowMs);
            }
        } catch (RuntimeException failure) {
            log.warn("citizen jump quest tick failed", failure);
        }
    }

    private void step(Attempt attempt, long nowMs) {
        CitizenSession session = attempt.session;
        Character agent = session.agent();
        if (agent == null || population.presence().session(session.characterId) == null) {
            attempts.remove(session.characterId);
            engine.clearNoise(session);
            return;
        }
        session.mapId = agent.getMapId();
        switch (attempt.phase) {
            case QUEST_START, QUEST_TURN_IN -> sabitrama(attempt, agent, nowMs);
            case GATE -> gate(attempt, agent, nowMs);
            case CLIMB -> climb(attempt, agent, nowMs);
            case TOP -> top(attempt, agent, nowMs);
            case EXIT -> exit(attempt, agent, nowMs);
            case GIVE_UP -> giveUp(attempt, agent, nowMs);
        }
    }

    /** Sleepywood: up to Sabitrama, a moment with him, then the errand starts (on to Ellinia) or the item goes back. */
    private void sabitrama(Attempt attempt, Character agent, long nowMs) {
        CitizenSession session = attempt.session;
        Course course = attempt.course;
        if (agent.getMapId() != JumpQuestCourses.SLEEPYWOOD) {
            finish(attempt, "moved away", nowMs);
            return;
        }
        Point spot = engine.npcSpot(session, JumpQuestCourses.SABITRAMA);
        Point at = agent.getPosition();
        boolean there = spot != null && at != null && at.distance(spot) <= TALK_PX;
        if (!there) {
            if (nowMs - attempt.phaseAtMs >= WALK_MAX_MS || stillTooLong(attempt, agent, nowMs)) {
                finish(attempt, "never got to Sabitrama", nowMs);
            } else if (spot != null && !engine.moving(session)) {
                engine.moveTo(session, spot, false);
            }
            return;
        }
        if (attempt.standUntilMs == 0L) {
            attempt.standUntilMs = nowMs + TALK_MS;
            return;
        }
        if (nowMs < attempt.standUntilMs) {
            return;
        }
        if (attempt.phase == Phase.QUEST_TURN_IN) {
            engine.completeQuest(session, course.questId(), JumpQuestCourses.SABITRAMA);
            session.journal.add("quest", "brought Sabitrama what he asked for from the " + course.spoken());
            log.info("citizen {} turns in quest {} at Sabitrama", session.name(), course.questId());
            finish(attempt, "turned in", nowMs);
            return;
        }
        engine.startQuest(session, course.questId(), JumpQuestCourses.SABITRAMA);
        session.journal.add("quest", "took Sabitrama's errand into the " + course.spoken());
        attempts.remove(session.characterId);
        planned.put(session.characterId, new Planned(course, Phase.GATE));
        population.presence().travel(session, Activity.JQ, JumpQuestCourses.ELLINIA, nowMs);
    }

    /** Ellinia: up to Shane, his fee and his warp onto the course its level (or quest) takes it to. */
    private void gate(Attempt attempt, Character agent, long nowMs) {
        CitizenSession session = attempt.session;
        Course course = attempt.course;
        if (onCourseMap(agent.getMapId())) {
            Course landed = JumpQuestCourses.onMap(agent.getMapId());
            if (landed != course && landed != null) {
                attempts.put(session.characterId, attempt = new Attempt(session, landed, Phase.CLIMB, nowMs, attempt.skill));
            }
            tally(attempt.course)[0]++;
            attempt.to(Phase.CLIMB, nowMs);
            climb(attempt, agent, nowMs);
            return;
        }
        if (agent.getMapId() != JumpQuestCourses.ELLINIA || agent.getMeso() < course.fee()) {
            finish(attempt, "no way in", nowMs);
            return;
        }
        Point spot = engine.npcSpot(session, course.gateNpcId());
        Point at = agent.getPosition();
        if (spot == null || at == null || at.distance(spot) > TALK_PX) {
            if (nowMs - attempt.phaseAtMs >= WALK_MAX_MS || stillTooLong(attempt, agent, nowMs)) {
                finish(attempt, "never got to Shane", nowMs);
            } else if (spot != null && !engine.moving(session)) {
                engine.moveTo(session, spot, false);
            }
            return;
        }
        if (attempt.gateTries++ >= GATE_TRIES) {
            finish(attempt, "Shane did not let it in", nowMs);
            return;
        }
        engine.npc(session, course.gateNpcId()); // the fee, then the warp: the next tick finds it on the course
    }

    private static boolean onCourseMap(int mapId) {
        return JumpQuestCourses.onMap(mapId) != null;
    }

    private void climb(Attempt attempt, Character agent, long nowMs) {
        CitizenSession session = attempt.session;
        Course course = attempt.course;
        int legOnMap = course.legOn(agent.getMapId(), Math.max(0, attempt.leg));
        if (legOnMap < 0) {
            finish(attempt, attempt.stagesClimbed > 0 ? "left the course" : "not on the course", nowMs);
            return;
        }
        if (legOnMap != attempt.leg) {
            beginLeg(attempt, legOnMap, nowMs);
        }
        countFalls(attempt, nowMs);
        if (attempt.falls >= config().JQ_FALLS_MAX || nowMs - attempt.startedAtMs >= config().JQ_ATTEMPT_MAX_MS
                || stillTooLong(attempt, agent, nowMs)) {
            toGiveUp(attempt, nowMs);
            return;
        }
        Point at = agent.getPosition();
        Point checkpoint = attempt.stages.checkpoints().get(attempt.stage);
        if (at != null && Math.abs(at.x - checkpoint.x) <= REACH_PX && Math.abs(at.y - checkpoint.y) <= 20) {
            stageCleared(attempt, nowMs);
            if (attempt.stage >= attempt.stages.count()) {
                attempt.standUntilMs = nowMs + TOP_STAND_MIN_MS + random.nextInt(TOP_STAND_SPAN_MS);
                attempt.to(Phase.TOP, nowMs);
                engine.clearNoise(session);
                return;
            }
            checkpoint = attempt.stages.checkpoints().get(attempt.stage);
            engine.moveTo(session, checkpoint, true);
            return;
        }
        if (!engine.moving(session)) {
            engine.moveTo(session, checkpoint, true); // navigation dropped it (a fall, a re-plan): the same checkpoint again
        }
    }

    private void beginLeg(Attempt attempt, int leg, long nowMs) {
        CitizenSession session = attempt.session;
        boolean first = attempt.leg < 0;
        attempt.leg = leg;
        attempt.stages = engine.stages(attempt.course, leg, session);
        attempt.stage = 0;
        attempt.stageMisses = 0;
        attempt.seenMisses = 0;
        installNoise(attempt);
        engine.moveTo(session, attempt.stages.checkpoints().get(0), true);
        if (first) {
            say("jq.start", session);
        }
    }

    /** Its noise for this leg: a press spread per stage that clears the stage with its skill's chance. */
    private void installNoise(Attempt attempt) {
        Stages stages = attempt.stages;
        double[] sigmas = new double[stages.count()];
        for (int s = 0; s < sigmas.length; s++) {
            JumpWindows.Jump[] jumps = stages.jumps().get(s);
            sigmas[s] = JumpWindows.sigmaFor(jumps, JumpWindows.targetFor(attempt.skill, jumps.length));
        }
        int citizenId = attempt.session.characterId;
        engine.installNoise(attempt.session, new AgentJumpNoise.Plan() {
            @Override
            public double sigmaAt(int launchY) {
                return sigmas[stages.stageAt(launchY)];
            }

            @Override
            public double sigmaAt(Point launch) {
                return sigmas[stages.stageAt(launch)];
            }

            @Override
            public boolean belowMap(Character agent) {
                return fellBelowMap(citizenId, agent);
            }
        }, citizenId * 31L + attempt.leg + attempt.falls);
    }

    /**
     * Its fall went below the map although every draw was probed: with a player on the map it logs off (a disconnect
     * is a normal sight; a teleport is not), else the recovery teleport runs unseen. Both counted (/runtime/citizens).
     */
    boolean fellBelowMap(int citizenId, Character agent) {
        boolean watched = agent != null && agent.getMap() != null && Population.humansIn(agent.getMap()) > 0;
        if (watched) {
            belowMapLogoffs++;
            disconnects.add(citizenId);
        } else {
            belowMapTeleports++;
        }
        log.warn("citizen {} fell below map {} in a jump quest: {}", agent == null ? citizenId : agent.getName(),
                agent == null ? 0 : agent.getMapId(), watched ? "logs off (a player watches)" : "recovery teleport, unseen");
        return watched;
    }

    private void countFalls(Attempt attempt, long nowMs) {
        AgentJumpNoise.Counts counts = engine.noise(attempt.session);
        int fresh = counts.misses() - attempt.seenMisses;
        if (fresh <= 0) {
            return;
        }
        attempt.seenMisses = counts.misses();
        attempt.falls += fresh;
        attempt.stageMisses += fresh;
        tally(attempt.course)[3] += fresh;
        if (random.nextInt(100) < 45) {
            say("jq.fall", attempt.session);
        }
    }

    private void stageCleared(Attempt attempt, long nowMs) {
        int[] counts = tally(attempt.course);
        counts[4]++;
        attempt.stagesClimbed++;
        if (attempt.stageMisses == 0 && attempt.stages.jumps().get(attempt.stage).length > 0) {
            counts[5]++;
            attempt.stagesClean++;
            attempt.skill = Math.min(SKILL_CEILING, attempt.skill + config().JQ_PRACTICE_STEP); // practice
        }
        attempt.stage++;
        attempt.stageMisses = 0;
        if (attempt.stage < attempt.stages.count()) {
            installNoise(attempt);
            attempt.seenMisses = 0;
        }
    }

    /** Stood within STILL_PX for STILL_MAX_MS: never a long idle in a jump quest (the user's rule). */
    private static boolean stillTooLong(Attempt attempt, Character agent, long nowMs) {
        Point at = agent.getPosition();
        if (at == null) {
            return false;
        }
        if (attempt.lastPos == null || attempt.lastPos.distance(at) > STILL_PX) {
            attempt.lastPos = new Point(at);
            attempt.stillSinceMs = nowMs;
            return false;
        }
        return nowMs - attempt.stillSinceMs >= STILL_MAX_MS;
    }

    /** A leg's top: a moment there, then on (the road's end: a cheer and down by its portal). */
    private void top(Attempt attempt, Character agent, long nowMs) {
        if (nowMs < attempt.standUntilMs) {
            return;
        }
        Leg leg = attempt.course.legs().get(attempt.leg);
        boolean last = attempt.leg == attempt.course.legs().size() - 1;
        if (last) {
            say("jq.clear", attempt.session);
            int[] counts = tally(attempt.course);
            counts[1]++;
            attempt.session.journal.add("jq", "cleared " + attempt.course.spoken() + " after "
                    + (attempt.falls == 0 ? "no falls" : attempt.falls + (attempt.falls == 1 ? " fall" : " falls")));
            remember(attempt.session.name() + " cleared " + attempt.course.spoken() + " (" + attempt.falls + " falls, "
                    + (nowMs - attempt.startedAtMs) / 60_000L + " min)");
            log.info("citizen {} cleared {} with {} falls in {} s", attempt.session.name(), attempt.course.spoken(),
                    attempt.falls, (nowMs - attempt.startedAtMs) / 1000L);
        }
        if (leg.exit() == JumpQuestCourses.Exit.STAND && leg.portal() == null) {
            finish(attempt, "cleared", nowMs);
            return;
        }
        attempt.to(Phase.EXIT, nowMs);
        exit(attempt, agent, nowMs);
    }

    /** To the leg's portal (next map, up the same map, or the road's way down) or the pile, and through it. */
    private void exit(Attempt attempt, Character agent, long nowMs) {
        CitizenSession session = attempt.session;
        Leg leg = attempt.course.legs().get(attempt.leg);
        boolean last = attempt.leg == attempt.course.legs().size() - 1;
        if (attempt.exitAsked) {
            int legNow = attempt.course.legOn(agent.getMapId(), attempt.leg + 1);
            if (leg.exit() == JumpQuestCourses.Exit.PORTAL && legNow > attempt.leg && movedOn(attempt, agent, legNow)) {
                attempt.to(Phase.CLIMB, nowMs);
                climb(attempt, agent, nowMs);
                return;
            }
            if (last && (agent.getMapId() != leg.mapId() || leg.exit() == JumpQuestCourses.Exit.STAND)) {
                afterClear(attempt, agent, nowMs);
                return;
            }
            if (nowMs - attempt.phaseAtMs < 15_000L) {
                return; // its click or step through is still being run
            }
            attempt.exitAsked = false; // nothing happened: try again
            if (attempt.exitTries >= EXIT_TRIES) {
                if (attempt.course.exitNpcId() == 0) {
                    finish(attempt, "its way out did not open", nowMs);
                } else {
                    attempt.to(Phase.GIVE_UP, nowMs); // down to the exit NPC instead, no give-up line: it did climb
                    engine.clearNoise(attempt.session);
                }
                return;
            }
        }
        Point spot = leg.exit() == JumpQuestCourses.Exit.NPC ? engine.npcSpot(session, leg.npcId())
                : engine.portalSpot(session, leg.portal());
        Point at = agent.getPosition();
        boolean there = spot != null && at != null
                && at.distance(spot) <= (leg.exit() == JumpQuestCourses.Exit.NPC ? TALK_PX : REACH_PX);
        if (!there && nowMs - attempt.phaseAtMs < WALK_MAX_MS && !stillTooLong(attempt, agent, nowMs)) {
            if (spot != null && !engine.moving(session)) {
                engine.moveTo(session, spot, true);
            }
            return;
        }
        attempt.exitAsked = true;
        attempt.exitTries++;
        attempt.phaseAtMs = nowMs;
        if (leg.exit() == JumpQuestCourses.Exit.NPC) {
            engine.npc(session, leg.npcId()); // the pile: the prize, its quest item, and the warp to Ellinia
        } else {
            engine.portal(session, leg.portal());
        }
    }

    /**
     * The course is behind it: with the pile's item for a started quest it goes straight on to Sabitrama in Sleepywood
     * (no cooldown in between), else the attempt is over.
     */
    private void afterClear(Attempt attempt, Character agent, long nowMs) {
        Course course = attempt.course;
        CitizenSession session = attempt.session;
        if (course.questItemId() > 0 && agent.haveItem(course.questItemId())
                && agent.getQuestStatus(course.questId()) == QuestStatus.Status.STARTED.getId()) {
            attempts.remove(session.characterId);
            engine.clearNoise(session);
            planned.put(session.characterId, new Planned(course, Phase.QUEST_TURN_IN));
            population.presence().travel(session, Activity.JQ, JumpQuestCourses.SLEEPYWOOD, nowMs);
            return;
        }
        finish(attempt, "cleared", nowMs);
    }

    /** After a portal: on the next leg's map, or for a portal up the same map, near the next leg's start. */
    private static boolean movedOn(Attempt attempt, Character agent, int legNow) {
        Leg next = attempt.course.legs().get(legNow);
        Leg current = attempt.course.legs().get(attempt.leg);
        if (next.mapId() != current.mapId()) {
            return agent.getMapId() == next.mapId();
        }
        Point at = agent.getPosition();
        return at != null && at.distance(next.start()) <= 150;
    }

    private void toGiveUp(Attempt attempt, long nowMs) {
        attempt.to(Phase.GIVE_UP, nowMs);
        engine.clearNoise(attempt.session);
        tally(attempt.course)[2]++;
        say("jq.giveup", attempt.session);
        attempt.toldGiveUp = true;
        attempt.session.journal.add("jq", "gave up on " + attempt.course.spoken() + " after " + attempt.falls + " falls");
        remember(attempt.session.name() + " gave up on " + attempt.course.spoken() + " (" + attempt.falls + " falls)");
        log.info("citizen {} gives up on {} after {} falls", attempt.session.name(), attempt.course.spoken(), attempt.falls);
        if (attempt.course.exitNpcId() == 0) {
            finish(attempt, "gave up", nowMs); // the road: it just walks off with its next plan
        }
    }

    /** The Forest: down to the exit NPC at this map's foot and back to Ellinia (at most WALK_MAX_MS walking). */
    private void giveUp(Attempt attempt, Character agent, long nowMs) {
        CitizenSession session = attempt.session;
        if (!onCourseMap(agent.getMapId())) {
            finish(attempt, "gave up", nowMs);
            return;
        }
        if (attempt.exitAsked) {
            if (nowMs - attempt.phaseAtMs < 15_000L) {
                return;
            }
            attempt.exitAsked = false;
            if (attempt.exitTries >= EXIT_TRIES) {
                stuckLogoffs++; // the exit NPC never sent it out: a disconnect, never a teleport or a long idle
                attempts.remove(session.characterId);
                engine.clearNoise(session);
                log.warn("citizen {} could not leave map {} by its exit NPC; logs off", session.name(), agent.getMapId());
                population.presence().logout(session, "stuck in a jump quest");
                return;
            }
        }
        Point spot = engine.npcSpot(session, attempt.course.exitNpcId());
        Point at = agent.getPosition();
        boolean there = spot != null && at != null && at.distance(spot) <= TALK_PX;
        boolean late = nowMs - attempt.phaseAtMs >= STILL_MAX_MS || stillTooLong(attempt, agent, nowMs);
        if (!there && !late) {
            if (spot != null && !engine.moving(session)) {
                engine.moveTo(session, spot, false);
            }
            return;
        }
        attempt.exitAsked = true;
        attempt.exitTries++;
        attempt.phaseAtMs = nowMs;
        engine.npc(session, attempt.course.exitNpcId()); // "return to Ellinia?" yes
    }

    private void finish(Attempt attempt, String outcome, long nowMs) {
        CitizenSession session = attempt.session;
        attempts.remove(session.characterId);
        engine.clearNoise(session);
        coolUntil.put(session.characterId, nowMs + config().JQ_COOLDOWN_MS);
        Character agent = session.agent();
        if (agent != null) {
            session.mapId = agent.getMapId();
        }
        log.debug("citizen {} jump quest over: {}", session.name(), outcome);
        population.presence().replan(session, nowMs);
    }

    // ---- ops -----------------------------------------------------------------------------

    /** GM aid (!citizens jq send): a loaded citizen goes for {@code key} (or a course that fits) now. */
    public String send(CitizenSession session, String key, long nowMs) {
        Character agent = session == null || session.virtual ? null : session.agent();
        if (agent == null) {
            return "not loaded";
        }
        if (holds(session)) {
            return session.name() + " is on a jump quest";
        }
        coolUntil.remove(session.characterId);
        int map = mapFor(session, agent.getLevel());
        Planned plan = planned.get(session.characterId);
        if (key != null && !key.isBlank()) {
            Course course = JumpQuestCourses.byKey(key);
            if (course == null || !courses().contains(course)) {
                return "no course " + key + " (JQ_COURSES: " + config().JQ_COURSES + ")";
            }
            boolean questFirst = questStartable(agent, course);
            plan = new Planned(course, questFirst ? Phase.QUEST_START : course.gateNpcId() > 0 ? Phase.GATE : Phase.CLIMB);
            planned.put(session.characterId, plan);
            map = questFirst ? JumpQuestCourses.SLEEPYWOOD : course.entryMapId();
        }
        if (plan == null || map <= 0) {
            return "no course fits " + session.name() + " (lv" + agent.getLevel() + ", " + agent.getMeso() + " mesos)";
        }
        population.presence().travel(session, Activity.JQ, map, nowMs);
        return session.name() + " goes for " + plan.course().spoken() + " (" + plan.start() + ") at map " + map;
    }

    /** One line for !citizens jq and /runtime/citizens. */
    public String status() {
        StringBuilder out = new StringBuilder("jump quests: ").append(attempts.size()).append(" on");
        tally.forEach((key, c) -> out.append("; ").append(key).append(' ').append(c[0]).append(" tries ").append(c[1])
                .append(" cleared ").append(c[2]).append(" gave up ").append(c[3]).append(" falls, stages ")
                .append(c[5]).append('/').append(c[4]).append(" clean"));
        out.append("; below map: ").append(belowMapLogoffs).append(" logged off, ").append(belowMapTeleports)
                .append(" teleported unseen; stuck exits logged off: ").append(stuckLogoffs);
        return out.toString();
    }

    public List<String> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    int belowMapLogoffs() {
        return belowMapLogoffs;
    }

    int belowMapTeleports() {
        return belowMapTeleports;
    }

    private void remember(String line) {
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > RECENT) {
                recent.removeFirst();
            }
        }
    }

    private int[] tally(Course course) {
        return tally.computeIfAbsent(course.key(), key -> new int[6]);
    }

    private void say(String trigger, CitizenSession session) {
        population.chatter().speakTo(ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session));
    }

    // ---- the live engine ------------------------------------------------------------------

    /** The citizen's moves and clicks through the Agent layer, each on its own Agent tick when writes are routed there. */
    static final class LiveEngine implements Engine {
        @Override
        public void moveTo(CitizenSession session, Point spot, boolean precise) {
            if (session.entry != null && spot != null) {
                AgentMovementCommandRuntime.moveTo(session.entry, spot, precise);
            }
        }

        @Override
        public boolean moving(CitizenSession session) {
            return session.entry != null && AgentMoveTargetStateRuntime.hasMoveTarget(session.entry);
        }

        @Override
        public void npc(CitizenSession session, int npcId) {
            runAs(session, "jq npc", agent -> AgentPrimitiveCapabilityGatewayRuntime.gateway().runNpcScript(agent, npcId));
        }

        @Override
        public void startQuest(CitizenSession session, int questId, int npcId) {
            runAs(session, "jq quest", agent -> AgentPrimitiveCapabilityGatewayRuntime.gateway().startQuest(agent, questId, npcId));
        }

        @Override
        public void completeQuest(CitizenSession session, int questId, int npcId) {
            runAs(session, "jq quest", agent -> AgentPrimitiveCapabilityGatewayRuntime.gateway().completeQuest(agent, questId, npcId));
        }

        @Override
        public void portal(CitizenSession session, String portal) {
            runAs(session, "jq portal", agent -> {
                Portal door = agent.getMap() == null ? null : agent.getMap().getPortal(portal);
                if (door != null) {
                    AgentPrimitiveCapabilityGatewayRuntime.gateway().enterPortal(agent, door.getId());
                }
            });
        }

        @Override
        public Point npcSpot(CitizenSession session, int npcId) {
            Character agent = session.agent();
            NPC npc = agent == null || agent.getMap() == null ? null : agent.getMap().getNPCById(npcId);
            return npc == null ? null : new Point(npc.getPosition());
        }

        @Override
        public Point portalSpot(CitizenSession session, String portal) {
            Character agent = session.agent();
            Portal door = agent == null || agent.getMap() == null ? null : agent.getMap().getPortal(portal);
            return door == null ? null : new Point(door.getPosition());
        }

        @Override
        public Stages stages(Course course, int leg, CitizenSession session) {
            Character agent = session.agent();
            return JumpQuestCourses.stages(course, leg, agent == null ? null : agent.getMap());
        }

        @Override
        public void installNoise(CitizenSession session, AgentJumpNoise.Plan plan, long seed) {
            AgentJumpNoise.install(session.entry, plan, seed);
        }

        @Override
        public void clearNoise(CitizenSession session) {
            AgentJumpNoise.clear(session.entry);
        }

        @Override
        public AgentJumpNoise.Counts noise(CitizenSession session) {
            return AgentJumpNoise.counts(session.entry);
        }

        private static void runAs(CitizenSession session, String what, java.util.function.Consumer<Character> step) {
            AgentRuntimeEntry entry = session.entry;
            Character agent = session.agent();
            if (entry == null || agent == null) {
                return;
            }
            try {
                if (AgentForeignWriteRuntime.foreign(entry)) {
                    AgentForeignWriteRuntime.defer(entry, what, owner -> step.accept(agent));
                } else {
                    step.accept(agent);
                }
            } catch (RuntimeException failure) {
                log.debug("{} for citizen {} failed", what, session.name(), failure);
            }
        }
    }
}
