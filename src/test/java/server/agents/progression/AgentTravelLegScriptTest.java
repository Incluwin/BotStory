package server.agents.progression;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every travel leg does what its NPC scripts do when the headless gateway answers them with the catalog's
 * selections: the seller hands over the ticket for its price, and the boarding NPC takes the ticket and the fare
 * and sends the rider to the leg's destination or onto its ride. The scripts run in Graal against a recording
 * conversation, as NPCScriptManager runs them for an Agent.
 */
public class AgentTravelLegScriptTest {
    private static final int PURSE = 100_000;
    private static final int MAX_ADVANCES = 10;
    private static final Pattern LIFE_BLOCK = Pattern.compile("(?s)<imgdir name=\"life\">(.*?)\\n  </imgdir>");
    private static final Pattern LIFE_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern FIELD = Pattern.compile("name=\"(\\w+)\" value=\"([^\"]*)\"");

    private static ScriptEngineManager engines;

    @BeforeAll
    static void graal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        engines = new ScriptEngineManager();
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
            run(leg.npcId(), cm, leg.selections());

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
            assertTrue(placed(leg.fromMapId(), leg.npcId()), () -> leg.id() + ": its NPC stands on " + leg.fromMapId());
            assertTrue(nodes.contains(leg.fromMapId()), () -> leg.id() + ": boards on the walking graph");
            assertTrue(nodes.contains(leg.toMapId()), () -> leg.id() + ": lands on the walking graph");
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

    private static AgentTravelLegCatalog.Leg leg(String id) {
        return AgentTravelLegCatalog.legs().stream().filter(leg -> leg.id().equals(id)).findFirst().orElseThrow();
    }

    /** As NPCScriptManager and the headless gateway do: start, then one action(1, 0, selection) per advance. */
    private static void run(int npcId, Conversation cm, List<Integer> selections) throws Exception {
        ScriptEngine engine = engines.getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        bindings.put("cm", cm);
        engine.eval(Files.readString(Path.of("scripts", "npc", npcId + ".js")));
        Invocable script = (Invocable) engine;
        script.invokeFunction("start");
        for (int advance = 0; !cm.disposed && advance < MAX_ADVANCES; advance++) {
            int selection = advance < selections.size() ? selections.get(advance) : 0;
            script.invokeFunction("action", 1, 0, selection);
        }
        assertTrue(cm.disposed, () -> "NPC " + npcId + " never ended its conversation");
    }

    private static boolean placed(int mapId, int npcId) throws Exception {
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

    /** The NPC conversation an Agent's headless client holds, recording what the script does. */
    public static final class Conversation {
        public final ClientStub c;
        private final PlayerStub player;
        final Map<Integer, Integer> items = new HashMap<>();
        final String entry;
        int mesos;
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

        public void sendOk(String text) { }

        public void sendNext(String text) { }

        public void sendNextPrev(String text) { }

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
