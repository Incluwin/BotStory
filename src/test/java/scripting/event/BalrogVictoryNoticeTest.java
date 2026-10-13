package scripting.event;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.life.Monster;
import server.maps.MapleMap;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Balrog victory notice in both Balrog event scripts. It names the expedition leader. A leader who logged out
 * before the clear (a second owner's party fought on, run 6 of P7 Part B) left getLeader() null, and the stock
 * {@code eim.getLeader().getName()} threw at the clear. The notice now names the first human still inside, skips
 * companions, and is not sent when no human is left.
 */
class BalrogVictoryNoticeTest {
    private final EventInstanceManager eim = mock(EventInstanceManager.class);
    private final MapleMap battle = mock(MapleMap.class);
    private final Monster body = mock(Monster.class);

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scripts/event/BalrogBattle_Easy.js", "scripts/event/BalrogBattle.js"})
    void theLeaderIsNamedWhileStillInside(String script) throws Exception {
        Character leader = player("Bossfriend", mock(Client.class));
        when(eim.getLeader()).thenReturn(leader);
        when(body.getMap()).thenReturn(battle);

        engine(script).invokeFunction("broadcastVictory", body, eim);

        verify(battle).broadcastBalrogVictory("Bossfriend");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scripts/event/BalrogBattle_Easy.js", "scripts/event/BalrogBattle.js"})
    void aLeaderWhoLoggedOutGivesTheNoticeToTheFirstHumanInside(String script) throws Exception {
        Character orrin = player("Orrin", mock(BotClient.class));
        Character ohboy = player("ohboy", mock(Client.class));
        when(eim.getLeader()).thenReturn(null);
        when(eim.getPlayers()).thenReturn(List.of(orrin, ohboy));
        when(body.getMap()).thenReturn(battle);

        engine(script).invokeFunction("broadcastVictory", body, eim);

        verify(battle).broadcastBalrogVictory("ohboy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scripts/event/BalrogBattle_Easy.js", "scripts/event/BalrogBattle.js"})
    void withNoHumanInsideNoNoticeIsSent(String script) throws Exception {
        Character orrin = player("Orrin", mock(BotClient.class));
        when(eim.getLeader()).thenReturn(null);
        when(eim.getPlayers()).thenReturn(List.of(orrin));
        when(body.getMap()).thenReturn(battle);

        engine(script).invokeFunction("broadcastVictory", body, eim);

        verify(battle, never()).broadcastBalrogVictory(anyString());
    }

    private static Character player(String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    private static Invocable engine(String script) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(Path.of(script), StandardCharsets.US_ASCII));
        return (Invocable) engine;
    }
}
