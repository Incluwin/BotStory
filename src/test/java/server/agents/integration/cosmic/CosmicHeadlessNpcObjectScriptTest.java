package server.agents.integration.cosmic;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCScriptManager;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A headless NPC run can name the NPC map object it talks to, as a client click does, so scripts that tell their NPC
 * objects apart (RJPQ's stage-1 books read {@code getNpcObjectId() % 26}) see the right one; every other run still
 * talks to no object (-1).
 */
class CosmicHeadlessNpcObjectScriptTest {
    /** Has no scripts/npc file, so the conversation can only run on the prepared engine. */
    private static final int UNSCRIPTED_NPC = 9_999_998;
    private static final int BOOK_OBJECT = 1_000_000_007;
    private static final String CONVERSATION = """
            var seenObject = null;
            var picked = -1;
            function start() { seenObject = cm.getNpcObjectId(); }
            function action(mode, type, selection) { picked = selection; cm.dispose(); }
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

    private static Character agent() {
        BotClient client = mock(BotClient.class);
        Character agent = mock(Character.class);
        when(agent.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(agent);
        when(client.canClickNPC()).thenReturn(true);
        return agent;
    }

    private int seenObjectAfter(boolean onObject) throws Exception {
        assertFalse(Files.exists(Path.of("scripts", NPCScriptManager.npcScriptPath(UNSCRIPTED_NPC))));
        ExecutorService prep = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-npc-object-prep"));
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, prep, 1, 4);
        try {
            engines.prepare(NPCScriptManager.npcScriptPath(UNSCRIPTED_NPC));
            prep.submit(() -> { }).get(10, TimeUnit.SECONDS);

            boolean ran = onObject
                    ? CosmicHeadlessNpcScriptGateway.executeOnObject(engines, agent(), UNSCRIPTED_NPC, BOOK_OBJECT, 3)
                    : CosmicHeadlessNpcScriptGateway.execute(engines, agent(), UNSCRIPTED_NPC, 3);

            assertTrue(ran);
            ScriptEngine used = created.get(0);
            assertEquals(3, ((Number) used.get("picked")).intValue(), "the selection still reaches action");
            return ((Number) used.get("seenObject")).intValue();
        } finally {
            prep.shutdownNow();
        }
    }

    @Test
    void aRunOnAnObjectShowsTheScriptThatObject() throws Exception {
        assertEquals(BOOK_OBJECT, seenObjectAfter(true));
    }

    @Test
    void pinEveryOtherHeadlessRunStillTalksToNoObject() throws Exception {
        assertEquals(-1, seenObjectAfter(false));
    }
}
