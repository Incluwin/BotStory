package scripting.reactor;

import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The HPQ full-moon reactor (9101000.js). The 2026-11-09 audit removed its call to the missing
 * {@code PacketCreator.showHPQMoon()}, but the two remaining lines reached the map through
 * {@code rm.getClient().getMap()}, and {@code client.Client} has no {@code getMap()}: act() stopped at its first line
 * (no map effect, no "Protect the Moon Bunny" notice). The reactor manager's own {@code getMap()} is the player's map.
 */
class HpqMoonReactorScriptTest {

    /** Loads a script the way the reactor script manager does (Graal.js with host access). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @Test
    void actShowsTheMoonBunnyEffectAndNoticeOnTheMap() throws Exception {
        ScriptEngine engine = new Loader().load("reactor/9101000.js");
        assertNotNull(engine, "the script evaluates");
        ReactorActionManager rm = mock(ReactorActionManager.class);
        MapleMap map = mock(MapleMap.class);
        when(rm.getMap()).thenReturn(map);
        when(rm.getClient()).thenReturn(mock(Client.class)); // as live: a client, which has no map of its own
        engine.put("rm", rm);

        ((Invocable) engine).invokeFunction("act");

        verify(map).startMapEffect(anyString(), eq(5120016), anyLong());
        verify(map).broadcastMessage(any(Packet.class));
    }
}
