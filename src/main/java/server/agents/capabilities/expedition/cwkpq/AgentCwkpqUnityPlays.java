package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;
import server.partyquest.CwkpqRelicWeapons;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 5, the Test of Unity (CWKPQ plan B6, phase C6). Each archetype's weapon lies behind its own room: the warrior,
 * bowman and magician masters give theirs once their room is empty of monsters, the thief master once the five eyes
 * are broken, and the pirate room's gun drops from the one real chest of five (shuffled; nobody can tell them apart).
 * <ul>
 * <li>The hall (610030500): a member holding a weapon carries it to its relic and drops it on a spot whose drop lands
 * inside the relic's area (C0: the warrior's area lies 300-500 px right of its picture); the relic takes it 5 s later.
 * A member whose archetype's weapon is still wanted (its relic empty, no copy held or lying) takes its room's portal;
 * everyone else waits by the exit, and with all five relics filled everyone takes it.</li>
 * <li>A room: once the weapon is no longer wanted (someone holds it), or the member holds it, it leaves by the room's
 * exit. Otherwise its members clear the room; the first by slot then talks to the master. One who has asked the master
 * MAX_MASTER_TALKS times without a weapon says so once and leaves. In the thief room each member breaks the nearest
 * eye nobody else is on; in the pirate room each breaks the nearest chest, and the nearest pirate picks the gun up
 * where it fell.</li>
 * </ul>
 * A human of the archetype may claim the weapon first; companions then see it held and leave.
 */
final class AgentCwkpqUnityPlays {
    static final String HALL_EXIT = "next00";
    static final String ROOM_EXIT = "out00";
    /** The warrior room's master stands at the top of the tower; down00 beside him leads to its out00. */
    static final String WARRIOR_SHORTCUT = "down00";
    static final int SHORTCUT_REACH_PX = 400;
    static final Map<Archetype, String> ROOM_PORTALS = new EnumMap<>(Map.of(Archetype.WARRIOR, "in01",
            Archetype.BOWMAN, "in02", Archetype.MAGICIAN, "in03", Archetype.THIEF, "in04", Archetype.PIRATE, "in05"));
    static final Map<Archetype, Integer> WEAPONS = new EnumMap<>(Map.of(Archetype.WARRIOR, 4_001_259,
            Archetype.MAGICIAN, 4_001_257, Archetype.BOWMAN, 4_001_258, Archetype.THIEF, 4_001_256,
            Archetype.PIRATE, 4_001_260));
    static final Map<Archetype, Integer> MASTERS = new EnumMap<>(Map.of(Archetype.WARRIOR, 9_201_107,
            Archetype.BOWMAN, 9_201_108, Archetype.MAGICIAN, 9_201_109, Archetype.THIEF, 9_201_110));
    static final Map<Archetype, String> WEAPON_NAMES = new EnumMap<>(Map.of(Archetype.WARRIOR, "master sword",
            Archetype.BOWMAN, "ancestral bow", Archetype.MAGICIAN, "staff", Archetype.THIEF, "primal claw",
            Archetype.PIRATE, "forbidden gun"));
    /**
     * Where a member stands to drop each weapon (C0 check 1c: the first ground point routed to whose own drop lands in
     * the relic's area, reachable at base stats from the hall's spawn and every room return).
     */
    static final Map<Integer, Point> DROP_SPOTS = Map.of(4_001_259, new Point(1093, -586),
            4_001_258, new Point(1280, -415), 4_001_257, new Point(795, -916), 4_001_256, new Point(1343, -887),
            4_001_260, new Point(1266, -565));
    static final int THIEF_EYE = 6_108_004;
    static final int GUN_CHEST = 6_102_001;
    static final int DECOY_CHEST = 6_108_003;
    static final int NPC_REACH_PX = 80;
    static final int DROP_REACH_PX = 20;
    static final int LOOT_REACH_PX = 60;
    static final int MAX_MASTER_TALKS = 3;
    /** The relic takes a weapon 5 s after it lands; the dropper waits that long. */
    static final long RELIC_PAUSE_MS = 6_000L;

    private AgentCwkpqUnityPlays() {
    }

    // ---- the hall ----

    static void hall(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event, AgentCwkpqRun run,
                     long nowMs) {
        if (event.getIntProperty("glpq5") >= 5) {
            for (Character member : members) {
                AgentCwkpqStagePlays.noteRole(run, member, "unity", "take the exit");
                AgentCwkpqStagePlays.takePortal(ports, run, member, HALL_EXIT, nowMs);
            }
            return;
        }
        for (Character member : AgentCwkpqStagePlays.bySlot(members, run)) {
            Integer held = heldWeapon(ports, member);
            if (held != null && !CwkpqRelicWeapons.relicTook(event, held)) {
                AgentCwkpqStagePlays.noteRole(run, member, "unity", "carry the " + weaponName(held) + " to its relic");
                carry(ports, run, member, held, nowMs);
                continue;
            }
            Archetype archetype = AgentCwkpqSigils.archetypeOf(member.getJob().getId());
            if (archetype != null && CwkpqRelicWeapons.wanted(event, WEAPONS.get(archetype))) {
                AgentCwkpqStagePlays.noteRole(run, member, "unity",
                        "go for the " + WEAPON_NAMES.get(archetype) + " in its room");
                AgentCwkpqStagePlays.takePortal(ports, run, member, ROOM_PORTALS.get(archetype), nowMs);
            } else {
                AgentCwkpqStagePlays.noteRole(run, member, "unity", "wait by the exit");
                AgentCwkpqStagePlays.waitBy(ports, member, HALL_EXIT);
            }
        }
    }

    /** Walks to its weapon's drop spot and drops it there, once its drop would land in the relic's area. */
    static void carry(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, int weapon, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        Point spot = DROP_SPOTS.get(weapon);
        if (entry == null || spot == null || !AgentCwkpqStagePlays.standAt(ports, entry, member, spot, DROP_REACH_PX)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        Rectangle area = relicArea(ports, member, weapon);
        Point landing = ports.gateway().predictDropLanding(member, member.getPosition());
        if (area != null && landing != null && !area.contains(landing)) {
            ports.gateway().navigate(entry, spot, true);
            return;
        }
        ports.gateway().stop(entry);
        if (ports.dropOne(member, weapon)) {
            run.busy(member.getId(), nowMs + RELIC_PAUSE_MS);
        }
    }

    private static Rectangle relicArea(AgentCwkpqPorts ports, Character member, int weapon) {
        CwkpqRelicWeapons.Weapon kind = CwkpqRelicWeapons.Weapon.of(weapon);
        for (Reactor reactor : ports.gateway().reactors(member)) {
            if (kind != null && reactor.getId() == kind.relicReactorId()) {
                return reactor.getArea();
            }
        }
        return null;
    }

    // ---- the rooms ----

    static void room(AgentCwkpqPorts ports, Archetype archetype, List<Character> members, EventInstanceManager event,
                     AgentCwkpqRun run, long nowMs) {
        int weapon = WEAPONS.get(archetype);
        List<Character> bySlot = AgentCwkpqStagePlays.bySlot(members, run);
        MapleMap map = bySlot.getFirst().getMap();
        if (archetype == Archetype.PIRATE && map != null && pickUpGun(ports, run, bySlot, map, nowMs)) {
            return;
        }
        boolean wanted = CwkpqRelicWeapons.wanted(event, weapon);
        List<Character> working = bySlot.stream().filter(member -> wanted && heldWeapon(ports, member) == null
                && run.count("master-talks-" + member.getId()) < MAX_MASTER_TALKS).toList();
        for (Character member : bySlot) {
            if (!working.contains(member)) {
                AgentCwkpqStagePlays.noteRole(run, member, "unity", "leave the room");
                leaveRoom(ports, run, member, nowMs);
            }
        }
        if (working.isEmpty() || map == null) {
            return;
        }
        // The thief room's high eyes are out of reach at base stats (C9 live: no path to the eye at (857,-767)).
        working.forEach(member -> AgentCwkpqStagePlays.keepHaste(ports, run, member, nowMs));
        switch (archetype) {
            case THIEF -> {
                if (breakEach(ports, run, working, THIEF_EYE, nowMs)) {
                    working.forEach(member -> AgentCwkpqStagePlays.noteRole(run, member, "unity", "break an eye"));
                } else {
                    AgentCwkpqStagePlays.noteRole(run, working.getFirst(), "unity", "ask the master");
                    askMaster(ports, run, working.getFirst(), archetype, nowMs);
                }
            }
            case PIRATE -> {
                working.forEach(member -> AgentCwkpqStagePlays.noteRole(run, member, "unity", "break a chest"));
                breakEach(ports, run, working, GUN_CHEST, nowMs);
            }
            default -> {
                if (map.countMonsters() > 0) {
                    for (Character member : working) {
                        AgentCwkpqStagePlays.noteRole(run, member, "unity", "clear the room");
                        AgentRuntimeEntry entry = ports.entry(member.getId());
                        if (entry != null) {
                            ports.gateway().grind(entry, Set.of());
                        }
                    }
                } else {
                    AgentCwkpqStagePlays.noteRole(run, working.getFirst(), "unity", "ask the master");
                    askMaster(ports, run, working.getFirst(), archetype, nowMs);
                }
            }
        }
    }

    /** The master's own test passed (the room is empty, or the eyes broken): the claimer walks up and asks. */
    static void askMaster(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, Archetype archetype,
                          long nowMs) {
        int npcId = MASTERS.get(archetype);
        int weapon = WEAPONS.get(archetype);
        AgentRuntimeEntry entry = ports.entry(member.getId());
        Point npc = ports.gateway().npcPosition(member, npcId);
        if (entry == null || npc == null) {
            return;
        }
        Point ground = ports.gateway().groundPoint(member.getMap(), npc);
        if (!AgentCwkpqStagePlays.standAt(ports, entry, member, ground != null ? ground : npc, NPC_REACH_PX)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        int before = ports.gateway().itemCount(member, weapon);
        ports.partyQuest().runNpc(member, npcId);
        run.busy(member.getId(), nowMs + AgentCwkpqStagePlays.PORTAL_PAUSE_MS);
        if (ports.gateway().itemCount(member, weapon) > before) {
            if (run.once("weapon-" + archetype)) {
                ports.sayInParty(member, "got the " + WEAPON_NAMES.get(archetype) + ", taking it to its relic");
            }
        } else if (run.bump("master-talks-" + member.getId()) >= MAX_MASTER_TALKS) {
            ports.sayInParty(member, "the master won't give me the " + WEAPON_NAMES.get(archetype));
        }
    }

    /**
     * Each member breaks the nearest still-standing reactor of the kind nobody else is on (the thief room's eyes, the
     * pirate room's five chests: the real one and its decoys look alike). False when none is left standing.
     */
    static boolean breakEach(AgentCwkpqPorts ports, AgentCwkpqRun run, List<Character> working, int kind,
                             long nowMs) {
        List<Reactor> standing = ports.gateway().reactors(working.getFirst()).stream()
                .filter(reactor -> (reactor.getId() == kind || kind == GUN_CHEST && reactor.getId() == DECOY_CHEST)
                        && reactor.isAlive() && reactor.isActive())
                .toList();
        if (standing.isEmpty()) {
            working.forEach(member -> run.assign(member.getId(), null));
            return false;
        }
        for (Character member : working) {
            Integer held = run.spotOf(member.getId());
            Reactor target = held == null ? null
                    : standing.stream().filter(reactor -> reactor.getObjectId() == held).findFirst().orElse(null);
            if (target == null) {
                Point at = member.getPosition();
                target = standing.stream().filter(reactor -> !run.spotTaken(reactor.getObjectId(), member.getId()))
                        .min(Comparator.comparingDouble(reactor -> at == null ? 0d
                                : at.distanceSq(reactor.getPosition())))
                        .orElse(null);
                run.assign(member.getId(), target == null ? null : target.getObjectId());
            }
            if (target != null) {
                hitPlain(ports, run, member, target, nowMs);
            }
        }
        return true;
    }

    /** One plain hit from beside a reactor that takes any hit. */
    static void hitPlain(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, Reactor reactor, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || !AgentCwkpqStagePlays.standAt(ports, entry, member, AgentCwkpqStagePlays.beside(ports,
                member, reactor.getPosition(), AgentCwkpqSigilCast.CLOSE_STAND_PX), AgentCwkpqStagePlays.REACH_PX)
                || !run.ready(member.getId(), nowMs)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.gateway().facePosition(member, reactor.getPosition());
        ports.gateway().hitReactor(member, reactor.getObjectId());
        run.busy(member.getId(), nowMs + AgentCwkpqStagePlays.CAST_PAUSE_MS);
    }

    /** The gun lies in the pirate room: the nearest pirate walks over and takes it. True while it does. */
    static boolean pickUpGun(AgentCwkpqPorts ports, AgentCwkpqRun run, List<Character> pirates, MapleMap map,
                             long nowMs) {
        MapItem gun = map.getDroppedItems().stream()
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == WEAPONS.get(Archetype.PIRATE))
                .findFirst().orElse(null);
        if (gun == null) {
            return false;
        }
        Point at = gun.getPosition();
        Character taker = pirates.stream().filter(pirate -> pirate.getPosition() != null)
                .min(Comparator.comparingDouble(pirate -> pirate.getPosition().distanceSq(at))).orElse(null);
        AgentRuntimeEntry entry = taker == null ? null : ports.entry(taker.getId());
        if (entry == null) {
            return true;
        }
        AgentCwkpqStagePlays.noteRole(run, taker, "unity", "pick up the gun at " + AgentCwkpqStagePlays.pointText(at));
        Point ground = ports.gateway().groundPoint(map, at);
        if (!AgentCwkpqStagePlays.standAt(ports, entry, taker, ground != null ? ground : at, LOOT_REACH_PX)
                || !run.ready(taker.getId(), nowMs)) {
            return true;
        }
        ports.gateway().stop(entry);
        if (ports.pickUp(taker, WEAPONS.get(Archetype.PIRATE)) && run.once("weapon-" + Archetype.PIRATE)) {
            ports.sayInParty(taker, "got the " + WEAPON_NAMES.get(Archetype.PIRATE) + ", taking it to its relic");
        }
        run.busy(taker.getId(), nowMs + AgentCwkpqStagePlays.CAST_PAUSE_MS);
        return true;
    }

    /** Leaves the room by its exit (from the warrior master's ledge, by the shortcut beside him). */
    static void leaveRoom(AgentCwkpqPorts ports, AgentCwkpqRun run, Character member, long nowMs) {
        run.assign(member.getId(), null);
        MapleMap map = member.getMap();
        Portal shortcut = map == null ? null : map.getPortal(WARRIOR_SHORTCUT);
        if (shortcut != null && AgentCwkpqStagePlays.near(member.getPosition(), shortcut.getPosition(),
                SHORTCUT_REACH_PX)) {
            AgentCwkpqStagePlays.takePortal(ports, run, member, WARRIOR_SHORTCUT, nowMs);
        } else {
            AgentCwkpqStagePlays.takePortal(ports, run, member, ROOM_EXIT, nowMs);
        }
    }

    /** What a player calls {@code weapon} ("master sword"). */
    static String weaponName(int weapon) {
        for (Map.Entry<Archetype, Integer> kind : WEAPONS.entrySet()) {
            if (kind.getValue() == weapon) {
                return WEAPON_NAMES.get(kind.getKey());
            }
        }
        return "weapon";
    }

    /** The first stage-5 weapon in its bag, or null. */
    static Integer heldWeapon(AgentCwkpqPorts ports, Character member) {
        for (int weapon : WEAPONS.values()) {
            if (ports.gateway().itemCount(member, weapon) > 0) {
                return weapon;
            }
        }
        return null;
    }
}
