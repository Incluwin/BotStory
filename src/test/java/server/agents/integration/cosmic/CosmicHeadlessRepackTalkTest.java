package server.agents.integration.cosmic;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCScriptManager;
import server.Shop;
import server.ShopFactory;
import server.maps.MapleMap;
import server.mods.RepackGuide;
import server.mods.RepackMaps;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An Agent talks to an NPC of the Community Repack's maps that has no script of its own as NPCTalkHandler lets a player:
 * through npc/repack_talk.js, so Twilight Perion's and Arcane River's town guides send it back to Three Doors. An NPC
 * with a script, one with a shop, and an unscripted NPC elsewhere answer it exactly as before.
 */
class CosmicHeadlessRepackTalkTest {
    private static final int TWILIGHT_PERION = 273000000;
    private static final int CLAUDINE = 2142107;
    private static final int HENESYS = 100000000;
    /** Has no scripts/npc file: it runs only on a prepared engine, as a scripted NPC runs its own script. */
    private static final int SCRIPTED_NPC = 9_999_997;
    private static final String CONVERSATION = """
            var picked = -1;
            function start() { }
            function action(mode, type, selection) { picked = selection; cm.dispose(); }
            """;

    private static ScriptEngineFactory graal;

    private final List<ScriptEngine> created = new CopyOnWriteArrayList<>();
    private MockedStatic<ShopFactory> shops;
    private ShopFactory factory;

    @BeforeAll
    static void graal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        graal = new ScriptEngineManager().getEngineByName("graal.js").getFactory();
    }

    @BeforeEach
    void noShopDatabase() {
        factory = mock(ShopFactory.class);
        shops = mockStatic(ShopFactory.class);
        shops.when(ShopFactory::getInstance).thenReturn(factory);
    }

    @AfterEach
    void closeShops() {
        shops.close();
    }

    private ScriptEngine evaluate(String path) {
        ScriptEngine engine = graal.getScriptEngine();
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        try {
            engine.eval(CONVERSATION);
        } catch (ScriptException e) {
            throw new IllegalStateException(e);
        }
        created.add(engine);
        return engine;
    }

    private static Character agentOn(int mapId) {
        BotClient client = mock(BotClient.class);
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(agent.getClient()).thenReturn(client);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getMap()).thenReturn(map);
        when(client.getPlayer()).thenReturn(agent);
        when(client.canClickNPC()).thenReturn(true);
        return agent;
    }

    private static CosmicPreparedNpcScriptEngines nothingPrepared() {
        return new CosmicPreparedNpcScriptEngines(path -> null, path -> 1L, task -> { }, 1, 4);
    }

    @Test
    void twilightPerionsGuideSendsTheAgentBackToThreeDoors() {
        assertFalse(Files.exists(Path.of("scripts", NPCScriptManager.npcScriptPath(CLAUDINE))), "Claudine has no script");
        assertTrue(RepackMaps.listed(TWILIGHT_PERION));
        assertTrue(RepackGuide.offersWayBack(TWILIGHT_PERION, CLAUDINE));
        Character agent = agentOn(TWILIGHT_PERION);

        assertTrue(CosmicHeadlessNpcScriptGateway.execute(nothingPrepared(), agent, CLAUDINE),
                "repack_talk.js holds the conversation");

        verify(agent).changeMap(RepackGuide.THREE_DOORS, 0);
        assertNull(NPCScriptManager.getInstance().getCM(agent.getClient()), "and ends it");
    }

    @Test
    void anUnscriptedNpcOffTheRepackMapsStillFails() {
        Character agent = agentOn(HENESYS);

        assertFalse(CosmicHeadlessNpcScriptGateway.execute(nothingPrepared(), agent, CLAUDINE));
        verify(agent, never()).changeMap(anyInt(), anyInt());
        verify(factory, never()).getShopForNPC(anyInt());
    }

    @Test
    void anUnscriptedShopkeeperOnARepackMapStillFails() {
        when(factory.getShopForNPC(CLAUDINE)).thenReturn(mock(Shop.class));
        Character agent = agentOn(TWILIGHT_PERION);

        assertFalse(CosmicHeadlessNpcScriptGateway.execute(nothingPrepared(), agent, CLAUDINE),
                "as for a player, a shop NPC opens its shop instead of talking");
        verify(agent, never()).changeMap(anyInt(), anyInt());
    }

    @Test
    void aScriptedNpcOnARepackMapRunsItsOwnScript() throws Exception {
        ExecutorService prep = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-repack-talk-prep"));
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, prep, 1, 4);
        try {
            engines.prepare(NPCScriptManager.npcScriptPath(SCRIPTED_NPC));
            prep.submit(() -> { }).get(10, TimeUnit.SECONDS);
            Character agent = agentOn(TWILIGHT_PERION);

            assertTrue(CosmicHeadlessNpcScriptGateway.execute(engines, agent, SCRIPTED_NPC, 5));

            assertEquals(5, ((Number) created.get(0).get("picked")).intValue(), "its own conversation ran");
            verify(agent, never()).changeMap(anyInt(), anyInt());
            verify(factory, never()).getShopForNPC(anyInt());
        } finally {
            prep.shutdownNow();
        }
    }
}
