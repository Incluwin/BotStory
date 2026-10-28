package server.living.mentor;

import client.Character;
import client.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.progression.AgentCareerBuildBundle;
import server.agents.progression.AgentCareerBuildBundleService;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentShaper;
import server.living.roster.CompanionRoles;
import server.living.roster.HiredHands;
import server.living.roster.ui.CompanionMenus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Apprentices (docs/BOT_CONCEPT_PLAN.md F4.5): a player adopts a beginner citizen and brings it up. Adoption: a
 * double-click on a Beginner of up to APPRENTICE_ADOPT_MAX_LEVEL whose warmth toward the player is REGULAR (15+), or,
 * with no standing gate, one that asked for a mentor on the needs board (NeedKind.MENTOR); the player is
 * APPRENTICE_ADOPT_MIN_OWNER_LEVEL or more. It is a hire with no fee and role APPRENTICE ({@link CompanionRoles}).
 * At each advancement its owner picks the branch (and, where the branch has more than one, the build: claw or dagger,
 * gun or knuckle) in a window, never in chat; JobErrands then sends it to its instructor for the job, and a job word in
 * chat opens that window instead of the instant change. At its second job it graduates: the role goes, it is a plain
 * companion, and its friendship (its {@code hire:} bond) stays as it was.
 */
public final class Apprentices {
    private static final Logger log = LoggerFactory.getLogger(Apprentices.class);
    static final String ADOPTED = "apprentice.adopted";
    static final String PICK = "apprentice.pick";
    static final String BUILD = "apprentice.build";
    static final String GRADUATED = "apprentice.graduated";
    private static final int REGULAR_WARMTH = 15;
    private static final int MAGICIAN_LEVEL = 8;
    private static final int FIRST_JOB_LEVEL = 10;
    private static final int SECOND_JOB_LEVEL = 30;
    private static final long LOGIN_ASK_MS = 15_000L;

    private final LivingWorld world;

    public Apprentices(LivingWorld world) {
        this.world = world;
    }

    private boolean on() {
        return world.config().APPRENTICE_ENABLED;
    }

    // ---- adoption ------------------------------------------------------------------------------

    /**
     * True when {@code player} may adopt the citizen now (memory only: the double-click and the board ask on the I/O
     * thread): the switch, a loaded Beginner citizen within the level, nobody's yet, the player's level, and REGULAR
     * standing unless it asked for a mentor ({@code asked}: the board's request, which needs no standing).
     */
    public boolean mayAdopt(Character player, int citizenId, boolean asked) {
        if (!on() || player == null || AgentPresence.isAgent(player)
                || player.getLevel() < world.config().APPRENTICE_ADOPT_MIN_OWNER_LEVEL) {
            return false;
        }
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(citizenId);
        CitizenSession session = citizen == null ? null : population.presence().session(citizenId);
        Character agent = session == null || session.virtual ? null : session.agent();
        if (citizen == null || citizen.hired() || citizen.state() != Citizen.State.ACTIVE || agent == null
                || !agent.isBeginnerJob() || agent.getJob() != Job.BEGINNER
                || agent.getLevel() > world.config().APPRENTICE_ADOPT_MAX_LEVEL) {
            return false;
        }
        boolean requested = population.needs() != null && population.needs().mentorWanted(citizenId) != null;
        if (asked && requested) {
            return true;
        }
        return population.memories() != null && population.memories().warmth(citizenId, player.getId()) >= REGULAR_WARMTH
                || requested;
    }

    /** The adoption window (a double-click on it, or its request on a board): Accept adopts it on companion-ops. */
    public void offer(Character player, int citizenId) {
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(citizenId);
        CitizenSession session = citizen == null ? null : population.presence().session(citizenId);
        Character agent = session == null ? null : session.agent();
        if (citizen == null || agent == null) {
            return;
        }
        String text = "#e" + citizen.name() + "#n (Lv" + agent.getLevel() + " Beginner) wants to learn the ropes."
                + "\r\nTake them on as your apprentice? There is no fee. You pick their job at each advancement, and"
                + " they go to their instructor for it; at their second job they are a companion like any other.";
        LivingTalk.open(player, CompanionMenus.BOARD_NPC).acceptDecline(text,
                () -> world.executor().submit("apprentice.adopt " + citizenId, () -> adopt(player.getId(), citizenId)),
                null).show();
    }

    /** Adopted: a hire with no fee and role APPRENTICE; its request, if any, is met. */
    void adopt(int playerId, int citizenId) {
        Character player = world.roster().onlineCharacter(playerId);
        if (player == null || !mayAdopt(player, citizenId, true)) {
            if (player != null) {
                player.dropMessage(5, "They cannot be taken on right now.");
            }
            return;
        }
        HiredHands.Outcome hired = world.hiredHands().hireAs(player, citizenId, CompanionRoles.Role.APPRENTICE, 0,
                "really? thank you!! i'll work hard");
        if (!hired.hired()) {
            Persona persona = world.content().persona(HiredHands.hireId(citizenId));
            if (persona != null && !hired.line().isEmpty()) {
                world.residents().whisper(persona, player, hired.line());
            }
            if (!hired.notice().isEmpty()) {
                player.dropMessage(5, hired.notice());
            }
            return;
        }
        if (!hired.notice().isEmpty()) {
            player.dropMessage(5, hired.notice());
        }
        Population population = Population.get();
        if (population != null && population.needs() != null) {
            population.needs().mentorFound(citizenId, playerId, System.currentTimeMillis());
        }
        world.store().event(playerId, HiredHands.hireId(citizenId), ADOPTED, "lv" + levelOf(citizenId));
        log.info("{} adopted citizen {} as an apprentice", player.getName(), citizenId);
    }

    // ---- job picks -----------------------------------------------------------------------------

    /** A character reached a level (any thread): an apprentice due a job it has no pick for asks its owner. */
    public void levelUp(Character chr) {
        if (!on() || chr == null || !AgentPresence.isAgent(chr)) {
            return;
        }
        String personaId = HiredHands.hireId(chr.getId());
        if (CompanionRoles.apprentice(world, personaId) && !choices(chr.getJob(), chr.getLevel(), false).isEmpty()
                && pickOpen(personaId)) {
            world.executor().submit("apprentice.ask " + personaId, () -> askOwner(personaId));
        }
    }

    /** Its owner is back: a pick still open is asked for, once they are in (LOGIN_ASK_MS), on companion-ops. */
    public void loggedIn(Character owner) {
        if (!on() || owner == null || world.roles() == null) {
            return;
        }
        for (CompanionRoles.Held held : world.roles().all(CompanionRoles.Role.APPRENTICE)) {
            if (held.ownerId() == owner.getId()) {
                String personaId = held.personaId();
                server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY,
                        () -> world.executor().submit("apprentice.login " + personaId, () -> askOwner(personaId)),
                        LOGIN_ASK_MS);
            }
        }
    }

    /**
     * True while the apprentice's next job is its owner's to pick: it may take one at its level (from Lv 8 a Magician,
     * from Lv 10 any first job, from Lv 30 its line's second jobs) and has no pick for it yet.
     */
    public boolean pickOpen(String personaId) {
        if (!CompanionRoles.apprentice(world, personaId)) {
            return false;
        }
        int[] levelAndJob = levelAndJob(personaId);
        Job current = Job.getById(levelAndJob[1]);
        if (current == null || choices(current, levelAndJob[0], true).isEmpty()) {
            return false;
        }
        CompanionRoles.Held held = world.roles().of(personaId);
        Job target = held == null || held.target() == null ? null : ResidentShaper.job(held.target());
        return target == null || target.getJobTier() <= current.getJobTier();
    }

    /**
     * The jobs its owner may pick from {@code current} at {@code level}: the five first jobs (Magician from Lv 8, the
     * rest from Lv 10; {@code early} false, as the level-up asks: only from Lv 10), or its first job's second jobs from
     * Lv 30; none past that (from its second job on, its line decides).
     */
    static List<Job> choices(Job current, int level, boolean early) {
        List<Job> out = new ArrayList<>();
        if (current == Job.BEGINNER) {
            for (Job branch : MentorRoute.BRANCHES) {
                int min = branch == Job.MAGICIAN && early ? MAGICIAN_LEVEL : FIRST_JOB_LEVEL;
                if (level >= min) {
                    out.add(branch);
                }
            }
        } else if (current != null && current.getJobTier() == 1 && current.getId() < 1000 && level >= SECOND_JOB_LEVEL) {
            for (int step = 10; step <= 30; step += 10) {
                Job second = Job.getById(current.getId() + step);
                if (second != null) {
                    out.add(second);
                }
            }
        }
        return out;
    }

    /** Its owner's window: which job, then (where its branch has more than one) which build. */
    public void askOwner(String personaId) {
        CompanionRoles.Held held = world.roles().of(personaId);
        Persona persona = world.content().persona(personaId);
        Character owner = held == null ? null : world.roster().onlineCharacter(held.ownerId());
        if (owner == null || persona == null || !pickOpen(personaId) || !LivingTalk.mayShow(owner)) {
            return; // asked again at its next level, its owner's next login, or from its card
        }
        int[] levelAndJob = levelAndJob(personaId);
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Job job : choices(Job.getById(levelAndJob[1]), levelAndJob[0], true)) {
            options.add(new LivingTalk.Option(spoken(job), () -> build(owner, persona, job)));
        }
        options.add(new LivingTalk.Option("Later", null));
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC).menu("#e" + persona.name() + "#n is ready for their next job."
                + " Which path should they take?\r\nThey will go to their instructor for it once you pick.", options).show();
    }

    private void build(Character owner, Persona persona, Job job) {
        List<AgentCareerBuildBundle> bundles = AgentCareerBuildBundleService.choicesFor(job.getId());
        if (bundles.size() <= 1) {
            world.executor().submit("apprentice.pick " + persona.id(), () -> picked(owner.getId(), persona, job, null));
            return;
        }
        List<LivingTalk.Option> options = new ArrayList<>();
        for (AgentCareerBuildBundle bundle : bundles) {
            options.add(new LivingTalk.Option(buildName(bundle), () -> world.executor().submit("apprentice.pick "
                    + persona.id(), () -> picked(owner.getId(), persona, job, bundle.bundleId()))));
        }
        LivingTalk.open(owner, CompanionMenus.BOARD_NPC).menu("How should #e" + persona.name() + "#n build as a "
                + spoken(job) + "?", options).show();
    }

    /** The pick is written onto its row (role_target); JobErrands sends it to its instructor once the job is legal. */
    void picked(int ownerId, Persona persona, Job job, String bundleId) {
        if (!world.roles().set(ownerId, persona.id(), CompanionRoles.Role.APPRENTICE, job.name())) {
            return;
        }
        world.store().event(ownerId, persona.id(), PICK, job.name());
        if (bundleId != null) {
            world.store().event(ownerId, persona.id(), BUILD, bundleId);
            applyBuild(persona.id(), LivingContent.hireCharacterId(persona.id()), bundleId); // its first job's SP follows it
        }
        Character owner = world.roster().onlineCharacter(ownerId);
        if (owner != null) {
            world.residents().whisper(persona, owner, world.hiredHands().voice(LivingContent.hireCharacterId(persona.id()),
                    "ok! " + spoken(job).toLowerCase(Locale.ROOT) + " it is. i'll go see my instructor when i can"));
        }
    }

    /**
     * Its job changed (any thread): the build its owner picked is applied on its own mailbox, and its second job
     * graduates it into a plain companion, its friendship kept.
     */
    public void jobAdvanced(Character chr) {
        if (chr == null || !AgentPresence.isAgent(chr)) {
            return;
        }
        String personaId = HiredHands.hireId(chr.getId());
        CompanionRoles.Held held = world.roles() == null ? null : world.roles().of(personaId);
        if (held == null || held.role() != CompanionRoles.Role.APPRENTICE || !on()) {
            return;
        }
        Job job = chr.getJob();
        world.executor().submit("apprentice.advanced " + personaId, () -> advanced(held, chr.getId(), job));
    }

    void advanced(CompanionRoles.Held held, int characterId, Job job) {
        if (job != null && job.getJobTier() == 1) {
            List<String[]> builds = world.store().personaEvents(held.ownerId(), held.personaId(), List.of(BUILD), 1);
            if (!builds.isEmpty()) {
                // again after the job (it was benched at the pick, or a restart came between): the same pick
                applyBuild(held.personaId(), characterId, builds.get(builds.size() - 1)[1]);
            }
        }
        if (job != null && job.getJobTier() >= 2) {
            graduate(held, job);
        }
    }

    /** The owner's build pick saved as its career bundle, on its own mailbox; nothing while it is not loaded. */
    private void applyBuild(String personaId, int characterId, String bundleId) {
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        if (runtime == null || bundleId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            try {
                AgentCareerBuildBundleService.assignChosen(entry, bundleId, now);
            } catch (java.io.IOException | RuntimeException failure) {
                log.warn("apprentice {}: its build {} could not be set", personaId, bundleId, failure);
            }
            return null;
        });
    }

    /** Its second job: a plain companion from now on, with the bond (friendship) it earned as an apprentice. */
    void graduate(CompanionRoles.Held held, Job job) {
        if (!world.roles().set(held.ownerId(), held.personaId(), null, null)) {
            return;
        }
        world.store().event(held.ownerId(), held.personaId(), GRADUATED, job.name());
        Persona persona = world.content().persona(held.personaId());
        Character owner = world.roster().onlineCharacter(held.ownerId());
        if (persona != null && owner != null) {
            world.residents().whisper(persona, owner, world.hiredHands().voice(LivingContent.hireCharacterId(held.personaId()),
                    "thanks for everything. i think i've got it from here, but i'm sticking around"));
        }
        log.info("apprentice {} of {} graduated at {}", held.personaId(), held.ownerId(), job);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private int[] levelAndJob(String personaId) {
        int characterId = LivingContent.hireCharacterId(personaId);
        Character live = server.agents.integration.AgentRuntimeIdentityRuntime.bot(
                AgentRuntimeRegistry.findByAgentCharacterId(characterId));
        if (live != null && live.getJob() != null) {
            return new int[] {live.getLevel(), live.getJob().getId()};
        }
        return world.store().characterLevelAndJob(characterId);
    }

    private int levelOf(int characterId) {
        return levelAndJob(HiredHands.hireId(characterId))[0];
    }

    static String spoken(Job job) {
        String name = job.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return switch (job) {
            case FP_WIZARD -> "Fire/Poison Wizard";
            case IL_WIZARD -> "Ice/Lightning Wizard";
            default -> java.lang.Character.toUpperCase(name.charAt(0)) + name.substring(1);
        };
    }

    /** "Claw", "Dagger", "Gun", "Knuckle", from the bundle's career ("thief-claw"); "Standard" for a single line. */
    static String buildName(AgentCareerBuildBundle bundle) {
        String career = bundle.career() == null ? "" : bundle.career();
        int dash = career.indexOf('-');
        String word = dash < 0 ? "standard" : career.substring(dash + 1);
        return java.lang.Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
