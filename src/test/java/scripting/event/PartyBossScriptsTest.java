package scripting.event;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * P14: the party bosses' event scripts (Capt. Latanica, the five Neo City bosses) hand their instance to the living
 * world in afterSetup, once the stock registerParty took the party in, under their own boss key
 * (LivingWorldHooks.bossPartyEntered). The hook itself does nothing while that key is off.
 */
class PartyBossScriptsTest {
    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @ParameterizedTest
    @CsvSource({"LatanicaBattle, latanica", "TD_Battle1, neo_bergamot", "TD_Battle2, neo_dunas",
            "TD_Battle3, neo_aufheben", "TD_Battle4, neo_oberon", "TD_Battle5, neo_nibelung"})
    void afterSetupHandsTheInstanceToTheLivingWorldUnderItsBossKey(String script, String key) throws Exception {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            invocable(script).invokeFunction("afterSetup", eim);

            hooks.verify(() -> LivingWorldHooks.bossPartyEntered(eim, key));
            hooks.verify(() -> LivingWorldHooks.bossPartyEntered(any(), anyString()), times(1));
        }
    }

    private static Invocable invocable(String script) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(Path.of("scripts/event/" + script + ".js"), StandardCharsets.US_ASCII));
        return (Invocable) engine;
    }
}
