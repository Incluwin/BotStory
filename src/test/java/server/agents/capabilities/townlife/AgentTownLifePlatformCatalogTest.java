package server.agents.capabilities.townlife;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTownLifePlatformCatalogTest {
    private static final int KERNING_CITY = 103000000;

    @Test
    void catalogsUpperLowerAndMiniPlatformsAsIndependentReservationSlots() {
        AgentNavigationGraph.Region upper = region(1, 1, 0, 100, 640, 100);
        AgentNavigationGraph.Region lower = region(2, 2, 0, 650, 640, 650);
        AgentNavigationGraph.Region mini = region(3, 3, 800, 380, 960, 380);
        List<AgentNavigationGraph.Region> regions = List.of(upper, lower, mini);
        AgentNavigationGraph graph = new AgentNavigationGraph(
                LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID, 1, regions,
                Map.of(1, upper, 2, lower, 3, mini), Map.of(), Map.of(), Set.of());
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository()
                .require(LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID);

        List<AgentTownLifePlatformCatalog.PlatformSpot> spots =
                AgentTownLifePlatformCatalog.reachable(graph, profile, -1);

        assertTrue(spots.stream().anyMatch(spot ->
                spot.district() == AgentTownLifeState.District.UPPER));
        assertTrue(spots.stream().anyMatch(spot ->
                spot.district() == AgentTownLifeState.District.LOWER));
        assertTrue(spots.stream().anyMatch(spot ->
                spot.platformKind() == AgentTownLifeState.PlatformKind.MINI));
        assertEquals(8, spots.stream().filter(spot -> spot.space().rowId() == 1).count());
    }

    @Test
    void boundedPlatformPolicyControlsDynamicCapacity() {
        AgentNavigationGraph.Region truck = region(10, 154, 3798, 469, 3971, 417);
        AgentNavigationGraph graph = new AgentNavigationGraph(
                LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID, 1, List.of(truck),
                Map.of(10, truck), Map.of(), Map.of(), Set.of());
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository()
                .require(LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID);

        List<AgentTownLifePlatformCatalog.PlatformSpot> spots =
                AgentTownLifePlatformCatalog.reachable(graph, profile, -1);

        assertEquals(6, spots.size());
        assertTrue(spots.stream().allMatch(spot ->
                spot.space().catalogId().contains("duey-truck-roof")));
    }

    @Test
    void kerningDropOnlyRoofIsOfferedOnlyToAgentsWhoCanWalkThere() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(KERNING_CITY);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        AgentTownLifeProfile profile = AgentTownLifeProfileRepository.defaultRepository()
                .require(KERNING_CITY);
        int street = graph.findRegionId(map, new Point(-1826, -233));
        int roof = graph.findRegionId(map, new Point(-2127, -338));
        assertEquals(graph.connectedComponentId(street), graph.connectedComponentId(roof));

        List<AgentTownLifePlatformCatalog.PlatformSpot> fromStreet =
                AgentTownLifePlatformCatalog.reachable(graph, profile, street);
        List<AgentTownLifePlatformCatalog.PlatformSpot> fromRoof =
                AgentTownLifePlatformCatalog.reachable(graph, profile, roof);

        assertTrue(fromRoof.stream().anyMatch(spot -> spot.space().rowId() == roof),
                "the roof is a catalogued platform");
        assertTrue(fromStreet.stream().noneMatch(spot -> spot.space().rowId() == roof),
                "nothing on the street can climb onto the roof");
        assertTrue(fromStreet.stream().allMatch(
                        spot -> graph.canReach(street, spot.space().rowId())),
                "every offered slot is walkable from the street");
        assertTrue(fromRoof.containsAll(fromStreet),
                "skipping the roof does not renumber the slots both Agents can reserve");
        assertTrue(fromStreet.size() > fromRoof.size() / 2);
    }

    private static AgentNavigationGraph.Region region(int regionId,
                                                       int footholdId,
                                                       int x1,
                                                       int y1,
                                                       int x2,
                                                       int y2) {
        Foothold foothold = new Foothold(new Point(x1, y1), new Point(x2, y2), footholdId);
        return new AgentNavigationGraph.Region(
                regionId, List.of(new AgentNavigationGraph.Segment(foothold)));
    }
}
