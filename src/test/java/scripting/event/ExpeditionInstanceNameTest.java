package scripting.event;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.Monster;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Each expedition's recruiter lets a member back into the running fight: it looks the instance up as
 * {@code em.getInstance(expedName + player.getClient().getChannel())} and lets them in while canJoin is 1. The event
 * names that instance in setup, which EventManager.startInstance(Expedition) calls with the leader's channel alone.
 * Both Balrog scripts took (level, lobbyid) and named theirs "Balrogundefined", and Pink Bean and Showa named theirs
 * "PinkBean"/"Showa" while their recruiters ask for "Twilight of the Gods"/"Showa Gang", so nobody re-entered those.
 */
class ExpeditionInstanceNameTest {
    private static final int CHANNEL = 3;
    private static final String LOOKUP = "em.getInstance(expedName + player.getClient().getChannel())";

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @ParameterizedTest
    @CsvSource({
            "1061014, BalrogBattle_Easy",
            "1061014, BalrogBattle",
            "2030013, ZakumBattle",
            "2083004, HorntailBattle",
            "2141001, PinkBeanBattle",
            "9120201, ShowaBattle",
            "9270047, ScargaBattle"})
    void theEventNamesItsInstanceTheWayItsRecruiterLooksItUpAndLetsMembersBackIn(String npc, String event)
            throws Exception {
        String npcSource = read("scripts/npc/" + npc + ".js");
        assertTrue(npcSource.contains(LOOKUP), npc + " looks the run up by expedName and channel");
        String expedName = (String) engine(npcSource).get("expedName");

        Map<String, String> properties = new HashMap<>();
        String[] named = new String[1];
        EventManager em = mock(EventManager.class);
        EventInstanceManager eim = mock(EventInstanceManager.class, RETURNS_DEEP_STUBS);
        when(em.newInstance(anyString())).thenAnswer(inv -> {
            named[0] = inv.getArgument(0);
            return eim;
        });
        doAnswer(inv -> properties.put(inv.getArgument(0), String.valueOf((Object) inv.getArgument(1))))
                .when(eim).setProperty(anyString(), anyString());
        doAnswer(inv -> properties.put(inv.getArgument(0), String.valueOf((Object) inv.getArgument(1))))
                .when(eim).setProperty(anyString(), org.mockito.ArgumentMatchers.<Integer>any());

        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(inv -> mock(Monster.class));
            ScriptEngine engine = engine(read("scripts/event/" + event + ".js"), em);
            ((Invocable) engine).invokeFunction("setup", CHANNEL);
        }

        assertEquals(expedName + CHANNEL, named[0], event + "'s instance name");
        assertEquals("1", properties.get("canJoin"), event + " lets members back in");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.US_ASCII);
    }

    private static ScriptEngine engine(String source) throws Exception {
        return engine(source, null);
    }

    private static ScriptEngine engine(String source, EventManager em) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        if (em != null) {
            bindings.put("em", em);
        }
        engine.eval(source);
        return engine;
    }
}
