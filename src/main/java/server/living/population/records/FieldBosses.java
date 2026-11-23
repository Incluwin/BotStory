package server.living.population.records;

import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The field-boss catalogue ({@code field-bosses.yaml} in the population content folder, docs/WORLD_SYSTEMS_PLAN.md
 * N6.0): each boss that stands on an ordinary map, its key (the records' quest name), mob ids, maps, level, the levels
 * of the citizens it concerns, and how it respawns here (script: 0-3 h after a death; life: seconds).
 */
public final class FieldBosses {
    public static final String FILE = "field-bosses.yaml";

    /** One catalogued boss. */
    public record Boss(String key, String label, List<Integer> mobs, List<Integer> maps, int level, int minLevel,
                       int maxLevel, String respawn, List<String> aliases) {
        public Boss {
            mobs = List.copyOf(mobs);
            maps = List.copyOf(maps);
            aliases = List.copyOf(aliases);
        }

        /** Whether a citizen of {@code level} hunts it (or posts a bounty on it). */
        public boolean fits(int citizenLevel) {
            return citizenLevel >= minLevel && citizenLevel <= maxLevel;
        }

        /** Whether it comes back seconds after a death (map life) rather than 0-3 h later (an AreaBoss script). */
        public boolean quickRespawn() {
            return "life".equals(respawn);
        }
    }

    private static final FieldBosses NONE = new FieldBosses(List.of());

    private final Map<String, Boss> byKey = new LinkedHashMap<>();
    private final Map<Integer, Boss> byMob = new HashMap<>();
    private final Map<Integer, List<Boss>> byMap = new HashMap<>();

    public FieldBosses(List<Boss> bosses) {
        for (Boss boss : bosses) {
            byKey.put(boss.key(), boss);
            for (int mob : boss.mobs()) {
                byMob.put(mob, boss);
            }
            for (int map : boss.maps()) {
                byMap.computeIfAbsent(map, id -> new ArrayList<>()).add(boss);
            }
        }
    }

    public static FieldBosses none() {
        return NONE;
    }

    /** {@code field-bosses.yaml} in {@code contentDir}; none when it is missing. */
    public static FieldBosses load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        if (!Files.exists(file)) {
            return NONE;
        }
        List<Boss> out = new ArrayList<>();
        for (Node node : ContentLoader.read(file).get("bosses").items()) {
            List<Integer> levels = node.get("levels").integers();
            out.add(new Boss(node.get("key").string(), node.get("label").string(), node.get("mobs").integers(),
                    node.get("maps").integers(), node.get("level").integer(), levels.get(0), levels.get(1),
                    node.get("respawn").string("script"),
                    node.has("aliases") ? node.get("aliases").strings() : List.of()));
        }
        return new FieldBosses(out);
    }

    public List<Boss> all() {
        return List.copyOf(byKey.values());
    }

    public Boss byKey(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** The boss {@code mobId} is, or null for a monster no entry lists. */
    public Boss ofMob(int mobId) {
        return byMob.get(mobId);
    }

    /** The bosses that spawn on {@code mapId}; empty for none. */
    public List<Boss> onMap(int mapId) {
        List<Boss> out = byMap.get(mapId);
        return out == null ? List.of() : List.copyOf(out);
    }
}
