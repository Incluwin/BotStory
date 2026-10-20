package scripting;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.reactor.ReactorActionManager;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapleMap;
import server.maps.Reactor;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * CWKPQ Step C0 (docs/CWKPQ_C0_RESULTS.md): runs the stock stirge plate script scripts/reactor/6109013.js the way
 * AbstractScriptManager.getInvocableScriptEngine does (graal.js factory engine, allowHostAccess and
 * allowHostClassLookup bindings, eval of the file, "rm" put by ReactorScriptManager.initializeInvocable), with the
 * stage-4 counter at the trigger value 5, and prints what touch() does. Throwaway; not part of the suite.
 */
class CwkpqC0StirgeScriptProbe {
    @Test
    void probe() throws Exception {
        run("stock", Files.readString(Path.of("scripts", "reactor", "6109013.js")));
        run("arity fixed only", Files.readString(Path.of("scripts", "reactor", "6109013.js"))
                .replace("killAllMonsters(true)", "killAllMonsters()"));
    }

    private static void run(String label, String source) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js").getFactory().getScriptEngine();
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(source);

        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(610_030_400);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getIntProperty(eq("glpq_s"))).thenReturn(5);
        Character player = mock(Character.class);
        when(player.getMap()).thenReturn(map);
        when(player.getEventInstance()).thenReturn(eim);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(player);
        ReactorActionManager rm = new ReactorActionManager(client, mock(Reactor.class), (Invocable) engine);
        engine.put("rm", rm);
        String result;
        try {
            ((Invocable) engine).invokeFunction("touch");
            result = "returned normally";
        } catch (Throwable t) {
            result = t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println("[CWKPQ-C0 stirge] " + label + ": touch() at glpq_s=5 -> " + result);
        System.out.println("[CWKPQ-C0 stirge] " + label + ": eim calls = " + mockingDetails(eim).getInvocations());
    }
}
