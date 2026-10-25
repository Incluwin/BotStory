package server.agents.capabilities.partyquest.apq;

import client.Character;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PacketGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A simulated Amorian Challenge for the APQ engine's offline clear tests. The stage rules are the stock scripts'
 * (AmoriaPQ.js, 9201044.js, 9201045.js, 9201047.js, gendergo.js, apqDoor.js, apq00-02.js) at the real maps'
 * coordinates (Map.wz); movement is free but for the walls that matter to the run (the gender rooms, the ropes, the
 * seven gates, the arena), fights are a few hits a monster, and the human leader, Ben, is played by a script that acts
 * only on what a player there sees: his companions' lines, drops at his feet, an open portal, Amos's "the portal is
 * open". The session, coordinator, stage plays, puzzles and solver are the real ones; every gateway is this world.
 * One step is one background tick (250 ms).
 */
final class ApqWorld implements AutoCloseable {
    static final int BEN = 900;
    static final long STEP_MS = 250L;
    /** Ben (a man) and five companions, three women and two men. */
    static final List<Integer> COMPANIONS = List.of(11, 12, 13, 14, 15);
    private static final Map<Integer, String> NAMES = Map.of(BEN, "Ben", 11, "Mira", 12, "Tobi", 13, "Una",
            14, "Rook", 15, "Ivy");
    private static final Map<Integer, Integer> GENDERS = Map.of(BEN, 0, 11, 1, 12, 0, 13, 1, 14, 0, 15, 1);
    private static final int SPEED_PX = 150;
    private static final int HIT_RANGE_PX = 90;
    private static final int OUTSIDE_MAP = AgentApqDefinition.GATE_MAP;

    // ---- the maps, as Map.wz places them ----
    private static final Map<Integer, Map<Integer, Point>> PORTALS = new HashMap<>();
    private static final Map<Integer, Map<Integer, String>> PORTAL_NAMES = new HashMap<>();
    private static final Map<Integer, Map<Integer, Point>> NPCS = new HashMap<>();
    private static final Map<Integer, Point> ARRIVALS = new HashMap<>();
    private static final int[] GATE_X = {-8189, -6387, -4590, -901, 3596, 6300, 7198};
    private static final int[] GATE_PORTAL_X = {-8221, -6413, -4619, -924, 3568, 6272, 7170};
    private static final int[] GATE_LANDING_X = {-8110, -6309, -4511, -820, 3681, 6381, 7280};
    private static final int GATE_FLOOR_Y = 435;
    private static final Rectangle MIRROR_BOX = new Rectangle(-144, 100, 288, 80);

    static {
        int mirror = AgentApqDefinition.MIRROR_MAP;
        portal(mirror, 0, "sp", -143, -395);
        portal(mirror, 12, "go00", -360, -223);
        portal(mirror, 13, "go01", -4, -220);
        portal(mirror, 14, "go02", 359, -223);
        portal(mirror, 15, "male00", 415, 806);
        portal(mirror, 16, "female00", -434, 802);
        portal(mirror, 3, "male01", 171, 1771);
        portal(mirror, 4, "female01", -1362, 1712);
        npc(mirror, AgentApqDefinition.GLIMMER_NPC, 103, 783);
        npc(mirror, AgentApqDefinition.PUZZLE_NPC, -228, -216);
        ARRIVALS.put(mirror, new Point(-143, -395));
        int[][] doorIds = {{9, 10, 11}, {8, 9, 10}, {9, 10, 11}};
        int[][] doorY = {{-1142, -1200, -1262}, {-1144, -1144, -1144}, {-1205, -1146, -1084}};
        for (int i = 0; i < 3; i++) {
            int map = AgentApqDefinition.ROPE_MAPS.get(i);
            portal(map, 1, "st00", -377, -845);
            portal(map, 2, "SSM00", -629, -2071);
            portal(map, 3, "SSM01", -45, -2252);
            portal(map, 4, "SSM02", 540, -2368);
            portal(map, 5, "gate00", -223, -840);
            portal(map, 6, "gate01", -45, -840);
            portal(map, 7, "gate02", 134, -840);
            portal(map, doorIds[i][0], "door00", -227, doorY[i][0]);
            portal(map, doorIds[i][1], "door01", -39, doorY[i][1]);
            portal(map, doorIds[i][2], "door02", 133, doorY[i][2]);
            portal(map, AgentApqDefinition.nextPortalId(map), "next00", 586, -843);
            npc(map, AgentApqDefinition.PUZZLE_NPC, 357, -840);
            ARRIVALS.put(map, new Point(-377, -840));
        }
        int platform = AgentApqDefinition.PLATFORM_MAP;
        portal(platform, 1, "st00", 448, 90);
        portal(platform, 2, "next00", 2461, 240);
        npc(platform, AgentApqDefinition.PUZZLE_NPC, 1042, 82);
        ARRIVALS.put(platform, new Point(448, 90));
        int pass = AgentApqDefinition.PASS_MAP;
        portal(pass, 1, "st00", 417, -184);
        portal(pass, 2, "next00", 208, -178);
        npc(pass, AgentApqDefinition.LATE_NPC, 14, -187);
        ARRIVALS.put(pass, new Point(417, -184));
        int gates = AgentApqDefinition.GATE_RUN_MAP;
        portal(gates, 13, "st00", -8538, GATE_FLOOR_Y);
        portal(gates, 14, "gm00", -4823, -635);
        for (int i = 0; i < 7; i++) {
            portal(gates, 16 + i, "gt0" + i + "WP", GATE_PORTAL_X[i], GATE_FLOOR_Y);
        }
        npc(gates, AgentApqDefinition.LATE_NPC, 8916, 60);
        ARRIVALS.put(gates, new Point(-8535, GATE_FLOOR_Y));
        int boss = AgentApqDefinition.BOSS_MAP;
        portal(boss, 18, "st01", 499, -26);
        npc(boss, AgentApqDefinition.LATE_NPC, 224, -9);
        ARRIVALS.put(boss, new Point(-254, 51));
        int bonus = AgentApqDefinition.BONUS_MAP;
        npc(bonus, AgentApqDefinition.BONUS_NPC, 310, 590);
        ARRIVALS.put(bonus, new Point(187, 517));
        int exit = AgentApqDefinition.EXIT_MAP;
        npc(exit, AgentApqDefinition.GLIMMER_NPC, 39, 1779);
        ARRIVALS.put(exit, new Point(151, 1779));
        ARRIVALS.put(OUTSIDE_MAP, new Point(0, 0));
    }

    private static void portal(int map, int id, String name, int x, int y) {
        PORTALS.computeIfAbsent(map, ignored -> new HashMap<>()).put(id, new Point(x, y));
        PORTAL_NAMES.computeIfAbsent(map, ignored -> new HashMap<>()).put(id, name);
    }

    private static void npc(int map, int id, int x, int y) {
        NPCS.computeIfAbsent(map, ignored -> new HashMap<>()).put(id, new Point(x, y));
    }

    // ---- the world's state ----

    final class Body {
        final int id;
        final String name;
        final int gender;
        final boolean human;
        int mapId = AgentApqDefinition.MIRROR_MAP;
        Point pos = new Point(ARRIVALS.get(AgentApqDefinition.MIRROR_MAP));
        int hp = 1_000;
        boolean inEvent = true;
        Point dest;
        Set<Integer> grind;
        final Map<Integer, Integer> bag = new HashMap<>();
        Character character;
        AgentRuntimeEntry entry;

        Body(int id) {
            this.id = id;
            this.name = NAMES.get(id);
            this.gender = GENDERS.get(id);
            this.human = id == BEN;
        }

        int count(int itemId) {
            return bag.getOrDefault(itemId, 0);
        }

        void give(int itemId, int quantity) {
            bag.merge(itemId, quantity, Integer::sum);
            if (bag.get(itemId) <= 0) bag.remove(itemId);
        }
    }

    final class Mob {
        final int oid = nextOid++;
        final int mobId;
        final int mapId;
        final Point pos;
        int hp;

        Mob(int mobId, int mapId, Point pos, int hp) {
            this.mobId = mobId;
            this.mapId = mapId;
            this.pos = pos;
            this.hp = hp;
        }
    }

    final class Drop {
        final int oid = nextOid++;
        final int itemId;
        final int quantity;
        final int mapId;
        final Point pos;
        final Body dropper;
        final long dropTime;
        boolean picked;
        final MapItem item = stub(MapItem.class);

        Drop(int itemId, int quantity, int mapId, Point pos, Body dropper) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.mapId = mapId;
            this.pos = new Point(pos);
            this.dropper = dropper;
            this.dropTime = now;
            when(item.getItemId()).thenReturn(itemId);
            when(item.getObjectId()).thenReturn(oid);
            when(item.getPosition()).thenAnswer(call -> new Point(this.pos));
            when(item.isPickedUp()).thenAnswer(call -> picked);
            when(item.isPlayerDrop()).thenReturn(dropper != null);
            when(item.getDropTime()).thenReturn(dropTime);
            when(item.getDropper()).thenReturn(dropper == null ? null : (MapObject) dropper.character);
            when(item.getOwnerId()).thenReturn(dropper == null ? 0 : dropper.id);
        }
    }

    final class Box {
        final int oid = nextOid++;
        final int reactorId;
        final String name;
        final int mapId;
        final Point pos;
        final Rectangle area;
        int state;
        boolean alive = true;
        final Reactor reactor = stub(Reactor.class);

        Box(int reactorId, String name, int mapId, Point pos, Rectangle area) {
            this.reactorId = reactorId;
            this.name = name;
            this.mapId = mapId;
            this.pos = pos;
            this.area = area;
            when(reactor.getId()).thenReturn(reactorId);
            when(reactor.getName()).thenReturn(name);
            when(reactor.getObjectId()).thenReturn(oid);
            when(reactor.getPosition()).thenReturn(new Point(pos));
            when(reactor.getArea()).thenReturn(area);
            when(reactor.getState()).thenAnswer(call -> (byte) state);
            when(reactor.isAlive()).thenAnswer(call -> alive);
        }
    }

    private int nextOid = 1_000;
    long now = 10_000L;
    private final Random random;
    final Map<Integer, Body> bodies = new LinkedHashMap<>();
    final List<Mob> mobs = new ArrayList<>();
    final List<Drop> drops = new ArrayList<>();
    final List<Box> boxes = new ArrayList<>();
    final Map<String, String> properties = new HashMap<>();
    private final Map<Integer, Integer> wrongEffects = new HashMap<>();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    private final Map<String, Boolean> portalOpen = new HashMap<>();
    private final Map<String, String> portalScript = new HashMap<>();
    boolean eventCleared;
    boolean eventDisposed;
    private long timerEndsAtMs;
    private long hammerOnMirrorAtMs = -1L;
    /** Every line a companion said, in order: "speaker: text". */
    final List<String> lines = new ArrayList<>();
    private int linesRead;
    /** What Ben still means to do, in order, from what he heard. */
    private final List<Intent> intents = new ArrayList<>();
    /** Stage reports for the test: the leader's talks to Amos that a try was judged on, by stage. */
    final Map<Integer, Integer> judgedTries = new HashMap<>();
    final Map<Integer, Integer> wavesSpawned = new HashMap<>();
    int stageFiveTimerRestarts;
    boolean reachedBonus;
    /** When Ben first stood on each stage (simulated ms), in order. */
    final Map<AgentApqStage, Long> benStageAt = new LinkedHashMap<>();

    /** Where everyone stood when the event timer ran out before the clear; null when it never did. */
    String atDispose;

    final PrimitiveCapabilityGateway actions = stub(PrimitiveCapabilityGateway.class);
    final PartyQuestGateway partyQuest = stub(PartyQuestGateway.class);
    final PartyGateway party = stub(PartyGateway.class);
    final PacketGateway packets = stub(PacketGateway.class);
    final CharacterGateway online = stub(CharacterGateway.class);
    final EventInstanceManager event = stub(EventInstanceManager.class);
    final AgentApqSession session;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    ApqWorld(long seed) {
        random = new Random(seed);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuest);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentPacketGatewayRuntime.class).when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(online);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentMapPerception.class).when(() -> AgentMapPerception.items(any())).thenAnswer(call -> {
            MapleMap map = call.getArgument(0);
            return map == null ? List.of() : drops.stream().filter(drop -> !drop.picked && drop.mapId == map.getId())
                    .map(drop -> drop.item).toList();
        });
        open(AgentApqDrops.class).when(() -> AgentApqDrops.dropEtc(
                any(), anyInt(), any(Short.class))).thenAnswer(call -> {
                    AgentRuntimeEntry entry = call.getArgument(0);
                    int itemId = call.getArgument(1);
                    short quantity = call.getArgument(2);
                    return playerDrop(bodyOf(entry), itemId, quantity);
                });
        open(AgentLootEligibility.class).when(() -> AgentLootEligibility.canBotTargetLoot(
                any(), any(), any(), any(), anyLong())).thenAnswer(call -> {
                    Character bot = call.getArgument(1);
                    MapItem drop = call.getArgument(3);
                    return !AgentApqDefinition.EXCLUSIVE_ITEMS.contains(drop.getItemId())
                            || AgentApqSessionRegistry.canLootExclusive(bot, drop);
                });

        session = new AgentApqSession(seed, BEN, now);
        for (int id : allIds()) {
            Body body = new Body(id);
            bodies.put(id, body);
            body.character = character(body);
            session.addMember(id, body.human ? AgentApqMemberState.MemberType.HUMAN
                    : AgentApqMemberState.MemberType.AGENT, body.gender);
            if (!body.human) {
                body.entry = new AgentRuntimeEntry(body.character, stub(Character.class), null);
                registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(body.entry);
                identity.when(() -> AgentRuntimeIdentityRuntime.bot(body.entry)).thenReturn(body.character);
            }
        }
        when(online.findOnlineCharacterById(anyInt())).thenAnswer(call -> {
            Body body = bodies.get((Integer) call.getArgument(0));
            return body == null || body.mapId == 0 ? null : body.character;
        });
        session.setLeadership(BEN, COMPANIONS.getFirst());
        session.setMemberCoordinationChatEnabled(true);
        AgentApqSessionRegistry.registerComplete(session);
        wireEvent();
        wireActions();
        wireChat();
        populate();
    }

    private static List<Integer> allIds() {
        List<Integer> ids = new ArrayList<>();
        ids.add(BEN);
        ids.addAll(COMPANIONS);
        return ids;
    }

    /** A mock that records no calls: a run makes millions of them. */
    private static <T> T stub(Class<T> type) {
        return mock(type, withSettings().stubOnly());
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type, withSettings().stubOnly());
        statics.add(mocked);
        return mocked;
    }

    @Override
    public void close() {
        AgentApqSessionRegistry.remove(session);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    Body body(int id) {
        return bodies.get(id);
    }

    private Body bodyOf(AgentRuntimeEntry entry) {
        return bodies.values().stream().filter(body -> body.entry == entry).findFirst().orElseThrow();
    }

    private Body bodyOf(Character character) {
        return bodies.get(character.getId());
    }

    // ---- the mocks ----

    private Character character(Body body) {
        Character character = stub(Character.class);
        when(character.getId()).thenReturn(body.id);
        when(character.getName()).thenReturn(body.name);
        when(character.getGender()).thenReturn(body.gender);
        when(character.getMapId()).thenAnswer(call -> body.mapId);
        when(character.getMap()).thenAnswer(call -> map(body.mapId));
        when(character.getPosition()).thenAnswer(call -> new Point(body.pos));
        when(character.getHp()).thenAnswer(call -> body.hp);
        when(character.getEventInstance()).thenAnswer(call -> body.inEvent ? event : null);
        when(character.getItemQuantity(anyInt(), anyBoolean()))
                .thenAnswer(call -> body.count((Integer) call.getArgument(0)));
        doAnswer(call -> {
            MapObject object = call.getArgument(0);
            drops.stream().filter(drop -> drop.item == object).findFirst().ifPresent(drop -> pickUp(body, drop));
            return null;
        }).when(character).pickupItem(any());
        return character;
    }

    private MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = stub(MapleMap.class);
            when(map.getId()).thenReturn(id);
            when(map.getAllMonsters()).thenReturn(List.of());
            when(map.getEventInstance()).thenAnswer(call -> AgentApqDefinition.isEventMap(id) ? event : null);
            when(map.getPortal(anyString())).thenAnswer(call -> portalNamed(id, call.getArgument(0)));
            return map;
        });
    }

    private Portal portalNamed(int mapId, String name) {
        Map<Integer, String> names = PORTAL_NAMES.getOrDefault(mapId, Map.of());
        Integer id = names.entrySet().stream().filter(entry -> entry.getValue().equals(name)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (id == null) return null;
        String key = mapId + ":" + name;
        return portals.computeIfAbsent(key, ignored -> {
            Portal portal = stub(Portal.class);
            when(portal.getId()).thenReturn(id);
            when(portal.getName()).thenReturn(name);
            when(portal.getPortalStatus()).thenAnswer(call -> portalOpen.getOrDefault(key, !name.startsWith("go0")));
            when(portal.getScriptName()).thenAnswer(call -> portalScript.get(key));
            return portal;
        });
    }

    private void wireEvent() {
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        when(event.isEventCleared()).thenAnswer(call -> eventCleared);
        when(event.isEventDisposed()).thenAnswer(call -> eventDisposed);
        when(event.getTimeLeft()).thenAnswer(call -> Math.max(0L, timerEndsAtMs - now));
        when(event.getLeaderId()).thenReturn(BEN);
        when(event.getPlayers()).thenAnswer(call -> bodies.values().stream().filter(body -> body.inEvent)
                .map(body -> body.character).toList());
        when(event.wrongEffectCount(anyInt())).thenAnswer(call -> wrongEffects.getOrDefault(
                (Integer) call.getArgument(0), 0));
        // AmoriaPQ.js setup
        for (int stage = 1; stage <= 6; stage++) properties.put("statusStg" + stage, "-1");
        properties.put("missCount", "0");
        properties.put("marriedGroup", "0");
        timerEndsAtMs = now + AgentApqDefinition.EVENT_TIMER_MS;
    }

    private void wireActions() {
        doAnswer(call -> {
            Body body = bodyOf((AgentRuntimeEntry) call.getArgument(0));
            body.dest = new Point((Point) call.getArgument(1));
            body.grind = null;
            return null;
        }).when(actions).navigate(any(), any(), anyBoolean());
        doAnswer(call -> {
            Body body = bodyOf((AgentRuntimeEntry) call.getArgument(0));
            body.dest = null;
            body.grind = null;
            return null;
        }).when(actions).stop(any());
        doAnswer(call -> {
            Body body = bodyOf((AgentRuntimeEntry) call.getArgument(0));
            body.dest = null;
            body.grind = Set.copyOf((Set<Integer>) call.getArgument(1));
            return null;
        }).when(actions).grind(any(), any(Set.class));
        when(actions.groundPoint(any(), any())).thenAnswer(call -> new Point((Point) call.getArgument(1)));
        when(actions.portalPosition(any(), anyInt())).thenAnswer(call -> {
            Body body = bodyOf((Character) call.getArgument(0));
            Point at = PORTALS.getOrDefault(body.mapId, Map.of()).get((Integer) call.getArgument(1));
            return at == null ? null : new Point(at);
        });
        when(actions.npcPosition(any(), anyInt())).thenAnswer(call -> {
            Body body = bodyOf((Character) call.getArgument(0));
            Point at = NPCS.getOrDefault(body.mapId, Map.of()).get((Integer) call.getArgument(1));
            return at == null ? null : new Point(at);
        });
        when(actions.liveMonsterCount(any(), any())).thenAnswer(call -> {
            Body body = bodyOf((Character) call.getArgument(0));
            Set<Integer> ids = call.getArgument(1);
            return (int) mobs.stream().filter(mob -> mob.mapId == body.mapId && ids.contains(mob.mobId)).count();
        });
        when(actions.itemCount(any(), anyInt())).thenAnswer(call ->
                bodyOf((Character) call.getArgument(0)).count((Integer) call.getArgument(1)));
        when(actions.reactors(any())).thenAnswer(call -> {
            Body body = bodyOf((Character) call.getArgument(0));
            return boxes.stream().filter(box -> box.mapId == body.mapId).map(box -> box.reactor).toList();
        });
        when(actions.hitReactor(any(), anyInt())).thenAnswer(call ->
                hit(bodyOf((Character) call.getArgument(0)), (Integer) call.getArgument(1)));
        when(actions.grounded(any())).thenReturn(true);
        when(partyQuest.enterPortal(any(), anyInt())).thenAnswer(call ->
                enterPortal(bodyOf((Character) call.getArgument(0)), (Integer) call.getArgument(1)));
        when(partyQuest.runNpc(any(), anyInt(), any(int[].class))).thenAnswer(call ->
                talk(bodyOf((Character) call.getArgument(0)), (Integer) call.getArgument(1)));
    }

    private void wireChat() {
        doAnswer(call -> {
            lines.add(((Character) call.getArgument(0)).getName() + ": " + call.getArgument(1));
            return null;
        }).when(packets).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        when(party.sendPartyChat(any(), anyString())).thenAnswer(call -> {
            lines.add(((Character) call.getArgument(0)).getName() + " (party): " + call.getArgument(1));
            return true;
        });
    }

    /** The monsters and reactors AmoriaPQ.js's maps start with, fewer than stock but on the same sides. */
    private void populate() {
        int mirror = AgentApqDefinition.MIRROR_MAP;
        for (int i = 0; i < 8; i++) {
            int mobId = List.copyOf(new java.util.TreeSet<>(AgentApqDefinition.MIRROR_MOBS)).get(i % 3);
            mobs.add(new Mob(mobId, mirror, new Point(-1300 + i * 100, 1200 + (i % 4) * 250), 3));
            mobs.add(new Mob(mobId, mirror, new Point(300 + i * 90, 1200 + (i % 4) * 250), 3));
        }
        boxes.add(new Box(AgentApqDefinition.MIRROR_REACTOR, "", mirror, AgentApqDefinition.MIRROR_AT, MIRROR_BOX));
        int pass = AgentApqDefinition.PASS_MAP;
        List<Integer> passMobs = List.copyOf(new java.util.TreeSet<>(AgentApqDefinition.PASS_MOBS));
        for (int i = 0; i < 57; i++) {
            mobs.add(new Mob(passMobs.get(i % 3), pass, new Point(-900 + (i % 19) * 100, -184 - (i / 19) * 150), 3));
        }
        int gates = AgentApqDefinition.GATE_RUN_MAP;
        for (int i = 0; i < 7; i++) {
            boxes.add(new Box(AgentApqDefinition.GATE_REACTOR, "gate0" + i, gates, new Point(GATE_X[i], 435),
                    new Rectangle(GATE_X[i] - 20, 400, 40, 60)));
        }
        mobs.add(new Mob(AgentApqDefinition.BALROG_PHASES.getFirst(), AgentApqDefinition.BOSS_MAP,
                new Point(942, 400), 20));
        int bonus = AgentApqDefinition.BONUS_MAP;
        List<Integer> boxIds = List.copyOf(new java.util.TreeSet<>(AgentApqDefinition.BONUS_BOXES));
        for (int i = 0; i < 12; i++) {
            boxes.add(new Box(boxIds.get(i % boxIds.size()), "", bonus, new Point(-200 + i * 110, 500),
                    new Rectangle(-220 + i * 110, 470, 40, 60)));
        }
    }

    // ---- the stock rules ----

    private boolean enterPortal(Body body, int portalId) {
        Point at = PORTALS.getOrDefault(body.mapId, Map.of()).get(portalId);
        String name = PORTAL_NAMES.getOrDefault(body.mapId, Map.of()).get(portalId);
        if (at == null || name == null || body.pos.distance(at) > 80) return false;
        int mapId = body.mapId;
        if (mapId == AgentApqDefinition.MIRROR_MAP) {
            switch (name) {
                case "female00" -> { // gendergo.js
                    if (body.gender != 1) return false;
                    place(body, new Point(-1362, 1712));
                    return true;
                }
                case "male00" -> {
                    if (body.gender != 0) return false;
                    place(body, new Point(171, 1771));
                    return true;
                }
                case "go00", "go01", "go02" -> {
                    String key = mapId + ":" + name;
                    if (!portalOpen.getOrDefault(key, false) || portalScript.get(key) == null) return false;
                    warp(body, AgentApqDefinition.ROPE_MAPS.get(name.charAt(3) - '0'));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        if (AgentApqDefinition.ROPE_MAPS.contains(mapId)) {
            if (name.startsWith("gate0")) { // gateNN -> doorNN: the ledge above the floor
                place(body, PORTALS.get(mapId).get(idOf(mapId, "door0" + name.charAt(5))));
                return true;
            }
            if (name.startsWith("door0")) { // doorNN -> SSMNN: above the rope
                place(body, PORTALS.get(mapId).get(idOf(mapId, "SSM0" + name.charAt(5))));
                return true;
            }
            if (name.equals("next00") && cleared(2)) {
                warp(body, AgentApqDefinition.PLATFORM_MAP);
                return true;
            }
            return false;
        }
        if (mapId == AgentApqDefinition.PLATFORM_MAP && name.equals("next00") && cleared(3)) {
            warp(body, AgentApqDefinition.PASS_MAP);
            return true;
        }
        if (mapId == AgentApqDefinition.PASS_MAP && name.equals("next00") && cleared(4)) {
            warp(body, AgentApqDefinition.GATE_RUN_MAP);
            return true;
        }
        if (mapId == AgentApqDefinition.GATE_RUN_MAP && name.startsWith("gt0")) { // apqDoor.js
            int gate = name.charAt(3) - '0';
            Box reactor = gate(gate);
            if (reactor.state != AgentApqDefinition.GATE_OPEN_STATE) return false;
            place(body, new Point(GATE_LANDING_X[gate], GATE_FLOOR_Y));
            return true;
        }
        return false;
    }

    private static int idOf(int mapId, String name) {
        return PORTAL_NAMES.get(mapId).entrySet().stream().filter(entry -> entry.getValue().equals(name))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private boolean talk(Body body, int npcId) {
        Point at = NPCS.getOrDefault(body.mapId, Map.of()).get(npcId);
        if (at == null) return false;
        boolean leader = body.id == BEN;
        int mapId = body.mapId;
        if (npcId == AgentApqDefinition.GLIMMER_NPC) { // 9201047.js
            if (mapId == AgentApqDefinition.EXIT_MAP) {
                body.mapId = OUTSIDE_MAP;
                body.pos = new Point(ARRIVALS.get(OUTSIDE_MAP));
                body.inEvent = false;
                return true;
            }
            if (leader) {
                if (body.count(AgentApqDefinition.MIRROR_SHARD) > 0) {
                    body.give(AgentApqDefinition.MIRROR_SHARD, -1);
                    properties.put("statusStg1", "1");
                } else if (intProperty("statusStg1") < 1 && mobsOn(mapId) == 0) {
                    properties.put("statusStg1", "1");
                    mobs.add(new Mob(AgentApqDefinition.FIERRY, mapId, new Point(-245, 810), 4));
                }
            }
            return true;
        }
        int stage = AgentApqDefinition.stageNumber(mapId);
        if (npcId == AgentApqDefinition.PUZZLE_NPC) { // 9201044.js
            if (cleared(stage) || !leader) return true;
            if (intProperty("statusStg" + stage) == -1) {
                properties.put("statusStg" + stage, "0");
                return true;
            }
            if (stage == 1) {
                if (intProperty("statusStg1") == 1) clearStage(stage);
                return true;
            }
            if (mobsOn(mapId) > 0) return true;
            List<Rectangle> areas = stage == 2 ? AgentApqDefinition.ROPE_AREAS : AgentApqDefinition.PLATFORM_AREAS;
            int[] objset = new int[9];
            int onCombo = 0;
            for (Body member : bodies.values()) {
                if (!member.inEvent || member.mapId != mapId) continue;
                for (int y = 0; y < areas.size(); y++) {
                    if (areas.get(y).contains(member.pos)) {
                        onCombo++;
                        objset[y]++;
                        break;
                    }
                }
            }
            if (onCombo != 5) return true;
            String combo = properties.get("stage" + stage + "combo");
            if (combo == null || combo.isEmpty()) {
                combo = stage == 2 ? comboOne() : comboTwo();
                properties.put("stage" + stage + "combo", combo);
            }
            String[] parts = combo.split(",");
            boolean right = true;
            int playersRight = 0;
            for (int i = 0; i < 9; i++) {
                if (Integer.parseInt(parts[i]) != objset[i]) right = false;
                else if (objset[i] > 0) playersRight++;
            }
            judgedTries.merge(stage, 1, Integer::sum);
            if (right) {
                properties.put("statusStg" + stage, "1");
                clearStage(stage);
                return true;
            }
            int miss = intProperty("missCount") + 1;
            int maxMiss = stage == 2 ? AgentApqDefinition.ROPE_MISSES_PER_DRAW : 1;
            if (miss < maxMiss) {
                properties.put("missCount", String.valueOf(miss));
            } else {
                spawnMobs(stage, mapId, playersRight);
                properties.put("missCount", "0");
                if (stage == 2) properties.put("stage2combo", "");
            }
            wrongEffects.merge(mapId, 1, Integer::sum);
            return true;
        }
        if (npcId == AgentApqDefinition.LATE_NPC) { // 9201045.js
            if (cleared(stage)) {
                if (stage == 5) {
                    for (Body member : bodies.values()) {
                        if (member.inEvent) warp(member, AgentApqDefinition.BOSS_MAP);
                    }
                } else if (stage == 6 && leader) {
                    timerEndsAtMs = now + AgentApqDefinition.BONUS_TIMER_MS;
                    reachedBonus = true;
                    for (Body member : bodies.values()) {
                        if (member.inEvent) warp(member, AgentApqDefinition.BONUS_MAP);
                    }
                }
                return true;
            }
            if (stage == 6) {
                if (AgentApqDefinition.ARENA_DOOR_AREA.contains(body.pos)) {
                    if (body.hp > 0) place(body, new Point(499, -26));
                } else if (leader && body.count(AgentApqDefinition.GEIST_FANG) > 0) {
                    body.give(AgentApqDefinition.GEIST_FANG, -1);
                    clearStage(6);
                    eventCleared = true;
                }
                return true;
            }
            if (!leader) return true;
            if (intProperty("statusStg" + stage) == -1) {
                properties.put("statusStg" + stage, "0");
                return true;
            }
            if (stage == 4 && body.count(AgentApqDefinition.CUPID_CODE_PIECE) >= AgentApqDefinition.PIECES_FOR_GATES) {
                body.give(AgentApqDefinition.CUPID_CODE_PIECE, -AgentApqDefinition.PIECES_FOR_GATES);
                long left = timerEndsAtMs - now;
                if (left >= AgentApqDefinition.GATE_RUN_BANK_THRESHOLD_MS) {
                    properties.put("timeLeft", String.valueOf(left));
                    timerEndsAtMs = now + AgentApqDefinition.GATE_RUN_TIMER_MS;
                    stageFiveTimerRestarts++;
                }
                clearStage(4);
            } else if (stage == 5) {
                boolean together = bodies.values().stream().filter(member -> member.inEvent)
                        .allMatch(member -> member.mapId == mapId);
                boolean gathered = together && bodies.values().stream().filter(member -> member.inEvent)
                        .allMatch(member -> member.hp <= 0 || AgentApqDefinition.GATHER_AREA.contains(member.pos));
                boolean open = boxes.stream().filter(box -> box.reactorId == AgentApqDefinition.GATE_REACTOR)
                        .allMatch(box -> box.state == AgentApqDefinition.GATE_OPEN_STATE);
                if (gathered && open) {
                    String banked = properties.get("timeLeft");
                    if (banked != null) {
                        long tr = timerEndsAtMs - now;
                        timerEndsAtMs = now + Long.parseLong(banked) - (AgentApqDefinition.GATE_RUN_TIMER_MS - tr);
                    }
                    clearStage(5);
                }
            }
            return true;
        }
        return true;
    }

    private void clearStage(int stage) {
        properties.put(stage + "stageclear", "true");
        if (stage == 1) {
            int val = random.nextInt(3);
            String key = AgentApqDefinition.MIRROR_MAP + ":go0" + val;
            portalOpen.put(key, true);
            portalScript.put(key, "apq0" + val);
        }
    }

    private boolean cleared(int stage) {
        return properties.get(stage + "stageclear") != null;
    }

    private int intProperty(String key) {
        String value = properties.get(key);
        return value == null ? 0 : Integer.parseInt(value.trim());
    }

    /** generateCombo1 as fixed on this branch: each of five takes one of three ropes. */
    private String comboOne() {
        int[] positions = new int[9];
        int picked = random.nextInt(243);
        for (int placed = 0; placed < 5; placed++) {
            positions[picked % 3]++;
            picked /= 3;
        }
        return join(positions);
    }

    /** generateCombo2: five of the nine platforms. */
    private String comboTwo() {
        int[] positions = new int[9];
        int toPick = 5;
        while (toPick > 0) {
            int picked = random.nextInt(9);
            if (positions[picked] == 0) {
                positions[picked] = 1;
                toPick--;
            }
        }
        return join(positions);
    }

    private static String join(int[] positions) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) text.append(',');
            text.append(positions[i]);
        }
        return text.toString();
    }

    private void spawnMobs(int stage, int mapId, int playersRight) {
        if (stage == 2) {
            int[] xs = {619, 299, 47, -140, -471};
            for (int x : xs) {
                for (int j = 0; j < 2; j++) {
                    for (int mobId : new java.util.TreeSet<>(AgentApqDefinition.ROPE_WAVE_MOBS)) {
                        mobs.add(new Mob(mobId, mapId, new Point(x, AgentApqDefinition.ROPE_FLOOR_Y), 2));
                    }
                }
            }
            wavesSpawned.merge(stage, 1, Integer::sum);
            return;
        }
        int[] xs = {2303, 1832, 1656, 1379, 1171};
        int[] ys = {240, 150, 300, 150, 240};
        List<Integer> slimes = List.copyOf(new java.util.TreeSet<>(AgentApqDefinition.SLIMES));
        for (int i = 0; i < playersRight; i++) {
            int at = random.nextInt(5);
            mobs.add(new Mob(slimes.get(random.nextInt(4)), mapId, new Point(xs[at], ys[at]), 2));
        }
        if (playersRight > 0) wavesSpawned.merge(stage, 1, Integer::sum);
    }

    private int mobsOn(int mapId) {
        return (int) mobs.stream().filter(mob -> mob.mapId == mapId).count();
    }

    private Box gate(int index) {
        return boxes.stream().filter(box -> box.name.equals("gate0" + index)).findFirst().orElseThrow();
    }

    private boolean hit(Body body, int objectId) {
        Box box = boxes.stream().filter(candidate -> candidate.oid == objectId).findFirst().orElse(null);
        if (box == null || box.mapId != body.mapId || body.pos.distance(box.pos) > 120 || !box.alive) return false;
        if (box.reactorId == AgentApqDefinition.GATE_REACTOR) {
            if (box.state < AgentApqDefinition.GATE_OPEN_STATE) box.state++;
            return true;
        }
        if (AgentApqDefinition.BONUS_BOXES.contains(box.reactorId) && box.state == 0) {
            box.state = 1;
            box.alive = false;
            return true;
        }
        return false;
    }

    private boolean playerDrop(Body body, int itemId, short quantity) {
        if (body.count(itemId) < quantity || quantity <= 0) return false;
        body.give(itemId, -quantity);
        drops.add(new Drop(itemId, quantity, body.mapId, body.pos, body));
        if (itemId == AgentApqDefinition.WING_HAMMER && body.mapId == AgentApqDefinition.MIRROR_MAP
                && MIRROR_BOX.contains(body.pos)) {
            hammerOnMirrorAtMs = now; // MapleMap.activateItemReactors: 5 s later
        }
        return true;
    }

    private void pickUp(Body body, Drop drop) {
        if (drop.picked || drop.mapId != body.mapId || body.pos.distance(drop.pos) > 100) return;
        drop.picked = true;
        body.give(drop.itemId, drop.quantity);
    }

    private void warp(Body body, int mapId) {
        body.mapId = mapId;
        body.pos = new Point(ARRIVALS.get(mapId));
        body.dest = null;
        body.grind = null;
        if (body.entry != null) AgentClimbStateRuntime.setClimbUpIntent(body.entry, false);
    }

    private void place(Body body, Point at) {
        body.pos = new Point(at);
        body.dest = null;
    }

    // ---- one step ----

    /** One background tick: every companion's own tick, the run's pass, Ben, then the world moves. */
    void step() {
        for (int id : COMPANIONS) {
            Body body = bodies.get(id);
            if (body.mapId != OUTSIDE_MAP) {
                AgentApqCoordinator.tickMember(session, body.entry, body.character, now);
            }
        }
        AgentApqCoordinator.tickSession(session, now);
        benActs();
        benStageAt.putIfAbsent(AgentApqDefinition.stageOf(bodies.get(BEN).mapId), now);
        for (Body body : bodies.values()) {
            if (body.grind != null) fight(body);
            else if (body.dest != null) move(body, body.dest);
        }
        if (hammerOnMirrorAtMs >= 0L && now - hammerOnMirrorAtMs >= 5_000L) {
            hammerOnMirrorAtMs = -1L;
            drops.stream().filter(drop -> !drop.picked && drop.itemId == AgentApqDefinition.WING_HAMMER
                    && MIRROR_BOX.contains(drop.pos)).findFirst().ifPresent(hammer -> {
                        hammer.picked = true;
                        drops.add(new Drop(AgentApqDefinition.MIRROR_SHARD, 1, AgentApqDefinition.MIRROR_MAP,
                                AgentApqDefinition.MIRROR_AT, null));
                    });
        }
        if (!eventDisposed && now >= timerEndsAtMs) {
            if (!eventCleared) atDispose = describe();
            // AmoriaPQ.js scheduledTimeout: after the clear the vault's minute ends the run, before it the event
            eventDisposed = true;
            for (Body body : bodies.values()) {
                if (body.inEvent) {
                    warp(body, AgentApqDefinition.EXIT_MAP);
                    body.inEvent = false;
                }
            }
        }
        drops.removeIf(drop -> drop.picked || now - drop.dropTime > 180_000L);
        now += STEP_MS;
    }

    private void fight(Body body) {
        Mob target = mobs.stream().filter(mob -> mob.mapId == body.mapId && body.grind.contains(mob.mobId))
                .filter(mob -> reachable(body, mob))
                .min(Comparator.comparingDouble(mob -> mob.pos.distanceSq(body.pos))).orElse(null);
        if (target == null) return;
        if (body.pos.distance(target.pos) > HIT_RANGE_PX) {
            move(body, target.pos);
            return;
        }
        if (--target.hp > 0) return;
        mobs.remove(target);
        Point at = target.pos;
        if (target.mobId == AgentApqDefinition.FIERRY) {
            drops.add(new Drop(AgentApqDefinition.WING_HAMMER, 1, target.mapId, at, null));
        } else if (AgentApqDefinition.PASS_MOBS.contains(target.mobId)) {
            drops.add(new Drop(AgentApqDefinition.CUPID_CODE_PIECE, 1, target.mapId, at, null));
        } else if (AgentApqDefinition.BALROG_PHASES.contains(target.mobId)) {
            int phase = AgentApqDefinition.BALROG_PHASES.indexOf(target.mobId);
            if (phase + 1 < AgentApqDefinition.BALROG_PHASES.size()) {
                mobs.add(new Mob(AgentApqDefinition.BALROG_PHASES.get(phase + 1), target.mapId, at, 30 + phase * 30));
            } else {
                drops.add(new Drop(AgentApqDefinition.GEIST_FANG, 1, target.mapId, at, null));
            }
        }
    }

    /** The Mirror's rooms: a member fights its own side; the boys' side drops into the girls' (A0), not back. */
    private boolean reachable(Body body, Mob mob) {
        if (mob.mapId != AgentApqDefinition.MIRROR_MAP || mob.pos.y <= AgentApqDefinition.MIRROR_ROOMS_BELOW_Y) {
            return arenaSide(body.pos) == arenaSide(mob.pos) || body.mapId != AgentApqDefinition.BOSS_MAP;
        }
        if (body.pos.y <= AgentApqDefinition.MIRROR_ROOMS_BELOW_Y) return false;
        boolean boysSide = body.pos.x > 0;
        boolean mobBoys = mob.pos.x > 0;
        return boysSide == mobBoys || boysSide;
    }

    private static boolean arenaSide(Point at) {
        return at.x >= AgentApqDefinition.ARENA_WEST_X;
    }

    /** Moves a step toward {@code to} through the walls that matter: rooms, ropes, gates, the arena. */
    private void move(Body body, Point to) {
        Point target = new Point(to);
        int mapId = body.mapId;
        if (mapId == AgentApqDefinition.MIRROR_MAP) {
            boolean inRooms = body.pos.y > AgentApqDefinition.MIRROR_ROOMS_BELOW_Y;
            if (!inRooms && target.y > AgentApqDefinition.MIRROR_ROOMS_BELOW_Y) {
                target.y = AgentApqDefinition.MIRROR_ROOMS_BELOW_Y - 100; // only gendergo leads in
            }
        } else if (AgentApqDefinition.ROPE_MAPS.contains(mapId)) {
            if (!ropeMove(body, target)) return;
        } else if (mapId == AgentApqDefinition.GATE_RUN_MAP) {
            for (int i = 0; i < GATE_X.length; i++) {
                if (body.pos.x <= GATE_PORTAL_X[i] && target.x > GATE_PORTAL_X[i]) {
                    target.x = GATE_PORTAL_X[i] - 15; // the floor ends at the gate: only its portal passes
                    target.y = GATE_FLOOR_Y;
                    break;
                }
            }
        } else if (mapId == AgentApqDefinition.BOSS_MAP && arenaSide(body.pos) != arenaSide(target)) {
            target.x = arenaSide(body.pos) ? AgentApqDefinition.ARENA_WEST_X : 60; // Amos sends you in; no way back
        }
        double distance = body.pos.distance(target);
        if (distance <= SPEED_PX) {
            body.pos = target;
        } else {
            body.pos = new Point((int) Math.round(body.pos.x + (target.x - body.pos.x) * SPEED_PX / distance),
                    (int) Math.round(body.pos.y + (target.y - body.pos.y) * SPEED_PX / distance));
        }
    }

    /**
     * Heart Strings: on the floor a member walks the floor; on a rope it climbs that rope or lets go to the floor; on a
     * door ledge or above a rope it can only fall, onto the rope below with up held, else to the floor. False when the
     * step is a fall it took.
     */
    private boolean ropeMove(Body body, Point target) {
        int floor = AgentApqDefinition.ROPE_FLOOR_Y;
        if (body.pos.y >= floor - 60) {
            target.y = floor;
            return true;
        }
        Rectangle on = AgentApqDefinition.ROPE_AREAS.stream().filter(rope -> rope.contains(body.pos)).findFirst()
                .orElse(null);
        if (on != null) {
            if (on.contains(target)) return true;
            body.pos = new Point(body.pos.x, floor); // let go
            return false;
        }
        Rectangle below = AgentApqDefinition.ROPE_AREAS.stream()
                .filter(rope -> body.pos.x >= rope.x && body.pos.x < rope.x + rope.width && body.pos.y < rope.y)
                .findFirst().orElse(null);
        boolean up = body.entry != null && AgentClimbStateRuntime.climbUpIntent(body.entry);
        body.pos = below != null && up ? new Point(body.pos.x, below.y + below.height / 2)
                : new Point(body.pos.x, floor);
        return false;
    }

    // ---- Ben, the human leader ----

    private record Intent(Kind kind, int mapId) {
        enum Kind { PICK_UP, GLIMMER, AMOS, MIRROR_GATE, ARENA, GATES }
    }

    /** What Ben does this tick: the next thing he was told, else what a player there does by himself. */
    private void benActs() {
        Body ben = bodies.get(BEN);
        hear(ben);
        intents.removeIf(intent -> intent.mapId() != ben.mapId);
        if (ben.mapId == OUTSIDE_MAP) return;
        if (!intents.isEmpty()) ben.grind = null;
        if (!intents.isEmpty() && act(ben, intents.getFirst())) {
            intents.removeFirst();
            return;
        }
        if (!intents.isEmpty()) return;
        int stage = AgentApqDefinition.stageNumber(ben.mapId);
        int next = AgentApqDefinition.nextPortalId(ben.mapId);
        if (stage >= 2 && stage <= 4 && cleared(stage) && next >= 0) {
            walkThrough(ben, next); // Amos said the portal is open
            return;
        }
        switch (AgentApqDefinition.stageOf(ben.mapId)) {
            case MIRROR -> goNear(ben, NPCS.get(ben.mapId).get(AgentApqDefinition.GLIMMER_NPC));
            case ROPES, PLATFORMS -> goNear(ben, new Point(NPCS.get(ben.mapId).get(AgentApqDefinition.PUZZLE_NPC).x - 40,
                    NPCS.get(ben.mapId).get(AgentApqDefinition.PUZZLE_NPC).y));
            case PASSES -> ben.grind = Set.copyOf(AgentApqDefinition.PASS_MOBS);
            case BOSS -> {
                if (arenaSide(ben.pos)) ben.grind = Set.copyOf(AgentApqDefinition.BALROG_PHASES);
            }
            default -> {
            }
        }
    }

    private void hear(Body ben) {
        while (linesRead < lines.size()) {
            String line = lines.get(linesRead++).toLowerCase();
            int map = ben.mapId;
            if (line.contains("at your feet") || line.contains("pick up")) intents.add(new Intent(Intent.Kind.PICK_UP, map));
            if (line.contains("glimmer man") && !line.contains("head out")) {
                intents.add(new Intent(Intent.Kind.GLIMMER, map));
            }
            if (line.contains("head out")) intents.add(new Intent(Intent.Kind.GLIMMER, map));
            if (line.contains("gate's open up top")) intents.add(new Intent(Intent.Kind.MIRROR_GATE, map));
            if (line.contains("go go") || line.contains("run east")) {
                intents.add(new Intent(Intent.Kind.GATES, map)); // "run to amos at the far end": a run, not a talk
            } else if (line.contains("amos down there")) {
                intents.add(new Intent(Intent.Kind.ARENA, map));
            } else if (line.contains("talk to amos") || line.contains("ask amos") || line.contains("give amos")
                    || line.contains("to amos") || line.contains("once more") || line.contains("say hi to amos")) {
                intents.add(new Intent(Intent.Kind.AMOS, map));
            }
        }
    }

    /** One step of an intent; true once it is done. */
    private boolean act(Body ben, Intent intent) {
        switch (intent.kind()) {
            case PICK_UP -> {
                Drop mine = drops.stream().filter(drop -> !drop.picked && drop.mapId == ben.mapId
                                && drop.dropper != null && drop.dropper.id != BEN && ben.pos.distance(drop.pos) < 1_000)
                        .min(Comparator.comparingDouble(drop -> drop.pos.distance(ben.pos))).orElse(null);
                if (mine == null) return true;
                if (ben.pos.distance(mine.pos) > 40) {
                    ben.dest = new Point(mine.pos);
                    return false;
                }
                pickUp(ben, mine);
                return false;
            }
            case GLIMMER -> {
                return talkTo(ben, AgentApqDefinition.GLIMMER_NPC);
            }
            case AMOS -> {
                int npc = ben.mapId == AgentApqDefinition.MIRROR_MAP || AgentApqDefinition.ROPE_MAPS.contains(ben.mapId)
                        || ben.mapId == AgentApqDefinition.PLATFORM_MAP ? AgentApqDefinition.PUZZLE_NPC
                        : AgentApqDefinition.LATE_NPC;
                if (ben.mapId == AgentApqDefinition.BOSS_MAP && !arenaSide(ben.pos)) return true;
                return talkTo(ben, npc);
            }
            case MIRROR_GATE -> {
                for (String name : AgentApqDefinition.MIRROR_GATES) {
                    String key = AgentApqDefinition.MIRROR_MAP + ":" + name;
                    if (portalOpen.getOrDefault(key, false)) {
                        walkThrough(ben, idOf(AgentApqDefinition.MIRROR_MAP, name));
                        return ben.mapId != AgentApqDefinition.MIRROR_MAP;
                    }
                }
                return true;
            }
            case ARENA -> {
                if (arenaSide(ben.pos)) return true;
                if (!AgentApqDefinition.ARENA_DOOR_AREA.contains(ben.pos)) {
                    ben.dest = new Point(AgentApqDefinition.ARENA_DOOR_AT);
                    return false;
                }
                talk(ben, AgentApqDefinition.LATE_NPC);
                return true;
            }
            case GATES -> {
                if (AgentApqDefinition.GATHER_AREA.contains(ben.pos)) return true;
                int gateAhead = -1;
                for (int i = 0; i < GATE_X.length; i++) {
                    if (ben.pos.x <= GATE_PORTAL_X[i]) {
                        gateAhead = i;
                        break;
                    }
                }
                if (gateAhead < 0) {
                    ben.dest = new Point(8700, 60);
                    return false;
                }
                Point portal = new Point(GATE_PORTAL_X[gateAhead], GATE_FLOOR_Y);
                if (ben.pos.distance(portal) > 40) {
                    ben.dest = new Point(portal.x - 15, GATE_FLOOR_Y);
                    return false;
                }
                enterPortal(ben, 16 + gateAhead); // through when it is open; else wait for it here
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private boolean talkTo(Body ben, int npcId) {
        Point npc = NPCS.getOrDefault(ben.mapId, Map.of()).get(npcId);
        if (npc == null) return true;
        Point stand = ben.mapId == AgentApqDefinition.BOSS_MAP ? new Point(AgentApqDefinition.ARENA_WEST_X, ben.pos.y)
                : new Point(npc.x - 30, npc.y);
        if (ben.pos.distance(stand) > 60) {
            ben.dest = stand;
            ben.grind = null;
            return false;
        }
        ben.dest = null;
        ben.grind = null;
        talk(ben, npcId);
        return true;
    }

    private void walkThrough(Body ben, int portalId) {
        Point portal = PORTALS.get(ben.mapId).get(portalId);
        if (ben.pos.distance(portal) > 40) {
            ben.dest = new Point(portal);
            ben.grind = null;
            return;
        }
        enterPortal(ben, portalId);
    }

    private void goNear(Body ben, Point at) {
        ben.grind = null;
        ben.dest = ben.pos.distance(at) > 50 ? new Point(at) : null;
    }

    /** Where everyone stands, for a failing test's message. */
    String describe() {
        StringBuilder text = new StringBuilder();
        for (Body body : bodies.values()) {
            text.append(body.name).append(" map=").append(body.mapId).append(" at=").append(body.pos.x).append(',')
                    .append(body.pos.y).append(" dest=").append(body.dest == null ? "-" : body.dest.x + "," + body.dest.y)
                    .append(" grind=").append(body.grind).append(" hp=").append(body.hp).append(" bag=").append(body.bag)
                    .append(body.entry != null && AgentClimbStateRuntime.climbUpIntent(body.entry) ? " up" : "")
                    .append('\n');
            AgentApqMemberState member = session.member(body.id);
            if (member != null) text.append("  next=").append(member.nextActionAtMs() - now).append('\n');
        }
        AgentApqPuzzle puzzle = session.puzzle(bodies.get(BEN).mapId);
        text.append("puzzle=").append(puzzle).append(" props=").append(properties).append(" mobs=").append(mobs.size())
                .append(" intents=").append(intents).append('\n');
        return text.toString();
    }

    /** Steps until the run ends or {@code maxSteps} pass; the steps taken. */
    int runUntilDone(int maxSteps) {
        int steps = 0;
        while (!session.terminal() && steps < maxSteps) {
            step();
            steps++;
        }
        return steps;
    }
}
