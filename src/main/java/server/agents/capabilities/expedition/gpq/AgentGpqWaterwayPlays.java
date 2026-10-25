package server.agents.capabilities.expedition.gpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.bySlot;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.dropAt;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.dropsIn;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.pickUpNearest;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.reactorNamed;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitAt;

/**
 * Stage 4, the waterway and Sharen III's grave (GPQ plan B5 G3). The grave's remains (9201001) take the king's clothes
 * bottom to top, one at a time: shoes, pants, top, crown. Each lies in a box at the end of one waterway room:
 * <ul>
 * <li>the golem maze (610): nine Puppet Golems drop nine Marks of Evil; one stack of exactly nine on secretgate1 opens
 * the way to the pants (611);</li>
 * <li>the shoes maze (620): a climb to the box, no fight;</li>
 * <li>the gargoyle maze (630): the Gargoyle dies on its ledge, its drop sets off the reactor under it, eight Jr.
 * Gargoyles come and drop eight Marks; a stack of exactly eight on secretgate2 opens the way to the top (631);</li>
 * <li>the slime maze (640), for members of level 30 or under only: twenty Devil Slimes, twenty Marks on secretgate3,
 * then the crown (641).</li>
 * </ul>
 * From the hub each member takes a room by its slot (the golems by default, a few on the gargoyle, one on the shoes,
 * the Lv&lt;=30 member on the slimes); in a mark room every member fights and picks up Marks, the lowest slot there
 * carries them (the others hand theirs over: a gate wants one stack) and lays the exact count on the gate. Whoever
 * picks up a garment takes the room's end portal to the grave, where garments go on in order; with the crown on,
 * the leader is told to talk to the ghost. Marks are finite and the maps keep drops 180 s, so they are picked up first.
 */
final class AgentGpqWaterwayPlays {
    static final int MARK = 4_001_035;
    /** The Gargoyle's drop that sets off the reactor under its ledge (9201000). */
    static final int GARGOYLE_DROP = 4_001_036;
    /** The garments in the order the remains take them (its states 0-3). */
    static final List<Integer> GARMENTS = List.of(4_001_032, 4_001_031, 4_001_033, 4_001_034);
    static final int REMAINS = 9_201_001;
    static final Point REMAINS_STAND = new Point(1438, 185);
    static final Rectangle REMAINS_BOX = new Rectangle(1393, 160, 80, 40);
    static final Point GRAVE_WAIT = new Point(1200, 185);
    static final String CLEAR = "stage4clear";
    static final int SEEK_PX = 1200;
    /** How close a member walks to its room's carrier to hand over its Marks. */
    static final int HAND_OVER_PX = 50;

    /**
     * A mark room: its monsters, the Marks its gate wants, the gate (its item box, Reactor.wz) and where to lay them.
     */
    record MarkRoom(Stage stage, Set<Integer> mobs, int marks, String gate, Rectangle gateBox, Point gateStand) { }

    static final MarkRoom GOLEMS = new MarkRoom(Stage.GOLEM_MAZE, Set.of(9_300_024), 9, "secretgate1",
            new Rectangle(738, 87, 149, 60), new Point(811, 132));
    static final MarkRoom GARGOYLES = new MarkRoom(Stage.GARGOYLE_MAZE, Set.of(9_300_025, 9_300_033, 9_300_026), 8,
            "secretgate2", new Rectangle(339, 58, 149, 60), new Point(412, 102));
    static final MarkRoom SLIMES = new MarkRoom(Stage.SLIME_MAZE, Set.of(9_300_027), 20, "secretgate3",
            new Rectangle(512, -351, 149, 60), new Point(585, -307));
    /** The Gargoyle's reactor and where its drop must lie (990000630). */
    static final int GARGOYLE_REACTOR = 9_201_000;
    static final Rectangle GARGOYLE_BOX = new Rectangle(-162, -238, 160, 60);
    static final Point GARGOYLE_STAND = new Point(-82, -204);

    /** An end room: its garment's box, the garment, the portal to the grave. */
    record EndRoom(Stage stage, int box, int garment) { }

    static final EndRoom PANTS = new EndRoom(Stage.PANTS_END, 9_202_005, 4_001_031);
    static final EndRoom SHOES = new EndRoom(Stage.SHOES_MAZE, 9_202_006, 4_001_032);
    static final EndRoom TOP = new EndRoom(Stage.TOP_END, 9_202_007, 4_001_033);
    static final EndRoom CROWN = new EndRoom(Stage.CROWN_END, 9_202_008, 4_001_034);

    /** The hub's rooms, the portal into each and how many members take it (0: no cap). */
    private enum Room {
        GOLEMS("in00", 0, 4_001_031), GARGOYLES("in02", 3, 4_001_033), SHOES("in01", 1, 4_001_032),
        SLIMES("in03", 0, 4_001_034);

        final String portal;
        final int cap;
        final int garment;

        Room(String portal, int cap, int garment) {
            this.portal = portal;
            this.cap = cap;
            this.garment = garment;
        }
    }

    private AgentGpqWaterwayPlays() {
    }

    static void tick(AgentGpqPorts ports, Stage stage, List<Character> members, EventInstanceManager event,
                     AgentGpqRun run, long nowMs) {
        switch (stage) {
            case WATERWAY -> hub(ports, members, event, run, nowMs);
            case GOLEM_MAZE -> markRoom(ports, GOLEMS, members, event, run, nowMs);
            case GARGOYLE_MAZE -> {
                gargoyleReactor(ports, members, run, nowMs);
                markRoom(ports, GARGOYLES, members, event, run, nowMs);
            }
            case SLIME_MAZE -> markRoom(ports, SLIMES, members, event, run, nowMs);
            case PANTS_END -> endRoom(ports, PANTS, members, event, run, nowMs);
            case SHOES_MAZE -> endRoom(ports, SHOES, members, event, run, nowMs);
            case TOP_END -> endRoom(ports, TOP, members, event, run, nowMs);
            case CROWN_END -> endRoom(ports, CROWN, members, event, run, nowMs);
            case GRAVE -> grave(ports, members, event, run, nowMs);
            default -> {
            }
        }
    }

    // ---- the hub ----

    static void hub(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                    long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (run.once("clothes")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.CLOTHES);
        }
        boolean junior = run.adopted().stream().map(ports::member)
                .anyMatch(member -> member != null && AgentGpqRoles.junior(member));
        if (!junior && !have(ports, event, run, GARMENTS.get(3)) && run.once("no-junior")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.NO_JUNIOR);
        }
        for (int slot = 0; slot < bySlot.size(); slot++) {
            Character member = bySlot.get(slot);
            Room room = room(ports, event, run, member, slot);
            if (room == null) {
                // every garment found or on its way: on to the grave through the golem maze and the pants room
                noteRole(run, member, "waterway", "go on to the grave");
                takePortal(ports, run, member, Room.GOLEMS.portal, nowMs);
                continue;
            }
            if (room == Room.SLIMES && run.once("junior-" + member.getId())) {
                ports.sayInParty(member, AgentGpqLines.JUNIOR_ROOM);
            }
            noteRole(run, member, "waterway", "take the " + room.name().toLowerCase(Locale.ROOT) + " room");
            takePortal(ports, run, member, room.portal, nowMs);
        }
    }

    /** The member's room: the slime room for a Lv&lt;=30 member, else its slot's, else the first still to do. */
    private static Room room(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, Character member,
                             int slot) {
        int id = member.getId();
        for (Room held : Room.values()) {
            if (run.holder("wroom:" + held + ":" + id) == id) {
                if (!have(ports, event, run, held.garment)) {
                    return held;
                }
                run.release("wroom:" + held + ":" + id);
            }
        }
        List<Room> order = new ArrayList<>();
        if (AgentGpqRoles.junior(member)) {
            order.add(Room.SLIMES);
        }
        Room[] shared = {Room.GOLEMS, Room.GARGOYLES, Room.SHOES};
        order.add(shared[slot % shared.length]);
        order.addAll(List.of(shared));
        for (Room room : order) {
            if (have(ports, event, run, room.garment) || room.cap > 0 && teamSize(run, room) >= room.cap) {
                continue;
            }
            run.claim("wroom:" + room + ":" + id, id);
            return room;
        }
        return null;
    }

    // ---- the mark rooms ----

    /**
     * Fight, pick up every Mark, hand them to the room's carrier (the lowest slot here), who lays the exact count on
     * the gate; with the gate open everyone takes it.
     */
    static void markRoom(AgentGpqPorts ports, MarkRoom room, List<Character> members, EventInstanceManager event,
                         AgentGpqRun run, long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        Character carrier = bySlot.getFirst();
        String stage = AgentGpqMoves.lower(room.stage());
        boolean fighting = ports.monstersOn(event, room.stage().mapId(), room.mobs()) > 0;
        int held = bySlot.stream().mapToInt(member -> ports.carried(member, MARK)).sum();
        for (Character member : bySlot) {
            Reactor gate = reactorNamed(ports, member, room.gate());
            if (gate != null && gate.getState() > 0) {
                noteRole(run, member, stage, "go through the open gate");
                takePortal(ports, run, member, "in00", nowMs);
                continue;
            }
            boolean onGate = !dropsIn(ports, member, MARK, room.gateBox()).isEmpty();
            if (pickUpMark(ports, run, member, room, member == carrier, nowMs)) {
                noteRole(run, member, stage, "pick up a mark");
                continue;
            }
            int carried = ports.carried(member, MARK);
            if (member == carrier && (carried >= room.marks() || onGate)) {
                if (!onGate) {
                    noteRole(run, member, stage, "lay " + room.marks() + " marks on the gate");
                    dropAt(ports, run, member, MARK, room.marks(), room.gateStand(), nowMs);
                } else {
                    waitAt(ports, member, room.gateStand()); // the gate takes them five seconds after they land
                }
                continue;
            }
            if (member != carrier && carried > 0 && carrier.getPosition() != null) {
                noteRole(run, member, stage, "hand its marks to " + carrier.getName());
                dropAt(ports, run, member, MARK, carried, new Point(carrier.getPosition()), nowMs);
                continue;
            }
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (entry != null && fighting) {
                noteRole(run, member, stage, "fight");
                ports.gateway().grind(entry, room.mobs());
                continue;
            }
            boolean lying = ports.items(member).stream()
                    .anyMatch(drop -> drop.getItemId() == MARK && !drop.isPickedUp());
            if (member == carrier && !lying && held < room.marks() && run.once("short-" + room.stage())) {
                ports.sayInParty(member, AgentGpqLines.MARKS_SHORT);
            }
            noteRole(run, member, stage, "wait by the gate");
            waitAt(ports, member, room.gateStand());
        }
    }

    /**
     * Picks up the nearest Mark lying off the gate; the carrier takes any, the others only a monster's drop (a Mark a
     * member handed over is the carrier's to take, and the carrier's own stack on the gate stays there).
     */
    private static boolean pickUpMark(AgentGpqPorts ports, AgentGpqRun run, Character member, MarkRoom room,
                                      boolean carrier, long nowMs) {
        Point at = member.getPosition();
        MapItem nearest = null;
        for (MapItem drop : ports.items(member)) {
            if (drop.getItemId() != MARK || drop.isPickedUp() || drop.getPosition() == null || at == null
                    || room.gateBox().contains(drop.getPosition()) || !carrier && drop.isPlayerDrop()) {
                continue;
            }
            if (drop.getPosition().distanceSq(at) <= (double) SEEK_PX * SEEK_PX && (nearest == null
                    || drop.getPosition().distanceSq(at) < nearest.getPosition().distanceSq(at))) {
                nearest = drop;
            }
        }
        if (nearest == null) {
            return false;
        }
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry != null && AgentGpqMoves.standAt(ports, entry, member, new Point(nearest.getPosition()),
                AgentGpqMoves.REACH_PX) && run.ready(member.getId(), nowMs)) {
            ports.gateway().stop(entry);
            ports.pickUp(member, MARK);
            run.busy(member.getId(), nowMs + AgentGpqMoves.ITEM_PAUSE_MS);
        }
        return true;
    }

    /**
     * The gargoyle maze's first step: the Gargoyle's drop must lie on the reactor under its ledge; one that fell
     * elsewhere is picked up and laid there (anyone may take it here).
     */
    static void gargoyleReactor(AgentGpqPorts ports, List<Character> members, AgentGpqRun run, long nowMs) {
        for (Character member : bySlot(members, run)) {
            boolean armed = AgentGpqMoves.live(ports, member, GARGOYLE_REACTOR).stream()
                    .anyMatch(reactor -> reactor.getState() == 0);
            if (!armed) {
                return;
            }
            if (ports.carried(member, GARGOYLE_DROP) > 0) {
                noteRole(run, member, "gargoyle_maze", "lay the gargoyle's drop on its ledge");
                dropAt(ports, run, member, GARGOYLE_DROP, 1, GARGOYLE_STAND, nowMs);
                return;
            }
            boolean lyingOff = ports.items(member).stream().anyMatch(drop -> drop.getItemId() == GARGOYLE_DROP
                    && !drop.isPickedUp() && drop.getPosition() != null && !GARGOYLE_BOX.contains(drop.getPosition()));
            if (lyingOff && pickUpNearest(ports, run, member, GARGOYLE_DROP, SEEK_PX * 2, nowMs)) {
                noteRole(run, member, "gargoyle_maze", "pick up the gargoyle's drop");
                return;
            }
        }
    }

    // ---- the end rooms ----

    /**
     * Hit the garment's box until the garment drops, pick it up, then take the end portal to the grave; once anyone
     * here holds it (or it is already on its way) everyone goes.
     */
    static void endRoom(AgentGpqPorts ports, EndRoom room, List<Character> members, EventInstanceManager event,
                        AgentGpqRun run, long nowMs) {
        String stage = AgentGpqMoves.lower(room.stage());
        boolean held = have(ports, event, run, room.garment());
        for (Character member : bySlot(members, run)) {
            if (!held && pickUpNearest(ports, run, member, room.garment(), SEEK_PX * 2, nowMs)) {
                noteRole(run, member, stage, "pick up the garment");
                continue;
            }
            List<Reactor> boxes = AgentGpqMoves.live(ports, member, room.box());
            if (!held && !boxes.isEmpty()) {
                noteRole(run, member, stage, "hit the box at "
                        + AgentGpqMoves.pointText(boxes.getFirst().getPosition()));
                hit(ports, run, member, boxes.getFirst(), nowMs);
                continue;
            }
            noteRole(run, member, stage, "go to the grave");
            takePortal(ports, run, member, "guild1F00", nowMs);
        }
    }

    // ---- the grave ----

    static void grave(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                      long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (event != null && "true".equals(event.getProperty(CLEAR))) {
            for (Character member : bySlot) {
                noteRole(run, member, "grave", "go on through the ghost's gate");
                takePortal(ports, run, member, "next00", nowMs);
            }
            return;
        }
        int wants = remainsWant(ports, event, run);
        if (wants >= GARMENTS.size() && run.once("ghost")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.GHOST);
        }
        boolean pending = ports.itemsOn(event, Stage.GRAVE.mapId()).stream().anyMatch(drop ->
                GARMENTS.contains(drop.getItemId()) && !drop.isPickedUp() && drop.getPosition() != null
                        && REMAINS_BOX.contains(drop.getPosition()));
        for (Character member : bySlot) {
            if (wants >= 0 && wants < GARMENTS.size() && !pending && ports.carried(member, GARMENTS.get(wants)) > 0) {
                noteRole(run, member, "grave", "lay garment " + (wants + 1) + " on the remains");
                dropAt(ports, run, member, GARMENTS.get(wants), 1, REMAINS_STAND, nowMs);
                pending = true;
                continue;
            }
            noteRole(run, member, "grave", "wait by the remains");
            waitAt(ports, member, GRAVE_WAIT);
        }
    }

    /**
     * Which garment the remains want next (0 shoes ... 3 crown), 4 once the crown is on, -1 while nobody has seen
     * them. The reactor leaves the map after the crown; the last state seen tells that apart from not seen yet.
     */
    static int remainsWant(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run) {
        List<Reactor> remains = ports.liveReactorsOn(event, Stage.GRAVE.mapId(), REMAINS);
        if (!remains.isEmpty()) {
            int state = remains.getFirst().getState();
            run.mark("remains", state + 1L);
            return state;
        }
        long seen = run.mark("remains");
        return seen >= GARMENTS.size() ? GARMENTS.size() : -1;
    }

    // ---- helpers ----

    /**
     * True once the garment is found: a run member carries it, it lies in the grave, or the remains have taken it.
     */
    static boolean have(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, int garment) {
        int index = GARMENTS.indexOf(garment);
        long remains = run.mark("remains") - 1L;
        if (remains > index) {
            return true;
        }
        for (int memberId : run.adopted()) {
            Character member = ports.member(memberId);
            if (member != null && ports.carried(member, garment) > 0) {
                return true;
            }
        }
        for (MapItem drop : ports.itemsOn(event, Stage.GRAVE.mapId())) {
            if (drop.getItemId() == garment && !drop.isPickedUp()) {
                return true;
            }
        }
        return false;
    }

    private static int teamSize(AgentGpqRun run, Room room) {
        int size = 0;
        for (int memberId : run.adopted()) {
            if (run.holder("wroom:" + room + ":" + memberId) == memberId) {
                size++;
            }
        }
        return size;
    }
}
