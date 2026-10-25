package server.agents.capabilities.expedition.gpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.bySlot;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.dropAt;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.pickUpNearest;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitByPortal;

/**
 * Stage 2, the Longinus spears (990000400 Hall of the Knight and its rooms, GPQ plan G3). Four sealed spears break
 * into a Longinus Spear each (one carried at a time, slotMax 1): three at the top of the Room of Glory (410) and one in
 * the Room of Pledge (431), behind the Room of Faith's metal gate. The four altars in the Room of Justice (440) each
 * take one; at the fourth the spear gate opens (speargate state 4) and next00 leads on. On the way:
 * <ul>
 * <li>The stone gate (400, in02 to 430) wants the Key the Master Muscle Stone drops in the Room of Courage (420):
 * the key team (up to three fighters) clears the room, one of them picks the Key up and lays it on the stone gate.</li>
 * <li>In the Room of Faith the debris (30 hits) drops the Rusty Key; the metal gate at the far end of the upper
 * corridor wants it, across 19 invincible Dark Muscle Stones, so a thief with Dark Sight (the shade) carries it,
 * then breaks the Room of Pledge's spear and brings it back the same way. With no shade the debris is left alone: its
 * key would only lie there until it expired, and that spear is the leader's.</li>
 * <li>Climbers break the Room of Glory's spears one each and pick them up at once (drops there last 180 s).</li>
 * <li>Each carrier takes the altar it reaches, the hard ones first: spear1 and spear4 sit on towers only Teleport or
 * Flash Jump climbs (AgentGpqMovementSkillPolicy), spear2 on a jump stair hasted members climb, spear3 at the top of
 * the hidden-portal maze anyone walks. A climber's altar is chosen before it climbs (claim "altar:spearN"), so the
 * spears go where they can be laid.</li>
 * </ul>
 * Everyone else waits in the hall by next00. The plan is worked out once per tick from what the party knows (the gates,
 * the altars, who carries what) and each map's play acts its own members' part.
 */
final class AgentGpqSpearPlays {
    static final int HALL = Stage.HALL.mapId();
    static final int GLORY = Stage.GLORY.mapId();
    static final int COURAGE = Stage.COURAGE.mapId();
    static final int FAITH = Stage.FAITH.mapId();
    static final int PLEDGE = Stage.PLEDGE.mapId();
    static final int JUSTICE = Stage.JUSTICE.mapId();
    static final int KEY = 4_001_026;
    static final int RUSTY_KEY = 4_001_037;
    static final int SPEAR = 4_001_025;
    static final int SEALED_SPEAR = 9_202_001;
    static final int DEBRIS = 9_202_002;
    static final Set<Integer> COURAGE_MOBS = Set.of(9_300_019, 9_300_020);
    static final int MASTER_MUSCLE_STONE = 9_300_019;
    static final int KEY_TEAM_SIZE = 3;
    static final int DEBRIS_TEAM_SIZE = 3;
    /** Where the Key is laid: on the y -515 ledge inside the stone gate's box (x -840..-691, y -560..-500). */
    static final Point STONEGATE_STAND = new Point(-767, -515);
    static final Rectangle STONEGATE_BOX = new Rectangle(-840, -560, 149, 60);
    /** Where the Rusty Key is laid: on the corridor floor inside the metal gate's box (x 854..1003, y -380..-320). */
    static final Point METALGATE_STAND = new Point(927, -334);
    static final Rectangle METALGATE_BOX = new Rectangle(854, -380, 149, 60);
    /** Above this height in the Room of Faith a member walks the stones' corridor: the shade keeps Dark Sight up. */
    static final int CORRIDOR_Y = -200;
    /** The altars, the hard ones first, and where a carrier stands on each tower top to lay its spear (+-20 px). */
    static final Map<String, Point> ALTARS = altars();
    static final int SEEK_PX = 400;

    /** A member's part in the stage this tick. */
    enum Job {
        /** The gate is open: on to the Middle Path. */
        DONE,
        /** Clear the Room of Courage and pick up the Key. */
        KEY_TEAM,
        /** Lay the Key on the stone gate. */
        KEY_CARRIER,
        /** Break the Room of Faith's debris. */
        DEBRIS,
        /** The shade with the Rusty Key: lay it on the metal gate. */
        RUSTY_KEY,
        /** The shade: break the Room of Pledge's spear. */
        PLEDGE_SPEAR,
        /** Climb the Room of Glory and break one of its spears. */
        GLORY_SPEAR,
        /** Carry a spear to its altar. */
        CARRY,
        /** Nothing for it now: wait in the hall. */
        WAIT
    }

    /** The stage's plan for one tick. */
    record Plan(long atMs, Map<Integer, Job> jobs) {
        Job jobOf(int memberId) {
            return jobs.getOrDefault(memberId, Job.WAIT);
        }
    }

    private AgentGpqSpearPlays() {
    }

    private static Map<String, Point> altars() {
        Map<String, Point> altars = new LinkedHashMap<>();
        altars.put("spear1", new Point(-2393, -1044));
        altars.put("spear4", new Point(1571, -1042));
        altars.put("spear2", new Point(-1399, -1043));
        altars.put("spear3", new Point(582, -1042));
        return altars;
    }

    static void tick(AgentGpqPorts ports, Stage stage, List<Character> members, EventInstanceManager event,
                     AgentGpqRun run, long nowMs) {
        Plan plan = plan(ports, event, run, nowMs);
        for (Character member : bySlot(members, run)) {
            Job job = plan.jobOf(member.getId());
            noteRole(run, member, "spears", AgentGpqMoves.lower(job).replace('_', ' '));
            play(ports, stage, member, job, event, run, nowMs);
        }
    }

    // ---- the plan ----

    /** This tick's plan, worked out once for every map of the stage. */
    static Plan plan(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, long nowMs) {
        Plan cached = run.spearPlan();
        if (cached != null && cached.atMs() == nowMs) {
            return cached;
        }
        Plan made = makePlan(ports, event, run, nowMs);
        run.spearPlan(made);
        return made;
    }

    static Plan makePlan(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, long nowMs) {
        Map<Integer, Job> jobs = new HashMap<>();
        List<Character> crew = new ArrayList<>();
        for (int id : run.adopted()) {
            Character member = ports.member(id);
            Stage at = member == null ? null : Stage.of(member.getMapId());
            if (member != null && member.isAlive() && at != null && at.spearStage()) {
                crew.add(member);
            }
        }
        if (state(ports, event, HALL, "speargate") >= 4) {
            crew.forEach(member -> jobs.put(member.getId(), Job.DONE));
            return new Plan(nowMs, jobs);
        }
        boolean stonegateOpen = state(ports, event, HALL, "stonegate") >= 1;
        boolean metalgateOpen = state(ports, event, FAITH, "metalgate") >= 1;
        Character shade = crew.stream().filter(AgentGpqRoles::shade).findFirst().orElse(null);
        List<Character> free = new ArrayList<>();
        for (Character member : crew) {
            if (ports.carried(member, SPEAR) > 0) {
                jobs.put(member.getId(), Job.CARRY);
            } else if (ports.carried(member, KEY) > 0) {
                jobs.put(member.getId(), Job.KEY_CARRIER);
            } else if (ports.carried(member, RUSTY_KEY) > 0) {
                jobs.put(member.getId(), Job.RUSTY_KEY);
            } else {
                free.add(member);
            }
        }
        boolean keyHeld = jobs.containsValue(Job.KEY_CARRIER);
        if (!stonegateOpen && !keyHeld) {
            int team = 0;
            for (Character member : List.copyOf(free)) {
                if (team < KEY_TEAM_SIZE && !AgentGpqRoles.junior(member) && member != shade) {
                    jobs.put(member.getId(), Job.KEY_TEAM);
                    free.remove(member);
                    team++;
                }
            }
        }
        boolean rustyHeld = jobs.containsValue(Job.RUSTY_KEY);
        boolean pledgeLeft = !ports.liveReactorsOn(event, PLEDGE, SEALED_SPEAR).isEmpty();
        if (shade != null && free.contains(shade)) {
            if (stonegateOpen && !metalgateOpen && !rustyHeld) {
                jobs.put(shade.getId(), Job.DEBRIS);
                free.remove(shade);
                int team = 1;
                for (Character member : List.copyOf(free)) {
                    if (team < DEBRIS_TEAM_SIZE && !AgentGpqRoles.junior(member)) {
                        jobs.put(member.getId(), Job.DEBRIS);
                        free.remove(member);
                        team++;
                    }
                }
            } else if (metalgateOpen && pledgeLeft) {
                jobs.put(shade.getId(), Job.PLEDGE_SPEAR);
                free.remove(shade);
            }
        } else if (shade == null && stonegateOpen && !metalgateOpen && run.once("no-shade")) {
            crew.stream().findFirst().ifPresent(member -> ports.sayInParty(member, AgentGpqLines.NO_SHADE));
        }
        // the pledge spear's altar is the shade's, chosen before it is broken, so the climbers leave it
        if (shade != null && (pledgeLeft || jobs.get(shade.getId()) == Job.CARRY)) {
            claimAltar(ports, event, run, shade);
        }
        List<Reactor> glorySpears = ports.liveReactorsOn(event, GLORY, SEALED_SPEAR);
        free.sort((a, b) -> Boolean.compare(AgentGpqRoles.skilledClimber(b), AgentGpqRoles.skilledClimber(a)));
        boolean shadeKept = shade != null && pledgeLeft;
        for (Character member : free) {
            boolean spearLeft = glorySpears.stream().anyMatch(spear -> run.holder("glory:" + spear.getObjectId()) == 0
                    || run.holder("glory:" + spear.getObjectId()) == member.getId());
            boolean kept = shadeKept && member == shade; // its altar waits for the pledge spear
            if (spearLeft && !kept && !AgentGpqRoles.junior(member) && claimAltar(ports, event, run, member) != null) {
                jobs.put(member.getId(), Job.GLORY_SPEAR);
            } else {
                jobs.put(member.getId(), Job.WAIT);
                if (!kept) {
                    run.releaseAll(member.getId(), "altar:");
                }
            }
        }
        if (crew.stream().noneMatch(AgentGpqRoles::skilledClimber) && (state(ports, event, JUSTICE, "spear1") < 1
                || state(ports, event, JUSTICE, "spear4") < 1) && !crew.isEmpty() && run.once("no-climber")) {
            ports.sayInParty(crew.getFirst(), AgentGpqLines.NO_CLIMBER);
        }
        return new Plan(nowMs, jobs);
    }

    /**
     * The altar {@code member} lays its spear on: the one it holds while still empty, else the first empty altar no
     * other member holds that it reaches (spear1, spear4, spear2, spear3); null when none is left for it.
     */
    static String claimAltar(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, Character member) {
        for (String held : run.claimsOf(member.getId(), "altar:")) {
            String altar = held.substring("altar:".length());
            if (state(ports, event, JUSTICE, altar) < 1) {
                return altar;
            }
            run.release(held);
        }
        for (String altar : ALTARS.keySet()) {
            if (state(ports, event, JUSTICE, altar) >= 1 || run.holder("altar:" + altar) != 0
                    || !reaches(member, altar)) {
                continue;
            }
            if (run.claim("altar:" + altar, member.getId())) {
                return altar;
            }
        }
        return null;
    }

    /** True when {@code member} climbs to {@code altar}'s tower (GPQ plan G0's probe of 990000440). */
    static boolean reaches(Character member, String altar) {
        return switch (altar) {
            case "spear1", "spear4" -> AgentGpqRoles.skilledClimber(member);
            case "spear2" -> AgentGpqRoles.skilledClimber(member) || AgentGpqRoles.hastedClimber(member);
            default -> true;
        };
    }

    /** The state of reactor {@code name} on {@code mapId}; -1 when it is not there. */
    static int state(AgentGpqPorts ports, EventInstanceManager event, int mapId, String name) {
        Reactor reactor = ports.reactorOn(event, mapId, name);
        return reactor == null ? -1 : reactor.getState();
    }

    // ---- one member's part ----

    static void play(AgentGpqPorts ports, Stage stage, Character member, Job job, EventInstanceManager event,
                     AgentGpqRun run, long nowMs) {
        if (job != Job.CARRY && job != Job.PLEDGE_SPEAR && job != Job.RUSTY_KEY && stage == Stage.FAITH
                && member.getPosition() != null && member.getPosition().y < CORRIDOR_Y) {
            AgentGpqRoles.keepDarkSight(ports, run, member, nowMs); // stepping back down off the stones' corridor
        }
        switch (job) {
            case DONE -> walkTo(ports, run, member, stage, HALL, () -> takePortal(ports, run, member, "next00", nowMs),
                    nowMs);
            case KEY_TEAM -> walkTo(ports, run, member, stage, COURAGE, () -> keyTeam(ports, member, event, run, nowMs),
                    nowMs);
            case KEY_CARRIER -> walkTo(ports, run, member, stage, HALL, () -> layKey(ports, member, event, run, nowMs),
                    nowMs);
            case DEBRIS -> walkTo(ports, run, member, stage, FAITH, () -> debris(ports, member, run, nowMs), nowMs);
            case RUSTY_KEY -> walkTo(ports, run, member, stage, FAITH, () -> layRustyKey(ports, member, run, nowMs),
                    nowMs);
            case PLEDGE_SPEAR -> pledge(ports, stage, member, run, nowMs);
            case GLORY_SPEAR -> walkTo(ports, run, member, stage, GLORY, () -> glory(ports, member, run, nowMs), nowMs);
            case CARRY -> carry(ports, stage, member, event, run, nowMs);
            case WAIT -> walkTo(ports, run, member, stage, HALL, () -> waitByPortal(ports, member, "next00"), nowMs);
        }
    }

    /** Runs {@code there} once the member stands on {@code mapId}; else takes the next portal toward it. */
    static void walkTo(AgentGpqPorts ports, AgentGpqRun run, Character member, Stage stage, int mapId, Runnable there,
                       long nowMs) {
        if (stage.mapId() == mapId) {
            there.run();
            return;
        }
        if (stage == Stage.HALL) {
            String portal = mapId == GLORY ? "in00" : mapId == COURAGE ? "in01" : mapId == FAITH || mapId == PLEDGE
                    ? "in02" : mapId == JUSTICE ? "in03" : null;
            if (portal != null) {
                takePortal(ports, run, member, portal, nowMs);
            }
        } else if (stage == Stage.PLEDGE) {
            takePortal(ports, run, member, "out00", nowMs); // back onto the stones' corridor in 430
        } else if (stage == Stage.FAITH && mapId == PLEDGE) {
            AgentGpqRoles.keepHaste(ports, run, member, nowMs);
            if (!AgentGpqRoles.keepDarkSight(ports, run, member, nowMs)) {
                takePortal(ports, run, member, "in00", nowMs); // metalgate_open, at the corridor's far end
            }
        } else {
            takePortal(ports, run, member, "out00", nowMs); // every room's way back to the hall
        }
    }

    /** The Room of Courage: clear it; with the Master Muscle Stone down, pick up the Key it dropped. */
    static void keyTeam(AgentGpqPorts ports, Character member, EventInstanceManager event, AgentGpqRun run,
                        long nowMs) {
        if (run.once("key-hunt")) {
            ports.sayInParty(member, AgentGpqLines.KEY_HUNT);
        }
        if (pickUpNearest(ports, run, member, KEY, SEEK_PX, nowMs)) {
            return;
        }
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry != null && ports.monstersOn(event, COURAGE, COURAGE_MOBS) > 0) {
            ports.gateway().grind(entry, Set.of(MASTER_MUSCLE_STONE), COURAGE_MOBS);
        } else {
            waitByPortal(ports, member, "out00");
        }
    }

    /** The hall: lay the Key on the stone gate, once; a Key already lying there waits for the gate to take it. */
    static void layKey(AgentGpqPorts ports, Character member, EventInstanceManager event, AgentGpqRun run,
                       long nowMs) {
        if (!AgentGpqMoves.dropsIn(ports, member, KEY, STONEGATE_BOX).isEmpty()) {
            return;
        }
        if (dropAt(ports, run, member, KEY, 1, STONEGATE_STAND, nowMs) && run.once("key-placed")) {
            ports.sayInParty(member, AgentGpqLines.KEY_PLACED);
        }
    }

    /** The Room of Faith: break the debris; the shade picks up the Rusty Key it drops. */
    static void debris(AgentGpqPorts ports, Character member, AgentGpqRun run, long nowMs) {
        if (AgentGpqRoles.shade(member) && pickUpNearest(ports, run, member, RUSTY_KEY, SEEK_PX, nowMs)) {
            return;
        }
        List<Reactor> debris = AgentGpqMoves.live(ports, member, DEBRIS);
        if (debris.isEmpty()) {
            waitByPortal(ports, member, "out00");
            return;
        }
        hit(ports, run, member, debris.getFirst(), nowMs);
    }

    /** The shade crosses the corridor under Dark Sight and lays the Rusty Key on the metal gate. */
    static void layRustyKey(AgentGpqPorts ports, Character member, AgentGpqRun run, long nowMs) {
        if (run.once("rusty-key")) {
            ports.sayInParty(member, AgentGpqLines.RUSTY_KEY);
        }
        AgentGpqRoles.keepHaste(ports, run, member, nowMs);
        if (AgentGpqRoles.keepDarkSight(ports, run, member, nowMs)
                || !AgentGpqMoves.dropsIn(ports, member, RUSTY_KEY, METALGATE_BOX).isEmpty()) {
            return;
        }
        dropAt(ports, run, member, RUSTY_KEY, 1, METALGATE_STAND, nowMs);
    }

    /** The shade breaks the Room of Pledge's spear and picks it up. */
    static void pledge(AgentGpqPorts ports, Stage stage, Character member, AgentGpqRun run, long nowMs) {
        if (stage != Stage.PLEDGE) {
            walkTo(ports, run, member, stage, PLEDGE, () -> { }, nowMs);
            return;
        }
        if (pickUpNearest(ports, run, member, SPEAR, SEEK_PX, nowMs)) {
            return;
        }
        List<Reactor> spear = AgentGpqMoves.live(ports, member, SEALED_SPEAR);
        if (!spear.isEmpty()) {
            hit(ports, run, member, spear.getFirst(), nowMs);
        }
    }

    /** The Room of Glory: climb to the spear this member holds (or the nearest no one holds), break it, pick it up. */
    static void glory(AgentGpqPorts ports, Character member, AgentGpqRun run, long nowMs) {
        if (pickUpNearest(ports, run, member, SPEAR, 120, nowMs)) {
            return;
        }
        Reactor target = null;
        for (Reactor spear : AgentGpqMoves.live(ports, member, SEALED_SPEAR)) {
            int holder = run.holder("glory:" + spear.getObjectId());
            if (holder == member.getId()) {
                target = spear;
                break;
            }
            if (holder == 0 && target == null) {
                target = spear;
            }
        }
        if (target == null) {
            run.releaseAll(member.getId(), "glory:");
            waitByPortal(ports, member, "out00");
            return;
        }
        if (run.claim("glory:" + target.getObjectId(), member.getId())) {
            hit(ports, run, member, target, nowMs);
        }
    }

    /** A carrier takes its spear to its altar and lays it there; from the Room of Pledge it recrosses the stones. */
    static void carry(AgentGpqPorts ports, Stage stage, Character member, EventInstanceManager event,
                      AgentGpqRun run, long nowMs) {
        run.releaseAll(member.getId(), "glory:");
        String altar = claimAltar(ports, event, run, member);
        if (altar == null) {
            if (run.once("no-altar-" + member.getId())) {
                ports.sayInParty(member, AgentGpqLines.NO_ALTAR);
            }
            walkTo(ports, run, member, stage, HALL, () -> waitByPortal(ports, member, "next00"), nowMs);
            return;
        }
        if (stage == Stage.FAITH) {
            AgentGpqRoles.keepHaste(ports, run, member, nowMs);
            if (member.getPosition() != null && member.getPosition().y < CORRIDOR_Y
                    && AgentGpqRoles.keepDarkSight(ports, run, member, nowMs)) {
                return;
            }
        }
        if (stage != Stage.JUSTICE) {
            walkTo(ports, run, member, stage, JUSTICE, () -> { }, nowMs);
            return;
        }
        if (altar.equals("spear2")) {
            AgentGpqRoles.keepHaste(ports, run, member, nowMs);
        }
        Point stand = ALTARS.get(altar);
        if (!AgentGpqMoves.dropsIn(ports, member, SPEAR, altarBox(altar)).isEmpty()) {
            return; // already laid; the altar takes it in a moment
        }
        if (dropAt(ports, run, member, SPEAR, 1, stand, nowMs)) {
            ports.sayInParty(member, AgentGpqLines.SPEAR_PLACED);
        }
    }

    /** The altar's item box: its position +-20 px along x and +-30 px up and down (Reactor.wz 9208007). */
    static Rectangle altarBox(String altar) {
        Point stand = ALTARS.get(altar);
        return new Rectangle(stand.x - 20, stand.y - 40, 40, 60);
    }
}
