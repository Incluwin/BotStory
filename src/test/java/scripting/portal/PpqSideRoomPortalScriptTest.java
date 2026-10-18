package scripting.portal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scripting.AbstractScriptManager;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PPQ's hidden side rooms (925100202 off deck one, 925100302 off deck two) are entered by the invisible in01 portal,
 * whose scripts davy2_hd1/davy3_hd1 spawn the room's Kru and Captains once at the event level and warp the player in.
 * PiratePQ.js stores "level" with setProperty, so getProperty("level") is a string, and Graal.js finds no
 * spawnAllMonstersFromMapSpawnList(int, boolean) for it: live on 2026-10-18 both portals threw that TypeError and
 * nobody could enter either room. The scripts now read it with getIntProperty, as PiratePQ.js's own respawn does.
 */
class PpqSideRoomPortalScriptTest {
    /** Loads a script the way the portal script manager does (Graal.js with host access). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    private static EventInstanceManager event(String flag, String flagValue) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        // As PiratePQ.js leaves it: the level set as a string, read back as either.
        when(eim.getProperty("level")).thenReturn("3");
        when(eim.getIntProperty("level")).thenReturn(3);
        when(eim.getProperty(flag)).thenReturn(flagValue);
        return eim;
    }

    @ParameterizedTest
    @CsvSource({"davy2_hd1, stage2b, 925100202", "davy3_hd1, stage3b, 925100302"})
    void firstEntrySpawnsTheRoomAtTheEventLevelOnceAndWarpsIn(String script, String flag, int room) throws Exception {
        ScriptEngine engine = new Loader().load("portal/" + script + ".js");
        assertNotNull(engine, "the script evaluates");
        PortalPlayerInteraction pi = mock(PortalPlayerInteraction.class);
        EventInstanceManager eim = event(flag, "0");
        MapleMap map = mock(MapleMap.class);
        when(pi.getEventInstance()).thenReturn(eim);
        when(pi.getMap(room)).thenReturn(map);

        Object entered = ((Invocable) engine).invokeFunction("enter", pi);

        assertEquals(true, entered);
        var order = inOrder(map, eim, pi);
        order.verify(map).spawnAllMonstersFromMapSpawnList(3, true);
        order.verify(eim).setProperty(flag, "1");
        order.verify(pi).warp(room, 0);
    }

    @ParameterizedTest
    @CsvSource({"davy2_hd1, stage2b, 925100202", "davy3_hd1, stage3b, 925100302"})
    void laterEntriesOnlyWarpIn(String script, String flag, int room) throws Exception {
        ScriptEngine engine = new Loader().load("portal/" + script + ".js");
        PortalPlayerInteraction pi = mock(PortalPlayerInteraction.class);
        EventInstanceManager eim = event(flag, "1");
        MapleMap map = mock(MapleMap.class);
        when(pi.getEventInstance()).thenReturn(eim);
        when(pi.getMap(room)).thenReturn(map);

        Object entered = ((Invocable) engine).invokeFunction("enter", pi);

        assertEquals(true, entered);
        verify(map, never()).spawnAllMonstersFromMapSpawnList(anyInt(), anyBoolean());
        verify(pi).warp(room, 0);
    }
}
