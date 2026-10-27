package server.agents.progression;

import java.util.List;

/**
 * Versioned standard-portal graph for ordinary Victoria Island travel, and for the continents outside it, with the
 * scripted portals the world exporter authors (Export-WorldPortalRouteGraph.ps1; none in the Victoria catalog).
 */
public record AgentVictoriaPortalRouteGraph(
        int schemaVersion,
        String catalogId,
        String sourcePath,
        String sourceSha256,
        List<Edge> edges,
        List<ScriptedPortal> scriptedPortals) {

    public AgentVictoriaPortalRouteGraph {
        if (schemaVersion <= 0 || blank(catalogId) || blank(sourcePath) || blank(sourceSha256)
                || edges == null || edges.isEmpty()) {
            throw new IllegalArgumentException("a complete Victoria portal route graph is required");
        }
        edges = List.copyOf(edges);
        scriptedPortals = scriptedPortals == null ? List.of() : List.copyOf(scriptedPortals);
    }

    public record Edge(int fromMapId, int toMapId) {
        public Edge {
            if (fromMapId <= 0 || toMapId <= 0 || fromMapId == toMapId) {
                throw new IllegalArgumentException("a portal edge requires two distinct maps");
            }
        }
    }

    /**
     * A portal whose script ({@code script}, scripts/portal) sends whoever walks in to {@code destinationMapId}: the
     * Time Gate to Ellin Forest, An Empty House's door to the Mushroom Castle, the dragon flights' ways into and out
     * of the Temple of Time. {@code gate}, when there is one, is the script's own condition.
     */
    public record ScriptedPortal(int sourceMapId, int destinationMapId, String portalName, int portalId, String script,
                                 QuestGate gate) {
        public ScriptedPortal {
            if (sourceMapId <= 0 || destinationMapId <= 0 || sourceMapId == destinationMapId || portalId < 0
                    || blank(portalName) || blank(script)) {
                throw new IllegalArgumentException("a scripted portal needs two maps, a portal and its script");
            }
        }
    }

    /**
     * Lets a traveller through when one of {@code completedAny} is completed, or one of {@code activeAny} is started
     * or completed (TD_MC_first: quest 2260 done, or any of 2300-2310 taken).
     */
    public record QuestGate(List<Integer> completedAny, List<Integer> activeAny) {
        public QuestGate {
            completedAny = completedAny == null ? List.of() : List.copyOf(completedAny);
            activeAny = activeAny == null ? List.of() : List.copyOf(activeAny);
            if (completedAny.isEmpty() && activeAny.isEmpty()) {
                throw new IllegalArgumentException("a quest gate names at least one quest");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
