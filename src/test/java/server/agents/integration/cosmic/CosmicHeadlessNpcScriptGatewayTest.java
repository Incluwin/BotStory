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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CosmicHeadlessNpcScriptGatewayTest {
    /** Has no scripts/npc file, so the conversation can only run if the gateway used a prepared engine. */
    private static final int UNSCRIPTED_NPC = 9_999_999;
    private static final String CONVERSATION = """
            var status = -1;
            var picked = -1;
            function start() { status = 0; }
            function action(mode, type, selection) { picked = selection; cm.dispose(); }
            """;

    private static ScriptEngineFactory graal;

    private final List<ScriptEngine> created = new CopyOnWriteArrayList<>();
    private final List<String> evaluatedOn = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void graal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        graal = new ScriptEngineManager().getEngineByName("graal.js").getFactory();
    }

    private ScriptEngine evaluate(String path) {
        evaluatedOn.add(Thread.currentThread().getName());
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

    @Test
    void preparedEngineRunsTheConversationWithoutEvaluatingOnTheTickThread() throws Exception {
        assertFalse(Files.exists(Path.of("scripts", NPCScriptManager.npcScriptPath(UNSCRIPTED_NPC))));
        ExecutorService prep = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-npc-script-prep"));
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, prep, 1, 4);
        try {
            engines.prepare(NPCScriptManager.npcScriptPath(UNSCRIPTED_NPC));
            prep.submit(() -> { }).get(10, TimeUnit.SECONDS);

            assertTrue(CosmicHeadlessNpcScriptGateway.execute(engines, agent(), UNSCRIPTED_NPC, 7));

            ScriptEngine used = created.get(0);
            assertEquals(7, ((Number) used.get("picked")).intValue(), "start and action ran on the prepared engine");
            assertEquals("test-npc-script-prep", evaluatedOn.get(0));
            assertEquals(1, engines.snapshot().hits());
            assertEquals(0, engines.snapshot().misses());
        } finally {
            prep.shutdownNow();
        }
    }

    @Test
    void withoutAPreparedEngineTheGatewayFallsBackToTheSynchronousPath() {
        CosmicPreparedNpcScriptEngines engines =
                new CosmicPreparedNpcScriptEngines(this::evaluate, path -> 1L, task -> { }, 1, 4);
        Character agent = agent();

        assertFalse(CosmicHeadlessNpcScriptGateway.execute(engines, agent, UNSCRIPTED_NPC),
                "an unscripted NPC still fails exactly as before when nothing was prepared");
        assertNull(NPCScriptManager.getInstance().getCM(agent.getClient()));
        assertEquals(0, engines.snapshot().hits());
        assertEquals(1, engines.snapshot().misses());
    }
}
