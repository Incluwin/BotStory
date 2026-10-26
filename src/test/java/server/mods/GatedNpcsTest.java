package server.mods;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cryssea reaches a client only while server mod cryssea is on, and only in New Leaf City. */
class GatedNpcsTest {
    @Test
    void crysseaStandsInTheTownCenterOnlyWhileHerModIsOn() {
        assertTrue(GatedNpcs.on(600_000_000, mod -> false).isEmpty());
        List<GatedNpcs.Placement> on = GatedNpcs.on(600_000_000, mod -> true);
        assertEquals(1, on.size());
        assertEquals(9_010_018, on.get(0).npcId());
        assertEquals(ServerMods.CRYSSEA, on.get(0).mod());
        assertTrue(GatedNpcs.on(600_000_001, mod -> true).isEmpty(), "nowhere else");
    }

    @Test
    void theShippedConfigPlacesNobody() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(600_000_000);

        GatedNpcs.place(map);

        verify(map, never()).addMapObject(any());
    }
}
