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
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.standAt;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitAt;

/**
 * Stage 3, the vassals (GPQ plan B5 G3), on the Fountain of the Wiseman (990000500), the Center Banquet (501) and the
 * Wine Cellar (502). The fountain (9040006) hides one offering per area before it; the leader asks it, and it says
 * aloud how the vassals took the offerings that stood (decision 7) and shows its wrong effect. The offerings are
 * finite: four Medals of Valor from the Black Knights and four Scrolls of Wisdom from the Myst Knights here, four
 * Spoiled Food under four of the banquet's twelve plates, four Jr. Necki Drinks in four of the cellar's twelve boxes
 * (the rest are empty: the party tries them all, four hits each).
 *
 * <p>Each tick, on the fountain: the members pick up every offering lying outside the areas (the fountain refuses a
 * try while one more lies anywhere on the map), and the party sets the try AgentGpqVassals picks, one offering per
 * area. Only its dropper can pick an offering back up here (MapId self-loot), so each member re-places its own. With
 * the try set, the leader is told to ask; a new wrong effect means the try that stood was judged, and its verdict is
 * worked out from that try and the fountain's combination exactly as 9040006.js counts it (what it just said). Members
 * not placing fight the knights (the misses' extra knights too) or work the plates and boxes, one team per room.
 */
final class AgentGpqVassalPlays {
    static final int FOUNTAIN = Stage.FOUNTAIN.mapId();
    static final int BANQUET = Stage.BANQUET.mapId();
    static final int CELLAR = Stage.CELLAR.mapId();
    /** The offerings by kind, as the fountain numbers them: 0 Medal of Valor ... 3 Jr. Necki Drink. */
    static final List<Integer> OFFERINGS = List.of(4_001_027, 4_001_028, 4_001_029, 4_001_030);
    static final int FOOD = 4_001_029;
    static final int DRINK = 4_001_030;
    /** Black Knight and Myst Knight (drop Medals and Scrolls), and the dropless pair each miss spawns. */
    static final Set<Integer> KNIGHTS = Set.of(9_300_022, 9_300_023, 9_300_036, 9_300_037);
    /** The banquet's plates (9202003 hold food, 9202010 nothing) and the cellar's boxes (9202004 drink, 9202011). */
    static final Set<Integer> PLATES = Set.of(9_202_003, 9_202_010);
    static final Set<Integer> BOXES = Set.of(9_202_004, 9_202_011);
    /** The four offering areas (990000500 area, in WZ order: getArea(0..3)). */
    static final List<Rectangle> AREAS = List.of(new Rectangle(-230, 120, 91, 47), new Rectangle(-103, 116, 125, 51),
            new Rectangle(71, 117, 133, 52), new Rectangle(222, 128, 96, 40));
    static final int FLOOR_Y = 155;
    /** Where members with nothing to do wait: the floor left of the areas, clear of the knights' spawns. */
    static final Point IDLE = new Point(-480, FLOOR_Y);
    static final String COMBO = "stage3combo";
    static final String CLEAR = "stage3clear";
    /** How many members work each room at most. */
    static final int ROOM_TEAM = 2;

    private enum Task { KNIGHTS, BANQUET, CELLAR }

    private AgentGpqVassalPlays() {
    }

    static void tick(AgentGpqPorts ports, Stage stage, List<Character> members, EventInstanceManager event,
                     AgentGpqRun run, long nowMs) {
        observe(ports, members, event, run);
        if (stage == Stage.FOUNTAIN) {
            fountain(ports, members, event, run, nowMs);
        } else {
            room(ports, stage, members, event, run, nowMs);
        }
    }

    // ---- what the party knows of the fountain ----

    /** A new wrong effect: the try that stood was judged (or, after the seventh miss, the combination was redrawn). */
    static void observe(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run) {
        if (event == null || members.isEmpty()) {
            return;
        }
        AgentGpqVassals vassals = run.vassals();
        int wrong = event.wrongEffectCount(FOUNTAIN);
        if (wrong <= vassals.judgedSeen()) {
            return;
        }
        Character speaker = bySlot(members, run).getFirst();
        int combo = AgentGpqVassals.comboOf(event.getProperty(COMBO));
        if (combo < 0) {
            vassals.newDraw();
            vassals.seen(wrong);
            ports.sayInParty(speaker, AgentGpqLines.FOUNTAIN_RESET);
            return;
        }
        int[] stood = standing(ports.itemsOn(event, FOUNTAIN));
        if (stood == null) {
            vassals.seen(wrong);
            return;
        }
        int tried = AgentGpqVassals.code(stood);
        vassals.judged(wrong, stood, AgentGpqVassals.verdict(tried, combo));
        ports.sayInParty(speaker, AgentGpqLines.FOUNTAIN_AGAIN);
    }

    /**
     * The try the fountain would judge now, kind per area, exactly as 9040006.js reads the floor: four offerings in
     * all on the map, each in an area, every area holding one; null otherwise.
     */
    static int[] standing(List<MapItem> floor) {
        List<MapItem> offerings = offerings(floor);
        if (offerings.size() != AREAS.size()) {
            return null;
        }
        int[] kinds = {-1, -1, -1, -1};
        for (MapItem offering : offerings) {
            int area = areaOf(offering.getPosition());
            if (area >= 0) {
                kinds[area] = OFFERINGS.indexOf(offering.getItemId());
            }
        }
        for (int kind : kinds) {
            if (kind < 0) {
                return null;
            }
        }
        return kinds;
    }

    // ---- the fountain ----

    static void fountain(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                         long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (cleared(event)) {
            for (Character member : bySlot) {
                noteRole(run, member, "vassals", "go on through the water gate");
                takePortal(ports, run, member, "next00", nowMs);
            }
            return;
        }
        if (run.once("fountain")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.FOUNTAIN_START);
        }
        List<MapItem> floor = ports.itemsOn(event, FOUNTAIN);
        int combo = AgentGpqVassals.comboOf(event.getProperty(COMBO));
        int[] trying = combo >= 0 ? run.vassals().trying() : null;
        if (trying != null && set(floor, trying)) {
            AgentGpqVassals vassals = run.vassals();
            if (run.once("ready-" + vassals.draws() + "-" + vassals.judgedSeen())) {
                ports.sayInParty(bySlot.getFirst(), AgentGpqLines.FOUNTAIN_READY);
            }
        }
        if (trying != null) {
            askForHumanOfferings(ports, bySlot, floor, trying, run);
        }
        for (int i = 0; i < bySlot.size(); i++) {
            Character member = bySlot.get(i);
            if (pickUpStray(ports, run, member, floor, nowMs)) {
                continue;
            }
            if (trying != null && place(ports, run, member, floor, trying, nowMs)) {
                continue;
            }
            Task task = task(ports, event, run, member, i);
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (task == Task.KNIGHTS && entry != null) {
                noteRole(run, member, "vassals", "fight the knights");
                ports.gateway().grind(entry, KNIGHTS);
            } else if (task == Task.BANQUET) {
                noteRole(run, member, "vassals", "go try the banquet's plates");
                if (run.once("banquet-" + member.getId())) {
                    ports.sayInParty(member, AgentGpqLines.BANQUET);
                }
                takePortal(ports, run, member, "in00", nowMs);
            } else if (task == Task.CELLAR) {
                noteRole(run, member, "vassals", "go try the cellar's boxes");
                if (run.once("cellar-" + member.getId())) {
                    ports.sayInParty(member, AgentGpqLines.CELLAR);
                }
                takePortal(ports, run, member, "in01", nowMs);
            } else {
                noteRole(run, member, "vassals", "wait by the fountain");
                waitAt(ports, member, IDLE);
            }
        }
    }

    /**
     * Picks up the nearest offering lying outside the areas that this member may pick up (a knight's drop, or its own
     * drop: here only a dropper picks its drop back up) and no other member is after; true while it does.
     */
    static boolean pickUpStray(AgentGpqPorts ports, AgentGpqRun run, Character member, List<MapItem> floor,
                               long nowMs) {
        int id = member.getId();
        MapItem nearest = null;
        double best = Double.MAX_VALUE;
        Point at = member.getPosition();
        for (MapItem offering : offerings(floor)) {
            if (areaOf(offering.getPosition()) >= 0 || !mayPickUp(offering, id) || at == null) {
                continue;
            }
            String task = "pick:" + offering.getObjectId();
            if (run.holder(task) != 0 && run.holder(task) != id) {
                continue;
            }
            double distance = offering.getPosition().distanceSq(at);
            if (distance < best) {
                best = distance;
                nearest = offering;
            }
        }
        if (nearest == null) {
            run.releaseAll(id, "pick:");
            return false;
        }
        run.claim("pick:" + nearest.getObjectId(), id);
        noteRole(run, member, "vassals", "pick up the " + kindName(nearest.getItemId()) + " lying out");
        pickUp(ports, run, member, nearest, nowMs);
        return true;
    }

    /**
     * The member's part in setting {@code trying}: it picks up its own offering where the area wants another kind (or
     * holds one too many), else drops the wanted kind into an empty area if it carries one; true while it does.
     */
    static boolean place(AgentGpqPorts ports, AgentGpqRun run, Character member, List<MapItem> floor, int[] trying,
                         long nowMs) {
        int id = member.getId();
        for (int area = 0; area < AREAS.size(); area++) {
            List<MapItem> here = in(floor, area);
            int wanted = OFFERINGS.get(trying[area]);
            MapItem kept = here.stream().filter(offering -> offering.getItemId() == wanted).findFirst().orElse(null);
            for (MapItem offering : here) {
                if (offering != kept && mayPickUp(offering, id)) {
                    noteRole(run, member, "vassals", "take back the " + kindName(offering.getItemId())
                            + " from vassal " + (area + 1));
                    pickUp(ports, run, member, offering, nowMs);
                    return true;
                }
            }
        }
        for (int area = 0; area < AREAS.size(); area++) {
            String task = "offer:" + area;
            int wanted = OFFERINGS.get(trying[area]);
            if (!in(floor, area).isEmpty() || ports.carried(member, wanted) <= 0) {
                if (run.holder(task) == id) {
                    run.release(task); // set, or it gave its last one elsewhere: another carrier may take it
                }
                continue;
            }
            if (!run.claim(task, id)) {
                continue;
            }
            noteRole(run, member, "vassals", "offer the " + kindName(wanted) + " to vassal " + (area + 1));
            dropAt(ports, run, member, wanted, 1, stand(ports, member, area), nowMs);
            return true;
        }
        return false;
    }

    /** A human's offering lying where the try wants another kind: only they can pick it up, so they are asked. */
    private static void askForHumanOfferings(AgentGpqPorts ports, List<Character> bySlot, List<MapItem> floor,
                                             int[] trying, AgentGpqRun run) {
        for (int area = 0; area < AREAS.size(); area++) {
            int wanted = OFFERINGS.get(trying[area]);
            for (MapItem offering : in(floor, area)) {
                boolean companion = bySlot.stream().anyMatch(member -> member.getId() == offering.getOwnerId());
                if (offering.isPlayerDrop() && !companion && offering.getItemId() != wanted
                        && run.once("human-offering-" + offering.getObjectId() + "-" + run.vassals().judgedSeen())) {
                    ports.sayInParty(bySlot.getFirst(),
                            AgentGpqLines.YOUR_OFFERING.replace("{n}", String.valueOf(area + 1)));
                }
            }
        }
    }

    /**
     * The member's errand when it has no offering to move: its slot's own (slot 0 the knights, 1 the banquet, 2 the
     * cellar, and so on round), else the first left of knights, banquet, cellar; null when nothing is left.
     */
    private static Task task(AgentGpqPorts ports, EventInstanceManager event, AgentGpqRun run, Character member,
                             int slot) {
        boolean knights = ports.monstersOn(event, FOUNTAIN, KNIGHTS) > 0;
        boolean banquet = live(ports, event, BANQUET, PLATES) > 0 || lying(ports, event, BANQUET, FOOD);
        boolean cellar = live(ports, event, CELLAR, BOXES) > 0 || lying(ports, event, CELLAR, DRINK);
        Task preferred = Task.values()[slot % Task.values().length];
        for (Task task : List.of(preferred, Task.KNIGHTS, Task.BANQUET, Task.CELLAR)) {
            boolean open = task == Task.KNIGHTS ? knights : task == Task.BANQUET ? banquet : cellar;
            if (!open) {
                continue;
            }
            if (task == Task.KNIGHTS) {
                return task;
            }
            String team = "team:" + task.name().toLowerCase(Locale.ROOT);
            if (run.holder(team + ":" + member.getId()) == member.getId() || teamSize(run, team) < ROOM_TEAM) {
                run.claim(team + ":" + member.getId(), member.getId());
                return task;
            }
        }
        return null;
    }

    // ---- the banquet hall and the wine cellar ----

    /**
     * A room team: picks up the food or drink lying in its room, else hits the nearest plate or box no other member
     * is on (each takes four hits; the empty ones break and give nothing); with every one broken and nothing left
     * lying, back to the fountain.
     */
    static void room(AgentGpqPorts ports, Stage stage, List<Character> members, EventInstanceManager event,
                     AgentGpqRun run, long nowMs) {
        boolean banquet = stage == Stage.BANQUET;
        int prize = banquet ? FOOD : DRINK;
        Set<Integer> reactors = banquet ? PLATES : BOXES;
        String team = banquet ? "team:banquet" : "team:cellar";
        for (Character member : bySlot(members, run)) {
            int id = member.getId();
            if (cleared(event)) {
                run.releaseAll(id, team);
                takePortal(ports, run, member, "out00", nowMs);
                continue;
            }
            MapItem lying = null;
            Point at = member.getPosition();
            for (MapItem drop : ports.items(member)) {
                if (drop.getItemId() == prize && !drop.isPickedUp() && mayPickUp(drop, id) && at != null
                        && (lying == null || drop.getPosition().distanceSq(at) < lying.getPosition().distanceSq(at))) {
                    lying = drop;
                }
            }
            if (lying != null) {
                noteRole(run, member, lower(stage), "pick up the " + kindName(prize));
                pickUp(ports, run, member, lying, nowMs);
                continue;
            }
            Reactor next = nextReactor(ports, run, member, reactors);
            if (next != null) {
                noteRole(run, member, lower(stage), "try the " + (banquet ? "plate" : "box") + " at "
                        + AgentGpqMoves.pointText(next.getPosition()));
                hit(ports, run, member, next, nowMs);
                continue;
            }
            if (reactors.stream().anyMatch(reactorId -> !AgentGpqMoves.live(ports, member, reactorId).isEmpty())) {
                noteRole(run, member, lower(stage), "wait while the others finish theirs");
                continue; // the last ones are a teammate's: leaving now would only bring it straight back
            }
            run.releaseAll(id, team);
            noteRole(run, member, lower(stage), "go back to the fountain");
            takePortal(ports, run, member, "out00", nowMs);
        }
    }

    /** The plate or box the member works: the one it holds while it stands, else the nearest no other member holds. */
    private static Reactor nextReactor(AgentGpqPorts ports, AgentGpqRun run, Character member, Set<Integer> ids) {
        List<Reactor> standing = new ArrayList<>();
        for (int reactorId : ids) {
            standing.addAll(AgentGpqMoves.live(ports, member, reactorId));
        }
        int id = member.getId();
        for (String held : run.claimsOf(id, "try:")) {
            int oid = Integer.parseInt(held.substring("try:".length()));
            Reactor still = standing.stream().filter(reactor -> reactor.getObjectId() == oid).findFirst().orElse(null);
            if (still != null) {
                return still;
            }
            run.release(held);
        }
        Point at = member.getPosition();
        Reactor nearest = null;
        for (Reactor reactor : standing) {
            if (run.holder("try:" + reactor.getObjectId()) != 0 || at == null) {
                continue;
            }
            if (nearest == null || reactor.getPosition().distanceSq(at) < nearest.getPosition().distanceSq(at)) {
                nearest = reactor;
            }
        }
        if (nearest != null && run.claim("try:" + nearest.getObjectId(), id)) {
            return nearest;
        }
        return null;
    }

    // ---- helpers ----

    static boolean cleared(EventInstanceManager event) {
        return event != null && "true".equals(event.getProperty(CLEAR));
    }

    /** True when every area holds exactly the kind {@code trying} wants and no other offering lies on the floor. */
    static boolean set(List<MapItem> floor, int[] trying) {
        int[] standing = standing(floor);
        if (standing == null) {
            return false;
        }
        for (int area = 0; area < AREAS.size(); area++) {
            if (standing[area] != trying[area]) {
                return false;
            }
        }
        return true;
    }

    static int areaOf(Point at) {
        if (at == null) {
            return -1;
        }
        for (int area = 0; area < AREAS.size(); area++) {
            if (AREAS.get(area).contains(at)) {
                return area;
            }
        }
        return -1;
    }

    /** The offerings lying on {@code floor}. */
    static List<MapItem> offerings(List<MapItem> floor) {
        List<MapItem> out = new ArrayList<>();
        for (MapItem drop : floor) {
            if (OFFERINGS.contains(drop.getItemId()) && !drop.isPickedUp() && drop.getPosition() != null) {
                out.add(drop);
            }
        }
        return out;
    }

    private static List<MapItem> in(List<MapItem> floor, int area) {
        List<MapItem> out = new ArrayList<>();
        for (MapItem offering : offerings(floor)) {
            if (areaOf(offering.getPosition()) == area) {
                out.add(offering);
            }
        }
        return out;
    }

    /** Here only a player drop's own dropper picks it up (MapId self-loot); anyone takes a monster's or a box's. */
    static boolean mayPickUp(MapItem drop, int memberId) {
        return !drop.isPlayerDrop() || drop.getOwnerId() == memberId;
    }

    private static void pickUp(AgentGpqPorts ports, AgentGpqRun run, Character member, MapItem drop, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry != null && standAt(ports, entry, member, new Point(drop.getPosition()), AgentGpqMoves.REACH_PX)
                && run.ready(member.getId(), nowMs)) {
            ports.gateway().stop(entry);
            ports.pickUp(member, drop.getItemId());
            run.busy(member.getId(), nowMs + AgentGpqMoves.ITEM_PAUSE_MS);
        }
    }

    /** The floor in the middle of {@code area}. */
    private static Point stand(AgentGpqPorts ports, Character member, int area) {
        Rectangle box = AREAS.get(area);
        Point candidate = new Point((int) box.getCenterX(), FLOOR_Y);
        Point ground = ports.gateway().groundPoint(member.getMap(), candidate);
        return ground != null && box.contains(ground) ? ground : candidate;
    }

    private static int live(AgentGpqPorts ports, EventInstanceManager event, int mapId, Set<Integer> ids) {
        int count = 0;
        for (int id : ids) {
            count += ports.liveReactorsOn(event, mapId, id).size();
        }
        return count;
    }

    private static boolean lying(AgentGpqPorts ports, EventInstanceManager event, int mapId, int itemId) {
        return ports.itemsOn(event, mapId).stream().anyMatch(drop -> drop.getItemId() == itemId && !drop.isPickedUp());
    }

    private static int teamSize(AgentGpqRun run, String team) {
        int size = 0;
        for (int memberId : run.adopted()) {
            if (run.holder(team + ":" + memberId) == memberId) {
                size++;
            }
        }
        return size;
    }

    private static String kindName(int itemId) {
        return switch (OFFERINGS.indexOf(itemId)) {
            case 0 -> "medal";
            case 1 -> "scroll";
            case 2 -> "food";
            case 3 -> "drink";
            default -> "item";
        };
    }

    private static String lower(Stage stage) {
        return AgentGpqMoves.lower(stage);
    }
}
