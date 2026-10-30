package server.agents.progression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.mods.RepackGuide;
import server.mods.RepackMaps;
import server.mods.ServerMods;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Every travel leg does what its NPC scripts do when the headless gateway answers them with the catalog's
 * selections: the seller hands over the ticket for its price, and the boarding NPC takes the ticket and the fare
 * and sends the rider to the leg's destination or onto its ride. The scripts run in Graal against a recording
 * conversation, as NPCScriptManager runs them for an Agent. The Community Repack areas' guides find their gate open,
 * and a bot (no client) counts as having the Repack files, as RepackMaps lets it in live.
 */
public class AgentTravelLegScriptTest {
    private static final int PURSE = 100_000;
    private static final int MAX_ADVANCES = 10;
    private static final Pattern LIFE_BLOCK = Pattern.compile("(?s)<imgdir name=\"life\">(.*?)\\n  </imgdir>");
    private static final Pattern PORTAL_BLOCK = Pattern.compile("(?s)<imgdir name=\"portal\">(.*?)\\n  </imgdir>");
    private static final Pattern LIFE_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern FIELD = Pattern.compile("name=\"(\\w+)\" value=\"([^\"]*)\"");

    private static ScriptEngineManager engines;
    private MockedStatic<RepackMaps> repack;

    @BeforeAll
    static void graal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        engines = new ScriptEngineManager();
    }

    @BeforeEach
    void openTheRepackGate() {
        RepackMaps gate = new RepackMaps(new ServerMods(ServerMods.ALL, Map.of()), new RepackMaps.Store() {
            @Override
            public Optional<RepackMaps.Answer> load(int accountId) {
                return Optional.empty();
            }

            @Override
            public void save(int accountId, RepackMaps.Answer answer) {
            }

            @Override
            public void forget(int accountId) {
            }
        }, client -> false);
        repack = mockStatic(RepackMaps.class, Mockito.CALLS_REAL_METHODS);
        repack.when(RepackMaps::get).thenReturn(gate);
    }

    @AfterEach
    void closeTheRepackGate() {
        repack.close();
    }

    @Test
    void everySellerHandsOverTheTicketForItsPrice() throws Exception {
        for (AgentTravelLegCatalog.Leg leg : AgentTravelLegCatalog.legs()) {
            AgentTravelLegCatalog.Ticket ticket = leg.ticket();
            if (ticket == null) {
                continue;
            }
            Conversation cm = new Conversation(ticket.sellerMapId(), PURSE, "true");
            run(ticket.sellerNpcId(), cm, ticket.selections());

            assertEquals(1, cm.count(ticket.itemId()), () -> leg.id() + ": the seller hands over its ticket");
            assertEquals(PURSE - ticket.price(), cm.mesos, () -> leg.id() + ": the ticket costs its price");
            assertNull(cm.warpedTo, () -> leg.id() + ": buying a ticket goes nowhere");
        }
    }

    @Test
    void everyBoardingNpcTakesTheTicketAndTheFareAndSendsTheRiderOnItsWay() throws Exception {
        for (AgentTravelLegCatalog.Leg leg : AgentTravelLegCatalog.legs()) {
            Conversation cm = new Conversation(leg.fromMapId(), PURSE, "true");
            if (leg.ticket() != null) {
                cm.items.put(leg.ticket().itemId(), 1);
            }
            if (leg.heldItemId() > 0) {
                cm.items.put(leg.heldItemId(), 1);
            }
            if (leg.savedLocation() != null) {
                cm.saved.put(leg.savedLocation(), leg.toMapId()); // as the way in saved it (the Dojo board: MIRROR)
            }
            board(leg, cm);

            if (leg.rideMapIds().isEmpty()) {
                assertEquals(leg.toMapId(), cm.warpedTo, () -> leg.id() + ": lands on its destination at once");
            } else {
                assertTrue(cm.startedEvent != null || leg.rideMapIds().get(0).equals(cm.warpedTo),
                        () -> leg.id() + ": boards its ride, not " + cm.warpedTo);
            }
            assertEquals(PURSE - leg.fare(), cm.mesos, () -> leg.id() + ": pays its fare");
            if (leg.ticket() != null) {
                assertEquals(0, cm.count(leg.ticket().itemId()), () -> leg.id() + ": hands in its ticket");
            }
            if (leg.heldItemId() > 0) {
                assertEquals(1, cm.count(leg.heldItemId()), () -> leg.id() + ": shows the item it needs and keeps it");
            }
        }
    }

    @Test
    void aTicketLegRefusesARiderWithoutTheTicket() throws Exception {
        for (AgentTravelLegCatalog.Leg leg : AgentTravelLegCatalog.legs()) {
            if (leg.ticket() == null) {
                continue;
            }
            Conversation cm = new Conversation(leg.fromMapId(), PURSE, "true");
            run(leg.npcId(), cm, leg.selections());
            assertNull(cm.warpedTo, () -> leg.id() + ": no ticket, no ride");
            assertNull(cm.startedEvent, () -> leg.id() + ": no ticket, no ride");
        }
    }

    @Test
    void aScheduledRideRefusesBoardingWhileItsGateIsClosed() throws Exception {
        for (String id : List.of("kerning-to-new-leaf-city", "new-leaf-city-to-kerning",
                "kerning-to-singapore", "singapore-to-kerning")) {
            AgentTravelLegCatalog.Leg leg = leg(id);
            Conversation cm = new Conversation(leg.fromMapId(), PURSE, "false");
            cm.items.put(leg.ticket().itemId(), 1);
            run(leg.npcId(), cm, leg.selections());
            assertNull(cm.warpedTo, () -> id + ": waits for the next departure");
            assertEquals(1, cm.count(leg.ticket().itemId()), () -> id + ": keeps its ticket");
        }
    }

    @Test
    void everyNpcStandsWhereItsLegSaysAndEveryEndIsOnTheWalkingGraph() throws Exception {
        Set<Integer> nodes = new HashSet<>();
        for (AgentVictoriaPortalRouteGraphRepository repository : List.of(
                AgentVictoriaPortalRouteGraphRepository.defaultRepository(),
                AgentVictoriaPortalRouteGraphRepository.worldRepository())) {
            for (AgentVictoriaPortalRouteGraph.Edge edge : repository.graph().edges()) {
                nodes.add(edge.fromMapId());
                nodes.add(edge.toMapId());
            }
        }
        for (AgentTravelLegCatalog.Leg leg : AgentTravelLegCatalog.legs()) {
            if (leg.portal() != null) {
                assertNotNull(portalScript(leg.fromMapId(), leg.portal()),
                        () -> leg.id() + ": its scripted portal " + leg.portal() + " is on " + leg.fromMapId());
            } else {
                assertTrue(placed(leg.fromMapId(), leg.npcId()),
                        () -> leg.id() + ": its NPC stands on " + leg.fromMapId());
            }
            assertTrue(nodes.contains(leg.fromMapId()) || walledIn(leg.fromMapId()),
                    () -> leg.id() + ": boards on the walking graph");
            assertTrue(nodes.contains(leg.toMapId()) || walledIn(leg.toMapId()),
                    () -> leg.id() + ": lands on the walking graph");
            for (int ride : leg.rideMapIds()) {
                assertTrue(!nodes.contains(ride), () -> leg.id() + ": its ride map " + ride + " is never walked");
            }
            if (leg.ticket() != null) {
                AgentTravelLegCatalog.Ticket ticket = leg.ticket();
                assertTrue(placed(ticket.sellerMapId(), ticket.sellerNpcId()),
                        () -> leg.id() + ": its ticket seller stands on " + ticket.sellerMapId());
                assertTrue(nodes.contains(ticket.sellerMapId()), () -> leg.id() + ": its seller is on the graph");
            }
        }
    }

    @Test
    void jeffSendsNobodyBelowLevelFiftyIntoTheDungeon() throws Exception {
        AgentTravelLegCatalog.Leg leg = leg("ice-valley-to-sharp-cliff");
        assertEquals(50, leg.minLevel());

        Conversation young = new Conversation(leg.fromMapId(), PURSE, "true");
        young.level = 49;
        run(leg.npcId(), young, leg.selections());
        assertNull(young.warpedTo, "a level 49 rider stays on Ice Valley II");

        Conversation ready = new Conversation(leg.fromMapId(), PURSE, "true");
        ready.level = 50;
        run(leg.npcId(), ready, leg.selections());
        assertEquals(leg.toMapId(), ready.warpedTo);
    }

    @Test
    void aWorldTourSavesWhereItBeganAndTheWayBackLandsThere() throws Exception {
        Conversation out = new Conversation(100000000, PURSE, "true");
        AgentTravelLegCatalog.Leg there = leg("henesys-to-mushroom-shrine");
        run(there.npcId(), out, there.selections());
        assertEquals(800000000, out.warpedTo);
        assertEquals(100000000, out.saved.get("WORLDTOUR"), "Spinel keeps Henesys as the tour's start");

        for (String id : List.of("mushroom-shrine-to-lith-harbor", "kuala-lumpur-to-boat-quay",
                "florina-beach-to-lith-harbor")) {
            AgentTravelLegCatalog.Leg back = leg(id);
            Conversation cm = new Conversation(back.fromMapId(), PURSE, "true");
            cm.saved.put(back.savedLocation(), 100000000);
            run(back.npcId(), cm, back.selections());
            assertEquals(100000000, cm.warpedTo, () -> id + ": lands where its saved location points");
            assertNull(cm.saved.get(back.savedLocation()), () -> id + ": the saved location is used up");
        }
    }

    @Test
    void shanksSailsNobodyBelowLevelSevenOffMapleIsland() throws Exception {
        for (String id : List.of("southperry-to-lith-harbor", "maple-road-southperry-to-lith-harbor")) {
            AgentTravelLegCatalog.Leg leg = leg(id);
            assertEquals(7, leg.minLevel());

            Conversation young = new Conversation(leg.fromMapId(), PURSE, "true");
            young.level = 6;
            run(leg.npcId(), young, leg.selections());
            assertNull(young.warpedTo, () -> id + ": a level 6 newcomer stays on the island");

            Conversation ready = new Conversation(leg.fromMapId(), PURSE, "true");
            ready.level = 7;
            run(leg.npcId(), ready, leg.selections());
            assertEquals(leg.toMapId(), ready.warpedTo);
        }
    }

    @Test
    void theDojoBoardAndRooneySaveWhereTheRiderWasAndTheWayOutLandsThere() throws Exception {
        AgentTravelLegCatalog.Leg board = leg("mu-lung-temple-to-the-dojo");
        Conversation challenger = new Conversation(board.fromMapId(), PURSE, "true");
        run(board.npcId(), challenger, board.selections());
        assertEquals(925020000, challenger.warpedTo);
        assertEquals(250000100, challenger.saved.get("MIRROR"), "the board keeps Mu Lung Temple");

        AgentTravelLegCatalog.Leg out = leg("the-dojo-to-mu-lung-temple");
        assertEquals("MIRROR", out.savedLocation());
        Conversation leaving = new Conversation(out.fromMapId(), PURSE, "true");
        leaving.saved.put("MIRROR", 250000100);
        board(out, leaving);
        assertEquals(out.toMapId(), leaving.warpedTo, "the exit lands where the board saved the rider");
        Conversation resident = new Conversation(out.fromMapId(), PURSE, "true");
        board(out, resident);
        assertEquals(100000000, resident.warpedTo, "with nothing saved the exit lands in Henesys");

        AgentTravelLegCatalog.Leg rooney = leg("ellinia-to-happyville");
        Conversation visitor = new Conversation(rooney.fromMapId(), PURSE, "true");
        run(rooney.npcId(), visitor, rooney.selections());
        assertEquals(209000000, visitor.warpedTo);
        assertEquals(101000000, visitor.saved.get("HAPPYVILLE"));
        AgentTravelLegCatalog.Leg rupi = leg("happyville-to-ellinia");
        Conversation home = new Conversation(rupi.fromMapId(), PURSE, "true");
        run(rupi.npcId(), home, rupi.selections());
        assertEquals(rupi.toMapId(), home.warpedTo, "with nothing saved Rupi sends you to Ellinia, Rooney's own town");
    }

    @Test
    void kerobenLetsOnlyACertificateHolderFromLevelOneHundredIntoTheCaveOfLife() throws Exception {
        AgentTravelLegCatalog.Leg leg = leg("cave-of-life-entrance-to-the-cave");
        assertEquals(100, leg.minLevel());
        assertEquals(4001086, leg.heldItemId(), "the Certificate of the Dragon Squad");

        Conversation outsider = new Conversation(leg.fromMapId(), PURSE, "true");
        run(leg.npcId(), outsider, leg.selections());
        assertNull(outsider.warpedTo, "no certificate (and no dragon shape): get lost");

        Conversation young = new Conversation(leg.fromMapId(), PURSE, "true");
        young.items.put(leg.heldItemId(), 1);
        young.level = 99;
        run(leg.npcId(), young, leg.selections());
        assertNull(young.warpedTo, "a level 99 holder stays at the entrance");

        Conversation member = new Conversation(leg.fromMapId(), PURSE, "true");
        member.items.put(leg.heldItemId(), 1);
        member.level = 100;
        run(leg.npcId(), member, leg.selections());
        assertEquals(leg.toMapId(), member.warpedTo);
        assertEquals(PURSE, member.mesos, "selection 2 goes in; 1 would buy his potions");
    }

    @Test
    void theTempleKeeperOpensTwilightPerionAndEveryArcaneTownAndEachTownsGuideLeadsBack() throws Exception {
        Map<Integer, Integer> guides = Map.of(273000000, 2142107, 450001000, 3003111, 450002000, 3003150,
                450003000, 3003202, 450005000, 3003324, 450006130, 3003467, 450007040, 3003533, 450009050, 3003570,
                450011120, 3003799, 450012000, 3003952);
        guides.forEach((town, guide) -> {
            assertTrue(AgentTravelLegCatalog.legs().stream().anyMatch(leg -> leg.fromMapId() == 270000000
                    && leg.npcId() == 2140000 && leg.toMapId() == town), () -> "the Temple Keeper's door to " + town);
            assertTrue(AgentTravelLegCatalog.legs().stream().anyMatch(leg -> leg.fromMapId() == town
                    && leg.npcId() == guide && leg.toMapId() == 270000000), () -> town + "'s guide back");
            assertTrue(RepackMaps.listed(town) && RepackGuide.offersWayBack(town, guide), () -> town + ": " + guide);
            assertFalse(Files.exists(Path.of("scripts", "npc", guide + ".js")),
                    () -> guide + " has no script: it answers through repack_talk.js");
        });
    }

    private static AgentTravelLegCatalog.Leg leg(String id) {
        return AgentTravelLegCatalog.legs().stream().filter(leg -> leg.id().equals(id)).findFirst().orElseThrow();
    }

    /** Talks to the leg's NPC, or walks into its portal: the portal's script runs as PortalScriptManager runs it. */
    private static void board(AgentTravelLegCatalog.Leg leg, Conversation cm) throws Exception {
        if (leg.portal() == null) {
            run(leg.npcId(), cm, leg.selections());
            return;
        }
        String name = portalScript(leg.fromMapId(), leg.portal());
        assertNotNull(name, () -> leg.id() + ": its portal runs a script");
        assertEquals(Boolean.TRUE, enter(name, cm), () -> leg.id() + ": the portal lets the rider through");
    }

    /** Runs portal script {@code name} for {@code cm} (as PortalScriptManager does) and returns enter's answer. */
    static Object enter(String name, Conversation cm) throws Exception {
        ScriptEngine engine = engines().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(Path.of("scripts", "portal", name + ".js")));
        return ((Invocable) engine).invokeFunction("enter", cm);
    }

    private static synchronized ScriptEngineManager engines() {
        if (engines == null) {
            graal();
        }
        return engines;
    }

    /** No portal on the map leads to another map: only travel legs come and go (Happyville). */
    private static boolean walledIn(int mapId) throws Exception {
        Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000, String.format("%09d.img.xml", mapId));
        Matcher block = PORTAL_BLOCK.matcher(Files.readString(file));
        if (!block.find()) {
            return true;
        }
        Matcher target = Pattern.compile("<int name=\"tm\" value=\"(\\d+)\"/>").matcher(block.group(1));
        while (target.find()) {
            int to = Integer.parseInt(target.group(1));
            if (to != 999999999 && to != mapId) {
                return false;
            }
        }
        return true;
    }

    /** The script of the portal of that name on the map, or null when there is none. */
    private static String portalScript(int mapId, String portalName) throws Exception {
        Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000, String.format("%09d.img.xml", mapId));
        Matcher block = PORTAL_BLOCK.matcher(Files.readString(file));
        if (!block.find()) {
            return null;
        }
        Matcher entry = LIFE_ENTRY.matcher(block.group(1));
        while (entry.find()) {
            Map<String, String> fields = new HashMap<>();
            Matcher field = FIELD.matcher(entry.group(1));
            while (field.find()) {
                fields.put(field.group(1), field.group(2));
            }
            if (portalName.equals(fields.get("pn")) && !fields.getOrDefault("script", "").isBlank()) {
                return fields.get("script");
            }
        }
        return null;
    }

    /**
     * As NPCScriptManager and the headless gateway do: start, then one action(1, 0, selection) per advance. An NPC of
     * the Community Repack's maps with no script of its own talks through repack_talk.js, as the gateway falls back
     * for it (none of the legs' NPCs keeps a shop). A bot is no Agent operator (Shanks offers operators an
     * administration menu first), so AgentAuthorityService answers no for the stub player, which is no Character.
     */
    private static void run(int npcId, Conversation cm, List<Integer> selections) throws Exception {
        ScriptEngine engine = engines().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        bindings.put("cm", cm);
        engine.eval("var hostType = Java.type; Java.type = function (name) { return name === "
                + "'server.agents.auth.AgentAuthorityService' ? { mayOperate: function () { return false; } }"
                + " : hostType(name); };");
        Path file = Path.of("scripts", "npc", npcId + ".js");
        if (!Files.exists(file) && RepackMaps.listed(cm.getMapId())) {
            file = Path.of("scripts", "npc", "repack_talk.js");
        }
        cm.npc = npcId;
        engine.eval(Files.readString(file));
        Invocable script = (Invocable) engine;
        script.invokeFunction("start");
        for (int advance = 0; !cm.disposed && advance < MAX_ADVANCES; advance++) {
            int selection = advance < selections.size() ? selections.get(advance) : 0;
            script.invokeFunction("action", 1, 0, selection);
        }
        assertTrue(cm.disposed, () -> "NPC " + npcId + " never ended its conversation");
    }

    /** In the map's life, or spawned there by RepackMaps.ENTRIES (the Community Repack areas' guides on shared maps). */
    private static boolean placed(int mapId, int npcId) throws Exception {
        if (RepackMaps.ENTRIES.stream().anyMatch(entry -> entry.fromMap() == mapId && entry.npcId() == npcId)) {
            return true;
        }
        Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000, String.format("%09d.img.xml", mapId));
        Matcher life = LIFE_BLOCK.matcher(Files.readString(file));
        if (!life.find()) {
            return false;
        }
        Matcher entry = LIFE_ENTRY.matcher(life.group(1));
        while (entry.find()) {
            Map<String, String> fields = new HashMap<>();
            Matcher field = FIELD.matcher(entry.group(1));
            while (field.find()) {
                fields.put(field.group(1), field.group(2));
            }
            if ("n".equals(fields.get("type")) && Integer.parseInt(fields.getOrDefault("id", "0")) == npcId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The NPC conversation an Agent's headless client holds, recording what the script does; also the portal
     * interaction a portal script gets.
     */
    public static final class Conversation {
        public final ClientStub c;
        private final PlayerStub player;
        final Map<Integer, Integer> items = new HashMap<>();
        final Map<String, Integer> saved = new HashMap<>();
        final Set<Integer> completedQuests = new HashSet<>();
        final Set<Integer> startedQuests = new HashSet<>();
        final String entry;
        int mesos;
        int level = 120;
        int npc;
        boolean disposed;
        Integer warpedTo;
        String startedEvent;

        Conversation(int mapId, int mesos, String entry) {
            this.mesos = mesos;
            this.entry = entry;
            this.player = new PlayerStub(this, mapId);
            this.c = new ClientStub(player);
        }

        int count(int itemId) {
            return items.getOrDefault(itemId, 0);
        }

        public int getLevel() {
            return level;
        }

        public int getMapId() {
            return player.getMapId();
        }

        public int getNpc() {
            return npc;
        }

        public Object getClient() {
            return null;
        }

        public String numberWithCommas(int number) {
            return String.format("%,d", number);
        }

        public void useItem(int itemId) { }

        public void cancelItem(int itemId) { }

        public void playPortalSound() { }

        public boolean isQuestCompleted(int questId) {
            return completedQuests.contains(questId);
        }

        public boolean isQuestStarted(int questId) {
            return startedQuests.contains(questId);
        }

        public void message(String text) { }

        public void playerMessage(int type, String text) { }

        public void sendOk(String text) { }

        public void sendNext(String text) { }

        public void sendNextPrev(String text) { }

        public void sendPrev(String text) { }

        public void sendYesNo(String text) { }

        public void sendSimple(String text) { }

        public void sendAcceptDecline(String text) { }

        public void dispose() {
            disposed = true;
        }

        public boolean haveItem(int itemId) {
            return count(itemId) > 0;
        }

        public boolean haveItem(int itemId, int quantity) {
            return count(itemId) >= quantity;
        }

        public boolean hasItem(int itemId) {
            return haveItem(itemId);
        }

        public int itemQuantity(int itemId) {
            return count(itemId);
        }

        public boolean canHold(int itemId) {
            return true;
        }

        public boolean canHold(int itemId, int quantity) {
            return true;
        }

        public void gainItem(int itemId) {
            gainItem(itemId, 1);
        }

        public void gainItem(int itemId, int quantity) {
            items.merge(itemId, quantity, Integer::sum);
        }

        public void gainMeso(int delta) {
            mesos += delta;
        }

        public int getMeso() {
            return mesos;
        }

        public void warp(int mapId) {
            warpedTo = mapId;
        }

        public void warp(int mapId, int portal) {
            warpedTo = mapId;
        }

        public void warp(int mapId, String portal) {
            warpedTo = mapId;
        }

        public PlayerStub getPlayer() {
            return player;
        }

        public EventStub getEventManager(String name) {
            return new EventStub(this, name);
        }
    }

    public static final class ClientStub {
        private final PlayerStub player;

        ClientStub(PlayerStub player) {
            this.player = player;
        }

        public PlayerStub getPlayer() {
            return player;
        }
    }

    public static final class PlayerStub {
        private final Conversation cm;
        private final int mapId;

        PlayerStub(Conversation cm, int mapId) {
            this.cm = cm;
            this.mapId = mapId;
        }

        public int getMapId() {
            return mapId;
        }

        public MapStub getMap() {
            return new MapStub(mapId);
        }

        public int getMeso() {
            return cm.mesos;
        }

        public boolean haveItem(int itemId) {
            return cm.haveItem(itemId);
        }

        public boolean canHold(int itemId, int quantity) {
            return true;
        }

        public void saveLocation(String type) {
            cm.saved.put(type, mapId);
        }

        public int peekSavedLocation(String type) {
            return cm.saved.getOrDefault(type, -1);
        }

        public int getSavedLocation(String type) {
            Integer map = cm.saved.remove(type);
            return map == null ? -1 : map;
        }

        /** No buff: a rider in the Dragon Elixir's dragon shape (Keroben's other way in) is never planned for. */
        public int getBuffSource(Object stat) {
            return -1;
        }
    }

    public static final class MapStub {
        private final int id;

        MapStub(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    public static final class EventStub {
        private final Conversation cm;
        private final String name;

        EventStub(Conversation cm, String name) {
            this.cm = cm;
            this.name = name;
        }

        public String getProperty(String key) {
            return "entry".equals(key) ? cm.entry : "true";
        }

        public void setProperty(String key, String value) { }

        public boolean startInstance(Object player) {
            cm.startedEvent = name;
            return true;
        }
    }
}
