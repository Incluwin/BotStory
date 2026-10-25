package server.agents.capabilities.expedition.gpq;

import client.BuffStat;
import client.Character;
import constants.skills.Assassin;
import constants.skills.FPWizard;
import constants.skills.Hermit;
import constants.skills.Rogue;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Sharenian in miniature for the GPQ companion plays (GPQ plan B5 G3): the instance maps with their portals,
 * reactors, monsters and drops where Map.wz and the brief place them, each stage's script rules (the Gatekeeper's
 * patterns, the item gates that take an exact stack five seconds after it lands, the fountain's verdicts, the grave's
 * garments, the King's Corridor's rock), the air that kills a member without a Protector Rock, the Dark Muscle Stones'
 * corridor only Dark Sight crosses, and the Room of Justice's towers only Teleport, Flash Jump or Haste climb.
 * Movement is a straight walk at a fixed speed; a fight is a member standing by a monster taking its HP down.
 *
 * <p>Ben, the human leader, does what a player does when the companions tell him: talks to the Gatekeeper, the
 * fountain and the ghost, walks on when a stage opens, and takes the Rubian to the guard. The companions are driven
 * only by the run (AgentGpqRun with every stage play), through this world as their AgentGpqPorts.
 */
final class GpqWorld implements AgentGpqPorts {
    static final long STEP_MS = 250L;
    static final int BEN = 900;
    static final int MIRA = 11;
    static final int KADE = 12;
    static final int LIO = 13;
    static final List<Integer> COMPANIONS = List.of(11, 12, 13, 14, 15, 16, 17);
    private static final int SPEED_PX = 120;
    private static final int FIGHT_REACH_PX = 70;
    private static final int PICK_REACH_PX = 60;
    private static final long ITEM_GATE_MS = 5_000L;
    private static final long AIR_MS = 2_500L;
    static final long EVENT_MS = 90 * 60_000L;
    static final int CAMP = AgentGpqRun.CAMP_MAP_ID;
    private static final int ROCK = AgentGpqPorts.PROTECTOR_ROCK;
    private static final int MEDAL = 4_001_027;
    private static final int SCROLL = 4_001_028;
    private static final int FOOD = 4_001_029;
    private static final int DRINK = 4_001_030;
    private static final int KEY = AgentGpqSpearPlays.KEY;
    private static final int RUSTY_KEY = AgentGpqSpearPlays.RUSTY_KEY;
    private static final int SPEAR = AgentGpqSpearPlays.SPEAR;
    private static final int MARK = AgentGpqWaterwayPlays.MARK;
    private static final int GARGOYLE_DROP = AgentGpqWaterwayPlays.GARGOYLE_DROP;
    private static final int RUBIAN = AgentGpqThronePlays.RUBIAN;
    private static final int ERGOTH = AgentGpqThronePlays.ERGOTH;

    // ---- the world's state ----

    final class Body {
        final int id;
        final String name;
        final int level;
        final Map<Integer, Integer> skills;
        int mapId;
        Point pos;
        int hp = 1;
        boolean inEvent = true;
        Point dest;
        Set<Integer> grind;
        final Map<Integer, Integer> bag = new HashMap<>();
        boolean rockWorn;
        long noRockSinceMs = -1L;
        long darkSightUntilMs;
        long hasteUntilMs;
        long diedAtMs = -1L;
        int deaths;
        int dps = 9_000;
        Character character;
        AgentRuntimeEntry entry;

        Body(int id, String name, int level, Map<Integer, Integer> skills) {
            this.id = id;
            this.name = name;
            this.level = level;
            this.skills = skills;
        }

        int count(int itemId) {
            return bag.getOrDefault(itemId, 0);
        }

        void give(int itemId, int quantity) {
            bag.merge(itemId, quantity, Integer::sum);
            if (bag.get(itemId) <= 0) {
                bag.remove(itemId);
            }
        }

        boolean alive() {
            return hp > 0;
        }
    }

    final class Mob {
        final int oid = nextOid++;
        final int mobId;
        final int mapId;
        final Point pos;
        int hp;
        final int dropItem;

        Mob(int mobId, int mapId, Point pos, int hp, int dropItem) {
            this.mobId = mobId;
            this.mapId = mapId;
            this.pos = new Point(pos);
            this.hp = hp;
            this.dropItem = dropItem;
        }
    }

    final class Drop {
        final int oid = nextOid++;
        final int itemId;
        final int quantity;
        final int mapId;
        final Point pos;
        final Body dropper;
        boolean picked;
        final MapItem item = stub(MapItem.class);

        Drop(int itemId, int quantity, int mapId, Point pos, Body dropper) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.mapId = mapId;
            this.pos = new Point(pos);
            this.dropper = dropper;
            when(item.getItemId()).thenReturn(itemId);
            when(item.getObjectId()).thenReturn(oid);
            when(item.getPosition()).thenAnswer(call -> new Point(this.pos));
            when(item.isPickedUp()).thenAnswer(call -> picked);
            when(item.isPlayerDrop()).thenReturn(dropper != null);
            when(item.getOwnerId()).thenReturn(dropper == null ? 0 : dropper.id);
        }
    }

    /** A reactor: hit ones break after {@code hits} and run {@code broken}; item ones take {@code wants} in {@code box}. */
    final class Box {
        final int oid;
        final int reactorId;
        final String name;
        final int mapId;
        final Point pos;
        int state;
        int hitsTaken;
        boolean alive = true;
        int hits = 5;
        long respawnMs = -1L;
        Consumer<Box> broken = box -> { };
        /** Item gate: the item and exact quantity it takes, where, and what taking it does. */
        int wantsItem;
        int wantsQuantity;
        Rectangle box;
        Consumer<Box> took = box -> { };
        long pendingAtMs = -1L;
        Drop pending;
        final Reactor reactor = stub(Reactor.class);

        Box(int reactorId, String name, int mapId, Point pos) {
            this.oid = nextOid++;
            this.reactorId = reactorId;
            this.name = name;
            this.mapId = mapId;
            this.pos = pos;
            when(reactor.getId()).thenReturn(reactorId);
            when(reactor.getName()).thenReturn(name);
            when(reactor.getObjectId()).thenReturn(oid);
            when(reactor.getPosition()).thenReturn(new Point(pos));
            when(reactor.getState()).thenAnswer(call -> (byte) state);
            when(reactor.isAlive()).thenAnswer(call -> alive);
            when(reactor.isActive()).thenAnswer(call -> alive);
        }
    }

    private int nextOid = 5_000;
    long now = 100_000L;
    final long startedAtMs = now;
    private final Random random;
    final Map<Integer, Body> bodies = new LinkedHashMap<>();
    final List<Mob> mobs = new ArrayList<>();
    final List<Drop> drops = new ArrayList<>();
    final List<Box> boxes = new ArrayList<>();
    final Map<String, String> properties = new HashMap<>();
    private final Map<Integer, Integer> wrongEffects = new HashMap<>();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    private final Map<String, String[]> portalTargets = new HashMap<>();
    private final Map<Integer, Point> arrivals = new HashMap<>();
    private final List<long[]> scheduledAt = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    boolean cleared;
    long clearedAtMs = -1L;
    final List<String> lines = new ArrayList<>();
    /** When Ben first stood on each map, in order. */
    final Map<Integer, Long> benAt = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    int fountainJudged;
    int fountainDraws;
    int gatekeeperMisses;
    /** Members who walked onto the Gatekeeper's map without a rock on. */
    final List<String> rocklessAt300 = new ArrayList<>();

    final PrimitiveCapabilityGateway gateway = stub(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuest = stub(PartyQuestGateway.class);
    final EventInstanceManager event = stub(EventInstanceManager.class);
    final AgentGpqRun run;
    /** The lines that ask Ben to talk to the stage's NPC. */
    private static final Set<String> TALK_LINES = Set.of(AgentGpqLines.GATEKEEPER_START,
            AgentGpqLines.GATEKEEPER_READY, AgentGpqLines.GATEKEEPER_WRONG, AgentGpqLines.FOUNTAIN_START,
            AgentGpqLines.FOUNTAIN_READY, AgentGpqLines.FOUNTAIN_RESET, AgentGpqLines.GHOST);
    private long benTalkAtMs = -1L;
    private int talkMapId;
    private long benMoveAtMs = -1L;

    GpqWorld(long seed) {
        random = new Random(seed);
        add(new Body(BEN, "Ben", 70, Map.of()));
        add(new Body(MIRA, "Mira", 25, Map.of()));
        add(new Body(KADE, "Kade", 120, Map.of(Rogue.DARK_SIGHT, 20, Assassin.HASTE, 20, Hermit.FLASH_JUMP, 20)));
        add(new Body(LIO, "Lio", 120, Map.of(FPWizard.TELEPORT, 20)));
        add(new Body(14, "Orrin", 120, Map.of()));
        add(new Body(15, "Magna", 120, Map.of()));
        add(new Body(16, "Rook", 120, Map.of()));
        add(new Body(17, "Ivy", 120, Map.of()));
        bodies.get(MIRA).dps = 2_500;
        buildMaps();
        populate();
        wire();
        run = new AgentGpqRun(AgentGpqStagePlays.all(this), ErgothFight::new,
                (members, ev, r, nowMs) -> AgentGpqThronePlays.afterClear(this, members, ev, r, nowMs), this);
        int ordinal = 0;
        for (int id : COMPANIONS) {
            run.adopt(id, ordinal++);
        }
        properties.put(AgentGpqStagePlays.ENTRY_TIMESTAMP, String.valueOf(now + 180_000L));
        for (Body body : bodies.values()) {
            body.mapId = Stage.EXCAVATION.mapId();
            body.pos = new Point(-200 + body.id * 10, 161);
        }
    }

    private void add(Body body) {
        bodies.put(body.id, body);
    }

    Body body(int id) {
        return bodies.get(id);
    }

    private static <T> T stub(Class<T> type) {
        return mock(type, withSettings().stubOnly());
    }

    // ---- the maps ----

    private void buildMaps() {
        portal(990_000_000, 1, "join00", 132, 161, "990000100", "");
        arrival(990_000_000, 0, 161);
        arrival(990_000_100, -900, 437);
        portal(990_000_100, 2, "next00", 238, -704, "990000200", "");
        arrival(990_000_200, -800, 18);
        portal(990_000_200, 2, "next00", 1070, 220, "990000300", "");
        arrival(990_000_300, -500, 163);
        portal(990_000_300, 3, "next00", 537, 140, "990000301", "");
        arrival(990_000_301, 0, 209);
        portal(990_000_301, 1, "next01", 514, 209, "990000400", "");
        arrival(990_000_400, 300, 96);
        portal(990_000_400, 1, "in00", -741, 137, "990000410", "out00");
        portal(990_000_400, 2, "in01", -978, -202, "990000420", "out00");
        portal(990_000_400, 3, "in02", -766, -518, "990000430", "out00");
        portal(990_000_400, 4, "in03", -118, -534, "990000440", "out00");
        portal(990_000_400, 5, "next00", 296, 66, "990000401", "");
        portal(990_000_410, 1, "out00", -792, 134, "990000400", "in00");
        portal(990_000_420, 1, "out00", -840, 135, "990000400", "in01");
        portal(990_000_430, 1, "out00", -1065, 135, "990000400", "in02");
        portal(990_000_430, 2, "in00", 922, -337, "990000431", "out00");
        portal(990_000_431, 1, "out00", -28, 133, "990000430", "in00");
        portal(990_000_440, 1, "out00", -341, 499, "990000400", "in03");
        arrival(990_000_401, 0, 210);
        portal(990_000_401, 1, "next01", 506, 210, "990000500", "");
        arrival(990_000_500, -909, 150);
        portal(990_000_500, 1, "in00", 629, -169, "990000501", "out00");
        portal(990_000_500, 2, "in01", -568, -167, "990000502", "out00");
        portal(990_000_500, 3, "next00", -557, 150, "990000600", "");
        portal(990_000_501, 1, "out00", -1064, 149, "990000500", "in00");
        portal(990_000_502, 1, "out00", 882, 150, "990000500", "in01");
        arrival(990_000_600, -828, 371);
        portal(990_000_600, 1, "in00", 170, 312, "990000610", "out00");
        portal(990_000_600, 2, "in01", 875, 315, "990000620", "out00");
        portal(990_000_600, 3, "in02", 2636, 231, "990000630", "out00");
        portal(990_000_600, 4, "in03", 1871, 329, "990000640", "out00");
        portal(990_000_610, 1, "out00", -857, 184, "990000600", "in00");
        portal(990_000_610, 2, "in00", 811, 131, "990000611", "out00");
        portal(990_000_611, 1, "out00", -361, 185, "990000610", "in00");
        portal(990_000_611, 2, "guild1F00", 2618, 185, "990000700", "");
        portal(990_000_620, 1, "out00", -713, 184, "990000600", "in01");
        portal(990_000_620, 2, "guild1F00", 1089, -465, "990000700", "");
        portal(990_000_630, 1, "out00", -537, 103, "990000600", "in02");
        portal(990_000_630, 2, "in00", 411, 103, "990000631", "out00");
        portal(990_000_631, 1, "out00", -679, 185, "990000630", "in00");
        portal(990_000_631, 2, "guild1F00", 655, 184, "990000700", "");
        portal(990_000_640, 1, "out00", -424, 154, "990000600", "in03");
        portal(990_000_640, 2, "in00", 592, -307, "990000641", "out00");
        portal(990_000_641, 1, "out00", -1183, 182, "990000640", "in00");
        portal(990_000_641, 2, "guild1F00", 1004, -336, "990000700", "");
        arrival(990_000_700, 66, 185);
        portal(990_000_700, 1, "next00", 1688, 160, "990000800", "");
        arrival(990_000_800, -900, 155);
        portal(990_000_800, 1, "boss00", 251, 102, "990000900", "");
        arrival(990_000_900, -650, 146);
        arrival(AgentGpqRun.WAREHOUSE_MAP_ID, 0, 154);
        arrival(AgentGpqRun.RETURNING_MAP_ID, 0, 525);
        arrival(CAMP, -58, 1305);
    }

    private void portal(int mapId, int portalId, String name, int x, int y, String target, String targetPortal) {
        Portal portal = stub(Portal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.getName()).thenReturn(name);
        when(portal.getPosition()).thenReturn(new Point(x, y));
        portals.put(mapId + ":" + name, portal);
        portals.put(mapId + "#" + portalId, portal);
        portalTargets.put(mapId + ":" + name, new String[] {target, targetPortal});
    }

    private void arrival(int mapId, int x, int y) {
        arrivals.put(mapId, new Point(x, y));
    }

    private MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = stub(MapleMap.class);
            when(map.getId()).thenReturn(id);
            when(map.getPortal(anyString())).thenAnswer(call -> portals.get(id + ":" + call.getArgument(0)));
            return map;
        });
    }

    /** True while {@code portal} on {@code mapId} lets {@code body} through (the gate portals' scripts). */
    private boolean open(int mapId, String portal, Body body) {
        return switch (mapId + ":" + portal) {
            case "990000000:join00" -> now >= Long.parseLong(properties.get(AgentGpqStagePlays.ENTRY_TIMESTAMP));
            case "990000300:next00" -> "true".equals(properties.get("stage1clear"));
            case "990000400:in02" -> named(990_000_400, "stonegate").state >= 1;
            case "990000400:next00" -> named(990_000_400, "speargate").state >= 4;
            case "990000430:in00" -> named(990_000_430, "metalgate").state >= 1;
            case "990000500:next00" -> named(990_000_500, "watergate").state >= 1;
            case "990000600:in03" -> body.level <= 30;
            case "990000610:in00" -> named(990_000_610, "secretgate1").state >= 1;
            case "990000630:in00" -> named(990_000_630, "secretgate2").state >= 1;
            case "990000640:in00" -> named(990_000_640, "secretgate3").state >= 1;
            case "990000700:next00" -> "true".equals(properties.get("stage4clear"));
            case "990000800:boss00" -> named(990_000_800, "kinggate").state >= 1;
            default -> true;
        };
    }

    // ---- what lives on them ----

    private void populate() {
        int[][] valley = {{-741, -444}, {252, 49}, {445, -27}, {340, 49}, {-744, 171}, {1032, -70}, {-972, 437},
                {-219, 434}, {422, 431}, {1020, 436}, {10, 429}, {1046, 163}};
        for (int[] at : valley) {
            rockStone(990_000_100, new Point(at[0], at[1]), AgentGpqStagePlays.ROCK_STONE).respawnMs = 60_000L;
        }
        rockStone(990_000_200, new Point(-596, 18), AgentGpqStagePlays.ROCK_STONE_TWO).respawnMs = 1_000L;
        rockStone(990_000_200, new Point(587, 52), AgentGpqStagePlays.ROCK_STONE_TWO).respawnMs = 1_000L;

        int[][] statues = {{9_208_000, -80, 163}, {9_208_000, 132, 163}, {9_208_000, 344, 165}, {9_208_000, 772, 164},
                {9_208_000, 984, 165}, {9_208_000, -292, 163}, {9_208_001, 246, -3}, {9_208_001, -188, -3},
                {9_208_001, 34, -3}, {9_208_001, 893, -3}, {9_208_001, 1106, -4}, {9_208_002, -53, -133},
                {9_208_002, 156, -133}, {9_208_002, 370, -134}, {9_208_002, 806, -134}, {9_208_000, 1212, 164},
                {9_208_002, 1018, -134}, {9_208_002, 1239, -134}, {9_208_002, -273, -133}, {9_208_001, 554, -140}};
        for (int i = 0; i < statues.length; i++) {
            Box statue = new Box(statues[i][0], String.valueOf(i + 1), 990_000_300,
                    new Point(statues[i][1], statues[i][2]));
            statue.hits = Integer.MAX_VALUE;
            statue.broken = this::statueHit;
            boxes.add(statue);
        }

        boxes.add(new Box(9_208_004, "speargate", 990_000_400, new Point(256, 96)));
        itemGate(new Box(9_208_006, "stonegate", 990_000_400, new Point(-767, -532)), KEY, 1,
                AgentGpqSpearPlays.STONEGATE_BOX, box -> box.state = 1);
        for (int x : new int[] {-182, 447, 1073}) {
            Box spear = new Box(AgentGpqSpearPlays.SEALED_SPEAR, "", 990_000_410, new Point(x, -1157));
            spear.broken = box -> drop(SPEAR, 1, box.mapId, box.pos, null);
            boxes.add(spear);
        }
        mobs.add(new Mob(AgentGpqSpearPlays.MASTER_MUSCLE_STONE, 990_000_420, new Point(200, 135), 30_000, KEY));
        for (int i = 0; i < 6; i++) {
            mobs.add(new Mob(9_300_020, 990_000_420, new Point(-500 + i * 180, 135), 10_000, 0));
        }
        Box debris = new Box(AgentGpqSpearPlays.DEBRIS, "", 990_000_430, new Point(876, 153));
        debris.hits = 30;
        debris.broken = box -> drop(RUSTY_KEY, 1, box.mapId, box.pos, null);
        boxes.add(debris);
        itemGate(new Box(9_208_005, "metalgate", 990_000_430, new Point(927, -352)), RUSTY_KEY, 1,
                AgentGpqSpearPlays.METALGATE_BOX, box -> box.state = 1);
        Box pledge = new Box(AgentGpqSpearPlays.SEALED_SPEAR, "", 990_000_431, new Point(-183, -138));
        pledge.broken = box -> drop(SPEAR, 1, box.mapId, box.pos, null);
        boxes.add(pledge);
        for (String altar : AgentGpqSpearPlays.ALTARS.keySet()) {
            Point at = AgentGpqSpearPlays.ALTARS.get(altar);
            itemGate(new Box(9_208_007, altar, 990_000_440, new Point(at.x, at.y - 10)), SPEAR, 1,
                    AgentGpqSpearPlays.altarBox(altar), box -> {
                        box.state = 1;
                        named(990_000_400, "speargate").state++;
                    });
        }

        boxes.add(new Box(9_208_013, "watergate", 990_000_500, new Point(-557, 68)));
        for (int[] at : new int[][] {{-181, 144}, {-170, 144}, {272, 144}, {280, 144}}) {
            mobs.add(new Mob(9_300_022, 990_000_500, new Point(at[0], at[1]), 40_000, MEDAL));
        }
        for (int[] at : new int[][] {{-40, 144}, {-30, 144}, {148, 147}, {155, 147}}) {
            mobs.add(new Mob(9_300_023, 990_000_500, new Point(at[0], at[1]), 40_000, SCROLL));
        }
        int[][] plates = {{-414, -341}, {2, -341}, {-1123, -190}, {1031, -157}};
        int[][] plateDecoys = {{541, -35}, {805, -341}, {399, -341}, {-223, -213}, {-826, -342}, {-573, 40},
                {132, 41}, {375, -166}};
        offeringBoxes(990_000_501, 9_202_003, 9_202_010, plates, plateDecoys, FOOD);
        int[][] crates = {{-1057, -170}, {-752, -264}, {-194, -2}, {499, -214}};
        int[][] crateDecoys = {{849, -147}, {224, -303}, {245, 5}, {79, -31}, {-399, 153}, {-953, 153}, {-601, -209},
                {-356, -349}};
        offeringBoxes(990_000_502, 9_202_004, 9_202_011, crates, crateDecoys, DRINK);

        for (int i = 0; i < 9; i++) {
            mobs.add(new Mob(9_300_024, 990_000_610, new Point(-586 + i * 145, 165), 35_000, MARK));
        }
        itemGate(new Box(9_208_010, "secretgate1", 990_000_610, new Point(811, 115)), MARK, 9,
                AgentGpqWaterwayPlays.GOLEMS.gateBox(), box -> box.state = 1);
        garmentBox(990_000_611, 9_202_005, new Point(2623, -74), 4_001_031);
        garmentBox(990_000_620, 9_202_006, new Point(1084, -1037), 4_001_032);
        mobs.add(new Mob(9_300_025, 990_000_630, new Point(-82, -204), 35_000, GARGOYLE_DROP));
        itemGate(new Box(AgentGpqWaterwayPlays.GARGOYLE_REACTOR, "", 990_000_630, new Point(-82, -208)),
                GARGOYLE_DROP, 1, AgentGpqWaterwayPlays.GARGOYLE_BOX, box -> {
                    box.state = 1;
                    for (int i = 0; i < 8; i++) {
                        mobs.add(new Mob(9_300_033, 990_000_630, new Point(-100 + i * 40, 50), 15_000, MARK));
                    }
                });
        itemGate(new Box(9_208_011, "secretgate2", 990_000_630, new Point(412, 86)), MARK, 8,
                AgentGpqWaterwayPlays.GARGOYLES.gateBox(), box -> box.state = 1);
        garmentBox(990_000_631, 9_202_007, new Point(11, -776), 4_001_033);
        for (int i = 0; i < 20; i++) {
            mobs.add(new Mob(9_300_027, 990_000_640, new Point(-300 + i * 40, i < 10 ? 154 : -307), 1_000, MARK));
        }
        itemGate(new Box(9_208_012, "secretgate3", 990_000_640, new Point(585, -323)), MARK, 20,
                AgentGpqWaterwayPlays.SLIMES.gateBox(), box -> box.state = 1);
        garmentBox(990_000_641, 9_202_008, new Point(-1185, -594), 4_001_034);

        Box remains = new Box(AgentGpqWaterwayPlays.REMAINS, "", 990_000_700, new Point(1438, 180));
        itemGate(remains, AgentGpqWaterwayPlays.GARMENTS.get(0), 1, AgentGpqWaterwayPlays.REMAINS_BOX, box -> {
            box.state++;
            if (box.state < AgentGpqWaterwayPlays.GARMENTS.size()) {
                box.wantsItem = AgentGpqWaterwayPlays.GARMENTS.get(box.state);
            } else {
                box.alive = false; // the ghost rises where the remains lay
                properties.put("ghost", "true");
            }
        });
        boxes.add(new Box(9_208_008, "ghostgate", 990_000_700, new Point(1641, 180)));
        itemGate(new Box(9_208_009, "kinggate", 990_000_800, new Point(261, 83)), ROCK, 1,
                AgentGpqThronePlays.KINGGATE_BOX, box -> {
                    box.state = 1;
                    properties.put("canRevive", "1");
                });
        Box seal = new Box(9_201_002, AgentGpqThronePlays.RUBIAN_REACTOR, 990_000_900, new Point(344, 12));
        seal.broken = box -> {
            mobs.add(new Mob(ERGOTH, 990_000_900, new Point(344, 155), 1_700_000, RUBIAN));
            mobs.add(new Mob(9_300_029, 990_000_900, new Point(130, 150), 1_000_000, 0));
            mobs.add(new Mob(9_300_031, 990_000_900, new Point(130, 90), 900_000, 0));
        };
        boxes.add(seal);
        for (int i = 0; i < 25; i++) {
            Box treasure = new Box(AgentGpqThronePlays.BONUS_BOX, "", AgentGpqRun.WAREHOUSE_MAP_ID,
                    new Point(-900 + i * 75, 154));
            treasure.hits = Integer.MAX_VALUE;
            boxes.add(treasure);
        }
    }

    private Box rockStone(int mapId, Point at, int reactorId) {
        Box stone = new Box(reactorId, "", mapId, at);
        stone.broken = box -> drop(ROCK, 1, box.mapId, box.pos, null);
        boxes.add(stone);
        return stone;
    }

    private void offeringBoxes(int mapId, int real, int decoy, int[][] reals, int[][] decoys, int prize) {
        for (int[] at : reals) {
            Box box = new Box(real, "", mapId, new Point(at[0], at[1]));
            box.hits = 4;
            box.broken = broken -> drop(prize, 1, broken.mapId, broken.pos, null);
            boxes.add(box);
        }
        for (int[] at : decoys) {
            Box box = new Box(decoy, "", mapId, new Point(at[0], at[1]));
            box.hits = 4;
            boxes.add(box);
        }
    }

    private void garmentBox(int mapId, int reactorId, Point at, int garment) {
        Box box = new Box(reactorId, "", mapId, at);
        box.hits = Integer.MAX_VALUE; // its last state loops: every hit from the fifth drops the garment again
        box.broken = hit -> {
            if (hit.hitsTaken >= 5) {
                drop(garment, 1, hit.mapId, hit.pos, null);
            }
        };
        boxes.add(box);
    }

    private void itemGate(Box gate, int item, int quantity, Rectangle area, Consumer<Box> took) {
        gate.hits = Integer.MAX_VALUE;
        gate.wantsItem = item;
        gate.wantsQuantity = quantity;
        gate.box = area;
        gate.took = took;
        boxes.add(gate);
    }

    Box named(int mapId, String name) {
        for (Box box : boxes) {
            if (box.mapId == mapId && name.equals(box.name)) {
                return box;
            }
        }
        throw new IllegalStateException("no reactor " + name + " on " + mapId);
    }

    private Drop drop(int itemId, int quantity, int mapId, Point at, Body dropper) {
        Drop drop = new Drop(itemId, quantity, mapId, at, dropper);
        drops.add(drop);
        return drop;
    }

    // ---- the game's answers to the plays ----

    private void wire() {
        for (Body body : bodies.values()) {
            body.character = character(body);
            if (body.id != BEN) {
                body.entry = new AgentRuntimeEntry(body.character, stub(Character.class), null);
            }
        }
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        when(event.wrongEffectCount(anyInt())).thenAnswer(call -> wrongEffects.getOrDefault(
                (Integer) call.getArgument(0), 0));
        when(event.getLeader()).thenAnswer(call -> bodies.get(BEN).character);
        when(event.getLeaderId()).thenReturn(BEN);
        when(event.isEventCleared()).thenAnswer(call -> cleared);

        doAnswer(call -> {
            Body body = bodyOf(call.getArgument(0));
            body.dest = new Point((Point) call.getArgument(1));
            body.grind = null;
            return null;
        }).when(gateway).navigate(any(), any(), anyBoolean());
        doAnswer(call -> {
            Body body = bodyOf(call.getArgument(0));
            body.dest = null;
            body.grind = null;
            return null;
        }).when(gateway).stop(any());
        when(gateway.grounded(any())).thenReturn(true);
        when(gateway.groundPoint(any(), any())).thenAnswer(call -> new Point((Point) call.getArgument(1)));
        when(gateway.reactors(any())).thenAnswer(call -> {
            Character member = call.getArgument(0);
            return boxes.stream().filter(box -> box.mapId == member.getMapId()).map(box -> box.reactor).toList();
        });
        when(gateway.hitReactor(any(), anyInt())).thenAnswer(call -> hit(bodies.get(((Character) call.getArgument(0))
                .getId()), call.getArgument(1)));
        doAnswer(call -> {
            Body body = bodyOf(call.getArgument(0));
            body.grind = Set.copyOf((Set<Integer>) call.getArgument(1));
            body.dest = null;
            return null;
        }).when(gateway).grind(any(), any(Set.class));
        doAnswer(call -> {
            Body body = bodyOf(call.getArgument(0));
            Set<Integer> wanted = new java.util.HashSet<>((Set<Integer>) call.getArgument(1));
            wanted.addAll((Set<Integer>) call.getArgument(2));
            body.grind = Set.copyOf(wanted);
            body.dest = null;
            return null;
        }).when(gateway).grind(any(), any(Set.class), any(Set.class));
        when(partyQuest.enterPortal(any(), anyInt())).thenAnswer(call -> {
            Body body = bodies.get(((Character) call.getArgument(0)).getId());
            return enterPortal(body, call.getArgument(1));
        });
        when(partyQuest.runNpc(any(), anyInt(), any(int[].class))).thenAnswer(call -> {
            Body body = bodies.get(((Character) call.getArgument(0)).getId());
            int npc = call.getArgument(1);
            if (npc == AgentGpqThronePlays.NURIS && body.mapId == AgentGpqRun.RETURNING_MAP_ID) {
                body.inEvent = false;
                move(body, CAMP, arrivals.get(CAMP));
                return true;
            }
            return false;
        });
    }

    private Body bodyOf(AgentRuntimeEntry entry) {
        for (Body body : bodies.values()) {
            if (body.entry == entry) {
                return body;
            }
        }
        throw new IllegalStateException("no body for " + entry);
    }

    private Character character(Body body) {
        Character chr = stub(Character.class);
        when(chr.getId()).thenReturn(body.id);
        when(chr.getName()).thenReturn(body.name);
        when(chr.getLevel()).thenReturn(body.level);
        when(chr.getMapId()).thenAnswer(call -> body.mapId);
        when(chr.getMap()).thenAnswer(call -> map(body.mapId));
        when(chr.getPosition()).thenAnswer(call -> body.pos == null ? null : new Point(body.pos));
        when(chr.getHp()).thenAnswer(call -> body.hp);
        when(chr.isAlive()).thenAnswer(call -> body.alive());
        when(chr.getSkillLevel(anyInt())).thenAnswer(call -> body.skills.getOrDefault((Integer) call.getArgument(0), 0));
        when(chr.getTotalMoveSpeedStat()).thenAnswer(call -> now < body.hasteUntilMs ? 160 : 100);
        when(chr.getTotalJumpStat()).thenAnswer(call -> now < body.hasteUntilMs ? 123 : 100);
        when(chr.getBuffedValue(any(BuffStat.class))).thenAnswer(call -> {
            BuffStat stat = call.getArgument(0);
            if (stat == BuffStat.DARKSIGHT) {
                return now < body.darkSightUntilMs ? 1 : null;
            }
            if (stat == BuffStat.SPEED) {
                return now < body.hasteUntilMs ? 20 : null;
            }
            return null;
        });
        when(chr.getEventInstance()).thenAnswer(call -> body.inEvent ? event : null);
        return chr;
    }

    // ---- AgentGpqPorts ----

    @Override
    public AgentRuntimeEntry entry(int characterId) {
        Body body = bodies.get(characterId);
        return body == null ? null : body.entry;
    }

    @Override
    public PrimitiveCapabilityGateway gateway() {
        return gateway;
    }

    @Override
    public PartyQuestGateway partyQuest() {
        return partyQuest;
    }

    @Override
    public void sayInParty(Character member, String line) {
        lines.add(member.getName() + ": " + line);
        if (TALK_LINES.contains(line)) {
            talkMapId = member.getMapId(); // Ben goes where it was said, then talks
            if (benTalkAtMs < 0) {
                benTalkAtMs = now + 2_000L;
            }
        }
    }

    @Override
    public boolean buff(AgentRuntimeEntry entry, Character member, int skillId) {
        Body body = bodies.get(member.getId());
        if (body.skills.getOrDefault(skillId, 0) <= 0) {
            return false;
        }
        if (skillId == Rogue.DARK_SIGHT) {
            body.darkSightUntilMs = now + 60_000L;
        } else if (skillId == Assassin.HASTE) {
            body.hasteUntilMs = now + 120_000L;
        }
        return true;
    }

    @Override
    public int carried(Character member, int itemId) {
        return bodies.get(member.getId()).count(itemId);
    }

    @Override
    public boolean wearsRock(Character member) {
        return bodies.get(member.getId()).rockWorn;
    }

    @Override
    public boolean wearRock(Character member) {
        Body body = bodies.get(member.getId());
        if (body.count(ROCK) <= 0) {
            return false;
        }
        body.give(ROCK, -1);
        body.rockWorn = true;
        return true;
    }

    @Override
    public boolean dropWornRock(Character member) {
        Body body = bodies.get(member.getId());
        if (!body.rockWorn) {
            return false;
        }
        body.rockWorn = false;
        drop(ROCK, 1, body.mapId, body.pos, body);
        return true;
    }

    @Override
    public boolean dropEtc(Character member, int itemId, int quantity) {
        Body body = bodies.get(member.getId());
        if (body.count(itemId) < quantity) {
            return false;
        }
        body.give(itemId, -quantity);
        drop(itemId, quantity, body.mapId, body.pos, body);
        log.add(at() + " " + body.name + " drops " + itemId + " x" + quantity + " at " + body.pos.x + "," + body.pos.y
                + " on " + body.mapId);
        return true;
    }

    @Override
    public boolean pickUp(Character member, int itemId) {
        Body body = bodies.get(member.getId());
        Drop nearest = null;
        for (Drop drop : drops) {
            if (drop.picked || drop.itemId != itemId || drop.mapId != body.mapId
                    || drop.pos.distance(body.pos) > PICK_REACH_PX) {
                continue;
            }
            boolean selfLoot = body.mapId >= 990_000_500 && body.mapId <= 990_000_502;
            if (selfLoot && drop.dropper != null && drop.dropper != body) {
                continue;
            }
            if (nearest == null || drop.pos.distance(body.pos) < nearest.pos.distance(body.pos)) {
                nearest = drop;
            }
        }
        if (nearest == null || itemId == ROCK && (body.rockWorn || body.count(ROCK) > 0)) {
            return false; // a Protector Rock is one per person (only=1)
        }
        nearest.picked = true;
        body.give(itemId, nearest.quantity);
        if (itemId != ROCK) {
            log.add(at() + " " + body.name + " picks up " + itemId + " x" + nearest.quantity + " on " + body.mapId);
        }
        return true;
    }

    @Override
    public List<MapItem> items(Character member) {
        return itemsOn(event, member.getMapId());
    }

    @Override
    public Character member(int characterId) {
        Body body = bodies.get(characterId);
        return body == null || !body.inEvent ? null : body.character;
    }

    @Override
    public Reactor reactorOn(EventInstanceManager ev, int mapId, String name) {
        for (Box box : boxes) {
            if (box.mapId == mapId && name.equals(box.name)) {
                return box.reactor;
            }
        }
        return null;
    }

    @Override
    public List<Reactor> liveReactorsOn(EventInstanceManager ev, int mapId, int reactorId) {
        return boxes.stream().filter(box -> box.mapId == mapId && box.reactorId == reactorId && box.alive)
                .map(box -> box.reactor).toList();
    }

    @Override
    public int monstersOn(EventInstanceManager ev, int mapId, Set<Integer> mobIds) {
        return (int) mobs.stream().filter(mob -> mob.mapId == mapId && mob.hp > 0 && mobIds.contains(mob.mobId))
                .count();
    }

    @Override
    public List<MapItem> itemsOn(EventInstanceManager ev, int mapId) {
        return drops.stream().filter(drop -> !drop.picked && drop.mapId == mapId).map(drop -> drop.item).toList();
    }

    // ---- the scripts ----

    private boolean hit(Body body, int oid) {
        Box box = boxes.stream().filter(candidate -> candidate.oid == oid).findFirst().orElse(null);
        if (box == null || !box.alive || box.mapId != body.mapId || box.pos.distance(body.pos) > 220) {
            return false;
        }
        box.hitsTaken++;
        if (box.hits == Integer.MAX_VALUE) {
            box.broken.accept(box);
            return true;
        }
        box.state = Math.min(box.hitsTaken, box.hits);
        if (box.hitsTaken >= box.hits) {
            box.alive = false;
            box.broken.accept(box);
            if (box.respawnMs > 0) {
                at(now + box.respawnMs, () -> {
                    box.alive = true;
                    box.hitsTaken = 0;
                    box.state = 0;
                });
            }
        }
        return true;
    }

    /** A statue hit while the pattern is open adds the statue to the guess, up to the pattern's length (9208000.js). */
    private void statueHit(Box statue) {
        if (!"active".equals(properties.get("stage1status"))) {
            return; // a hit from an attack during the display counts for nothing
        }
        int phase = Integer.parseInt(properties.get("stage1phase"));
        String guess = properties.getOrDefault("stage1guess", "");
        if (guess.length() != 3 * (phase + 3)) {
            properties.put("stage1guess", guess + String.format("%03d", statue.oid % 1000));
        }
    }

    /**
     * Ben talks to the Gatekeeper (9040009.js): while waiting, the talk starts a pattern of phase + 3 different statues,
     * each lit 5 s + 3.5 s apart, the last one opening the guess; while active, it judges the guess (a miss sends the
     * test back to part 1, part 3 right clears the stage).
     */
    private void gatekeeper() {
        if ("true".equals(properties.get("stage1clear"))) {
            return;
        }
        String status = properties.get("stage1status");
        if (status == null || "waiting".equals(status)) {
            int phase = Integer.parseInt(properties.getOrDefault("stage1phase", "1"));
            properties.put("stage1phase", String.valueOf(phase));
            List<Box> statues = new ArrayList<>(boxes.stream().filter(box -> box.mapId == 990_000_300
                    && AgentGpqStatuePlay.STATUES.contains(box.reactorId)).toList());
            java.util.Collections.shuffle(statues, random);
            properties.put("stage1status", "display");
            properties.put("stage1combo", "");
            int length = phase + 3;
            for (int i = 0; i < length; i++) {
                Box shown = statues.get(i);
                at(now + 5_000L + 3_500L * i, () -> {
                    String combo = properties.get("stage1combo") + String.format("%03d", shown.oid % 1000);
                    properties.put("stage1combo", combo);
                    if (combo.length() == 3 * length) {
                        properties.put("stage1status", "active");
                        properties.put("stage1guess", "");
                    }
                });
            }
        } else if ("active".equals(status)) {
            int phase = Integer.parseInt(properties.get("stage1phase"));
            if (properties.get("stage1combo").equals(properties.get("stage1guess"))) {
                if (phase == 3) {
                    properties.put("stage1clear", "true");
                } else {
                    properties.put("stage1phase", String.valueOf(phase + 1));
                }
            } else {
                gatekeeperMisses++;
                wrongEffects.merge(990_000_300, 1, Integer::sum);
                properties.put("stage1phase", "1");
            }
            properties.put("stage1status", "waiting");
        }
    }

    /** Ben talks to the fountain, as 9040006.js answers him. */
    private void fountain() {
        String combo = properties.get("stage3combo");
        if (combo == null || "reset".equals(combo)) {
            int code = 0;
            for (int i = 0; i < 4; i++) {
                code += random.nextInt(4) * (int) Math.pow(10, i);
            }
            properties.put("stage3combo", String.valueOf(code));
            properties.put("stage3attempt", "1");
            fountainDraws++;
            return;
        }
        int[] stood = AgentGpqVassalPlays.standing(itemsOn(event, 990_000_500));
        if (stood == null) {
            return; // "Please make sure your attempt is properly set": no attempt spent
        }
        int guess = stood[0] * 1000 + stood[1] * 100 + stood[2] * 10 + stood[3];
        fountainJudged++;
        if (guess == Integer.parseInt(combo)) {
            named(990_000_500, "watergate").state = 1;
            properties.put("stage3clear", "true");
            drops.stream().filter(drop -> drop.mapId == 990_000_500 && AgentGpqVassalPlays.OFFERINGS.contains(
                    drop.itemId)).forEach(drop -> drop.picked = true);
            return;
        }
        int attempt = Integer.parseInt(properties.get("stage3attempt"));
        if (attempt < 7) {
            mobs.add(new Mob(9_300_036, 990_000_500, new Point(-350, 150), 22_000, 0));
            mobs.add(new Mob(9_300_037, 990_000_500, new Point(400, 150), 22_000, 0));
            properties.put("stage3attempt", String.valueOf(attempt + 1));
        } else {
            properties.put("stage3combo", "reset");
            for (int i = 0; i < 6; i++) {
                mobs.add(new Mob(9_300_036, 990_000_500, new Point(-350 + random.nextInt(750), 150), 22_000, 0));
                mobs.add(new Mob(9_300_037, 990_000_500, new Point(-350 + random.nextInt(750), 150), 22_000, 0));
            }
        }
        wrongEffects.merge(990_000_500, 1, Integer::sum);
    }

    private boolean enterPortal(Body body, int portalId) {
        Portal portal = portals.get(body.mapId + "#" + portalId);
        if (portal == null || portal.getPosition().distance(body.pos) > 80) {
            return false;
        }
        String name = portal.getName();
        if (!open(body.mapId, name, body)) {
            return false;
        }
        String[] target = portalTargets.get(body.mapId + ":" + name);
        int mapId = Integer.parseInt(target[0]);
        Portal landing = target[1].isEmpty() ? null : portals.get(mapId + ":" + target[1]);
        move(body, mapId, landing != null ? landing.getPosition() : arrivals.get(mapId));
        return true;
    }

    private void move(Body body, int mapId, Point to) {
        body.mapId = mapId;
        body.pos = new Point(to);
        body.dest = null;
        body.grind = null;
        if (body.id == BEN) {
            benAt.putIfAbsent(mapId, now);
        }
        if (mapId == 990_000_300 && !body.rockWorn && body.id != BEN) {
            rocklessAt300.add(body.name);
        }
    }

    private void at(long whenMs, Runnable action) {
        scheduledAt.add(new long[] {whenMs});
        scheduled.add(action);
    }

    // ---- one step of the world ----

    /** Advances the world one step; true while the run goes on. */
    boolean step() {
        now += STEP_MS;
        for (int i = 0; i < scheduled.size(); i++) {
            if (scheduledAt.get(i)[0] <= now) {
                Runnable action = scheduled.remove(i);
                scheduledAt.remove(i);
                action.run();
                i--;
            }
        }
        itemGates();
        for (Body body : bodies.values()) {
            if (!body.alive()) {
                if ("1".equals(properties.get("canRevive")) && now - body.diedAtMs >= 3_000L) {
                    body.hp = 1; // revives at the Entrance to the Remains (GuildQuest.js playerRevive)
                    move(body, Stage.ENTRANCE.mapId(), arrivals.get(Stage.ENTRANCE.mapId()));
                    log.add(at() + " " + body.name + " revives at the Entrance");
                }
                continue;
            }
            walk(body);
            fight(body);
            air(body);
        }
        ben();
        List<Character> fighters = new ArrayList<>();
        List<Character> after = new ArrayList<>();
        for (int id : COMPANIONS) {
            Body body = bodies.get(id);
            if (!body.inEvent || !body.alive()) {
                continue;
            }
            if (Stage.of(body.mapId) != null) {
                fighters.add(body.character);
            } else if (run.playsAfterClear(body.mapId)) {
                after.add(body.character);
            }
        }
        if (!fighters.isEmpty()) {
            run.tick(fighters, event, now);
        }
        if (!after.isEmpty()) {
            run.tickAfterClear(after, event, now);
        }
        return now - startedAtMs < EVENT_MS && COMPANIONS.stream().anyMatch(id -> bodies.get(id).inEvent);
    }

    private void walk(Body body) {
        if (body.dest == null) {
            return;
        }
        Point from = body.pos;
        Point to = body.dest;
        double distance = from.distance(to);
        Point next = distance <= SPEED_PX ? new Point(to) : new Point(
                (int) Math.round(from.x + (to.x - from.x) * SPEED_PX / distance),
                (int) Math.round(from.y + (to.y - from.y) * SPEED_PX / distance));
        if (blocked(body, next)) {
            return;
        }
        body.pos = next;
    }

    /** The Dark Muscle Stones' corridor wants Dark Sight; the outer altar towers want a climber. */
    private boolean blocked(Body body, Point next) {
        if (body.mapId == Stage.FAITH.mapId() && next.y < AgentGpqSpearPlays.CORRIDOR_Y
                && now >= body.darkSightUntilMs) {
            return true;
        }
        if (body.mapId == Stage.JUSTICE.mapId()) {
            for (Map.Entry<String, Point> altar : AgentGpqSpearPlays.ALTARS.entrySet()) {
                if (next.distance(altar.getValue()) < 150 && !AgentGpqSpearPlays.reaches(body.character,
                        altar.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void fight(Body body) {
        if (body.grind == null) {
            return;
        }
        Mob target = null;
        for (Mob mob : mobs) {
            if (mob.mapId == body.mapId && mob.hp > 0 && body.grind.contains(mob.mobId)
                    && (target == null || mob.pos.distance(body.pos) < target.pos.distance(body.pos))) {
                target = mob;
            }
        }
        if (target == null) {
            return;
        }
        if (target.pos.distance(body.pos) > FIGHT_REACH_PX) {
            Point aim = target.pos;
            double distance = body.pos.distance(aim);
            body.pos = distance <= SPEED_PX ? new Point(aim) : new Point(
                    (int) Math.round(body.pos.x + (aim.x - body.pos.x) * SPEED_PX / distance),
                    (int) Math.round(body.pos.y + (aim.y - body.pos.y) * SPEED_PX / distance));
            return;
        }
        target.hp -= body.dps;
        if (target.hp <= 0 && target.dropItem != 0) {
            drop(target.dropItem, 1, target.mapId, target.pos, null);
            log.add(at() + " " + target.mobId + " falls on " + target.mapId);
        }
    }

    /**
     * Sharenian's air (Map.wz decHP 30000, protectItem 1032033) kills a member with no rock on at the first hurt, 2.5 to
     * 5 s after it enters (MAP_DAMAGE_OVERTIME 2500 ms x 2); the Gatekeeper's map to the warehouse have it.
     */
    private void air(Body body) {
        boolean bad = body.inEvent && body.mapId >= Stage.GATEKEEPER.mapId()
                && body.mapId <= AgentGpqRun.WAREHOUSE_MAP_ID;
        if (!bad || body.rockWorn) {
            body.noRockSinceMs = -1L;
            return;
        }
        if (body.noRockSinceMs < 0) {
            body.noRockSinceMs = now;
        } else if (now - body.noRockSinceMs >= AIR_MS) {
            body.hp = 0;
            body.deaths++;
            body.diedAtMs = now;
            body.dest = null;
            body.grind = null;
            log.add(at() + " " + body.name + " dies to the air on " + body.mapId);
        }
    }

    /** Item gates take an exact stack lying in their box five seconds after it landed (MapleMap item reactors). */
    private void itemGates() {
        for (Box gate : boxes) {
            if (gate.wantsItem == 0 || !gate.alive) {
                continue;
            }
            if (gate.pending != null) {
                if (gate.pending.picked) {
                    gate.pending = null;
                } else if (now >= gate.pendingAtMs) {
                    gate.pending.picked = true;
                    gate.pending = null;
                    gate.took.accept(gate);
                    log.add(at() + " " + (gate.name.isEmpty() ? String.valueOf(gate.reactorId) : gate.name)
                            + " takes its item");
                }
                continue;
            }
            for (Drop drop : drops) {
                if (!drop.picked && drop.mapId == gate.mapId && drop.itemId == gate.wantsItem
                        && drop.quantity == gate.wantsQuantity && gate.box.contains(drop.pos)) {
                    gate.pending = drop;
                    gate.pendingAtMs = now + ITEM_GATE_MS;
                    break;
                }
            }
        }
    }

    // ---- Ben, the human leader ----

    private void ben() {
        Body ben = bodies.get(BEN);
        if (benTalkAtMs > 0 && now >= benTalkAtMs && ben.mapId == talkMapId) {
            benTalkAtMs = -1L;
            switch (ben.mapId) {
                case 990_000_300 -> gatekeeper();
                case 990_000_500 -> fountain();
                case 990_000_700 -> {
                    if ("true".equals(properties.get("ghost"))) {
                        properties.put("stage4clear", "true");
                    }
                }
                default -> {
                }
            }
        }
        if (ben.mapId == 990_000_900) {
            for (Drop drop : drops) {
                if (!drop.picked && drop.itemId == RUBIAN && drop.mapId == ben.mapId
                        && drop.pos.distance(ben.pos) <= PICK_REACH_PX) {
                    drop.picked = true;
                    ben.give(RUBIAN, 1);
                }
            }
            if (ben.count(RUBIAN) > 0 && !cleared) {
                ben.give(RUBIAN, -1);
                clear();
            }
        }
        benWalks(ben);
    }

    /** Ben walks on along the main way whenever it opens, a few seconds after; he wears his own rock. */
    private void benWalks(Body ben) {
        if (ben.mapId == Stage.VALLEY.mapId()) {
            ben.rockWorn = true;
        }
        String next = switch (ben.mapId) {
            case 990_000_000 -> "join00";
            case 990_000_100, 990_000_200, 990_000_300, 990_000_400, 990_000_500, 990_000_700 -> "next00";
            case 990_000_301, 990_000_401 -> "next01";
            case 990_000_600, 990_000_610 -> "in00";
            case 990_000_611 -> "guild1F00";
            case 990_000_800 -> "boss00";
            default -> null;
        };
        if (next == null || !open(ben.mapId, next, ben)) {
            benMoveAtMs = -1L;
            return;
        }
        if (benMoveAtMs < 0) {
            benMoveAtMs = now + 3_000L;
        } else if (now >= benMoveAtMs) {
            benMoveAtMs = -1L;
            ben.pos = portals.get(ben.mapId + ":" + next).getPosition();
            enterPortal(ben, portals.get(ben.mapId + ":" + next).getId());
            ben.pos = new Point(ben.pos.x + 20, ben.pos.y); // a step off the landing, as players do
        }
    }

    /** The guard took the Rubian: clearPQ warps every registered player to the warehouse for its 30 seconds. */
    private void clear() {
        cleared = true;
        clearedAtMs = now;
        log.add(at() + " cleared");
        for (Body body : bodies.values()) {
            if (body.inEvent && body.alive()) {
                move(body, AgentGpqRun.WAREHOUSE_MAP_ID, arrivals.get(AgentGpqRun.WAREHOUSE_MAP_ID));
            }
        }
        at(now + 30_000L, () -> {
            for (Body body : bodies.values()) {
                if (body.inEvent) {
                    body.hp = Math.max(body.hp, 1);
                    move(body, AgentGpqRun.RETURNING_MAP_ID, arrivals.get(AgentGpqRun.RETURNING_MAP_ID));
                }
            }
        });
    }

    String at() {
        long ms = now - startedAtMs;
        return String.format("%02d:%02d", ms / 60_000L, ms / 1_000L % 60);
    }

    /** What has moved on so far: the flags, every gate's state, the maps the leader reached, the clear. */
    String progress() {
        StringBuilder out = new StringBuilder(new java.util.TreeMap<>(properties).toString());
        boxes.stream().filter(box -> !box.name.isEmpty() && box.mapId != 990_000_300)
                .forEach(box -> out.append(box.name).append(box.state).append(box.alive));
        COMPANIONS.forEach(id -> out.append(bodies.get(id).mapId));
        return out.append(benAt.keySet()).append(cleared).toString();
    }

    /** Where everyone stands, for a failure message. */
    String where() {
        StringBuilder out = new StringBuilder();
        for (Body body : bodies.values()) {
            out.append(String.format("%n  %s on %d at %s hp %d rock %s bag %s role %s", body.name, body.mapId,
                    body.pos, body.hp, body.rockWorn, body.bag, run.roleOf(body.id)));
        }
        List<String> lying = drops.stream().filter(drop -> !drop.picked && drop.itemId != ROCK)
                .map(drop -> drop.itemId + "@" + drop.mapId + "(" + drop.pos.x + "," + drop.pos.y + ")").toList();
        List<String> gates = boxes.stream().filter(box -> !box.name.isEmpty() && box.mapId != 990_000_300)
                .map(box -> box.name + "=" + box.state + (box.alive ? "" : "x")).toList();
        out.append(String.format("%n  properties %s%n  gates %s%n  lying %s%n  last lines %s%n  last log %s",
                properties, gates, lying, lines.subList(Math.max(0, lines.size() - 8), lines.size()),
                log.subList(Math.max(0, log.size() - 25), log.size())));
        return out.toString();
    }

    /** Ergoth's fight as the boss track's priority fight makes it here: every member on the throne goes for him. */
    final class ErgothFight implements AgentBossFight {
        @Override
        public void tick(List<Character> fighters, EventInstanceManager ev, long nowMs) {
            for (Character fighter : fighters) {
                gateway.grind(bodies.get(fighter.getId()).entry, Set.of(ERGOTH));
            }
        }

        @Override
        public void adopt(int characterId, int ordinal) {
        }

        @Override
        public void end(EventInstanceManager ev) {
        }
    }
}
