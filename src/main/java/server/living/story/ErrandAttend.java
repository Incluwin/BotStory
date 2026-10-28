package server.living.story;

import client.Character;
import client.Job;
import constants.game.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Story;
import server.living.mentor.MentorLead;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentDialogue;
import server.living.roster.JobErrandLines;
import server.living.roster.JobInstructors;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Attended job errands (docs/BOT_CONCEPT_PLAN.md F6.3). Before its 3rd or 4th job a companion asks its owner, in a
 * yes/no window, to come along (JobErrands.leave hands it over through {@link #offer}). Yes: it stays in the party and
 * follows them to the instructor's town, ships included, saying once per town where the way goes on; there (El Nath,
 * Leafre, Ereve, Rien) it leads the last stretch one leg at a time with the mentor's lead ({@link MentorLead}: two-way
 * portals it goes through first, Leafre's {@code in00} gate its owner takes first), walks up to its instructor, says its
 * line, advances in front of them (JobErrands' own job change, the stock job-advance effect) and thanks them; at
 * friendship Lv 9 its story's last chapter follows. No, no answer, not in town within JOB_ERRAND_ATTEND_MAX_MINUTES, a
 * lead its owner did not follow, a refused job change or the kill switch: it goes alone as before ({@link #alone}).
 * Memory only: a restart forgets it, and the companion asks again the next time it is due. Runs on companion-ops.
 */
public final class ErrandAttend {
    private static final Logger log = LoggerFactory.getLogger(ErrandAttend.class);
    static final int EL_NATH = 211000000;
    static final int CHIEFS_RESIDENCE = 211000001;
    static final int LEAFRE = 240000000;
    static final int FOREST_GATE = 240010500;
    static final int FOREST_OF_THE_PRIEST = 240010501;
    static final String FOREST_GATE_PORTAL = "in00";
    static final int ORBIS = 200000000;
    /** A closed window with no answer: it waits this long for a click already on its way, then takes it as no. */
    static final long CLOSED_GRACE_MS = 2000L;
    /** Within this of a door or its instructor it is there. */
    static final double REACH_PX = 110;
    /** After the job change, before its thanks: the stock effect plays 777 ms after the change. */
    static final long AFTER_MS = 2500L;
    /** Victoria's towns: from any of them the way to Ossyria is the boat at Ellinia station. */
    static final Set<Integer> VICTORIA_TOWNS = Set.of(100000000, 101000000, 102000000, 103000000, 104000000, 105040300,
            120000000);

    enum Phase { ASKING, FOLLOWING, LEADING, AT_INSTRUCTOR }

    /** One leg of the lead: through {@code portalId} on {@code mapId} to {@code toMapId}, waiting at {@code door}. */
    record Leg(int mapId, int portalId, int toMapId, int backPortalId, Point door, MentorLead.Leg lead) { }

    /** A companion's attended errand, from the ask to the thanks. */
    static final class Attend {
        final String personaId;
        final int ownerId;
        final int botId;
        final Job target;
        final JobInstructors.Instructor instructor;
        final long startedAtMs;
        volatile Phase phase = Phase.ASKING;
        /** The window's answer, set on the owner's I/O thread; null while none came. */
        volatile Boolean answer;
        boolean shown;
        int asks;
        long nextAskAtMs;
        long closedSeenAtMs;
        Leg leg;
        MentorLead walk;
        final Set<Integer> hinted = new HashSet<>();
        int visit;
        long visitAtMs;

        Attend(String personaId, int ownerId, int botId, Job target, JobInstructors.Instructor instructor, long now) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.botId = botId;
            this.target = target;
            this.instructor = instructor;
            this.startedAtMs = now;
            this.nextAskAtMs = now;
        }

        int tier() {
            return GameConstants.getJobBranch(target);
        }
    }

    private final LivingWorld world;
    private final ErrandHands hands;
    private final LongSupplier clock;
    private final Map<String, Attend> attends = new ConcurrentHashMap<>();
    /** Personas whose next errand goes alone, unasked (their owner said no, or the attend fell through). */
    private final Set<String> alone = ConcurrentHashMap.newKeySet();
    /** Companions walking ahead of their owner: off follow (CompanionFollowGuard). */
    private final Set<Integer> leading = ConcurrentHashMap.newKeySet();

    ErrandAttend(LivingWorld world) {
        this(world, ErrandHands.LIVE, System::currentTimeMillis);
    }

    ErrandAttend(LivingWorld world, ErrandHands hands, LongSupplier clock) {
        this.world = world;
        this.hands = hands;
        this.clock = clock;
    }

    boolean on() {
        return world.config().JOB_ERRAND_ATTEND_ENABLED;
    }

    boolean any() {
        return !attends.isEmpty();
    }

    Attend attend(String personaId) {
        return attends.get(personaId);
    }

    /** True while the companion walks ahead of its owner (from memory, on any thread): it is kept off follow. */
    public boolean leading(int agentCharacterId) {
        return !leading.isEmpty() && leading.contains(agentCharacterId);
    }

    /** {@code !story}: this owner's attended errands now, one line each. */
    List<String> report(int ownerId) {
        List<String> out = new java.util.ArrayList<>();
        for (Attend attend : attends.values()) {
            if (attend.ownerId == ownerId) {
                out.add("attended errand: " + attend.personaId + " for " + attend.target + " from " + attend.instructor.name()
                        + ", " + attend.phase + (attend.leg == null ? "" : " leg " + attend.leg.mapId() + " -> "
                        + attend.leg.toMapId()) + (leading(attend.botId) ? ", leading" : ""));
            }
        }
        return out;
    }

    // ---- the seam: JobErrands.leave (companion-ops) --------------------------------------------

    /**
     * A companion out with its owner is due its {@code step} from {@code instructor}: true when this errand is
     * attended (asked now, or already under way), so JobErrands sends it nowhere; false for today's errand alone.
     */
    public boolean offer(Character owner, Persona persona, Character bot, Job step, JobInstructors.Instructor instructor) {
        if (attends.containsKey(persona.id())) {
            return true;
        }
        if (!on() || GameConstants.getJobBranch(step) < 3 || alone.remove(persona.id())) {
            return false;
        }
        Attend attend = new Attend(persona.id(), owner.getId(), bot.getId(), step, instructor, clock.getAsLong());
        attends.put(persona.id(), attend);
        world.store().event(owner.getId(), persona.id(), "job.attend.ask", step + " from " + instructor.name());
        ask(attend, owner, persona, clock.getAsLong());
        return true;
    }

    // ---- the sweep (companion-ops) --------------------------------------------------------------

    void tick() {
        long now = clock.getAsLong();
        for (Attend attend : List.copyOf(attends.values())) {
            try {
                step(attend, now);
            } catch (RuntimeException failure) {
                log.warn("attended errand of {} failed; it goes alone", attend.personaId, failure);
                alone(attend, null, "failure");
            }
        }
    }

    private void step(Attend attend, long now) {
        if (!on()) {
            alone(attend, null, "switched off");
            return;
        }
        Character owner = world.roster().onlineCharacter(attend.ownerId);
        if (owner == null) {
            alone(attend, null, "its owner logged out"); // the next errand, once they are back, goes as before
            return;
        }
        RosterEntry row = world.roster().entry(attend.ownerId, attend.personaId);
        Character bot = row == null || row.state() != RosterState.ACTIVE ? null : world.roster().companion(row);
        Persona persona = world.content().persona(attend.personaId);
        if (row == null || row.state() != RosterState.ACTIVE || persona == null) {
            drop(attend, null, "no longer out with its owner"); // benched or released: the bench's errand, if any
            return;
        }
        if (bot == null) {
            return; // loading: the next sweep
        }
        if (bot.getJob() == attend.target && !(attend.phase == Phase.AT_INSTRUCTOR && attend.visit >= 3)) {
            drop(attend, bot, "advanced meanwhile"); // its owner's word in chat, a GM set; not its own change here
            return;
        }
        switch (attend.phase) {
            case ASKING -> ask(attend, owner, persona, now);
            case FOLLOWING -> follow(attend, owner, bot, persona, now);
            case LEADING -> lead(attend, owner, bot, persona, now);
            case AT_INSTRUCTOR -> visit(attend, owner, bot, persona, now);
        }
    }

    /** The yes/no, once no window waits; an answer, or a window closed without one (a no). */
    private void ask(Attend attend, Character owner, Persona persona, long now) {
        if (attend.answer != null) {
            if (attend.answer) {
                accepted(attend, owner);
            } else {
                alone(attend, null, "its owner said no");
            }
            return;
        }
        if (attend.shown) {
            if (LivingTalk.active(owner.getClient())) {
                attend.closedSeenAtMs = 0L;
            } else if (attend.closedSeenAtMs == 0L) {
                attend.closedSeenAtMs = now;
            } else if (now - attend.closedSeenAtMs >= CLOSED_GRACE_MS) {
                alone(attend, null, "the window closed unanswered");
            }
            return;
        }
        if (now < attend.nextAskAtMs) {
            return;
        }
        if (owner.getEventInstance() == null && LivingTalk.mayShow(owner)) {
            String text = line(persona, attend, "ask", AttendLines.ASK);
            LivingTalk.open(owner, ResidentDialogue.portraitOf(persona))
                    .yesNo(text, () -> attend.answer = Boolean.TRUE, () -> attend.answer = Boolean.FALSE).show();
            attend.shown = LivingTalk.active(owner.getClient());
        }
        if (!attend.shown) {
            if (++attend.asks >= world.config().JOB_ERRAND_ATTEND_ASK_TRIES) {
                alone(attend, null, "no moment to ask");
            } else {
                attend.nextAskAtMs = now + world.config().JOB_ERRAND_ATTEND_ASK_RETRY_MS;
            }
        }
    }

    private void accepted(Attend attend, Character owner) {
        attend.phase = Phase.FOLLOWING;
        RosterEntry row = world.roster().entry(attend.ownerId, attend.personaId);
        Character bot = row == null ? null : world.roster().companion(row);
        if (bot != null) {
            world.roster().sayInParty(bot, fill(AttendLines.YES.get(Math.floorMod(owner.getId(), AttendLines.YES.size())),
                    attend));
        }
        world.store().event(attend.ownerId, attend.personaId, "job.attend", attend.target + " from "
                + attend.instructor.name() + " at map " + attend.instructor.mapId());
    }

    /** Following its owner to the instructor's town; there, on one map with them, the lead starts. */
    private void follow(Attend attend, Character owner, Character bot, Persona persona, long now) {
        if (now - attend.startedAtMs > world.config().JOB_ERRAND_ATTEND_MAX_MINUTES * 60_000L) {
            alone(attend, bot, "not in " + attend.instructor.town() + " in time");
            return;
        }
        int map = owner.getMapId();
        if (bot.getMap() != owner.getMap() || owner.getEventInstance() != null) {
            return;
        }
        if (inRegion(attend.instructor.mapId(), map)) {
            attend.phase = Phase.LEADING;
            lead(attend, owner, bot, persona, now);
            return;
        }
        String hint = hint(attend.tier(), map);
        if (hint != null && attend.hinted.add(map)) {
            world.roster().sayInParty(bot, hint);
        }
    }

    /** The last stretch, a leg at a time; at its instructor's map it walks up to them. */
    private void lead(Attend attend, Character owner, Character bot, Persona persona, long now) {
        if (attend.walk != null) {
            MentorLead.View view = view(attend.leg, owner, bot, now);
            MentorLead.Move move = attend.walk.step(view);
            apply(attend, bot, move);
            if (attend.walk.over()) {
                boolean offPlan = view.newcomerOffPlan();
                boolean gaveUp = attend.walk.gaveUp();
                attend.walk = null;
                attend.leg = null;
                stopWalking(attend, bot);
                if (offPlan) {
                    attend.phase = Phase.FOLLOWING; // they went their own way: it follows, and leads again in town
                } else if (gaveUp) {
                    alone(attend, bot, "its owner did not follow the lead");
                }
            }
            return;
        }
        if (!inRegion(attend.instructor.mapId(), owner.getMapId())) {
            attend.phase = Phase.FOLLOWING;
            return;
        }
        if (bot.getMap() != owner.getMap()) {
            return; // the follow brings it across
        }
        MapleMap here = owner.getMap();
        if (here.getId() == attend.instructor.mapId()) {
            attend.phase = Phase.AT_INSTRUCTOR;
            attend.visit = 0;
            visit(attend, owner, bot, persona, now);
            return;
        }
        Leg leg = nextLeg(attend, owner, bot, here);
        if (leg == null) {
            alone(attend, bot, "no way on from map " + here.getId());
            return;
        }
        attend.leg = leg;
        attend.walk = new MentorLead(leg.lead(), world.config().MENTOR_LEAD_WAIT_MS, world.config().MENTOR_LEAD_TRIES, now);
        apply(attend, bot, attend.walk.start());
    }

    Leg nextLeg(Attend attend, Character owner, Character bot, MapleMap here) {
        int goal = attend.instructor.mapId();
        if (goal == FOREST_OF_THE_PRIEST) {
            if (here.getId() == FOREST_GATE) {
                return hands.gateLeg(here, FOREST_GATE_PORTAL, FOREST_OF_THE_PRIEST);
            }
            goal = FOREST_GATE; // the forest itself is behind a script portal no route walks
        }
        int hop = hands.nextHop(bot, here.getId(), goal);
        return hop == 0 ? null : hands.portalLeg(owner, here, hop);
    }

    /** What the lead sees of one leg this second (Mentorship.view's portal case). */
    MentorLead.View view(Leg leg, Character owner, Character bot, long now) {
        Point at = bot.getPosition();
        boolean atDoor = bot.getMapId() == leg.mapId() && at != null && leg.door() != null && at.distance(leg.door()) <= REACH_PX;
        boolean ownerAcross = owner.getMapId() == leg.toMapId();
        boolean offPlan = owner.getMapId() != leg.mapId() && !ownerAcross;
        return new MentorLead.View(now, atDoor, hands.walking(bot.getId()), bot.getMapId() == leg.toMapId(), ownerAcross,
                offPlan);
    }

    private void apply(Attend attend, Character bot, MentorLead.Move move) {
        Leg leg = attend.leg;
        if (move.line() != null) {
            String line = AttendLines.LEAD.get(move.line());
            if (line != null) {
                world.roster().sayInParty(bot, line);
            }
        }
        switch (move.act()) {
            case GO_DOOR -> walk(attend, bot, AgentMapWalk.Target.reach(leg.mapId(), leg.portalId()));
            case ENTER -> walk(attend, bot, AgentMapWalk.Target.enter(leg.mapId(), leg.portalId()));
            case COME_BACK -> walk(attend, bot, AgentMapWalk.Target.enter(leg.toMapId(), leg.backPortalId()));
            case FOLLOW, NONE -> { }
        }
    }

    /**
     * At its instructor: it walks up to them (its owner on the map), says its line, advances in front of them, then
     * thanks them and follows again; at friendship Lv 9 its story's last chapter is offered.
     */
    private void visit(Attend attend, Character owner, Character bot, Persona persona, long now) {
        int mapId = attend.instructor.mapId();
        if (owner.getMapId() != mapId || bot.getMapId() != mapId) {
            if (!inRegion(mapId, owner.getMapId())) {
                stopWalking(attend, bot);
                attend.phase = Phase.FOLLOWING;
            }
            return;
        }
        switch (attend.visit) {
            case 0 -> {
                walk(attend, bot, AgentMapWalk.Target.npc(mapId, attend.instructor.npcId()));
                attend.visitAtMs = now;
                attend.visit = 1;
            }
            case 1 -> {
                Point npc = hands.npcAt(bot.getMap(), attend.instructor.npcId());
                Point at = bot.getPosition();
                boolean there = npc == null || at != null && at.distance(npc) <= REACH_PX;
                if (!there && now - attend.visitAtMs < world.config().COMPANION_JOB_ERRAND_WALK_MAX_MS) {
                    return;
                }
                hands.say(bot, line(persona, attend, "arrive", AttendLines.ARRIVE));
                attend.visitAtMs = now + world.config().COMPANION_JOB_ERRAND_TALK_MS;
                attend.visit = 2;
            }
            case 2 -> {
                if (now < attend.visitAtMs) {
                    return;
                }
                if (!world.jobErrands().advanceAttended(bot, attend.personaId, attend.ownerId, attend.target,
                        attend.instructor)) {
                    drop(attend, bot, "the job change was refused");
                    return;
                }
                attend.visitAtMs = now + AFTER_MS;
                attend.visit = 3;
            }
            default -> {
                if (now < attend.visitAtMs) {
                    return;
                }
                hands.say(bot, line(persona, attend, "after", AttendLines.AFTER));
                attends.remove(attend.personaId, attend);
                stopWalking(attend, bot);
                world.store().event(attend.ownerId, attend.personaId, "job.attend.done", attend.target.name());
                world.stories().capstoneAfterErrand(owner, persona);
            }
        }
    }

    // ---- endings --------------------------------------------------------------------------------

    /** It goes alone, as before: JobErrands sends it at its next due check, unasked; {@code line} first, if any. */
    void alone(Attend attend, Character bot, String why) {
        if (!attends.remove(attend.personaId, attend)) {
            return;
        }
        release(attend, bot);
        if (bot != null && attend.phase != Phase.ASKING) {
            world.roster().sayInParty(bot, AttendLines.AHEAD.get(Math.floorMod(attend.ownerId, AttendLines.AHEAD.size())));
        }
        alone.add(attend.personaId);
        world.store().event(attend.ownerId, attend.personaId, "job.attend.end", "alone: " + why);
        if (world.jobErrands() != null) {
            world.jobErrands().due(attend.botId, attend.target); // queued on companion-ops: leave() sends it off
        }
    }

    /** Over without an errand (advanced meanwhile, benched, released, refused). */
    private void drop(Attend attend, Character bot, String why) {
        if (attends.remove(attend.personaId, attend)) {
            release(attend, bot);
            world.store().event(attend.ownerId, attend.personaId, "job.attend.end", why);
        }
    }

    /** Off the lead for good: kept off follow no more, and (still loaded) following its owner again. */
    private void release(Attend attend, Character bot) {
        if (!leading.remove(attend.botId)) {
            return;
        }
        Character chr = bot;
        if (chr == null) {
            RosterEntry row = world.roster().entry(attend.ownerId, attend.personaId);
            chr = row == null ? null : world.roster().companion(row);
        }
        if (chr != null) {
            hands.follow(chr);
        }
    }

    private void walk(Attend attend, Character bot, AgentMapWalk.Target target) {
        leading.add(bot.getId());
        hands.walk(bot, target);
    }

    private void stopWalking(Attend attend, Character bot) {
        if (leading.remove(bot.getId())) {
            hands.follow(bot);
        }
    }

    // ---- where and what -------------------------------------------------------------------------

    /** The instructor's town, where it leads: El Nath for the Chief's Residence, Leafre for the Forest of the Priest. */
    static boolean inRegion(int instructorMapId, int mapId) {
        return mapId == instructorMapId
                || instructorMapId == CHIEFS_RESIDENCE && mapId == EL_NATH
                || instructorMapId == FOREST_OF_THE_PRIEST && mapId >= LEAFRE && mapId <= FOREST_OF_THE_PRIEST;
    }

    /** Where the way goes on from this town, said once per town on the way; null where there is nothing to say. */
    static String hint(int tier, int mapId) {
        if (VICTORIA_TOWNS.contains(mapId)) {
            return AttendLines.VICTORIA;
        }
        if (mapId == ORBIS) {
            return tier >= 4 ? AttendLines.ORBIS_TO_LEAFRE : AttendLines.ORBIS_TO_EL_NATH;
        }
        return null;
    }

    /** Its story's line for this tier ("errand.attend.ask.3") when it has one, else a stock one; filled in. */
    private String line(Persona persona, Attend attend, String what, List<String> stock) {
        Story story = world.content().storyOf(persona.id());
        List<String> own = story == null ? List.of() : story.lines("errand.attend." + what + "." + attend.tier());
        List<String> lines = own.isEmpty() ? stock : own;
        return fill(lines.get(Math.floorMod(attend.ownerId, lines.size())), attend);
    }

    private static String fill(String line, Attend attend) {
        return JobErrandLines.fill(line, attend.target, attend.instructor);
    }
}
