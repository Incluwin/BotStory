package server.living.townlife;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLifeGeneratedProfilesTest {
    private static final int GRAPH_BUILDERS = 4;

    /**
     * Every profile in the index, the seven hand-authored Victoria ones too (their floating and street spots were
     * grounded and Henesys's east benches put on the seats their ids name, 2026-10-29), stands on its map.
     */
    @Test
    void everyProfileInTheIndexStandsOnItsMap() throws Exception {
        // defaultRepository() already rejects invalid profiles, and the geometry check repeats the validator's errors.
        List<AgentTownLifeProfile> profiles = AgentTownLifeProfileRepository.defaultRepository().profiles().stream()
                .sorted(Comparator.comparing(AgentTownLifeProfile::profileId))
                .toList();
        assertTrue(profiles.stream().map(AgentTownLifeProfile::profileId).toList()
                .containsAll(TownLifeProfileMain.HAND_AUTHORED));
        buildNavigationGraphsInParallel(profiles);

        List<String> failures = new ArrayList<>();
        for (AgentTownLifeProfile profile : profiles) {
            TownLifeGeometryCheck.check(profile, TownMapData.load(profile.mapId())).errors()
                    .forEach(error -> failures.add(profile.profileId() + ": " + error));
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * Building the town navigation graphs is nearly all of this test's time on a fresh worktree, and the graphs are
     * independent, so build them on a few threads first; TownMapData.load then takes them from the graph cache.
     * Only the graph build runs in parallel: the rest of TownMapData.load reads shared WZ data trees.
     */
    private static void buildNavigationGraphsInParallel(List<AgentTownLifeProfile> profiles) throws Exception {
        List<Callable<AgentNavigationGraph>> builds = profiles.stream()
                .map(AgentTownLifeProfile::mapId)
                .distinct()
                .<Callable<AgentNavigationGraph>>map(mapId -> () -> AgentNavigationGraphService.getGraph(
                        AgentNavigationMapLoader.loadMapGeometry(mapId)))
                .toList();
        ExecutorService builders = Executors.newFixedThreadPool(GRAPH_BUILDERS);
        try {
            for (Future<AgentNavigationGraph> build : builders.invokeAll(builds)) {
                build.get();
            }
        } finally {
            builders.shutdownNow();
        }
    }
}
