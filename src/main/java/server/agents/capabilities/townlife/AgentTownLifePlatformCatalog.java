package server.agents.capabilities.townlife;

import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.maps.reservation.CharacterSpace;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts navigation regions into categorized, capacity-bearing TownLife reservation slots. */
public final class AgentTownLifePlatformCatalog {
    public record PlatformSpot(CharacterSpace space,
                               AgentTownLifeState.District district,
                               AgentTownLifeState.PlatformKind platformKind) {
    }

    private AgentTownLifePlatformCatalog() {
    }

    /**
     * Returns the platform slots an Agent standing on {@code originRegionId} can walk to, or
     * every slot when the origin is unknown ({@code originRegionId < 0}).
     */
    public static List<PlatformSpot> reachable(AgentNavigationGraph graph,
                                               AgentTownLifeProfile profile,
                                               int originRegionId) {
        if (graph == null || profile == null) {
            return List.of();
        }
        int originComponent = originRegionId < 0 ? -1 : graph.connectedComponentId(originRegionId);
        AgentTownLifeProfile.Geometry geometry = profile.geometry();
        List<PlatformSpot> result = new ArrayList<>();
        Map<String, Integer> nextSpotNumber = new HashMap<>();
        for (AgentNavigationGraph.Region region : graph.regions.stream()
                .sorted(Comparator.comparingInt(candidate -> candidate.id)).toList()) {
            if (region.isRopeRegion || region.width() < geometry.minimumPlatformWidth()
                    || (originComponent >= 0
                    && graph.connectedComponentId(region.id) != originComponent)) {
                continue;
            }
            // The component ignores edge direction, so drop-only roofs (Kerning City's region 80)
            // pass it although nothing below can climb back up. Choosing one made the
            // mover re-run an exhaustive unreachable search every tick until the stall watchdog
            // gave up. Skipped slots still take their number, so an Agent on the roof and one on
            // the ground agree on the reservation identity of every shared slot.
            boolean walkable = originRegionId < 0 || graph.canReach(originRegionId, region.id);
            Point midpoint = region.pointAt(region.minX + region.width() / 2);
            AgentTownLifeProfile.PlatformPolicy policy =
                    profile.platformPolicy(midpoint).orElse(null);
            int count = policy == null
                    ? Math.min(geometry.maxSlotsPerPlatform(),
                    Math.max(1, region.width() / geometry.slotSpacingPx()))
                    : policy.capacity();
            int inset = Math.min(geometry.edgeInsetPx(), Math.max(8, region.width() / 8));
            for (int slot = 0; slot < count; slot++) {
                int usableWidth = Math.max(0, region.width() - inset * 2);
                int x = region.minX + inset + (slot + 1) * usableWidth / (count + 1);
                Point point = region.pointAt(x);
                AgentTownLifeState.District district = profile.classify(point);
                AgentTownLifeState.PlatformKind platformKind =
                        profile.classifyPlatform(region.width());
                String catalogId = policy == null
                        ? "town-nav-" + profile.mapId() + '-'
                        + district.name().toLowerCase() + '-'
                        + platformKind.name().toLowerCase()
                        : "town-policy-" + profile.mapId() + '-' + policy.id();
                int spotNumber = nextSpotNumber.merge(catalogId, 1, Integer::sum);
                if (!walkable) {
                    continue;
                }
                CharacterSpace space = new CharacterSpace(
                        catalogId, spotNumber, profile.mapId(),
                        Math.max(0, region.id), slot, point.x, point.y);
                result.add(new PlatformSpot(space, district, platformKind));
            }
        }
        return List.copyOf(result);
    }
}
