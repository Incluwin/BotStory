package server.living.mentor;

import client.Character;
import client.Job;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.navigation.AgentLithHarborArrivalRouteRuntime;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.integration.AgentPresence;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.knowledge.ContentAnswers;
import server.life.LifeFactory;
import server.life.SpawnPoint;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentShaper;
import server.living.roster.CompanionRoles;
import server.living.roster.HiredHands;
import server.living.roster.JobInstructors;
import server.living.roster.ui.CompanionMenus;
import server.living.standing.HireStanding;
import server.living.standing.Standing;
import server.living.store.LivingStore.EventRow;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.Portal;
import server.life.NPC;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Mentors for new characters (docs/BOT_CONCEPT_PLAN.md F4.1-F4.4, decisions D8 and D9). A new character's first login
 * ({@link FirstLogin}) offers, once, a traveller to show it around; accepted, a citizen is born for it (an Explorer homed
 * in Victoria, MENTOR_LEAD_MIN..MAX levels above it: "a player on a fresh alt") and hired with no fee as role MENTOR
 * ({@link CompanionRoles}): it joins the party, holds no roster slot, rests while benched and earns no kill EXP of its
 * own. Then, on one 1 s companion-ops sweep:
 * <ul>
 *   <li>it stays that far ahead, one level at a time, MENTOR_LEVEL_DELAY_* after its newcomer's (its job comes by its
 *   own instructor errand on Victoria, JobErrands);</li>
 *   <li>it says each milestone tip once ({@link MentorTips}: low HP, the first death, Lv 8, Lv 10, Victoria), facts
 *   from ContentAnswers, until muted;</li>
 *   <li>it leads ({@link MentorLead}) along {@link MentorRoute}: down the island road when a map is outgrown, onto
 *   Shanks's ship, to the taxi by Lith's dock (or, short of the fare, off the deck and walking) and the instructor of
 *   the branch its newcomer picked in a window;</li>
 *   <li>at its newcomer's second job it says goodbye, drops its gift, and goes home, a FRIEND (warmth 40+).</li>
 * </ul>
 * Everything here runs on companion-ops; the hooks only hand over.
 */
public final class Mentorship {
    private static final Logger log = LoggerFactory.getLogger(Mentorship.class);
    public static final long SWEEP_MS = 1_000L;
    static final String TIP_EVENT = "mentor.tip";
    static final String MUTE_EVENT = "mentor.muted";
    static final String PICK_EVENT = "mentor.pick";
    private static final long WALK_HOP_TIMEOUT_MS = 60_000L;
    private static final double DOOR_REACH_PX = 110;
    private static final long GOODBYE_STEP_MS = 3_500L;
    private static final long JOB_MENU_RETRY_MS = 5_000L;
    private static final long ABANDON_SWEEP_MS = 3_600_000L;
    private static final int FRIEND_WARMTH = 40;
    private static final long DAY_MS = 86_400_000L;
    private static final Set<Integer> ISLAND_TRANSIT = Set.of(1_000_000, 1_010_000, 1_020_000); // nothing to outgrow

    /** One newcomer's mentorship, by its newcomer's id; touched on companion-ops only (the tips also from hooks). */
    static final class Mentee {
        final int newcomerId;
        final String personaId;
        final int mentorId;
        final String style;
        final MentorTips tips = new MentorTips();
        volatile int lead;
        volatile Job pick;
        MentorLead walk;
        Leg leg;
        long levelAtMs;
        int ledFromMapId;
        int ledAtLevel;
        boolean askJob;
        long jobMenuAtMs;
        volatile long goodbyeAtMs;
        int goodbyeStep;
        final Set<String> saidOnce = ConcurrentHashMap.newKeySet();

        Mentee(int newcomerId, String personaId, int mentorId, String style, int lead) {
            this.newcomerId = newcomerId;
            this.personaId = personaId;
            this.mentorId = mentorId;
            this.style = style;
            this.lead = lead;
        }
    }

    /** What a lead's leg is: through a portal, up to an NPC, or off Lith's ship deck (a hidden portal, same map). */
    enum LegKind { PORTAL, NPC, DECK }

    /**
     * The leg being led: on {@code mapId}, through {@code portalId} to {@code toMapId} (and back by {@code backPortalId},
     * -1 for none), or up to NPC {@code npcId}; {@code door} is where it stands to wait.
     */
    record Leg(LegKind kind, int mapId, int portalId, int toMapId, int backPortalId, int npcId, Point door,
               MentorLead.Leg lead, boolean advancedEnds) { }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Random random = new Random();
    private final Map<Integer, Mentee> mentees = new ConcurrentHashMap<>();
    /** Mentor character ids, for the EXP hook and the follow guard (any thread). */
    private final Set<Integer> mentorIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> leading = ConcurrentHashMap.newKeySet();
    private volatile MentorLines lines;
    private long abandonCheckedAtMs;

    public Mentorship(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    Mentorship(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    private boolean on() {
        return world.config().MENTOR_ENABLED;
    }

    MentorLines lines() {
        MentorLines loaded = lines;
        if (loaded == null) {
            loaded = MentorLines.load(java.nio.file.Path.of(world.config().CONTENT_DIR));
            lines = loaded;
        }
        return loaded;
    }

    void lines(MentorLines value) {
        lines = value;
    }

    // ---- boot ------------------------------------------------------------------------------------

    /** Boot, on companion-ops after the roles are read: every mentorship a restart cut into, as it stood. */
    public void load() {
        mentees.clear();
        mentorIds.clear();
        CompanionRoles roles = world.roles();
        if (roles == null) {
            return;
        }
        for (CompanionRoles.Held held : roles.all(CompanionRoles.Role.MENTOR)) {
            int mentorId = server.living.config.LivingContent.hireCharacterId(held.personaId());
            int[] mentor = world.store().characterLevelAndJob(mentorId);
            int[] newcomer = world.store().characterLevelAndJob(held.ownerId());
            int lead = clampLead(mentor[0] - newcomer[0]);
            Mentee mentee = new Mentee(held.ownerId(), held.personaId(), mentorId, lines().style(held.ownerId()), lead);
            List<String[]> rows = world.store().personaEvents(held.ownerId(), held.personaId(),
                    List.of(TIP_EVENT, MUTE_EVENT, PICK_EVENT), 50); // oldest first
            for (int i = rows.size() - 1; i >= 0; i--) {
                restore(mentee, rows.get(i)[0], rows.get(i)[1]);
            }
            mentees.put(held.ownerId(), mentee);
            mentorIds.add(mentorId);
        }
    }

    /** One logged row, newest first (personaEvents): the told tips, the newest mute and the newest pick win. */
    private static void restore(Mentee mentee, String kind, String detail) {
        if (TIP_EVENT.equals(kind)) {
            try {
                mentee.tips.told(MentorTips.Milestone.valueOf(detail), 0L);
            } catch (IllegalArgumentException | NullPointerException unknown) {
                // a milestone this build no longer has
            }
        } else if (MUTE_EVENT.equals(kind) && !mentee.saidOnce.contains("restored.mute")) {
            mentee.saidOnce.add("restored.mute");
            mentee.tips.muted("on".equals(detail));
        } else if (PICK_EVENT.equals(kind) && mentee.pick == null && detail != null) {
            mentee.pick = ResidentShaper.job(detail);
        }
    }

    private int clampLead(int lead) {
        return Math.max(world.config().MENTOR_LEAD_MIN, Math.min(world.config().MENTOR_LEAD_MAX, lead));
    }

    // ---- reads (any thread) --------------------------------------------------------------------

    /** True while the mentor character walks ahead of its newcomer or waits for them: the follow guard holds it. */
    public boolean leading(int agentCharacterId) {
        return !leading.isEmpty() && leading.contains(agentCharacterId);
    }

    /** True for a mentor character while MENTOR_ENABLED: it earns no kill EXP of its own. */
    public boolean isMentor(int characterId) {
        return !mentorIds.isEmpty() && mentorIds.contains(characterId) && on();
    }

    /** True while any mentorship is open (the sweep's timer hands nothing over otherwise). */
    public boolean any() {
        return !mentees.isEmpty();
    }

    /** True when {@code characterId} has a mentor out with it. */
    public boolean mentored(int characterId) {
        return mentees.containsKey(characterId);
    }

    /** The job a mentor's own career leads to (careers.yaml), for its instructor errands; null when unknown. */
    public Job careerJob(String personaId) {
        int characterId = server.living.config.LivingContent.hireCharacterId(personaId);
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        PopulationContent.Career career = citizen == null ? null : population.content().careers().get(citizen.career());
        return career == null ? null : ResidentShaper.job(career.job());
    }

    /**
     * A mentor may go for its own job now (JobErrands): on Victoria, not leading, its newcomer not on the island, and its
     * first job only once its newcomer has theirs ("my turn").
     */
    public boolean mayLeaveForJob(String personaId, int mapId) {
        if (MentorRoute.onIsland(mapId)) {
            return false;
        }
        for (Mentee mentee : mentees.values()) {
            if (mentee.personaId.equals(personaId)) {
                if (mentee.walk != null || mentee.goodbyeAtMs > 0) {
                    return false;
                }
                int[] newcomer = world.store().characterLevelAndJob(mentee.newcomerId);
                int[] mentor = world.store().characterLevelAndJob(mentee.mentorId);
                Job own = Job.getById(mentor[1]);
                Job theirs = Job.getById(newcomer[1]);
                return own != Job.BEGINNER || theirs != Job.BEGINNER; // its first job: after its newcomer's
            }
        }
        return true;
    }

    // ---- hooks (they hand over) -----------------------------------------------------------------

    /** Any login: the offer gate, or its mentor brought back (on companion-ops, after the roster's own restore). */
    public void loggedIn(Character chr) {
        if (chr == null || AgentPresence.isAgent(chr)) {
            return;
        }
        int id = chr.getId();
        world.executor().submit("mentor.login " + id, () -> afterLogin(id));
    }

    public void levelUp(Character chr) {
        Mentee mentee = chr == null ? null : mentees.get(chr.getId());
        if (mentee != null) {
            int level = chr.getLevel();
            if (level >= 8 && chr.isBeginnerJob()) {
                mentee.tips.reached(MentorTips.Milestone.LV8);
            }
            if (level >= 10 && chr.isBeginnerJob()) {
                mentee.tips.reached(MentorTips.Milestone.LV10);
            }
        }
    }

    public void died(Character chr) {
        Mentee mentee = chr == null ? null : mentees.get(chr.getId());
        if (mentee != null) {
            mentee.tips.reached(MentorTips.Milestone.FIRST_DEATH);
        }
    }

    public void mapEntered(Character chr, int mapId) {
        Mentee mentee = chr == null ? null : mentees.get(chr.getId());
        if (mentee != null && mapId >= 100_000_000 && mapId < 200_000_000) {
            mentee.tips.reached(MentorTips.Milestone.VICTORIA);
        }
    }

    /** Its newcomer's job changed: a second job graduates it (D9). */
    public void jobAdvanced(Character chr) {
        Mentee mentee = chr == null ? null : mentees.get(chr.getId());
        if (mentee != null && chr.getJob() != null && chr.getJob().getJobTier() >= 2 && mentee.goodbyeAtMs == 0) {
            mentee.goodbyeAtMs = clock.getAsLong() + GOODBYE_STEP_MS;
        }
    }

    /** Its newcomer's chat: "quiet please" mutes the tips, "tips on" brings them back, a branch word is their pick. */
    public void chat(Character speaker, String text) {
        Mentee mentee = speaker == null ? null : mentees.get(speaker.getId());
        if (mentee == null || text == null) {
            return;
        }
        Job branch = speaker.isBeginnerJob() ? MentorRoute.branchIn(text) : null;
        boolean mute = MentorTips.muteVerb(text);
        boolean unmute = !mute && MentorTips.unmuteVerb(text);
        if (mute || unmute || branch != null) {
            world.executor().submit("mentor.chat " + speaker.getId(), () -> heard(mentee, mute, unmute, branch));
        }
    }

    // ---- the offer (companion-ops) ---------------------------------------------------------------

    void afterLogin(int id) {
        Character chr = world.roster().onlineCharacter(id);
        if (chr == null) {
            return;
        }
        Mentee mentee = mentees.get(id);
        if (mentee != null) {
            if (!on()) {
                end(mentee, "mentors switched off", false);
            } else {
                recall(chr, mentee);
            }
            return;
        }
        if (!on()) {
            return;
        }
        Optional<EventRow> latest = world.store().latestCharacterEvent(id, FirstLogin.KINDS);
        FirstLogin.Gate gate = FirstLogin.gate(true, chr.getLevel(), chr.getJob() == Job.BEGINNER, chr.getMapId(),
                latest != null && latest.isEmpty() ? world.store().neverLoggedOut(id) : Boolean.FALSE, latest);
        if (gate == FirstLogin.Gate.NO) {
            return;
        }
        if (gate == FirstLogin.Gate.OFFER) {
            world.store().event(id, null, FirstLogin.PENDING, "first login");
        }
        offerLater(id, world.config().MENTOR_OFFER_TRIES, world.config().MENTOR_OFFER_DELAY_MS);
    }

    private void offerLater(int id, int tries, long delayMs) {
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor().submit("mentor.offer " + id, () -> offer(id, tries)), delayMs);
    }

    /**
     * The window, through LivingTalk's own guard: never over another window (a second NPC_TALK disconnects the v83
     * client). Another window open: again RETRY_MS later, OFFER_TRIES times; still none: it waits for the next login,
     * and was never offered. Shown: mentor.offered, final whatever the answer (D8).
     */
    void offer(int id, int tries) {
        Character chr = world.roster().onlineCharacter(id);
        if (chr == null || !on() || mentees.containsKey(id) || chr.getClient() == null) {
            return;
        }
        if (!LivingTalk.mayShow(chr) || chr.getEventInstance() != null || chr.getMap() == null) {
            if (tries > 1) {
                offerLater(id, tries - 1, world.config().MENTOR_OFFER_RETRY_MS);
            }
            return;
        }
        String style = lines().style(id);
        String text = say(style, "offer", Map.of("name", chr.getName()), id);
        if (text == null) {
            return;
        }
        world.store().event(id, null, FirstLogin.OFFERED, "style " + style);
        LivingTalk.open(chr, CompanionMenus.BOARD_NPC)
                .yesNo(text, () -> world.executor().submit("mentor.yes " + id, () -> accept(id)),
                        () -> world.executor().submit("mentor.no " + id,
                                () -> world.store().event(id, null, FirstLogin.DECLINED, "the window")))
                .show();
        if (!LivingTalk.active(chr.getClient())) {
            world.store().event(id, null, FirstLogin.PENDING, "the window did not show");
            if (tries > 1) {
                offerLater(id, tries - 1, world.config().MENTOR_OFFER_RETRY_MS);
            }
        }
    }

    /** Yes: a citizen is born for it and hired as its mentor; it walks in from the nearest portal and joins the party. */
    void accept(int id) {
        Character chr = world.roster().onlineCharacter(id);
        Population population = Population.get();
        if (chr == null || population == null || !on()) {
            world.store().event(id, null, FirstLogin.ENDED, chr == null ? "logged out before it came" : "no population");
            return;
        }
        int min = chr.getLevel() + world.config().MENTOR_LEAD_MIN;
        int max = chr.getLevel() + world.config().MENTOR_LEAD_MAX;
        Citizen born = population.generator().birthMentor(min, max);
        if (born == null) {
            world.store().event(id, null, FirstLogin.ENDED, "no mentor born for lv" + min + "-" + max);
            return;
        }
        String personaId = HiredHands.hireId(born.characterId());
        String style = lines().style(id);
        int[] level = world.store().characterLevelAndJob(born.characterId());
        Map<String, String> values = Map.of("name", chr.getName(), "me", born.name());
        world.roster().sayOnArrival(personaId, say(style, "arrive", values, born.characterId()));
        HiredHands.Outcome hired = world.hiredHands().hireAs(chr, born.characterId(), CompanionRoles.Role.MENTOR,
                chr.getMapId(), say(style, "accept", values, born.characterId()));
        if (!hired.hired()) {
            world.roster().sayOnArrival(personaId, null);
            world.store().event(id, personaId, FirstLogin.ENDED, "hire refused: " + hired.result());
            return;
        }
        world.roster().restAt(id, personaId, chr.getMapId());
        Persona persona = world.content().persona(personaId);
        if (persona != null) {
            world.manager().setLootPolicy(chr, persona, server.agents.capabilities.looting.AgentLootPolicy.Mode.QUEST_ONLY);
        }
        Mentee mentee = new Mentee(id, personaId, born.characterId(), style, clampLead(level[0] - chr.getLevel()));
        mentees.put(id, mentee);
        mentorIds.add(born.characterId());
        world.store().event(id, personaId, FirstLogin.ACCEPTED, born.name() + " lv" + level[0] + ", lead " + mentee.lead
                + ", style " + style);
        log.info("{} took {} ({}) as their mentor, lv{} (lead {})", chr.getName(), born.name(), personaId, level[0],
                mentee.lead);
    }

    /** Its newcomer is back: a mentor benched meanwhile camps where they are and is called straight to them. */
    private void recall(Character chr, Mentee mentee) {
        RosterEntry row = world.roster().entry(chr.getId(), mentee.personaId);
        Persona persona = world.content().persona(mentee.personaId);
        if (row == null || persona == null || row.state() != RosterState.BENCHED) {
            return; // the roster restored it with its owner, or it is on its way
        }
        world.roster().restAt(chr.getId(), mentee.personaId, chr.getMapId());
        world.roster().call(chr, persona);
    }

    // ---- the sweep (companion-ops) ----------------------------------------------------------------

    /** Once a second: each mentorship's level, tips, lead and goodbye; hourly, the ones whose newcomer went away. */
    public void tick() {
        if (mentees.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (Mentee mentee : List.copyOf(mentees.values())) {
            try {
                tick(mentee, now);
            } catch (RuntimeException failure) {
                log.warn("mentor {} of {}: sweep failed", mentee.personaId, mentee.newcomerId, failure);
            }
        }
        if (now - abandonCheckedAtMs >= ABANDON_SWEEP_MS) {
            abandonCheckedAtMs = now;
            abandoned(now);
        }
    }

    private void tick(Mentee mentee, long now) {
        Character newcomer = world.roster().onlineCharacter(mentee.newcomerId);
        if (newcomer == null) {
            if (mentee.walk != null) {
                stopLead(mentee, null);
            }
            return;
        }
        if (!on()) {
            return; // the next login sends it home
        }
        RosterEntry row = world.roster().entry(mentee.newcomerId, mentee.personaId);
        Character mentor = row == null || row.state() != RosterState.ACTIVE ? null : world.roster().companion(row);
        if (row == null) {
            forget(mentee);
            return;
        }
        if (mentor == null) {
            return; // benched, away on its own job, or on its way
        }
        if (mentee.goodbyeAtMs > 0) {
            goodbye(mentee, newcomer, mentor, now);
            return;
        }
        raise(mentee, newcomer, mentor, now);
        tips(mentee, newcomer, mentor, now);
        if (mentee.askJob && now >= mentee.jobMenuAtMs) {
            askJob(mentee, newcomer, now);
        }
        lead(mentee, newcomer, mentor, now);
    }

    /** One level at a time, MENTOR_LEVEL_DELAY_* after its newcomer's, until it is {@code lead} levels ahead. */
    private void raise(Mentee mentee, Character newcomer, Character mentor, long now) {
        if (!MentorLevels.behind(mentor.getLevel(), newcomer.getLevel(), mentee.lead)) {
            mentee.levelAtMs = 0L;
            return;
        }
        if (mentee.levelAtMs == 0L) {
            mentee.levelAtMs = now + MentorLevels.delayMs(random, world.config().MENTOR_LEVEL_DELAY_MIN_MS,
                    world.config().MENTOR_LEVEL_DELAY_MAX_MS);
            return;
        }
        if (now < mentee.levelAtMs) {
            return;
        }
        mentee.levelAtMs = 0L;
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(mentor.getId());
        if (runtime == null) {
            return;
        }
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            Character bot = entry.bot();
            if (bot != null && bot.getId() == mentor.getId() && bot.getLevel() < 200) {
                int need = ExpTable.getExpNeededForLevel(bot.getLevel()) - bot.getExp();
                if (need > 0) {
                    bot.gainExp(need, true, false); // the real level-up: its effect, AP and SP
                }
            }
            return null;
        });
    }

    private void tips(Mentee mentee, Character newcomer, Character mentor, long now) {
        if (newcomer.getHp() > 0 && newcomer.getCurrentMaxHp() > 0
                && newcomer.getHp() * 100L < (long) newcomer.getCurrentMaxHp() * world.config().MENTOR_LOW_HP_PERCENT) {
            mentee.tips.reached(MentorTips.Milestone.LOW_HP);
        }
        if (newcomer.getMap() != mentor.getMap()) {
            return; // said where its newcomer can hear it
        }
        MentorTips.Milestone next = mentee.tips.next(now, world.config().MENTOR_TIPS_PER_HOUR);
        if (next == null) {
            return;
        }
        String line = tip(mentee, next, newcomer, mentor);
        mentee.tips.told(next, now);
        world.store().event(mentee.newcomerId, mentee.personaId, TIP_EVENT, next.name());
        if (line != null) {
            partyChat(mentee, mentor, line);
        }
        if ((next == MentorTips.Milestone.LV8 || next == MentorTips.Milestone.VICTORIA) && mentee.pick == null
                && newcomer.isBeginnerJob()) {
            mentee.askJob = true; // a window, so the pick needs no typing
            mentee.jobMenuAtMs = now + GOODBYE_STEP_MS;
        }
    }

    /** The tip's line: authored framing with a ContentAnswers fact where it has one. */
    String tip(Mentee mentee, MentorTips.Milestone milestone, Character newcomer, Character mentor) {
        Map<String, String> values = new HashMap<>(Map.of("name", newcomer.getName(), "me", mentor.getName()));
        String branch = mentee.pick == null ? null : branchWord(mentee.pick);
        return switch (milestone) {
            case LOW_HP -> say(mentee.style, "tip.low_hp", values, newcomer.getHp());
            case LV8 -> say(mentee.style, "tip.lv8", values, newcomer.getId());
            case FIRST_DEATH -> withFact(mentee, "tip.first_death", values,
                    answer("where should i train", newcomer, mentor));
            case LV10 -> MentorRoute.onIsland(newcomer.getMapId())
                    ? say(mentee.style, "tip.lv10.island", values, newcomer.getId())
                    : withFact(mentee, "tip.lv10", values,
                            branch == null ? null : answer("how do i become a " + branch, newcomer, mentor));
            case VICTORIA -> withFact(mentee, "tip.victoria", values, branch != null
                    ? answer("how do i become a " + branch, newcomer, mentor)
                    : answer("where should i train", newcomer, mentor));
        };
    }

    private String withFact(Mentee mentee, String key, Map<String, String> values, String fact) {
        if (fact == null || fact.isBlank()) {
            return say(mentee.style, key + ".plain", values, mentee.newcomerId);
        }
        values.put("fact", fact);
        return say(mentee.style, key, values, mentee.newcomerId);
    }

    /** ContentAnswers' deterministic reply, the newcomer asking and the mentor answering as one of their party. */
    String answer(String question, Character newcomer, Character mentor) {
        try {
            ContentAnswers.Ask ask = new ContentAnswers.Ask(question,
                    new ContentAnswers.Asker(newcomer.getId(), newcomer.getName(), newcomer.getMapId(), newcomer.getLevel(),
                            newcomer.getJob().getId(), newcomer.getWorld()),
                    new ContentAnswers.Responder(mentor.getId(), mentor.getLevel(), "victoria", null, Set.of(), false),
                    null, clock.getAsLong());
            ContentAnswers.ContentAnswer reply = ContentAnswers.shared().explain(ask);
            return reply == null || reply.outcome() != ContentAnswers.Outcome.ANSWERED ? null : reply.reply();
        } catch (RuntimeException failure) {
            log.debug("mentor knowledge answer failed for '{}'", question, failure);
            return null;
        }
    }

    /** The newcomer's pick, without typing: the five branches and "Not sure yet". */
    private void askJob(Mentee mentee, Character newcomer, long now) {
        if (!newcomer.isBeginnerJob() || mentee.pick != null) {
            mentee.askJob = false;
            return;
        }
        if (!LivingTalk.mayShow(newcomer) || newcomer.getEventInstance() != null) {
            mentee.jobMenuAtMs = now + JOB_MENU_RETRY_MS;
            return;
        }
        Character mentor = mentor(mentee);
        String text = say(mentee.style, "job.window", Map.of("name", newcomer.getName(),
                "me", mentor == null ? "" : mentor.getName()), newcomer.getId());
        if (text == null) {
            mentee.askJob = false;
            return;
        }
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Job branch : MentorRoute.BRANCHES) {
            String label = capital(branchWord(branch)) + (branch == Job.MAGICIAN ? " (from Lv 8)" : " (from Lv 10)");
            options.add(new LivingTalk.Option(label, () -> world.executor().submit("mentor.pick " + mentee.newcomerId,
                    () -> picked(mentee, branch))));
        }
        options.add(new LivingTalk.Option("Not sure yet", () -> world.executor().submit("mentor.unsure " + mentee.newcomerId,
                () -> unsure(mentee))));
        LivingTalk.open(newcomer, CompanionMenus.BOARD_NPC).menu(text, options).show();
        mentee.askJob = !LivingTalk.active(newcomer.getClient());
        mentee.jobMenuAtMs = now + JOB_MENU_RETRY_MS;
    }

    void picked(Mentee mentee, Job branch) {
        mentee.pick = branch;
        world.store().event(mentee.newcomerId, mentee.personaId, PICK_EVENT, branch.name());
        Character mentor = mentor(mentee);
        Character newcomer = world.roster().onlineCharacter(mentee.newcomerId);
        if (mentor != null && newcomer != null) {
            partyChat(mentee, mentor, say(mentee.style, "job.picked", Map.of("branch", branchWord(branch),
                    "name", newcomer.getName()), mentee.newcomerId));
        }
    }

    private void unsure(Mentee mentee) {
        Character mentor = mentor(mentee);
        if (mentor != null) {
            partyChat(mentee, mentor, say(mentee.style, "job.unsure", Map.of(), mentee.newcomerId));
        }
    }

    private void heard(Mentee mentee, boolean mute, boolean unmute, Job branch) {
        Character mentor = mentor(mentee);
        if (mute && !mentee.tips.muted()) {
            mentee.tips.muted(true);
            world.store().event(mentee.newcomerId, mentee.personaId, MUTE_EVENT, "on");
            if (mentor != null) {
                partyChat(mentee, mentor, say(mentee.style, "mute.ok", Map.of(), mentee.newcomerId));
            }
        } else if (unmute && mentee.tips.muted()) {
            mentee.tips.muted(false);
            world.store().event(mentee.newcomerId, mentee.personaId, MUTE_EVENT, "off");
            if (mentor != null) {
                partyChat(mentee, mentor, say(mentee.style, "unmute.ok", Map.of(), mentee.newcomerId));
            }
        }
        if (branch != null && branch != mentee.pick) {
            mentee.askJob = false;
            picked(mentee, branch);
        }
    }

    // ---- the lead (companion-ops) -------------------------------------------------------------

    private void lead(Mentee mentee, Character newcomer, Character mentor, long now) {
        if (mentee.walk != null) {
            stepLead(mentee, newcomer, mentor, now);
            return;
        }
        if (newcomer.getMap() != mentor.getMap() || newcomer.getEventInstance() != null || !newcomer.isAlive()
                || world.jobErrands().underway(mentee.personaId)) {
            return;
        }
        Leg leg = nextLeg(mentee, newcomer, mentor);
        if (leg == null || mentee.ledFromMapId == leg.mapId() && mentee.ledAtLevel == newcomer.getLevel()) {
            return; // nowhere to go, or it led here at this level already: it follows until the next reason
        }
        mentee.leg = leg;
        mentee.walk = new MentorLead(leg.lead(), world.config().MENTOR_LEAD_WAIT_MS, world.config().MENTOR_LEAD_TRIES, now);
        mentee.ledFromMapId = leg.mapId();
        mentee.ledAtLevel = newcomer.getLevel();
        leading.add(mentor.getId());
        apply(mentee, newcomer, mentor, mentee.walk.start(), now);
    }

    private void stepLead(Mentee mentee, Character newcomer, Character mentor, long now) {
        Leg leg = mentee.leg;
        MentorLead.View view = view(leg, newcomer, mentor, now);
        MentorLead.Move move = mentee.walk.step(view);
        apply(mentee, newcomer, mentor, move, now);
        if (mentee.walk != null && mentee.walk.over()) {
            stopLead(mentee, mentor);
        }
    }

    /** What the sweep sees of one leg. */
    MentorLead.View view(Leg leg, Character newcomer, Character mentor, long now) {
        boolean atDoor = mentor.getMapId() == leg.mapId() && mentor.getPosition() != null && leg.door() != null
                && mentor.getPosition().distance(leg.door()) <= DOOR_REACH_PX;
        boolean walking = AgentMapWalk.walking(mentor.getId());
        boolean newcomerAcross;
        boolean mentorAcross;
        boolean offPlan;
        switch (leg.kind()) {
            case DECK -> {
                newcomerAcross = AgentLithHarborArrivalRouteRuntime.nextPortalId(newcomer) == null;
                mentorAcross = AgentLithHarborArrivalRouteRuntime.nextPortalId(mentor) == null;
                offPlan = newcomer.getMapId() != leg.mapId();
            }
            case NPC -> {
                newcomerAcross = newcomer.getMapId() != leg.mapId() || leg.advancedEnds() && !newcomer.isBeginnerJob();
                mentorAcross = false;
                offPlan = false;
            }
            default -> {
                newcomerAcross = newcomer.getMapId() == leg.toMapId();
                mentorAcross = mentor.getMapId() == leg.toMapId();
                offPlan = newcomer.getMapId() != leg.mapId() && newcomer.getMapId() != leg.toMapId();
            }
        }
        return new MentorLead.View(now, atDoor, walking, mentorAcross, newcomerAcross, offPlan);
    }

    private void apply(Mentee mentee, Character newcomer, Character mentor, MentorLead.Move move, long now) {
        Leg leg = mentee.leg;
        if (move.line() != null) {
            partyChat(mentee, mentor, say(mentee.style, move.line(), legValues(mentee, newcomer, mentor, leg), now));
        }
        switch (move.act()) {
            case GO_DOOR -> walk(mentor, leg.kind() == LegKind.NPC ? AgentMapWalk.Target.npc(leg.mapId(), leg.npcId())
                    : AgentMapWalk.Target.reach(leg.mapId(), leg.portalId()), now);
            case ENTER -> walk(mentor, AgentMapWalk.Target.enter(leg.mapId(), leg.portalId()), now);
            case COME_BACK -> walk(mentor, AgentMapWalk.Target.enter(leg.toMapId(), leg.backPortalId()), now);
            case FOLLOW -> stopLead(mentee, mentor);
            case NONE -> { }
        }
    }

    private Map<String, String> legValues(Mentee mentee, Character newcomer, Character mentor, Leg leg) {
        Map<String, String> values = new HashMap<>();
        values.put("name", newcomer.getName());
        values.put("me", mentor.getName());
        values.put("level", String.valueOf(MentorRoute.SHANKS_LEVEL));
        Job pick = mentee.pick;
        if (pick != null) {
            values.put("branch", branchWord(pick));
            JobInstructors.Instructor instructor = JobInstructors.of(pick);
            if (instructor != null) {
                values.put("npc", instructor.name());
            }
        }
        values.put("town", townName(pick == null ? MentorRoute.HUB_TOWN : MentorRoute.townOf(pick)));
        return values;
    }

    /** On the mentor's own mailbox: off follow, and the walk. */
    private void walk(Character mentor, AgentMapWalk.Target target, long now) {
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(mentor.getId());
        if (runtime == null) {
            return;
        }
        leading.add(mentor.getId());
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            Character bot = entry.bot();
            if (bot != null && bot.getId() == mentor.getId()) {
                AgentMovementCommandRuntime.stop(runtime);
                AgentMapWalk.start(runtime, bot, target, WALK_HOP_TIMEOUT_MS, now);
            }
            return null;
        });
    }

    /** The lead is over: the walk ends and it follows its newcomer again (the follow brings it to their map). */
    private void stopLead(Mentee mentee, Character mentor) {
        mentee.walk = null;
        mentee.leg = null;
        int mentorId = mentor != null ? mentor.getId() : mentee.mentorId;
        leading.remove(mentorId);
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(mentorId);
        if (runtime == null) {
            return;
        }
        AgentMailboxRuntime.dispatch(runtime, entry -> {
            AgentMapWalk.stopAll(mentorId);
            AgentMovementCommandRuntime.followConfiguredTarget(runtime);
            return null;
        });
    }

    /**
     * Where to lead next from where its newcomer stands, or null to just follow: the island road when the map is
     * outgrown (at once from Mushroom Town); Shanks from level 7 with the fare; the taxi by Lith's dock toward the
     * picked branch's town (Henesys when undecided), or short of the fare off the ship deck by its hidden way and
     * walking there; the instructor's door once the level fits; up to the instructor.
     */
    Leg nextLeg(Mentee mentee, Character newcomer, Character mentor) {
        MapleMap here = newcomer.getMap();
        int map = newcomer.getMapId();
        if (here == null) {
            return null;
        }
        if (MentorRoute.onIsland(map)) {
            if (map == MentorRoute.SOUTHPERRY) {
                if (!MentorRoute.mayBoard(newcomer.getLevel(), newcomer.getMeso(), newcomer.haveItem(MentorRoute.SHANKS_LETTER))) {
                    sayOnce(mentee, mentor, newcomer.getLevel() < MentorRoute.SHANKS_LEVEL ? "lead.shanks.level"
                            : "lead.shanks.fare", newcomer.getLevel());
                    return null;
                }
                return npcLeg(here, MentorRoute.SHANKS, "lead.shanks", false);
            }
            int next = MentorRoute.nextOnIsland(map);
            if (next == 0 || map != MentorRoute.MUSHROOM_TOWN && !ISLAND_TRANSIT.contains(map) && !outgrown(here, newcomer)) {
                return null;
            }
            return portalLeg(newcomer, here, next);
        }
        if (map < 100_000_000 || map >= 200_000_000 || !newcomer.isBeginnerJob()) {
            return null; // off Victoria, or past the first job: it follows
        }
        Job pick = mentee.pick;
        JobInstructors.Instructor instructor = pick == null ? null : JobInstructors.of(pick);
        boolean ready = pick != null && newcomer.getLevel() >= (pick == Job.MAGICIAN ? 8 : 10);
        int goal = instructor != null && ready ? instructor.mapId() : pick == null ? MentorRoute.HUB_TOWN : MentorRoute.townOf(pick);
        if (map == goal) {
            return instructor != null && map == instructor.mapId()
                    ? npcLeg(here, instructor.npcId(), "lead.instructor", true) : null;
        }
        if (map == MentorRoute.LITH_HARBOR && newcomer.getMeso() >= MentorRoute.TAXI_BEGINNER_FARE_MAX) {
            // the cab stands by the dock: a walk down from the ship deck, no portal (MentorRouteWalkTest)
            return npcLeg(here, MentorRoute.LITH_TAXI, "lead.taxi", false);
        }
        if (map == MentorRoute.LITH_HARBOR) {
            Integer deck = AgentLithHarborArrivalRouteRuntime.nextPortalId(newcomer);
            if (deck != null) { // short of the fare, walking: off the deck by its hidden way to the town's far side
                Portal portal = here.getPortal(deck);
                return portal == null ? null : new Leg(LegKind.DECK, map, deck, map, -1, 0, portal.getPosition(),
                        new MentorLead.Leg(false, true, "lead.deck"), false);
            }
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(mentor.getId());
        if (runtime == null) {
            return null;
        }
        List<AgentVictoriaRouteRuntime.PlannedStep> route = AgentVictoriaRouteRuntime.plannedRoute(runtime, map, goal,
                clock.getAsLong());
        AgentVictoriaRouteRuntime.PlannedStep first = route.isEmpty() ? null : route.get(0);
        if (first == null || first.fromMapId() != map || first.ride()) {
            return null;
        }
        if (map == MentorRoute.LITH_HARBOR) {
            sayOnce(mentee, mentor, "lead.walk", newcomer.getLevel());
        }
        return portalLeg(newcomer, here, first.toMapId());
    }

    private Leg portalLeg(Character newcomer, MapleMap here, int toMapId) {
        Portal door = portalTo(here, toMapId);
        if (door == null) {
            return null;
        }
        MapleMap there = channelMap(newcomer, toMapId);
        Portal back = there == null ? null : portalTo(there, here.getId());
        boolean twoWay = back != null && !MentorRoute.oneWayFrom(here.getId());
        return new Leg(LegKind.PORTAL, here.getId(), door.getId(), toMapId, back == null ? -1 : back.getId(), 0,
                door.getPosition(), new MentorLead.Leg(twoWay, false, "lead.oneway"), false);
    }

    private static Leg npcLeg(MapleMap here, int npcId, String line, boolean advancedEnds) {
        NPC npc = here.getNPCById(npcId);
        return npc == null ? null : new Leg(LegKind.NPC, here.getId(), -1, 0, -1, npcId, npc.getPosition(),
                new MentorLead.Leg(false, false, line), advancedEnds);
    }

    /** The visible portal on {@code map} straight to {@code toMapId} (a walking portal, not a script's), or null. */
    static Portal portalTo(MapleMap map, int toMapId) {
        for (Portal portal : map.getPortals()) {
            if (portal.getTargetMapId() == toMapId && portal.getPosition() != null) {
                return portal;
            }
        }
        return null;
    }

    /** True when its newcomer's level is past the map's toughest monster by MENTOR_OUTGROWN_LEVELS. */
    boolean outgrown(MapleMap map, Character newcomer) {
        int top = 0;
        for (SpawnPoint spawn : map.getMonsterSpawn()) {
            top = Math.max(top, LifeFactory.getMonsterLevel(spawn.getMonsterId()));
        }
        return newcomer.getLevel() >= top + world.config().MENTOR_OUTGROWN_LEVELS;
    }

    private MapleMap channelMap(Character newcomer, int mapId) {
        return newcomer.getClient() == null || newcomer.getClient().getChannelServer() == null ? null
                : newcomer.getClient().getChannelServer().getMapFactory().getMap(mapId);
    }

    private void sayOnce(Mentee mentee, Character mentor, String key, int level) {
        if (mentee.saidOnce.add(key + "@" + level)) {
            Character newcomer = world.roster().onlineCharacter(mentee.newcomerId);
            partyChat(mentee, mentor, say(mentee.style, key, Map.of("level", String.valueOf(MentorRoute.SHANKS_LEVEL),
                    "name", newcomer == null ? "" : newcomer.getName(), "town", townName(mentee.pick == null
                            ? MentorRoute.HUB_TOWN : MentorRoute.townOf(mentee.pick))), level));
        }
    }

    // ---- graduation (companion-ops) ---------------------------------------------------------------

    /**
     * Its newcomer's second job (D9), a few seconds apart so it reads as a farewell: the goodbye lines, then the gift
     * dropped in front of them, owner-locked, then (a step later, never in the same tick) the last line and home.
     */
    private void goodbye(Mentee mentee, Character newcomer, Character mentor, long now) {
        if (now < mentee.goodbyeAtMs || newcomer.getMap() != mentor.getMap()) {
            return;
        }
        if (mentee.walk != null) {
            stopLead(mentee, mentor);
        }
        Map<String, String> values = Map.of("name", newcomer.getName(), "me", mentor.getName(),
                "branch", branchWord(newcomer.getJob()));
        List<String> farewell = lines().lines(mentee.style, "goodbye");
        int step = mentee.goodbyeStep++;
        mentee.goodbyeAtMs = now + GOODBYE_STEP_MS;
        if (step < farewell.size()) {
            partyChat(mentee, mentor, MentorLines.fill(farewell.get(step), values));
        } else if (step == farewell.size()) {
            partyChat(mentee, mentor, say(mentee.style, "goodbye.gift", values, mentee.newcomerId));
            dropGift(mentor, newcomer);
        } else {
            partyChat(mentee, mentor, say(mentee.style, "goodbye.last", values, mentee.newcomerId));
            graduate(mentee, newcomer);
        }
    }

    /** MENTOR_GIFT_ITEMS on the ground in front of its newcomer, theirs to pick up (in their bag where drops are barred). */
    void dropGift(Character mentor, Character newcomer) {
        MapleMap map = newcomer.getMap();
        if (map == null || newcomer.getClient() == null) {
            return;
        }
        boolean barred = FieldLimit.DROP_LIMIT.check(map.getFieldLimit());
        for (int[] gift : world.config().mentorGiftItems()) {
            try {
                if (barred) {
                    if (InventoryManipulator.addById(newcomer.getClient(), gift[0], (short) gift[1])) {
                        newcomer.sendPacket(PacketCreator.getShowItemGain(gift[0], (short) gift[1], true));
                    }
                } else {
                    Point at = newcomer.getPosition() == null ? mentor.getPosition() : newcomer.getPosition();
                    map.spawnItemDrop(mentor, newcomer, new Item(gift[0], (short) 0, (short) gift[1]), new Point(at), false, true);
                }
            } catch (RuntimeException failure) {
                log.warn("mentor gift {} for {} failed", gift[0], newcomer.getName(), failure);
            }
        }
    }

    /** Home a friend: warmth 40+ toward its newcomer, with HIRE_FRIENDSHIP_ENABLED its bond at Lv 4+; then let go. */
    void graduate(Mentee mentee, Character newcomer) {
        Population population = Population.get();
        if (population != null && population.memories() != null) {
            population.memories().warmAtLeast(mentee.mentorId, newcomer.getId(), FRIEND_WARMTH);
        }
        Persona persona = world.content().persona(mentee.personaId);
        if (persona != null && HireStanding.on(world) && world.bonds() != null) {
            int friend = world.config().friendshipLevelPoints()[Standing.FRIEND_LEVEL - 1];
            int points = world.bonds().bond(newcomer, persona).points();
            if (friend > points) {
                world.bonds().addPoints(newcomer, persona, friend - points, "mentor graduated");
            }
        }
        world.store().event(newcomer.getId(), mentee.personaId, FirstLogin.GRADUATED, newcomer.getJob().name());
        end(mentee, "mentor graduated", true);
    }

    /** Its newcomer went away too long (MENTOR_ABANDON_DAYS): it goes home quietly. */
    private void abandoned(long now) {
        int days = world.config().MENTOR_ABANDON_DAYS;
        if (days <= 0) {
            return;
        }
        for (Mentee mentee : List.copyOf(mentees.values())) {
            if (world.roster().onlineCharacter(mentee.newcomerId) != null) {
                continue;
            }
            long seen = world.store().lastSeenAtMs(mentee.newcomerId);
            if (seen > 0 && seen != Long.MAX_VALUE && now - seen >= days * DAY_MS) {
                end(mentee, "newcomer away " + days + " days", false);
            }
        }
    }

    /** It goes home: let go through the roster (a walk to the nearest portal on its newcomer's map). */
    private void end(Mentee mentee, String reason, boolean graduated) {
        if (!graduated) {
            world.store().event(mentee.newcomerId, mentee.personaId, FirstLogin.ENDED, reason);
        }
        forget(mentee);
        Persona persona = world.content().persona(mentee.personaId);
        if (persona != null) {
            world.roster().release(mentee.newcomerId, persona, reason);
        }
    }

    /** HiredHands.onReleased: its row went (its newcomer let it go, its newcomer was deleted, or the end above). */
    public void released(int ownerId, CompanionRoles.Held held, String reason) {
        if (held.role() != CompanionRoles.Role.MENTOR) {
            return;
        }
        Mentee mentee = mentees.get(ownerId);
        if (mentee != null && mentee.personaId.equals(held.personaId())) {
            if (!reason.startsWith("mentor graduated") && !reason.startsWith("newcomer away")
                    && !reason.startsWith("mentors switched off")) {
                world.store().event(ownerId, held.personaId(), FirstLogin.ENDED, reason);
            }
            forget(mentee);
        }
    }

    private void forget(Mentee mentee) {
        mentees.remove(mentee.newcomerId, mentee);
        mentorIds.remove(mentee.mentorId);
        leading.remove(mentee.mentorId);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Character mentor(Mentee mentee) {
        RosterEntry row = world.roster().entry(mentee.newcomerId, mentee.personaId);
        return row == null ? null : world.roster().companion(row);
    }

    private void partyChat(Mentee mentee, Character mentor, String line) {
        if (line == null || line.isBlank() || mentor == null) {
            return;
        }
        String typed = world.hiredHands() == null ? line : world.hiredHands().voice(mentee.mentorId, line);
        try {
            if (mentor.getParty() == null || !AgentPartyGatewayRuntime.party().sendPartyChat(mentor, typed)) {
                Persona persona = world.content().persona(mentee.personaId);
                Character newcomer = world.roster().onlineCharacter(mentee.newcomerId);
                if (persona != null && newcomer != null) {
                    world.residents().whisper(persona, newcomer, typed);
                }
            }
        } catch (RuntimeException failure) {
            log.debug("mentor line for {} failed", mentee.newcomerId, failure);
        }
    }

    private String say(String style, String key, Map<String, String> values, long seed) {
        return lines().line(style, key, values, (int) (seed ^ (seed >>> 32)));
    }

    static String branchWord(Job job) {
        if (job == null) {
            return "";
        }
        Job first = job.getJobTier() <= 1 ? job : Job.getById(job.getId() / 100 * 100);
        return switch (first == null ? job : first) {
            case WARRIOR -> "warrior";
            case MAGICIAN -> "magician";
            case BOWMAN -> "bowman";
            case THIEF -> "thief";
            case PIRATE -> "pirate";
            default -> job.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    private static String capital(String word) {
        return word.isEmpty() ? word : java.lang.Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String townName(int mapId) {
        return switch (mapId) {
            case 102_000_000 -> "perion";
            case 101_000_000 -> "ellinia";
            case 100_000_000 -> "henesys";
            case 103_000_000 -> "kerning city";
            case 120_000_000 -> "nautilus";
            default -> "town";
        };
    }

    /** {@code !mentor <player>} (GM): the mentorship as it stands, or why there is none. */
    public String status(int characterId, String name) {
        Mentee mentee = mentees.get(characterId);
        if (mentee == null) {
            Optional<EventRow> latest = world.store().latestCharacterEvent(characterId, FirstLogin.KINDS);
            return name + ": no mentor out (newest mentor row: "
                    + (latest == null ? "read failed" : latest.map(row -> row.kind() + " " + row.detail()).orElse("none"))
                    + ", switch " + (on() ? "on" : "off") + ")";
        }
        Character mentor = mentor(mentee);
        StringBuilder out = new StringBuilder(name).append(": mentor ").append(mentee.personaId)
                .append(mentor == null ? " (not with them)" : " " + mentor.getName() + " lv" + mentor.getLevel())
                .append(", lead ").append(mentee.lead).append(", style ").append(mentee.style)
                .append(", pick ").append(mentee.pick == null ? "none" : mentee.pick.name())
                .append(", tips").append(mentee.tips.muted() ? " muted" : "").append(" told:");
        for (MentorTips.Milestone milestone : MentorTips.Milestone.values()) {
            if (mentee.tips.wasTold(milestone)) {
                out.append(' ').append(milestone.name());
            }
        }
        if (mentee.walk != null && mentee.leg != null) {
            out.append(", leading ").append(mentee.leg.kind()).append(" on ").append(mentee.leg.mapId())
                    .append(mentee.leg.toMapId() > 0 ? " to " + mentee.leg.toMapId() : "");
        }
        if (mentee.goodbyeAtMs > 0) {
            out.append(", saying goodbye");
        }
        return out.toString();
    }

    /** {@code !mentor offer <player>} (GM, test characters): the once-only gate opens again at their next login. */
    public void rearm(int characterId) {
        world.store().event(characterId, null, FirstLogin.PENDING, "re-armed by a GM");
    }

    Mentee mentee(int newcomerId) {
        return mentees.get(newcomerId);
    }

    void put(Mentee mentee) {
        mentees.put(mentee.newcomerId, mentee);
        mentorIds.add(mentee.mentorId);
    }
}
