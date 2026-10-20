package scripting.reactor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan Question 4 (b), user-approved content fix: the stage-4 stirge plates (6109013.js, shared count glpq_s)
 * clear the Blood Stirges when five players stand on them at once. The stock script threw a GraalJS arity error on
 * killAllMonsters(true), then a ReferenceError on an eim it never had, and fired only on the touch after the fifth
 * (C0 check 4). Run here as the reactor script manager runs it.
 */
class CwkpqStirgePlateScriptTest {
    private final Map<String, Integer> props = new HashMap<>();
    private final EventInstanceManager eim = mock(EventInstanceManager.class);
    private final MapleMap map = mock(MapleMap.class);
    private Invocable script;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @BeforeEach
    void plates() {
        ScriptEngine engine = new Loader().load("reactor/6109013.js");
        assertNotNull(engine, "the script evaluates");
        ReactorActionManager rm = mock(ReactorActionManager.class);
        when(rm.getEventInstance()).thenReturn(eim);
        when(rm.getMap()).thenReturn(map);
        props.put("glpq_s", 0);
        when(eim.getIntProperty(anyString())).thenAnswer(call -> props.get(call.<String>getArgument(0)));
        doAnswer(call -> props.put(call.getArgument(0), call.getArgument(1))).when(eim)
                .setIntProperty(anyString(), anyInt());
        engine.put("rm", rm);
        script = (Invocable) engine;
    }

    @Test
    void theFifthPlayerOnThePlatesClearsTheStirgesOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            script.invokeFunction("touch");
        }
        verify(map, never()).killAllMonsters();
        assertEquals(4, props.get("glpq_s"));

        script.invokeFunction("touch");
        verify(map).killAllMonsters();
        assertEquals(777, props.get("glpq_s"));

        script.invokeFunction("touch");
        script.invokeFunction("untouch");
        verify(map, times(1)).killAllMonsters();
        assertEquals(777, props.get("glpq_s"), "cleared for good");
    }

    @Test
    void steppingOffTakesOneOffTheCountAndNeverBelowNone() throws Exception {
        script.invokeFunction("touch");
        script.invokeFunction("touch");
        script.invokeFunction("untouch");
        assertEquals(1, props.get("glpq_s"));
        script.invokeFunction("untouch");
        script.invokeFunction("untouch");
        assertEquals(0, props.get("glpq_s"));
        verify(map, never()).killAllMonsters();
    }
}
