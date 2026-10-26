package scripting.npc;

import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.mods.RepackMaps;
import server.mods.RepackMaps.Answer;
import server.mods.ServerMods;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The travel NPCs of the Community Repack maps (docs/REPACK_IMPORT_PLAN.md), run in Graal as NPCScriptManager runs
 * them, with the real gate over an in-memory answer store: they ask an unknown account which game files it has,
 * refuse a Cosmic one, and take a Repack one in; inside they take it home.
 */
class RepackTravelScriptTest {
    private static final int ACCOUNT = 77;

    private final ServerMods mods = new ServerMods(ServerMods.ALL, Map.of());
    private final Map<Integer, Answer> rows = new HashMap<>();
    private final List<String> windows = new ArrayList<>();
    private RepackMaps maps;
    private MockedStatic<RepackMaps> gate;
    private NPCConversationManager cm;
    private Client client;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @BeforeEach
    void setUp() {
        maps = new RepackMaps(mods, new RepackMaps.Store() {
            @Override
            public Optional<Answer> load(int accountId) {
                return Optional.ofNullable(rows.get(accountId));
            }

            @Override
            public void save(int accountId, Answer answer) {
                rows.put(accountId, answer);
            }

            @Override
            public void forget(int accountId) {
                rows.remove(accountId);
            }
        }, c -> false);
        gate = mockStatic(RepackMaps.class, Mockito.CALLS_REAL_METHODS);
        gate.when(RepackMaps::get).thenReturn(maps);
        client = mock(Client.class);
        when(client.getAccID()).thenReturn(ACCOUNT);
    }

    @AfterEach
    void tearDown() {
        gate.close();
    }

    @Test
    void cokebearAsksAnUnknownAccountAndTakesARepackOneToCokeTown() throws Exception {
        talk("9001000", 9001000, 100000000, 0, 1);
        assertTrue(windows.get(0).startsWith("simple How about going to Coke Town to play!? Coke Town is only in the full"
                + " Community Repack game files"), windows.toString());
        assertTrue(windows.get(0).contains("#L1#The smaller Cosmic set#l"), windows.toString());
        assertEquals(RepackMaps.Files.REPACK, rows.get(ACCOUNT).files());
        verify(cm).warp(219000000, 0);
    }

    @Test
    void aCosmicAnswerIsRefusedAndRememberedWithNoWarp() throws Exception {
        talk("9001000", 9001000, 100000000, 1);
        assertEquals(RepackMaps.Files.COSMIC, rows.get(ACCOUNT).files());
        assertTrue(windows.get(1).startsWith("ok Coke Town is only in the full Community Repack game files, and your game"
                + " is on the Cosmic set"), windows.toString());
        verify(cm, never()).warp(anyInt(), anyInt());

        windows.clear();
        talk("9001000", 9001000, 100000000);
        assertTrue(windows.get(0).startsWith("ok Coke Town is only"), "asked once: " + windows);
    }

    @Test
    void notSureExplainsHowToCheck() throws Exception {
        talk("9001000", 9001000, 100000000, 2);
        assertTrue(windows.get(1).contains("base=cosmic"), windows.toString());
        assertEquals(null, rows.get(ACCOUNT));
    }

    @Test
    void insideCokeTownTheOperatorTakesYouBackAndElsewhereHeOnlyCheers() throws Exception {
        talk("9001000", 9001000, 219000000, 1);
        verify(cm).warp(100000000, 0);
        windows.clear();
        talk("9001000", 9001000, 109010100);
        assertEquals(List.of("ok Always Coca-Cola!", "dispose"), windows);
    }

    @Test
    void theSwitchOffClosesTheWay() throws Exception {
        rows.put(ACCOUNT, new Answer(RepackMaps.Files.REPACK, RepackMaps.Source.PLAYER));
        mods.set(ServerMods.REPACK_MAPS, false);
        talk("2140000", 2140000, 270000000);
        assertTrue(windows.get(0).startsWith("ok The doors are sealed"), windows.toString());
    }

    @Test
    void theTempleKeeperOpensTheFutureAndArcaneRiver() throws Exception {
        rows.put(ACCOUNT, new Answer(RepackMaps.Files.REPACK, RepackMaps.Source.PLAYER));
        talk("2140000", 2140000, 270000000, 3);
        assertTrue(windows.get(0).contains("#L4##bArcane River#k"), windows.toString());
        verify(cm).warp(273000000, 0);

        windows.clear();
        talk("2140000", 2140000, 270000000, 4, 2);
        assertTrue(windows.get(1).contains("#L2#Lachelein#l"), windows.toString());
        verify(cm).warp(450003000, 0);
    }

    @Test
    void ralphPerryAndTheStatueLeadIntoTheirAreas() throws Exception {
        rows.put(ACCOUNT, new Answer(RepackMaps.Files.REPACK, RepackMaps.Source.PLAYER));
        talk("9270030", 9270030, 540020100, 1);
        verify(cm).warp(541020000, "west00");
        talk("2020012", 2020012, 211040600, 1);
        verify(cm).warp(211060000, "out00");
        talk("9110000", 9110000, 800000000, 1);
        verify(cm).warp(800040000, 0);
        talk("9110000", 9110000, 800040000, 1);
        verify(cm).warp(800000000, 0);
    }

    @Test
    void ralphAwayFromTheRidgeOnlyTalks() throws Exception {
        talk("9270030", 9270030, 541000000);
        assertEquals(2, windows.size(), windows.toString());
        assertTrue(windows.get(0).startsWith("ok "), windows.toString());
    }

    @Test
    void anImportedNpcSaysItsOwnLineAndATownGuideOffersTheWayBack() throws Exception {
        talk("repack_talk", 2161003, 211060100);
        assertTrue(windows.get(0).startsWith("ok "), windows.toString());
        assertTrue(windows.get(0).length() > 10, "Jenn's own String.wz line: " + windows);

        windows.clear();
        talk("repack_talk", 3003570, 450009050, 1);
        assertTrue(windows.get(0).startsWith("yesno ") && windows.get(0).contains("#m270000000#"), windows.toString());
        verify(cm).warp(270000000, 0);
    }

    /** start, then action(1, 0, selection) per window until the talk ends. */
    private void talk(String script, int npc, int mapId, int... selections) throws Exception {
        cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getClient()).thenReturn(client);
        when(cm.getMapId()).thenReturn(mapId);
        when(cm.getNpc()).thenReturn(npc);
        doAnswer(inv -> windows.add("simple " + inv.getArgument(0))).when(cm).sendSimple(anyString());
        doAnswer(inv -> windows.add("ok " + inv.getArgument(0))).when(cm).sendOk(anyString());
        doAnswer(inv -> windows.add("yesno " + inv.getArgument(0))).when(cm).sendYesNo(anyString());
        doAnswer(inv -> {
            disposed.set(true);
            return windows.add("dispose");
        }).when(cm).dispose();

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(Path.of("scripts/npc/" + script + ".js"), StandardCharsets.UTF_8));
        engine.put("cm", cm);
        Invocable invocable = (Invocable) engine;
        invocable.invokeFunction("start");
        for (int selection : selections) {
            if (disposed.get()) {
                break;
            }
            invocable.invokeFunction("action", 1, 0, selection);
        }
    }
}
