package server.agents.capabilities.expedition.cwkpq;

import client.BuffStat;
import client.Character;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.NightWalker;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentMovementSkillPolicy;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqRun.Stage;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqRun.StagePlay;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CWKPQ stage plays built so far (CWKPQ plan B5, phase C4), each for the companions standing on its map:
 * <ul>
 * <li>Stage 1, the Inner Sanctum Hallway: the first member by its slot takes the hidden portal under the lion's head
 * (next01, glpqPortalDummy.js: it sets glpq1 and sends the taker back to the start), saying once where the way is;
 * the others wait by the exit (next00). Once glpq1 is set, everyone takes the exit.</li>
 * <li>Stage 2, the Forgotten Storage Chamber: for each archetype whose sigil still waits, the first member by its slot
 * who can turn it walks up and casts one of its skills on it (AgentCwkpqSigilCast); the others wait by the exit. A
 * sigil a player turned is left alone. Once all five are turned (glpq2), everyone takes the exit.</li>
 * <li>Stage 4, the Test of Wit: each of the 8 spots shows a class's picture, and the fakes show the warrior's, the
 * mage's or the thief's (C0). A member of the picture's class takes the spot. A bowman or pirate picture is always a
 * real sigil, so it casts at once; any other is tried with one plain hit first, as a player does: a fake turns at once
 * and is spent (its tester says which one: "the right one up top's a fake"), a real sigil does not move and then takes
 * the class skill. Once five are turned (glpq4), everyone takes the exit.</li>
 * </ul>
 * Walks and portals are those of the PQ plays: walk up, stand on the ground within reach, then act, with a pause after
 * each portal or cast so one tick never acts twice.
 */
final class AgentCwkpqStagePlays {
    static final String HIDDEN_PORTAL = "next01";
    static final String EXIT_PORTAL = "next00";
    /** How close a member stands to a portal to take it, and to a spot or sigil to hit it. */
    static final int REACH_PX = 45;
    /** How close a member waiting by the exit stands to it. */
    static final int WAIT_PX = 120;
    static final long PORTAL_PAUSE_MS = 900L;
    static final long CAST_PAUSE_MS = 700L;
    /** How long after a plain test hit a spot that has not moved counts as a real sigil. */
    static final long TEST_SETTLE_MS = 800L;
    static final String HALLWAY_HINT = "the way's behind the pillar under the lion's head";
    /** Stage 3: the top portal counts a pass and drops its taker to the bottom; the bottom one leads on. */
    static final String TOP_PORTAL = "next00";
    static final String BOTTOM_PORTAL = "next01";
    /**
     * Where a climber stands to take the top portal: its long floor (C0: footholds 397-479 at y -1104). The portal's
     * own point snaps to the short duplicate foothold 442 on top of that floor, which only a down-jump reaches, so the
     * climber stands just past it and takes the portal from there.
     */
    static final Point TOP_STAND = new Point(310, -1104);
    static final int TOP_REACH_PX = 100;
    /** Above this height a sigil needs a climber with Haste or better (C0: the 110 px gap at y -684). */
    static final int HIGH_CLIMB_Y = -684;
    static final String NO_HIGH_CLIMBER = "i can't make that climb without haste. our sigil up there is yours";
    private static final int[] HASTE_SKILL_IDS = {Assassin.HASTE, Bandit.HASTE, NightWalker.HASTE};
    /** The stage-4 fakes and the class picture each shows (C0: byte-identical canvases in the client's Reactor.wz). */
    static final Map<Integer, Archetype> FAKE_PICTURES = Map.of(6_108_001, Archetype.WARRIOR,
            6_108_000, Archetype.MAGICIAN, 6_108_005, Archetype.THIEF);

    private AgentCwkpqStagePlays() {
    }

    /** Every stage play built so far, over {@code ports}. */
    static Map<Stage, StagePlay> all(AgentCwkpqPorts ports) {
        Map<Stage, StagePlay> plays = new EnumMap<>(Stage.class);
        plays.put(Stage.HALLWAY, (members, event, run, nowMs) -> hallway(ports, members, event, run, nowMs));
        plays.put(Stage.STORAGE, (members, event, run, nowMs) -> storage(ports, members, event, run, nowMs));
        plays.put(Stage.AGILITY, (members, event, run, nowMs) -> agility(ports, members, event, run, nowMs));
        plays.put(Stage.WIT, (members, event, run, nowMs) -> wit(ports, members, event, run, nowMs));
        return plays;
    }

    // ---- stage 1 ----

    static void hallway(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event,
                        AgentCwkpqRun run, long nowMs) {
        if (event.getIntProperty("glpq1") >= 1) {
            members.forEach(member -> takePortal(ports, run, member, EXIT_PORTAL, nowMs));
            return;
        }
        List<Character> bySlot = bySlot(members, run);
        Character finder = bySlot.getFirst();
        for (Character member : bySlot) {
            if (member == finder) {
                if (run.once("hallway-hint")) {
                    ports.sayInParty(member, HALLWAY_HINT);
                }
                takePortal(ports, run, member, HIDDEN_PORTAL, nowMs);
            } else {
                waitBy(ports, member, EXIT_PORTAL);
            }
        }
    }

    // ---- stage 2 ----

    static void storage(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event,
                        AgentCwkpqRun run, long nowMs) {
        if (event.getIntProperty("glpq2") >= 5) {
            members.forEach(member -> takePortal(ports, run, member, EXIT_PORTAL, nowMs));
            return;
        }
        List<Character> bySlot = bySlot(members, run);
        Map<Archetype, Reactor> waiting = new EnumMap<>(Archetype.class);
        for (Reactor reactor : ports.gateway().reactors(bySlot.getFirst())) {
            for (Archetype archetype : Archetype.values()) {
                if (reactor.getId() == archetype.sigilReactorId() && reactor.isAlive() && reactor.isActive()) {
                    waiting.put(archetype, reactor);
                }
            }
        }
        Map<Integer, Archetype> turners = new HashMap<>();
        for (Map.Entry<Archetype, Reactor> sigil : waiting.entrySet()) {
            bySlot.stream().filter(member -> !turners.containsKey(member.getId())
                            && caster(member) == sigil.getKey())
                    .findFirst().ifPresent(member -> turners.put(member.getId(), sigil.getKey()));
        }
        for (Character member : bySlot) {
            Archetype archetype = turners.get(member.getId());
            if (archetype != null) {
                turnSigil(ports, run, member, waiting.get(archetype), archetype, nowMs);
            } else {
                waitBy(ports, member, EXIT_PORTAL);
            }
        }
    }

    // ---- stage 3 ----

    static void agility(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event,
                        AgentCwkpqRun run, long nowMs) {
        if (event.getIntProperty("glpq3") >= 5 && event.getIntProperty("glpq3_p") >= 5) {
            members.forEach(member -> takePortal(ports, run, member, BOTTOM_PORTAL, nowMs));
            return;
        }
        List<Character> bySlot = bySlot(members, run);
        for (Character member : bySlot) {
            if (hasteSkill(member) != 0 && member.getBuffedValue(BuffStat.SPEED) == null
                    && run.ready(member.getId(), nowMs)) {
                AgentRuntimeEntry entry = ports.entry(member.getId());
                if (entry != null && ports.casting().buff(entry, member, hasteSkill(member))) {
                    run.busy(member.getId(), nowMs + CAST_PAUSE_MS); // a party buff: the climbers by it get it too
                }
            }
        }
        Map<Archetype, Reactor> waiting = new EnumMap<>(Archetype.class);
        for (Reactor reactor : ports.gateway().reactors(bySlot.getFirst())) {
            for (Archetype archetype : Archetype.values()) {
                if (reactor.getId() == archetype.sigilReactorId() && reactor.isAlive() && reactor.isActive()) {
                    waiting.put(archetype, reactor);
                }
            }
        }
        Map<Integer, Archetype> turners = new HashMap<>();
        for (Map.Entry<Archetype, Reactor> sigil : waiting.entrySet()) {
            Point at = sigil.getValue().getPosition();
            boolean found = bySlot.stream().filter(member -> !turners.containsKey(member.getId())
                            && caster(member) == sigil.getKey() && reaches(member, at))
                    .findFirst().map(member -> turners.put(member.getId(), sigil.getKey()) == null).orElse(false);
            if (!found && at.y <= HIGH_CLIMB_Y && bySlot.stream().anyMatch(member -> caster(member) == sigil.getKey())
                    && run.once("agility-no-climber-" + sigil.getKey())) {
                Character speaker = bySlot.stream().filter(member -> caster(member) == sigil.getKey()).findFirst()
                        .orElse(bySlot.getFirst());
                ports.sayInParty(speaker, NO_HIGH_CLIMBER);
            }
        }
        boolean passesWanted = event.getIntProperty("glpq3_p") < 5;
        for (Character member : bySlot) {
            Archetype archetype = turners.get(member.getId());
            if (archetype != null) {
                turnSigil(ports, run, member, waiting.get(archetype), archetype, nowMs);
            } else if (passesWanted && climbsHigh(member)) {
                takePortalFrom(ports, run, member, TOP_PORTAL, TOP_STAND, TOP_REACH_PX, nowMs);
            } else {
                waitBy(ports, member, BOTTOM_PORTAL);
            }
        }
    }

    /** True when {@code member} can climb to a sigil at {@code sigil}: a low one at any stats, a high one hasted. */
    static boolean reaches(Character member, Point sigil) {
        return sigil.y > HIGH_CLIMB_Y || climbsHigh(member);
    }

    /**
     * True when {@code member} climbs the upper half of the Test of Agility: C0 found every sigil and the top portal
     * reached from speed 120 and jump 120, or speed 145 and jump 110 (Haste 20 gives 145/120), or with Flash Jump or
     * Teleport at base; at base stats the climb stops at the 110 px gap at y -684.
     */
    static boolean climbsHigh(Character member) {
        int speed = member.getTotalMoveSpeedStat();
        int jump = member.getTotalJumpStat();
        return speed >= 120 && jump >= 120 || speed >= 145 && jump >= 110
                || AgentMovementSkillPolicy.canUseAnyActiveMovementSkill(member);
    }

    /** Its Haste (a thief at 2nd job or above), or 0. */
    static int hasteSkill(Character member) {
        for (int skillId : HASTE_SKILL_IDS) {
            if (member.getSkillLevel(skillId) > 0) {
                return skillId;
            }
        }
        return 0;
    }

    // ---- stage 4 ----

    static void wit(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event, AgentCwkpqRun run,
                    long nowMs) {
        if (event.getIntProperty("glpq4") >= 5) {
            members.forEach(member -> {
                run.assign(member.getId(), null);
                takePortal(ports, run, member, EXIT_PORTAL, nowMs);
            });
            return;
        }
        List<Character> bySlot = bySlot(members, run);
        Map<Integer, Reactor> pictured = new HashMap<>();
        Map<Integer, Reactor> spots = new HashMap<>();
        for (Reactor reactor : ports.gateway().reactors(bySlot.getFirst())) {
            if (picture(reactor.getId()) != null && reactor.isAlive()) {
                pictured.put(reactor.getObjectId(), reactor);
                if (reactor.getState() == 1) {
                    spots.put(reactor.getObjectId(), reactor);
                }
            }
        }
        for (Reactor spot : spots.values()) {
            Long tested = run.testedAt(spot.getObjectId());
            if (tested != null && nowMs - tested >= TEST_SETTLE_MS) {
                run.markReal(spot.getObjectId()); // a plain hit did not move it: a real sigil
            }
        }
        for (Character member : bySlot) {
            Integer held = run.spotOf(member.getId());
            if (held != null && !spots.containsKey(held)) {
                run.assign(member.getId(), null); // spent: turned, or a fake that turned at the test hit
                if (run.testedAt(held) != null && !run.knownReal(held) && run.once("fake-" + held)) {
                    Reactor fake = pictured.get(held);
                    ports.sayInParty(member, fake == null ? "that one was a fake"
                            : "the " + where(fake.getPosition()) + "'s a fake");
                }
                held = null;
            }
            Archetype archetype = caster(member);
            if (held == null && archetype != null) {
                held = nearestFree(spots, archetype, member, run);
                run.assign(member.getId(), held);
            }
            if (held == null) {
                waitBy(ports, member, EXIT_PORTAL);
                continue;
            }
            Reactor spot = spots.get(held);
            boolean real = alwaysReal(picture(spot.getId())) || run.knownReal(held);
            if (real) {
                turnSigil(ports, run, member, spot, archetype, nowMs);
            } else if (run.testedAt(held) == null) {
                testHit(ports, run, member, spot, nowMs);
            }
        }
    }

    /** The class picture stage-4 reactor {@code reactorId} shows; null for any other reactor. */
    static Archetype picture(int reactorId) {
        for (Archetype archetype : Archetype.values()) {
            if (archetype.witSigilReactorId() == reactorId) {
                return archetype;
            }
        }
        return FAKE_PICTURES.get(reactorId);
    }

    /**
     * Where a stage-4 spot is, as a player would say it: "right one up top", "left one low". The 8 spots stand at x
     * -804..1181 and y -969..172 on 610030400.
     */
    static String where(Point spot) {
        String side = spot.x < -300 ? "left" : spot.x > 600 ? "right" : "middle";
        String height = spot.y < -800 ? "up top" : spot.y > -300 ? "low" : "halfway up";
        return side + " one " + height;
    }

    /** No fake shows the bowman's or the pirate's picture. */
    static boolean alwaysReal(Archetype picture) {
        return picture == Archetype.BOWMAN || picture == Archetype.PIRATE;
    }

    private static Integer nearestFree(Map<Integer, Reactor> spots, Archetype archetype, Character member,
                                       AgentCwkpqRun run) {
        Point at = member.getPosition();
        return spots.values().stream()
                .filter(spot -> picture(spot.getId()) == archetype && !run.spotTaken(spot.getObjectId(), member.getId()))
                .min(Comparator.comparingDouble(spot -> at == null ? 0d : at.distanceSq(spot.getPosition())))
                .map(Reactor::getObjectId).orElse(null);
    }

    /** One plain hit on a spot, from beside it, to see whether it is a fake. */
    private static void testHit(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, Reactor spot, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || !standAt(ports, entry, member, beside(ports, member, spot.getPosition(),
                AgentCwkpqSigilCast.CLOSE_STAND_PX), REACH_PX) || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.gateway().facePosition(member, spot.getPosition());
        if (ports.gateway().hitReactor(member, spot.getObjectId())) {
            run.tested(spot.getObjectId(), nowMs);
        }
        run.busy(member.getId(), nowMs + CAST_PAUSE_MS);
    }

    // ---- shared moves ----

    /** Walks up to {@code sigil}, stands beside it on the ground, faces it and casts one of its skills on it. */
    static void turnSigil(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, Reactor sigil,
                          Archetype archetype, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        int offset = AgentCwkpqSigilCast.standOffsetPx(ports.casting(), member, archetype);
        if (entry == null || offset == 0 || !standAt(ports, entry, member,
                beside(ports, member, sigil.getPosition(), offset), REACH_PX) || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.gateway().facePosition(member, sigil.getPosition());
        AgentCwkpqSigilCast.cast(ports.casting(), entry, member, sigil, archetype);
        run.busy(member.getId(), nowMs + CAST_PAUSE_MS);
    }

    /** Walks onto the portal and takes it (a script portal runs as the player's would). */
    static void takePortal(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, String portalName,
                           long nowMs) {
        MapleMap map = member.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        if (portal != null) {
            takePortalFrom(ports, run, member, portalName, new Point(portal.getPosition()), REACH_PX, nowMs);
        }
    }

    /** Walks to {@code stand}, within {@code reachPx} of the portal, and takes it. */
    static void takePortalFrom(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, String portalName,
                               Point stand, int reachPx, long nowMs) {
        MapleMap map = member.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (portal == null || entry == null || !standAt(ports, entry, member, stand, reachPx)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.partyQuest().enterPortal(member, portal.getId());
        run.busy(member.getId(), nowMs + PORTAL_PAUSE_MS);
    }

    /** Walks to wait near the portal, out of the way of the members at work. */
    static void waitBy(AgentCwkpqPorts ports, Character member, String portalName) {
        MapleMap map = member.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (portal != null && entry != null && !near(member.getPosition(), portal.getPosition(), WAIT_PX)) {
            ports.gateway().navigate(entry, new Point(portal.getPosition()), false);
        }
    }

    /** True once the member stands on the ground within {@code reachPx} of {@code stand}; else it walks there. */
    static boolean standAt(AgentCwkpqPorts ports, AgentRuntimeEntry entry, Character member, Point stand,
                           int reachPx) {
        if (near(member.getPosition(), stand, reachPx) && ports.gateway().grounded(member)) {
            return true;
        }
        ports.gateway().navigate(entry, stand, true);
        return false;
    }

    /** The ground {@code offset} px beside {@code target}, on the side the member comes from. */
    static Point beside(AgentCwkpqPorts ports, Character member, Point target, int offset) {
        Point at = member.getPosition();
        int side = at != null && at.x > target.x ? 1 : -1;
        Point candidate = new Point(target.x + side * offset, target.y);
        Point ground = ports.gateway().groundPoint(member.getMap(), candidate);
        return ground != null ? ground : candidate;
    }

    static boolean near(Point a, Point b, int reachPx) {
        return a != null && b != null && a.distanceSq(b) <= (long) reachPx * reachPx;
    }

    /**
     * The archetype whose sigils {@code member} can turn here: its job's, holding a sigil skill it can cast (a
     * Shadower whose only one is Meso Explosion cannot: it needs mesos on the floor); else null.
     */
    static Archetype caster(Character member) {
        Archetype archetype = AgentCwkpqSigils.turns(member);
        return archetype != null && !AgentCwkpqSigilCast.candidates(member, archetype).isEmpty() ? archetype : null;
    }

    /** The members in the order they were adopted (their formation slots). */
    private static List<Character> bySlot(List<Character> members, AgentCwkpqRun run) {
        List<Character> out = new ArrayList<>(members);
        out.sort(Comparator.comparingInt(member -> run.ordinal(member.getId())));
        return out;
    }
}
