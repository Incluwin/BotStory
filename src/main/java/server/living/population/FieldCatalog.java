package server.living.population;

import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.Lobby;
import server.living.population.PopulationContent.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Where citizens go (plan 3.3): training fields by level and region with occupancy, towns, lobbies, FM. */
public final class FieldCatalog {
    public static final int FM_ENTRANCE = 910000000;
    private static final int FM_LAST_ROOM = 910000022;
    private static final int LEVEL_BELOW = 6;
    private static final int LEVEL_ABOVE = 3;

    private final Population population;

    FieldCatalog(Population population) {
        this.population = population;
    }

    public static boolean isFreeMarket(int mapId) {
        return mapId >= FM_ENTRANCE && mapId <= FM_LAST_ROOM;
    }

    public boolean isTown(int mapId) {
        Field field = population.content().field(mapId);
        if (field != null) {
            return field.town();
        }
        for (Region region : population.content().regions().values()) {
            if (region.towns().contains(mapId)) {
                return true;
            }
        }
        return false;
    }

    public Lobby lobbyAt(int mapId) {
        for (Region region : population.content().regions().values()) {
            for (Lobby lobby : region.lobbies()) {
                if (lobby.mapId() == mapId) {
                    return lobby;
                }
            }
        }
        return null;
    }

    /** Chatter "where" class for a map: fm, lobby, town, field. */
    public String kind(int mapId) {
        if (isFreeMarket(mapId)) {
            return "fm";
        }
        if (lobbyAt(mapId) != null) {
            return "lobby";
        }
        return isTown(mapId) ? "town" : "field";
    }

    public Region regionOf(Citizen citizen) {
        Region region = population.content().region(citizen.homeRegion());
        return region != null ? region : population.content().region("victoria");
    }

    public int homeTown(Citizen citizen, Random random) {
        Region region = regionOf(citizen);
        if (region == null || region.towns().isEmpty()) {
            return 100000000;
        }
        return region.towns().get(Math.floorMod(citizen.characterId() + (random == null ? 0 : random.nextInt(3)), region.towns().size()));
    }

    /** A lobby in the citizen's region whose level band fits, else null. */
    public Lobby lobbyFor(Citizen citizen, int level) {
        Region region = regionOf(citizen);
        if (region == null) {
            return null;
        }
        for (Lobby lobby : region.lobbies()) {
            if (level >= lobby.levelMin() && level <= lobby.levelMax()) {
                return lobby;
            }
        }
        return null;
    }

    /**
     * Picks a training field: in the citizen's region, mob level within [level-6, level+3], with
     * free capacity given citizens already there; prefers maps humans are on when {@code social}.
     */
    public Field trainingField(Citizen citizen, int level, Random random, boolean social) {
        List<Field> candidates = new ArrayList<>();
        String region = citizen.homeRegion();
        for (Field field : population.content().fields().values()) {
            if (field.town() || field.capacity() <= 0 || !field.region().equals(region)) {
                continue;
            }
            if (field.mobLevel() < level - LEVEL_BELOW || field.mobLevel() > level + LEVEL_ABOVE) {
                continue;
            }
            if (occupancy(field.mapId()) >= field.capacity()) {
                continue;
            }
            candidates.add(field);
        }
        if (candidates.isEmpty()) {
            for (Field field : population.content().fields().values()) {
                if (!field.town() && field.capacity() > 0 && Math.abs(field.mobLevel() - level) <= LEVEL_BELOW + 4
                        && occupancy(field.mapId()) < field.capacity()) {
                    candidates.add(field);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (social) {
            java.util.Set<Integer> humanMaps = population.humanMaps();
            for (Field field : candidates) {
                if (humanMaps.contains(field.mapId()) && random.nextInt(100) < 60) {
                    return field;
                }
            }
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** Loaded citizens on this map id, any channel. */
    public int occupancy(int mapId) {
        int count = 0;
        for (CitizenSession session : population.presence().sessions()) {
            if (!session.virtual && session.mapId == mapId) {
                count++;
            }
        }
        return count;
    }

    public String name(int mapId) {
        Field field = population.content().field(mapId);
        return field == null ? "map " + mapId : field.name();
    }
}
