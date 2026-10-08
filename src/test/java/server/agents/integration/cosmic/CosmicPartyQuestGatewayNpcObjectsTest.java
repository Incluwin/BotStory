package server.agents.integration.cosmic;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCScriptManager;
import server.agents.integration.NpcSighting;
import server.life.NPC;
import server.life.NPCStats;
import server.maps.MapleMap;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.awt.Point;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Agent sees every NPC object of one NPC id on its map, hidden ones too (RJPQ hides its 26 books), and talks to
 * one of them only when that object really is such an NPC on its own map.
 */
class CosmicPartyQuestGatewayNpcObjectsTest {
    private static final int BOOK = 2_112_007;
    private static final int ROMEO = 2_112_004;
    /** Has no scripts/npc file, so a conversation with it can only run on the prepared engine. */
    private static final int UNSCRIPTED_BOOK = 9_999_997;
    private static final String CONVERSATION = """
            var seenObject = null;
            function start() { seenObject = cm.getNpcObjectId(); }
            function action(mode, type, selection) { cm.dispose(); }
            """;

    private static ScriptEngineFactory graal;

    private final List<ScriptEngine> created = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void graal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        graal = new ScriptEngineManager().getEngineByName("graal.js").getFactory();
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

    private static NPC npc(int id, int x, int y, boolean hidden) {
        NPC npc = new NPC(id, new NPCStats("test npc " + id));
        npc.setPosition(new Point(x, y));
        npc.setHide(hidden);
        return npc;
    }

    private static Character agentOn(MapleMap map) {
        BotClient client = mock(BotClient.class);
        Character agent = mock(Character.class);
        when(agent.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(agent);
        when(client.canClickNPC()).thenReturn(true);
        when(agent.getMap()).thenReturn(map);
        return agent;
    }

    @Test
    void everyObjectOfTheNpcIsSeenHiddenOnesTooInObjectOrder() {
        MapleMap lab = new MapleMap(926_100_000, 0, 1, 926_100_700, 1f);
        NPC hiddenBook = npc(BOOK, -1638, 310, true);
        NPC romeo = npc(ROMEO, 132, 304, false);
        NPC visibleBook = npc(BOOK, 1348, -22, false);
        lab.addMapObject(hiddenBook);
        lab.addMapObject(romeo);
        lab.addMapObject(visibleBook);

        List<NpcSighting> books = CosmicPartyQuestGateway.INSTANCE.npcObjects(agentOn(lab), BOOK);

        assertEquals(List.of(
                new NpcSighting(hiddenBook.getObjectId(), BOOK, new Point(-1638, 310)),
                new NpcSighting(visibleBook.getObjectId(), BOOK, new Point(1348, -22))), books);
        assertEquals(List.of(new NpcSighting(romeo.getObjectId(), ROMEO, new Point(132, 304))),
                CosmicPartyQuestGateway.INSTANCE.npcObjects(agentOn(lab), ROMEO));
        assertEquals(List.of(), CosmicPartyQuestGateway.INSTANCE.npcObjects(agentOn(null), BOOK));
    }

    @Test
    void anObjectThatIsNotThatNpcOnTheAgentsMapIsNeverTalkedTo() {
        MapleMap lab = new MapleMap(926_100_000, 0, 1, 926_100_700, 1f);
        NPC book = npc(BOOK, 10, 306, true);
        NPC romeo = npc(ROMEO, 132, 304, false);
        lab.addMapObject(book);
        lab.addMapObject(romeo);
        Character agent = agentOn(lab);

        assertFalse(CosmicPartyQuestGateway.INSTANCE.runNpcObject(agent, BOOK, romeo.getObjectId()),
                "Romeo's object is not a book");
        assertFalse(CosmicPartyQuestGateway.INSTANCE.runNpcObject(agent, BOOK, book.getObjectId() + 100),
                "no such object on the map");
        assertFalse(CosmicPartyQuestGateway.INSTANCE.runNpcObject(agentOn(null), BOOK, book.getObjectId()),
                "an Agent on no map");
        assertNull(NPCScriptManager.getInstance().getCM(agent.getClient()), "no conversation was opened");
    }

    @Test
    void anotherNpcsObjectNeverRunsTheBooksScript() throws Exception {
        MapleMap lab = new MapleMap(926_100_000, 0, 1, 926_100_700, 1f);
        NPC romeo = npc(ROMEO, 132, 304, false);
        lab.addMapObject(romeo);
        ExecutorService prep = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-npc-objects-prep"));
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, prep, 1, 4);
        try {
            engines.prepare(NPCScriptManager.npcScriptPath(UNSCRIPTED_BOOK));
            prep.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertFalse(CosmicPartyQuestGateway.runNpcObject(engines, agentOn(lab), UNSCRIPTED_BOOK,
                    romeo.getObjectId()));

            assertEquals(0, engines.snapshot().hits(), "the book's prepared script was never taken");
        } finally {
            prep.shutdownNow();
        }
    }

    @Test
    void aBookOnTheAgentsMapIsTalkedToAsThatObject() throws Exception {
        assertFalse(Files.exists(Path.of("scripts", NPCScriptManager.npcScriptPath(UNSCRIPTED_BOOK))));
        MapleMap lab = new MapleMap(926_100_000, 0, 1, 926_100_700, 1f);
        NPC first = npc(UNSCRIPTED_BOOK, -1638, 310, true);
        NPC second = npc(UNSCRIPTED_BOOK, 10, 306, true);
        lab.addMapObject(first);
        lab.addMapObject(second);
        ExecutorService prep = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-npc-objects-prep"));
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, prep, 1, 4);
        try {
            engines.prepare(NPCScriptManager.npcScriptPath(UNSCRIPTED_BOOK));
            prep.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertTrue(CosmicPartyQuestGateway.runNpcObject(engines, agentOn(lab), UNSCRIPTED_BOOK,
                    second.getObjectId()));

            assertEquals(second.getObjectId(), ((Number) created.get(0).get("seenObject")).intValue(),
                    "the script saw the clicked book, not the first one of its id");
        } finally {
            prep.shutdownNow();
        }
    }
}
