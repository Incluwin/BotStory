package server.living.population;

import server.knowledge.Decisions;
import server.knowledge.WorldKnowledge;
import server.living.population.PopulationContent.Career;
import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.Lobby;
import server.living.population.PopulationContent.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;

/** Where citizens go (plan 3.3): training fields by level and region with occupancy, towns, lobbies, FM. */
public final class FieldCatalog {
    public static final int FM_ENTRANCE = 910000000;
    private static final int FM_LAST_ROOM = 910000022;
    private static final int LEVEL_BELOW = 6;
    private static final int LEVEL_ABOVE = 3;
    /** Clerics, priests and bishops of this level or more lean toward undead fields: Heal hurts undead monsters. */
    private static final int HEALER_MIN_LEVEL = 30;
    private static final int HEALER_UNDEAD_PERCENT = 70;
    private static final double UNDEAD_MAJORITY = 0.5;
    private static final Set<String> HEALERS = Set.of("CLERIC", "PRIEST", "BISHOP");

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

    /**
     * A field listed with no room (not a town, capacity 0: the Mushroom Castle fields past their quest gates, say):
     * nobody trains or camps there, so a camp stored there from before is no camp now.
     */
    public boolean closed(int mapId) {
        Field field = population.content().field(mapId);
        return field != null && !field.town() && field.capacity() <= 0;
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
        return region.townFor(citizen.characterId() * 31 + (random == null ? 0 : random.nextInt(7)));
    }

    /** A lobby in the citizen's region whose level band fits (the first listed), else null. */
    public Lobby lobbyFor(Citizen citizen, int level) {
        List<Lobby> lobbies = lobbiesFor(citizen, level);
        return lobbies.isEmpty() ? null : lobbies.getFirst();
    }

    /**
     * Where a citizen out for a party quest goes: one of the lobbies of its region whose band fits, at random (an
     * Orbis citizen of Lv51-70 waits for OPQ or goes down to Mu Young for Balrog, 2026-10-25); null when none fits.
     */
    public Lobby lobbyFor(Citizen citizen, int level, Random random) {
        List<Lobby> lobbies = lobbiesFor(citizen, level);
        if (lobbies.size() <= 1 || random == null) {
            return lobbies.isEmpty() ? null : lobbies.getFirst();
        }
        return lobbies.get(random.nextInt(lobbies.size()));
    }

    /** Every lobby of the citizen's region whose level band fits, in regions.yaml order. */
    public List<Lobby> lobbiesFor(Citizen citizen, int level) {
        Region region = regionOf(citizen);
        if (region == null) {
            return List.of();
        }
        List<Lobby> fitting = new ArrayList<>();
        for (Lobby lobby : region.lobbies()) {
            if (level >= lobby.levelMin() && level <= lobby.levelMax()) {
                fitting.add(lobby);
            }
        }
        return fitting;
    }

    /**
     * Picks a training field: in the citizen's region, mob level within [level-6, level+3], with
     * free capacity given citizens already there; a healer of level 30+ mostly picks among the
     * undead-majority ones (docs/SERVER_KNOWLEDGE_PLAN.md 8.5); prefers maps humans are on when {@code social}.
     */
    public Field trainingField(Citizen citizen, int level, Random random, boolean social) {
        return trainingField(citizen, level, random, social, mapId -> false);
    }

    /** As above, never on a map {@code avoided} names (one it died on beside a field boss that still stands). */
    public Field trainingField(Citizen citizen, int level, Random random, boolean social, IntPredicate avoided) {
        List<Field> candidates = new ArrayList<>();
        String region = citizen.homeRegion();
        for (Field field : population.content().fields().values()) {
            if (field.town() || field.capacity() <= 0 || field.mobLevel() <= 0 || !field.region().equals(region)
                    || avoided.test(field.mapId())) {
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
                if (!field.town() && field.capacity() > 0 && field.mobLevel() > 0 && Math.abs(field.mobLevel() - level) <= LEVEL_BELOW + 4
                        && occupancy(field.mapId()) < field.capacity() && !avoided.test(field.mapId())) {
                    candidates.add(field);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Career career = population.content().career(citizen.career());
        if (career != null && HEALERS.contains(career.job()) && level >= HEALER_MIN_LEVEL) {
            List<Field> undead = healerFields(candidates, Decisions.active());
            if (!undead.isEmpty() && random.nextInt(100) < HEALER_UNDEAD_PERCENT) {
                candidates = undead;
            }
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

    /** The candidates whose spawn points are mostly undead monsters (server knowledge); none without knowledge. */
    static List<Field> healerFields(List<Field> candidates, WorldKnowledge k) {
        List<Field> out = new ArrayList<>();
        if (k != null) {
            for (Field field : candidates) {
                if (k.undeadShare(field.mapId()) >= UNDEAD_MAJORITY) {
                    out.add(field);
                }
            }
        }
        return out;
    }

    /**
     * Up to {@code limit} training fields whose mobs fit {@code level} (the band {@link #trainingField} uses), those in
     * {@code nearMapId}'s region first, then the closest mob level: where a companion may camp (the camp picker).
     */
    public List<Field> fieldsFor(int level, int nearMapId, int limit) {
        Field near = population.content().field(nearMapId);
        String region = near == null ? null : near.region();
        List<Field> fitting = new ArrayList<>();
        for (Field field : population.content().fields().values()) {
            if (!field.town() && field.capacity() > 0 && field.mobLevel() > 0 && field.mobLevel() >= level - LEVEL_BELOW
                    && field.mobLevel() <= level + LEVEL_ABOVE) {
                fitting.add(field);
            }
        }
        fitting.sort(java.util.Comparator.comparing((Field field) -> region != null && !region.equals(field.region()))
                .thenComparingInt(field -> Math.abs(field.mobLevel() - level)).thenComparingInt(Field::mapId));
        return fitting.subList(0, Math.min(limit, fitting.size()));
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
        if (mapId == FM_ENTRANCE) {
            return "Free Market";
        }
        if (isFreeMarket(mapId)) {
            return "Free Market room " + (mapId - FM_ENTRANCE);
        }
        Field field = population.content().field(mapId);
        return field == null ? "map " + mapId : field.name();
    }
}
