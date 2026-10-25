package server.living.population;

import client.Character;
import client.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.build.AgentJobPath;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.dialogue.AgentDialogueReportFormatter;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Career;
import server.living.resident.ResidentShaper;
import server.living.roster.JobInstructors;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * A citizen's third and fourth job, taken where a player takes them (2026-10-06). Once its life has reached the level,
 * the next session it logs in for (or its next re-plan, when it reached it fighting) is on its instructor's map: El
 * Nath's Chief's Residence, Leafre's Forest of the Priest (JobInstructors). It walks up to them, stands a moment,
 * advances there (the stock job change; banked SP goes into the new job's skills), says so and goes about its day with
 * the presence scheduler's usual plan. Logged out or moved on the way: no job yet, and it goes again with its next plan.
 * Refused by its instructor: again next session. A backlog citizen's overdue job came off-screen instead
 * (ProgressionLedger.catchUp); it mentions it.
 */
final class CitizenJobErrands {
    private static final Logger log = LoggerFactory.getLogger(CitizenJobErrands.class);
    /** Close enough to its instructor to talk: the NPC's spot is on its own platform. */
    private static final int REACH_PX = 60;
    /** How often it looks whether it has reached its instructor. */
    private static final long CHECK_MS = 1_000L;
    /** A JOB activity older than its walk and talk by this much is over, whatever held it up (the scheduler re-plans). */
    static final long OVERDUE_SLACK_MS = 120_000L;
    /** When an off-screen advancement is mentioned after login: a moment to settle in first. */
    private static final long MENTION_MIN_MS = 20_000L;
    private static final long MENTION_SPAN_MS = 40_000L;

    private final Population population;
    private final PresenceScheduler presence;
    private final Random random = new Random();
    LongSupplier clock = System::currentTimeMillis;
    /** Runs a task after a delay (ms); a test runs them by hand. */
    BiConsumer<Runnable, Long> later = (task, delayMs) ->
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, delayMs);
    /** The job change on the Agent's own mailbox: completes true when it holds the job afterwards. */
    BiFunction<AgentRuntimeEntry, Job, CompletableFuture<Boolean>> advance = CitizenJobErrands::advanceOnMailbox;

    CitizenJobErrands(Population population, PresenceScheduler presence) {
        this.population = population;
        this.presence = presence;
    }

    /** One walk up to an instructor. */
    static final class Visit {
        final CitizenSession session;
        final Job job;
        final JobInstructors.Instructor instructor;
        final Point spot;
        final long startedAtMs;
        long reachedAtMs;

        Visit(CitizenSession session, Job job, JobInstructors.Instructor instructor, Point spot, long startedAtMs) {
            this.session = session;
            this.job = job;
            this.instructor = instructor;
            this.spot = spot;
            this.startedAtMs = startedAtMs;
        }
    }

    /**
     * The job the citizen goes to its instructor for now, or null: errands on, not a backlog citizen, and a third/fourth
     * job step on its career's line that the game allows at its level. A loaded citizen is read live, else from the DB.
     */
    Job planned(CitizenSession session) {
        Citizen citizen = latest(session);
        if (session.errandFailed) {
            return null;
        }
        Character agent = session.virtual ? null : session.agent();
        if (agent != null) {
            return due(citizen, agent.getJob(), agent.getLevel());
        }
        int[] progress = population.store().characterProgress(citizen.characterId());
        return due(citizen, Job.getById(progress[2]), progress[0]);
    }

    /** {@link #planned} for a known job and level. */
    Job due(Citizen citizen, Job current, int level) {
        if (!population.config().JOB_ERRANDS_ENABLED || citizen.hired() || citizen.has(Citizen.FLAG_JOB_BACKLOG)) {
            return null;
        }
        Career career = population.content().career(citizen.career());
        if (career == null || current == null) {
            return null;
        }
        Job step = CitizenJobPath.errandStep(ResidentShaper.job(career.job()), current, level);
        return step == null || JobInstructors.of(step) == null ? null : step;
    }

    /** Where its instructor stands, or 0 with no errand. */
    int mapFor(CitizenSession session) {
        JobInstructors.Instructor instructor = session.errandJob == null ? null : JobInstructors.of(session.errandJob);
        return instructor == null ? 0 : instructor.mapId();
    }

    /** On its instructor's map: it walks up to them. False when it has nothing to go for here after all. */
    boolean begin(CitizenSession session, MapleMap map) {
        Job job = session.errandJob;
        JobInstructors.Instructor instructor = job == null ? null : JobInstructors.of(job);
        Character agent = session.agent();
        if (instructor == null || agent == null || session.entry == null || map == null || map.getId() != instructor.mapId()
                || due(latest(session), agent.getJob(), agent.getLevel()) != job) {
            return false;
        }
        NPC npc = map.getNPCById(instructor.npcId());
        Point spot = npc == null ? null : npc.getPosition();
        if (spot != null) {
            AgentMovementCommandRuntime.moveTo(session.entry, spot, false);
        }
        Visit visit = new Visit(session, job, instructor, spot, clock.getAsLong());
        later.accept(() -> step(visit), CHECK_MS);
        log.info("citizen {} goes to {} for its {} job ({})", session.name(), instructor.name(), JobInstructors.tier(job), job);
        return true;
    }

    /** It looks again: there yet, done talking, then the advancement. */
    void step(Visit visit) {
        if (!current(visit)) {
            return;
        }
        CitizenSession session = visit.session;
        Character agent = session.agent();
        long now = clock.getAsLong();
        Point at = agent.getPosition();
        boolean there = visit.spot == null || at != null && at.distance(visit.spot) <= REACH_PX;
        if (!there && now - visit.startedAtMs < population.config().JOB_ERRAND_WALK_MAX_MS) {
            later.accept(() -> step(visit), CHECK_MS);
            return;
        }
        if (visit.reachedAtMs == 0L) {
            visit.reachedAtMs = now;
        }
        long talkLeft = visit.reachedAtMs + population.config().JOB_ERRAND_TALK_MS - now;
        if (talkLeft > 0L) {
            later.accept(() -> step(visit), talkLeft);
            return;
        }
        CompletableFuture<Boolean> result = advance.apply(session.entry, visit.job);
        if (result == null) {
            done(visit, false);
            return;
        }
        result.whenComplete((advanced, failure) -> later.accept(() -> done(visit, failure == null && Boolean.TRUE.equals(advanced)), 0L));
    }

    /** Advanced or not, the errand is over: it says so when it advanced, then the scheduler's usual plan takes over. */
    void done(Visit visit, boolean advanced) {
        CitizenSession session = visit.session;
        if (presence.session(session.characterId) != session) {
            return; // logged out meanwhile
        }
        if (advanced) {
            log.info("citizen {} became a {} at {}", session.name(), visit.job, visit.instructor.name());
            population.chatter().event(ChatterEngine.Event.of("jobadvance.self", session.mapId, session.channel)
                    .self(session).subject(session.agent()).job(spoken(visit.job)));
        } else {
            log.warn("citizen {} could not take {} at {}; it goes again next session", session.name(), visit.job,
                    visit.instructor.name());
            session.errandFailed = true;
        }
        session.errandJob = null;
        if (session.activity == Activity.JOB) {
            presence.replan(session, clock.getAsLong());
        }
    }

    /**
     * A backlog citizen's overdue job came off-screen with its catch-up: its journal has it, and a little after it logs
     * in it mentions it ("got my 3rd job last night").
     */
    void mentionOffScreen(CitizenSession session, Job job) {
        session.journal.add("job", "became a " + spoken(job));
        later.accept(() -> {
            if (presence.session(session.characterId) == session && session.loaded()) {
                population.chatter().event(ChatterEngine.Event.of("jobadvance.offscreen.self", session.mapId, session.channel)
                        .self(session).subject(session.agent()).job(spoken(job)));
            }
        }, MENTION_MIN_MS + (long) (random.nextDouble() * MENTION_SPAN_MS));
    }

    /** Its current record: its backlog flag may be gone since it logged in. */
    private Citizen latest(CitizenSession session) {
        Citizen latest = population.citizen(session.characterId);
        return latest == null ? session.citizen : latest;
    }

    /** Still the same walk: its session, still on the JOB activity for this job. */
    private boolean current(Visit visit) {
        CitizenSession session = visit.session;
        return presence.session(session.characterId) == session && session.loaded()
                && session.activity == Activity.JOB && session.errandJob == visit.job;
    }

    /** "white knight", "f/p mage": the job as players type it. */
    static String spoken(Job job) {
        return AgentDialogueReportFormatter.jobDisplayName(job).toLowerCase(Locale.ROOT);
    }

    private static CompletableFuture<Boolean> advanceOnMailbox(AgentRuntimeEntry entry, Job job) {
        return AgentMailboxRuntime.dispatch(entry, runtime -> {
            boolean advanced = AgentJobPath.advance(runtime, job);
            Character agent = AgentRuntimeIdentityRuntime.bot(runtime);
            if (advanced && agent != null) {
                AgentSpBuildProfileService.assignOffline(agent); // SP banked past Lv70 goes into the new job (decision 2026-10-06)
                agent.saveCharToDB();
            }
            return advanced;
        });
    }
}
