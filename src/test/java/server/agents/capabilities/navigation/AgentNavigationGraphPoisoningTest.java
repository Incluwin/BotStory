package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.MapleMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ C9: a graph build queued against a Test of Agility instance ran after the event disposed it
 * (MapleMap.dispose drops the footholds). The empty graph was cached and saved, so every later run's
 * companions on that map had no region under them and stood still. A disposed map is refused, and an
 * empty graph on disk is built again.
 */
class AgentNavigationGraphPoisoningTest {
    private static final int MAP_ID = 10000;

    @Test
    void disposedMapIsRefusedAndNothingIsCachedOrSaved() throws Exception {
        AgentMovementProfile profile = new AgentMovementProfile(137, 113);
        Path file = repository().file(MAP_ID, profile);
        Files.deleteIfExists(file);
        MapleMap disposed = mock(MapleMap.class);
        when(disposed.getId()).thenReturn(MAP_ID);

        assertThrows(IllegalStateException.class, () -> AgentNavigationGraphService.getGraph(disposed, profile));

        assertNull(AgentNavigationGraphService.peekGraph(disposed, profile));
        assertFalse(Files.exists(file));
    }

    @Test
    void emptyGraphOnDiskIsBuiltAgain() throws Exception {
        AgentMovementProfile profile = new AgentMovementProfile(139, 117);
        FileAgentNavigationGraphRepository repository = repository();
        repository.save(new AgentNavigationGraph(MAP_ID, graphVersion(), profile,
                List.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(
                AgentNavigationMapLoader.loadMapGeometry(MAP_ID), profile);

        assertFalse(graph.regions.isEmpty());
        AgentNavigationGraph saved = repository.load(MAP_ID, profile);
        assertNotNull(saved);
        assertFalse(saved.regions.isEmpty(), "the rebuilt graph replaces the empty file");
    }

    private static FileAgentNavigationGraphRepository repository() {
        return new FileAgentNavigationGraphRepository(AgentNavigationGraphService.cacheDirectory(), graphVersion());
    }

    /** The service keeps its cache under "v<graph version>". */
    private static int graphVersion() {
        return Integer.parseInt(AgentNavigationGraphService.cacheDirectory().getFileName().toString().substring(1));
    }
}
