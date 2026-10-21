package scripting.portal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import scripting.event.EventInstanceManager;
import server.maps.Portal;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPQ's way up (920010700): sixteen rows of four doors, party3_r6pt.js. The right door climbs a row (or, at a
 * section's last row, warps to that section's top); a wrong one drops the player to the start of the section, or to
 * r6fail (portal 5) in the first two. 2026-10-20 live: the wrong-door warp passed pRow / 4, fractional on most rows,
 * and warp(int, double) has no overload, so those doors threw (556 times in one run) instead of dropping anyone.
 */
class OpqWayUpPortalScriptTest {
    private static final int WAY_UP = 920_010_700;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    /** Enters door {@code column} of {@code row} (both 1-based) when door 1 is right on every row. */
    private static PortalPlayerInteraction enter(int row, int column) throws Exception {
        ScriptEngine engine = new Loader().load("portal/party3_r6pt.js");
        PortalPlayerInteraction pi = mock(PortalPlayerInteraction.class);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getProperty("stage6_comb")).thenReturn("0" + "1".repeat(16));
        Portal portal = mock(Portal.class);
        when(portal.getName()).thenReturn(String.format("rp%02d%d", row, column));
        when(portal.getId()).thenReturn(20 + row * 4 + column - 1);
        when(pi.getEventInstance()).thenReturn(eim);
        when(pi.getPortal()).thenReturn(portal);
        when(pi.getMapId()).thenReturn(WAY_UP);

        assertEquals(true, ((Invocable) engine).invokeFunction("enter", pi), "row " + row + " door " + column);
        return pi;
    }

    @Test
    void everyWrongDoorDropsThePlayerToItsSectionStart() throws Exception {
        for (int row = 1; row <= 16; row++) {
            int section = (row - 1) / 4;
            verify(enter(row, 2)).warp(WAY_UP, section > 1 ? section : 5);
        }
    }

    @Test
    void theRightDoorClimbsARowOrToTheSectionTop() throws Exception {
        for (int row = 1; row <= 16; row++) {
            int door = 20 + row * 4;
            verify(enter(row, 1)).warp(WAY_UP, row % 4 != 0 ? door + 4 : row / 4);
        }
    }
}
