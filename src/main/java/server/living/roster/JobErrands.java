package server.living.roster;

import client.Character;
import client.Job;
import client.JobProgressionPolicy;
import constants.game.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.AgentJobPath;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentShaper;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Companions go for their next job by themselves (2026-10-05): the living world's answer to the Agent layer's job-path
 * seam (AgentJobPath.Rule). A companion's path is its persona's job (Casse, an I/L Wizard), and from its second job on
 * its own line to fourth job (no choice is left there); a hire at first job and a Beginner persona have none, so their
 * owner picks in chat as before. Found 2026-10-05: Casse reached Lv50 a Magician, Rurik, Yara and HappyWolf Lv45+
 * Warriors, because only an owner typing a branch name advanced a companion.
 *
 * <p>The errand: once a step is legal, a companion out with its owner and free (no run, no event, following them,
 * alive, not held at a GM level) says so in its own words, walks out keeping its seat (RosterService.leaveOnErrand)
 * and travels for the road time to its instructor (JobInstructors). There a bench slice of its own ({@link #form})
 * loads it on the instructor's map; it walks up to them, stands a moment, advances and thanks them, and is called back
 * to its owner (RosterService.call: the trip back, then "back! i/l wizard now"). A benched companion goes the same way
 * between its duties, and goes back to them. Called back before it advanced, it waits COMPANION_JOB_ERRAND_RETRY_MS
 * before it sets off again. An owner's errand is a world flag, so a restart carries it on.</p>
 *
 * <p>{@link #path} answers from memory on any thread, {@link #due} on the Agent tick only hands over to
 * companion-ops; everything else runs on companion-ops.</p>
 */
public final class JobErrands implements AgentJobPath.Rule {
    private static final Logger log = LoggerFactory.getLogger(JobErrands.class);
    static final String FLAG_PREFIX = "companion.job-errand.";

    /** On its way to its instructor, with them, or done (advanced or not) and heading back to its owner or duty. */
    enum Phase { ON_THE_WAY, VISITING, HOMEWARD }

    /** One companion's errand: whose it is, whether it comes back to them (else to its duties), for what, to whom. */
    static final class Errand {
        final String personaId;
        final int ownerId;
        /** It left its owner for this and comes back to them; a benched one goes back to its duties. */
        final boolean returns;
        final Job target;
        final JobInstructors.Instructor instructor;
        /** When it reaches its instructor's town. */
        final long arrivesAtMs;
        volatile Phase phase = Phase.ON_THE_WAY;
        /** True once it holds {@link #target}: back with its owner it says so. */
        volatile boolean advanced;
        Point spot;
        long visitStartedAtMs;
        long reachedAtMs;

        Errand(String personaId, int ownerId, boolean returns, Job target, JobInstructors.Instructor instructor,
               long arrivesAtMs) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.returns = returns;
            this.target = target;
            this.instructor = instructor;
            this.arrivesAtMs = arrivesAtMs;
        }
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    /** persona id -> its errand out now. */
    private final Map<String, Errand> errands = new ConcurrentHashMap<>();
    /** persona id -> no errand before then (its owner called it back from one). */
    private final Map<String, Long> notBeforeMs = new ConcurrentHashMap<>();
    /** Companions whose due step is waiting for companion-ops (one hand-over each). */
    private final Set<Integer> queued = ConcurrentHashMap.newKeySet();
    /** True while the Agent goes along with its owner (a test stands in for the Agent's follow state). */
    BiPredicate<AgentRuntimeEntry, Integer> goesAlong = AgentSupplyReadiness::carriedBy;
    /** The job change on the Agent's own mailbox: true when it holds the job afterwards. */
    BiFunction<AgentRuntimeEntry, Job, Boolean> advance = (runtime, job) ->
            Boolean.TRUE.equals(BenchDutyDispatcher.onAgent(runtime, agent -> AgentJobPath.advance(agent, job)));
    /** The instructor visit: the bench form an errand's slice runs on. */
    final BenchDutyDispatcher.Form form = new Visit();

    public JobErrands(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    JobErrands(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** Installs the job-path rule and the visit ahead of every duty (LivingWorld.start); carries on saved errands. */
    public void install() {
        AgentJobPath.install(this);
        world.benchDuty().first(this::formFor);
        world.executor().submit("job_errands.restore", this::restore);
    }

    /** Takes it out again (LivingWorld.stop): every companion is its owner's pick again. */
    public static void uninstall() {
        AgentJobPath.install(null);
    }

    // ---- the seam -----------------------------------------------------------------------------

    /** Its persona's job, or from second job on its own line's fourth job; null for its owner to pick. Memory only. */
    @Override
    public Job path(int agentCharacterId, Job current) {
        if (!world.config().COMPANION_JOB_ERRANDS_ENABLED || !world.residents().isPersonaCharacter(agentCharacterId)) {
            return null;
        }
        Persona persona = world.residents().personaOfCharacterId(agentCharacterId);
        if (persona == null || world.roster().cachedOwnerOf(persona.id()) == 0) {
            return null; // a Resident nobody employs: not a companion
        }
        return pathFor(current, personaJob(persona));
    }

    @Override
    public void due(int agentCharacterId, Job next) {
        if (!queued.add(agentCharacterId)) {
            return;
        }
        world.executor().submit("job.errand.due " + agentCharacterId, () -> {
            try {
                leave(agentCharacterId);
            } finally {
                queued.remove(agentCharacterId);
            }
        });
    }

    /**
     * The job a companion holding {@code current} is set on: once no choice is left (an Explorer from second job on, a
     * Cygnus Knight or an Aran from first job on), its line's last job (an Explorer's or an Aran's fourth, a Cygnus
     * Knight's third); before that, as its line's beginner (Beginner, Noblesse, Legend) or an Explorer at first job, its
     * persona's job when {@code current} lies on the way to it; else none.
     */
    static Job pathFor(Job current, Job personaJob) {
        if (current == null) {
            return null;
        }
        Job last = JobProgressionPolicy.lastJobOfLine(current);
        if (last != current || GameConstants.getJobBranch(current) >= 2) {
            return last; // at its end: its own job, nothing left to ask
        }
        if (personaJob == null || personaJob == current) {
            return null;
        }
        return current == JobProgressionPolicy.beginnerOf(personaJob)
                || AgentCharacterShaper.advancementChain(personaJob).contains(current) ? personaJob : null;
    }

    /** The job its persona names; null for a hire (its owner picks, as for any character they brought). */
    static Job personaJob(Persona persona) {
        return persona == null || persona.job() == null || LivingContent.isHire(persona.id()) ? null
                : ResidentShaper.job(persona.job());
    }

    // ---- leaving its owner (companion-ops) ------------------------------------------------------

    /** A companion out with its owner whose next step is legal sets off, when it is free to. */
    void leave(int characterId) {
        CompanionSettingsApplier.Bound at = world.roster().settings().boundTo(characterId);
        if (at == null || errands.containsKey(at.personaId()) || waiting(at.personaId())) {
            return; // not out with its owner (a bench slice goes from the bench), or on its way already
        }
        Persona persona = world.content().persona(at.personaId());
        Character owner = world.roster().onlineCharacter(at.ownerId());
        RosterEntry row = world.store().rosterEntry(at.ownerId(), at.personaId());
        Character bot = row == null ? null : world.roster().companion(row);
        if (persona == null || owner == null || bot == null || row.state() != RosterState.ACTIVE || !free(owner, bot, persona)) {
            return;
        }
        Job step = AgentJobPath.next(bot.getJob(), pathFor(bot.getJob(), personaJob(persona)), bot.getLevel());
        JobInstructors.Instructor instructor = JobInstructors.of(step);
        if (step == null || instructor == null) {
            return;
        }
        long etaMs = RecallTravel.etaMs(bot.getMapId(), instructor.mapId(), world.config().RECALL_MAX_ETA_MS);
        String line = line(persona, "jobErrand", JobErrandLines.GO, owner.getId(), step, instructor);
        RosterResult left = world.roster().leaveOnErrand(owner, persona, line);
        if (!left.ok()) {
            return;
        }
        errands.put(persona.id(), new Errand(persona.id(), owner.getId(), true, step, instructor, clock.getAsLong() + etaMs));
        world.store().setWorldFlag(flag(owner.getId(), persona.id()), owner.getId());
        world.store().event(owner.getId(), persona.id(), "job.errand",
                step + " from " + instructor.name() + " at map " + instructor.mapId() + ", eta " + etaMs + " ms");
    }

    /** Free to go: no run or event, alive, following its owner, not on its way anywhere, not held at a GM level. */
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

    // ---- the bench (companion-ops) --------------------------------------------------------------

    /**
     * The visit, ahead of its duty, for a row with an errand on its way to its instructor or a benched companion
     * whose next step is legal by its saved level and job (that errand starts here, with its road time from its camp);
     * null for its duty's own form.
     */
    BenchDutyDispatcher.Form formFor(RosterEntry entry) {
        if (!world.config().COMPANION_JOB_ERRANDS_ENABLED) {
            return null;
        }
        Errand errand = errands.get(entry.personaId());
        if (errand != null) {
            return errand.phase == Phase.HOMEWARD ? null : form; // done: its duty while it waits to be called back
        }
        if (waiting(entry.personaId())) {
            return null;
        }
        Persona persona = world.content().persona(entry.personaId());
        int characterId = persona == null ? 0 : world.residents().characterIdOf(persona.id());
        if (characterId == 0) {
            return null;
        }
        int[] levelAndJob = world.store().characterLevelAndJob(characterId);
        Job current = Job.getById(levelAndJob[1]);
        Job step = AgentJobPath.next(current, pathFor(current, personaJob(persona)), levelAndJob[0]);
        JobInstructors.Instructor instructor = JobInstructors.of(step);
        if (step == null || instructor == null || held(characterId, persona.id())) {
            return null;
        }
        int camp = entry.campMapId() != 0 ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        long etaMs = RecallTravel.etaMs(camp, instructor.mapId(), world.config().RECALL_MAX_ETA_MS);
        errands.put(persona.id(), new Errand(persona.id(), entry.characterId(), false, step, instructor, clock.getAsLong() + etaMs));
        world.store().event(entry.characterId(), persona.id(), "job.errand",
                step + " from " + instructor.name() + " at map " + instructor.mapId() + " (from the bench), eta " + etaMs + " ms");
        return form;
    }

    private final class Visit implements BenchDutyDispatcher.Form {
        @Override
        public CompanionBudget.Kind pool() {
            return CompanionBudget.Kind.BENCH_TOWN;
        }

        /** Its instructor's map once its road time is up; 0 (the ledger keeps it) while it is on its way. */
        @Override
        public int mapFor(RosterEntry entry, Persona persona, int characterId) {
            Errand errand = errands.get(persona.id());
            return errand == null || errand.phase == Phase.HOMEWARD || clock.getAsLong() < errand.arrivesAtMs ? 0
                    : errand.instructor.mapId();
        }

        /** Loaded at the map's portal: it walks up to its instructor (it holds still meanwhile, never fights). */
        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            Errand errand = errands.get(slice.personaId());
            if (errand == null) {
                return false;
            }
            Job step = AgentJobPath.next(agent.getJob(),
                    pathFor(agent.getJob(), personaJob(world.content().persona(slice.personaId()))), agent.getLevel());
            if (step != errand.target) {
                // advanced meanwhile (its owner's word in chat, a GM set) or not due after all: back to its duty, or
                // called back to its owner by the sweep (never from here: this slice is still starting)
                settle(errand, agent.getJob() == errand.target, "not due at its instructor");
                if (!errand.returns) {
                    end(errand);
                }
                return false;
            }
            AgentRuntimeEntry runtime = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            if (runtime == null) {
                return false;
            }
            MapleMap map = agent.getMap();
            NPC npc = map == null ? null : map.getNPCById(errand.instructor.npcId());
            errand.spot = npc == null ? null : npc.getPosition();
            errand.visitStartedAtMs = nowMs;
            errand.reachedAtMs = 0L;
            errand.phase = Phase.VISITING;
            if (errand.spot != null) {
                AgentMovementCommandRuntime.moveTo(runtime, errand.spot, false);
            }
            watch(errand, agent, runtime, ArrivalPlacement.WALK_OUT_CHECK_MS);
            return true;
        }

        @Override
        public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
            Errand errand = errands.get(slice.personaId());
            if (errand == null || errand.phase == Phase.HOMEWARD) {
                return errand != null && errand.advanced ? "advanced" : "errand over";
            }
            if (agent.getMapId() != slice.mapId()) {
                return "left its instructor";
            }
            long visitMaxMs = (long) world.config().COMPANION_JOB_ERRAND_WALK_MAX_MS + world.config().COMPANION_JOB_ERRAND_TALK_MS
                    + BenchDutyDispatcher.TICK_MS;
            return errand.phase == Phase.VISITING && nowMs - errand.visitStartedAtMs > visitMaxMs ? "visit took too long" : null;
        }

        /**
         * Ended before it advanced (a call, a release, a time-up): that errand is over and it may go again later. An
         * owner's companion is called back by the sweep (never from here: a call may be what ends this slice).
         */
        @Override
        public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
            Errand errand = errands.get(slice.personaId());
            if (errand != null && errand.phase != Phase.HOMEWARD) {
                settle(errand, false, reason);
                if (!errand.returns) {
                    end(errand);
                }
            }
        }
    }

    /** It looks again after {@code delayMs}: there yet, done talking, then the advancement. */
    private void watch(Errand errand, Character agent, AgentRuntimeEntry runtime, long delayMs) {
        world.roster().later("job.errand.visit " + errand.personaId, () -> visit(errand, agent, runtime), delayMs);
    }

    private void visit(Errand errand, Character agent, AgentRuntimeEntry runtime) {
        if (errands.get(errand.personaId) != errand || errand.phase != Phase.VISITING) {
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
        if (!Boolean.TRUE.equals(advance.apply(runtime, errand.target))) {
            log.warn("job errand: {} could not take {} at {}", errand.personaId, errand.target, errand.instructor.name());
            homeward(errand, false, "the job change was refused");
            return;
        }
        MapleMap map = agent.getMap();
        if (map != null && ResidentRuntime.humansIn(map) > 0) {
            List<String> thanks = JobErrandLines.THANKS;
            AgentDialogueTransportRuntime.sayMapNow(agent, thanks.get(ThreadLocalRandom.current().nextInt(thanks.size())));
        }
        homeward(errand, true, null);
    }

    /**
     * The errand is done, {@code advanced} or not ({@code why}; it may go again after COMPANION_JOB_ERRAND_RETRY_MS): an
     * owner's companion heads back to them, a benched one back to its duties.
     */
    private void homeward(Errand errand, boolean advanced, String why) {
        settle(errand, advanced, why);
        if (!errand.returns) {
            end(errand);
        } else {
            returnHome(errand);
        }
    }

    /** Done, {@code advanced} or not ({@code why}), in the log; one that did not advance waits before it goes again. */
    private void settle(Errand errand, boolean advanced, String why) {
        errand.advanced = advanced;
        errand.phase = Phase.HOMEWARD;
        if (advanced) {
            world.store().event(errand.ownerId, errand.personaId, "job.advanced",
                    errand.target + " from " + errand.instructor.name() + " at map " + errand.instructor.mapId());
        } else {
            notBeforeMs.put(errand.personaId, clock.getAsLong() + world.config().COMPANION_JOB_ERRAND_RETRY_MS);
            world.store().event(errand.ownerId, errand.personaId, "job.errand.end", "not advanced: " + why);
        }
    }

    /**
     * Called back to its owner; advanced, it says so in its own words on arrival (else its usual join line). With its
     * owner offline, in a run or their party full it waits (its duty meanwhile), and the sweep calls it once they can.
     */
    private void returnHome(Errand errand) {
        RosterEntry row = world.store().rosterEntry(errand.ownerId, errand.personaId);
        if (row == null || row.state() != RosterState.AWAY) {
            end(errand); // called back, benched or released meanwhile
            return;
        }
        Character owner = world.roster().onlineCharacter(errand.ownerId);
        Persona persona = world.content().persona(errand.personaId);
        if (owner == null || persona == null || owner.getEventInstance() != null || !world.roster().canSeat(owner, row)) {
            return;
        }
        if (errand.advanced) {
            world.roster().sayOnArrival(persona.id(), line(persona, "jobBack", JobErrandLines.BACK, owner.getId(),
                    errand.target, errand.instructor));
        }
        if (world.roster().call(owner, persona).ok()) {
            end(errand);
        } else {
            world.roster().sayOnArrival(persona.id(), null);
        }
    }

    // ---- the sweep and a restart (companion-ops) --------------------------------------------------

    /**
     * The errands out: one whose row is no longer away (called back, benched, released) is over, and waits before it
     * goes again when it had not advanced; one done is called back once its owner can take it; with the kill switch
     * off, one still on its way is called back at once.
     */
    public void sweep() {
        long now = clock.getAsLong();
        for (Errand errand : List.copyOf(errands.values())) {
            if (!errand.returns) {
                RosterEntry row = world.store().rosterOfPersona(errand.personaId);
                if (row == null || !BenchSessions.benchGoverned(row)) {
                    errands.remove(errand.personaId, errand); // called or released before it was done
                }
                continue;
            }
            RosterEntry row = world.store().rosterEntry(errand.ownerId, errand.personaId);
            if (row == null || row.state() != RosterState.AWAY) {
                if (!errand.advanced) {
                    notBeforeMs.put(errand.personaId, now + world.config().COMPANION_JOB_ERRAND_RETRY_MS);
                }
                end(errand);
            } else if (errand.phase == Phase.HOMEWARD) {
                returnHome(errand);
            } else if (errand.phase == Phase.ON_THE_WAY && !world.config().COMPANION_JOB_ERRANDS_ENABLED) {
                homeward(errand, false, "job errands switched off");
            }
        }
    }

    /**
     * The owners' errands a restart cut short: each is on its instructor's doorstep now (its road went on meanwhile),
     * or, already advanced, waits to be called back. A row no longer away has none.
     */
    void restore() {
        for (String flag : world.store().worldFlags()) {
            if (!flag.startsWith(FLAG_PREFIX)) {
                continue;
            }
            String[] parts = flag.substring(FLAG_PREFIX.length()).split("\\.", 2);
            int ownerId = parts.length == 2 && parts[0].matches("\\d{1,9}") ? Integer.parseInt(parts[0]) : 0;
            RosterEntry row = ownerId == 0 ? null : world.store().rosterEntry(ownerId, parts[1]);
            Persona persona = row == null ? null : world.content().persona(row.personaId());
            int characterId = persona == null ? 0 : world.residents().characterIdOf(persona.id());
            if (row == null || row.state() != RosterState.AWAY || characterId == 0) {
                world.store().clearWorldFlag(flag);
                continue;
            }
            int[] levelAndJob = world.store().characterLevelAndJob(characterId);
            Job current = Job.getById(levelAndJob[1]);
            Job step = AgentJobPath.next(current, pathFor(current, personaJob(persona)), levelAndJob[0]);
            Job job = step != null ? step : current;
            JobInstructors.Instructor instructor = JobInstructors.of(job);
            if (instructor == null) {
                world.store().clearWorldFlag(flag);
                continue;
            }
            Errand errand = new Errand(persona.id(), ownerId, true, job, instructor, clock.getAsLong());
            errand.phase = step != null ? Phase.ON_THE_WAY : Phase.HOMEWARD;
            errand.advanced = step == null; // it holds the job it went for
            errands.put(persona.id(), errand);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void end(Errand errand) {
        if (errands.remove(errand.personaId, errand) && errand.returns) {
            world.store().clearWorldFlag(flag(errand.ownerId, errand.personaId));
        }
    }

    private boolean waiting(String personaId) {
        Long until = notBeforeMs.get(personaId);
        return until != null && clock.getAsLong() < until;
    }

    private boolean held(int characterId, String personaId) {
        GmLevelHolds holds = world.gmLevelHolds();
        return holds != null && holds.held(characterId, personaId);
    }

    /** Its persona's own line for {@code intent} when it has one, else one of {@code fallback}, filled in. */
    private String line(Persona persona, String intent, List<String> fallback, int seed, Job job,
                        JobInstructors.Instructor instructor) {
        List<String> own = world.arcs().lines(persona, intent);
        List<String> lines = own == null || own.isEmpty() ? fallback : own;
        return JobErrandLines.fill(lines.get(Math.floorMod(seed, lines.size())), job, instructor);
    }

    static String flag(int ownerId, String personaId) {
        return FLAG_PREFIX + ownerId + "." + personaId;
    }

    /** The errand out for {@code personaId}, if any (tests and the crew view). */
    Errand errand(String personaId) {
        return errands.get(personaId);
    }

    /** True while {@code personaId} is on its way to its instructor or with them (not yet on its way back). */
    public boolean underway(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        return errand != null && errand.phase != Phase.HOMEWARD;
    }

    /** Whom {@code personaId} went to see, while {@link #underway}: "Grendel in Ellinia"; else null. */
    public String visiting(String personaId) {
        Errand errand = personaId == null ? null : errands.get(personaId);
        return errand == null || errand.phase == Phase.HOMEWARD ? null
                : errand.instructor.name() + " in " + errand.instructor.town();
    }
}
