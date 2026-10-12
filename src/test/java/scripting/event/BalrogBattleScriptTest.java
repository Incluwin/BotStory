package scripting.event;

import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.autonomy.balrog.EasyBalrogEncounterService;
import server.maps.MapleMap;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Normal Balrog's event script (BalrogBattle.js) with P11's companion branch, run in Graal as the event manager runs
 * it. With no companion signed up (EasyBalrogEncounterService.startNormal answers false) every step is the stock one:
 * the stock spawn, the scripted claw release at one minute, the kill count. With one, the server's encounter runs the
 * fight: no stock spawn or release, claw kills count nothing, the real body's death is the clear (clear effect, the
 * two quest counts and the victory notice) with no sealed form after it, and the event's end and dispose stop the
 * encounter.
 */
class BalrogBattleScriptTest {
    private static final Path SCRIPT = Path.of("scripts/event/BalrogBattle.js");

    private final EventInstanceManager eim = mock(EventInstanceManager.class);
    private final MapleMap battle = mock(MapleMap.class);
    private final Map<String, String> properties = new HashMap<>();

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @Test
    void withNoCompanionSignedUpEveryStepIsTheStockOne() throws Exception {
        try (MockedStatic<EasyBalrogEncounterService> encounter = mockStatic(false);
             MockedStatic<LifeFactory> life = org.mockito.Mockito.mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(inv -> mob(inv.getArgument(0)));
            Invocable script = script();

            script.invokeFunction("afterSetup", eim);
            verify(battle).spawnFakeMonsterOnGroundBelow(any(Monster.class), eq(new Point(412, 258)));
            assertNull(properties.get("serverEncounter"));

            script.invokeFunction("releaseLeftClaw", eim);
            verify(battle).killMonster(8830006);

            when(eim.getIntProperty("boss")).thenReturn(0);
            script.invokeFunction("monsterKilled", mob(8830002), eim);
            verify(eim).setIntProperty("boss", 1);

            script.invokeFunction("dispose", eim);
            encounter.verify(() -> EasyBalrogEncounterService.stop(any(MapleMap.class), anyString()), never());
        }
    }

    @Test
    void withACompanionSignedUpTheServersEncounterRunsTheFight() throws Exception {
        Character leader = mock(Character.class);
        when(leader.getName()).thenReturn("ohboy");
        when(eim.getLeader()).thenReturn(leader);
        when(eim.getPlayers()).thenReturn(List.of());
        Monster body = mob(8830000);
        when(body.getMap()).thenReturn(battle);

        try (MockedStatic<EasyBalrogEncounterService> encounter = mockStatic(true)) {
            Invocable script = script();

            script.invokeFunction("afterSetup", eim);
            assertEquals("1", properties.get("serverEncounter"));
            verify(battle, never()).spawnFakeMonsterOnGroundBelow(any(Monster.class), any(Point.class));

            script.invokeFunction("releaseLeftClaw", eim);
            verify(battle, never()).killMonster(anyInt());

            script.invokeFunction("monsterKilled", mob(8830002), eim);
            script.invokeFunction("monsterKilled", mob(8830001), eim);
            script.invokeFunction("monsterKilled", mob(6400005), eim);
            verify(eim, never()).setIntProperty(anyString(), anyInt());
            verify(eim, never()).clearPQ();

            script.invokeFunction("monsterKilled", body, eim);
            verify(eim).showClearEffect();
            verify(eim).clearPQ();
            verify(eim).dispatchRaiseQuestMobCount(8830003, 105100300);
            verify(eim).dispatchRaiseQuestMobCount(9101003, 105100300);
            verify(battle).broadcastBalrogVictory("ohboy");
            verify(eim, never()).schedule(eq("spawnSealedBalrog"), anyLong());

            script.invokeFunction("end", eim);
            encounter.verify(() -> EasyBalrogEncounterService.stop(battle, "event-end"));
            script.invokeFunction("dispose", eim);
            encounter.verify(() -> EasyBalrogEncounterService.stop(battle, "event-dispose"));
        }
    }

    private MockedStatic<EasyBalrogEncounterService> mockStatic(boolean companionFight) {
        MockedStatic<EasyBalrogEncounterService> encounter =
                org.mockito.Mockito.mockStatic(EasyBalrogEncounterService.class);
        encounter.when(() -> EasyBalrogEncounterService.startNormal(eq(battle), eq(new Point(412, 258))))
                .thenReturn(companionFight);
        return encounter;
    }

    private Invocable script() throws Exception {
        when(eim.getInstanceMap(105100300)).thenReturn(battle);
        doAnswer(inv -> properties.put(inv.getArgument(0), inv.getArgument(1)))
                .when(eim).setProperty(anyString(), anyString());
        when(eim.getProperty(anyString())).thenAnswer(inv -> properties.get(inv.<String>getArgument(0)));

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(SCRIPT, StandardCharsets.US_ASCII));
        return (Invocable) engine;
    }

    private static Monster mob(int id) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        return monster;
    }
}
