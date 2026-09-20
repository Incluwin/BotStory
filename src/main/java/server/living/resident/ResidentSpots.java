package server.living.resident;

import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the town profile of a Persona's home and the named spots of its routine:
 * {@code venue:<id>}, {@code hotspot:<id>}, {@code rest:<n>}, {@code npc:<npcId>}, {@code x,y}.
 */
public final class ResidentSpots {
    private static final Map<String, Integer> TOWN_MAPS = Map.of(
            "lith-harbor", 104000000,
            "henesys", 100000000,
            "kerning-city", 103000000,
            "perion", 102000000,
            "ellinia", 101000000,
            "nautilus", 120000000,
            "sleepywood", 105040300);

    private ResidentSpots() {
    }

    public static int townMapId(String town) {
        for (AgentTownLifeProfile profile : AgentTownLifeProfileRepository.defaultRepository().profiles()) {
            if (profile.profileId().equals(town)) {
                return profile.mapId();
            }
        }
        return TOWN_MAPS.getOrDefault(town, 0);
    }

    public static Optional<AgentTownLifeProfile> profile(int mapId) {
        return AgentTownLifeProfileRepository.defaultRepository().find(mapId);
    }

    /** A point for the spot, or null when it cannot be resolved (callers fall back to the arrival portal). */
    public static Point resolve(String spot, MapleMap map, int ordinal) {
        if (spot == null || map == null) {
            return null;
        }
        AgentTownLifeProfile profile = profile(map.getId()).orElse(null);
        String[] parts = spot.split(":", 2);
        if (parts.length == 1) {
            String[] xy = spot.split(",");
            if (xy.length == 2) {
                try {
                    return new Point(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
                } catch (NumberFormatException invalid) {
                    return null;
                }
            }
            return null;
        }
        String kind = parts[0].trim();
        String id = parts[1].trim();
        switch (kind) {
            case "venue" -> {
                if (profile == null) {
                    return null;
                }
                for (AgentTownLifeProfile.Venue venue : profile.venues()) {
                    if (venue.id().equals(id) && !venue.spots().isEmpty()) {
                        return venue.spots().get(Math.floorMod(ordinal, venue.spots().size())).point();
                    }
                }
                return null;
            }
            case "hotspot" -> {
                if (profile == null) {
                    return null;
                }
                for (AgentTownLifeProfile.Hotspot hotspot : profile.hotspots()) {
                    if (hotspot.id().equals(id)) {
                        return resolve("venue:" + hotspot.venueId(), map, ordinal);
                    }
                }
                return null;
            }
            case "rest" -> {
                if (profile == null || profile.restSpots().isEmpty()) {
                    return null;
                }
                try {
                    int index = Integer.parseInt(id);
                    return profile.restSpots().get(Math.floorMod(index, profile.restSpots().size())).point();
                } catch (NumberFormatException invalid) {
                    return null;
                }
            }
            case "npc" -> {
                try {
                    NPC npc = map.getNPCById(Integer.parseInt(id));
                    if (npc == null) {
                        return null;
                    }
                    Point at = npc.getPosition();
                    return new Point(at.x + 40 + 30 * Math.floorMod(ordinal, 3), at.y);
                } catch (NumberFormatException invalid) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
    }

    /** Fallback: the first roam/rest spot of the profile, else the map's first portal. */
    public static Point fallback(MapleMap map, int ordinal) {
        AgentTownLifeProfile profile = profile(map.getId()).orElse(null);
        if (profile != null) {
            if (!profile.roamFallbackSpots().isEmpty()) {
                return profile.roamFallbackSpots().get(Math.floorMod(ordinal, profile.roamFallbackSpots().size())).point();
            }
            if (!profile.restSpots().isEmpty()) {
                return profile.restSpots().get(Math.floorMod(ordinal, profile.restSpots().size())).point();
            }
        }
        var portal = map.getRandomPlayerSpawnpoint();
        return portal == null ? new Point(0, 0) : portal.getPosition();
    }
}
