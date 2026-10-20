package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CWKPQ plan C8, after the clear: the companions walk on from the Council Hall through the Secret Chamber and break
 * their share of the Armory's 30 boxes. The Armory's minute starts with the first character in (CWKPQ.js
 * current_instance 6), so a companion waits in the Secret Chamber until someone has gone in: it never starts the
 * humans' clock. Every member on the Armory, the humans too, gets a share of the boxes by area (B8): the humans' shares
 * come first along the shelves, and a companion breaks only its own, then picks up what the loot rules let it.
 */
final class AgentCwkpqArmoryPlay {
    static final int COUNCIL_HALL_MAP_ID = 610_030_600;
    static final int SECRET_CHAMBER_MAP_ID = 610_030_700;
    static final int ARMORY_MAP_ID = 610_030_800;
    /** Reactor.wz 6102002-6102005: five hits of any kind break one, and its script sprays the drops. */
    static final Set<Integer> BOX_REACTOR_IDS = Set.of(6_102_002, 6_102_003, 6_102_004, 6_102_005);
    static final String NEXT_PORTAL = "next00";
    /** CWKPQ.js changedMap: current_instance is 6 once the first character entered the Armory. */
    static final int ARMORY_STARTED = 6;
    /** How far a companion that broke its share looks for a drop it may take. */
    static final int LOOT_SEEK_PX = 600;
    static final int LOOT_REACH_PX = 32;
    /** The Armory's shelves (y -384, -204, -24, 156, 336): a box above this is on the upper three. */
    static final int UPPER_SHELVES_Y = 60;

    private AgentCwkpqArmoryPlay() {
    }

    /** The maps the run plays on after its clear. */
    static boolean playsOn(int mapId) {
        return mapId == COUNCIL_HALL_MAP_ID || mapId == SECRET_CHAMBER_MAP_ID || mapId == ARMORY_MAP_ID;
    }

    static void tick(AgentCwkpqPorts ports, List<Character> members, EventInstanceManager event, AgentCwkpqRun run,
                     long nowMs) {
        Map<Integer, List<Character>> byMap = new LinkedHashMap<>();
        for (Character member : members) {
            byMap.computeIfAbsent(member.getMapId(), ignored -> new ArrayList<>()).add(member);
        }
        for (Map.Entry<Integer, List<Character>> here : byMap.entrySet()) {
            switch (here.getKey()) {
                // glpqPortal6 opens at glpq6 >= 3, which the statue's last talk set before the clear
                case COUNCIL_HALL_MAP_ID -> here.getValue().forEach(member ->
                        AgentCwkpqStagePlays.takePortal(ports, run, member, NEXT_PORTAL, nowMs));
                case SECRET_CHAMBER_MAP_ID -> {
                    boolean started = event != null && event.getIntProperty("current_instance") >= ARMORY_STARTED;
                    for (Character member : here.getValue()) {
                        if (started) {
                            AgentCwkpqStagePlays.takePortal(ports, run, member, NEXT_PORTAL, nowMs);
                        } else {
                            AgentCwkpqStagePlays.waitBy(ports, member, NEXT_PORTAL);
                        }
                    }
                }
                case ARMORY_MAP_ID -> armory(ports, here.getValue(), run, nowMs);
                default -> {
                }
            }
        }
    }

    /** Each companion breaks the next box of its share, then takes what it may of the drops. */
    static void armory(AgentCwkpqPorts ports, List<Character> companions, AgentCwkpqRun run, long nowMs) {
        Character first = companions.getFirst();
        MapleMap map = first.getMap();
        if (map == null) {
            return;
        }
        List<Reactor> boxes = new ArrayList<>();
        for (Reactor reactor : ports.gateway().reactors(first)) {
            if (BOX_REACTOR_IDS.contains(reactor.getId()) && reactor.isAlive() && reactor.isActive()) {
                boxes.add(reactor);
            }
        }
        List<Character> bySlot = AgentCwkpqStagePlays.bySlot(companions, run);
        Set<Integer> companionIds = new HashSet<>();
        bySlot.forEach(member -> companionIds.add(member.getId()));
        // the humans first (any player on the Armory that is not one of the run's companions), then the companions
        List<Integer> sharers = new ArrayList<>();
        map.getAllPlayers().stream().map(Character::getId).filter(id -> !companionIds.contains(id)).sorted()
                .forEach(sharers::add);
        int humans = sharers.size();
        bySlot.forEach(member -> sharers.add(member.getId()));
        if (run.shareBoxes(sharers, boxes) && humans > 0 && run.once("armory-share")) {
            String yours = humansArea(boxes, run, companionIds);
            if (yours != null) {
                ports.sayInParty(bySlot.getFirst(), yours);
            }
        }
        for (Character member : bySlot) {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (entry == null || !run.ready(member.getId(), nowMs)) {
                continue;
            }
            Reactor box = nextBox(member, boxes, run);
            if (box != null) {
                hit(ports, run, entry, member, box, nowMs);
                continue;
            }
            MapItem drop = ports.nearestLoot(member, LOOT_SEEK_PX);
            if (drop != null) {
                Point ground = ports.gateway().groundPoint(map, drop.getPosition());
                if (AgentCwkpqStagePlays.standAt(ports, entry, member,
                        ground != null ? ground : new Point(drop.getPosition()), LOOT_REACH_PX)) {
                    ports.gateway().lootItem(member, drop.getObjectId(), LOOT_REACH_PX);
                }
            }
        }
    }

    /** The box it is breaking while it stands; else the nearest live box of its share; null when its share is done. */
    static Reactor nextBox(Character member, Collection<Reactor> boxes, AgentCwkpqRun run) {
        Integer working = run.boxOf(member.getId());
        Point at = member.getPosition();
        Reactor next = null;
        for (Reactor box : boxes) {
            if (working != null && box.getObjectId() == working) {
                return box;
            }
            Integer owner = run.boxOwner(box.getObjectId());
            if (owner != null && owner == member.getId() && (next == null || at != null
                    && at.distanceSq(box.getPosition()) < at.distanceSq(next.getPosition()))) {
                next = box;
            }
        }
        run.workOn(member.getId(), next == null ? null : next.getObjectId());
        return next;
    }

    /** From beside the box on its shelf: one hit; the box takes five. */
    private static void hit(AgentCwkpqPorts ports, AgentCwkpqRun run, AgentRuntimeEntry entry, Character member,
                            Reactor box, long nowMs) {
        if (!AgentCwkpqStagePlays.standAt(ports, entry, member, AgentCwkpqStagePlays.beside(ports, member,
                box.getPosition(), AgentCwkpqSigilCast.CLOSE_STAND_PX), AgentCwkpqStagePlays.REACH_PX)) {
            return;
        }
        ports.gateway().stop(entry);
        ports.gateway().facePosition(member, box.getPosition());
        ports.gateway().hitReactor(member, box.getObjectId());
        run.busy(member.getId(), nowMs + AgentCwkpqStagePlays.CAST_PAUSE_MS);
    }

    /**
     * The boxes along the shelves, the order the shares are cut from: the left half from the top shelf down, then the
     * right half from the floor up, nearer the middle first on a shelf, so each share is a few neighbouring boxes.
     */
    static List<Reactor> path(Collection<Reactor> boxes) {
        List<Reactor> path = new ArrayList<>(boxes);
        path.sort(Comparator.<Reactor>comparingInt(box -> box.getPosition().x < 0 ? 0 : 1)
                .thenComparingInt(box -> box.getPosition().x < 0 ? box.getPosition().y : -box.getPosition().y)
                .thenComparingInt(box -> Math.abs(box.getPosition().x))
                .thenComparingInt(Reactor::getObjectId));
        return path;
    }

    /**
     * Where the humans' boxes are, as a companion says it ("the top left boxes are yours, we'll break the rest"); null
     * when their boxes are not on one side or one height.
     */
    static String humansArea(Collection<Reactor> boxes, AgentCwkpqRun run, Set<Integer> companionIds) {
        List<Point> theirs = new ArrayList<>();
        for (Reactor box : boxes) {
            Integer owner = run.boxOwner(box.getObjectId());
            if (owner != null && !companionIds.contains(owner)) {
                theirs.add(box.getPosition());
            }
        }
        if (theirs.isEmpty()) {
            return null;
        }
        boolean left = theirs.stream().allMatch(at -> at.x < 0);
        boolean right = theirs.stream().allMatch(at -> at.x > 0);
        boolean top = theirs.stream().allMatch(at -> at.y < UPPER_SHELVES_Y);
        boolean bottom = theirs.stream().allMatch(at -> at.y >= UPPER_SHELVES_Y);
        String side = left ? "left " : right ? "right " : "";
        String height = top ? "top " : bottom ? "bottom " : "";
        return side.isEmpty() && height.isEmpty() ? null
                : "the " + height + side + "boxes are yours, we'll break the rest";
    }
}
