package server.agents.capabilities.expedition.boss;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The boss rules catalog (agents/catalogs/boss-rules.json, P11): per boss key, the mob ids its companion fight reads
 * instead of static constants. Normal Balrog (balrog_normal) is the first entry; P12 extends the file to every boss.
 *
 * <ul>
 * <li>{@code roles}: the fight's named parts (Normal Balrog: body, releasedClaw, initialClaw, releaseSeal).</li>
 * <li>{@code targetPriority}: the parts a member hits, first first (a part hits only once it is up). A fight with no
 * driver of its own reads it through {@link #preferences} (P12 G3).</li>
 * <li>{@code adds}: the boss's summons, which members also hit.</li>
 * <li>{@code neverTarget}: parts a run member never hits, as a primary, secondary or projectile target
 * ({@link AgentBossRunRegistry#neverTarget}): dummies, helpers and seals whose death would change the fight, and
 * display parts (Horntail's dead heads, Pink Bean's statues).</li>
 * <li>{@code neverTargetFakes}: a run member never hits a fake part (Zakum's body before its arms fall).</li>
 * <li>{@code minLevel} (P14): the level a companion needs for a party boss, which has no expedition type.</li>
 * <li>{@code markerItems}: drops a run member leaves on the floor, items the boss's own script takes (Zakum's Eye of
 * Fire). The run catalog (AgentBossRunCatalog) reads this and {@code neverTargetFakes} from here.</li>
 * <li>{@code fieldBosses} (P12 G1): for a field group key such as field_victoria, the boss ids that fight under it on
 * a map with no event ({@link #fieldKeyOf}); the mirror's field scope and its attack floor act on them while the key
 * is on.</li>
 * </ul>
 */
public final class AgentBossRules {
    private static final String RESOURCE = "/agents/catalogs/boss-rules.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static volatile List<Boss> bosses = load();

    /** One boss's rules. */
    public record Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                       List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                       boolean neverTargetFakes, List<Integer> markerItems, int minLevel) {
        public Boss {
            if (key == null || key.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("a boss rule needs a key and a label");
            }
            key = key.trim().toLowerCase(Locale.ROOT);
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            targetPriority = targetPriority == null ? List.of() : List.copyOf(targetPriority);
            adds = adds == null ? List.of() : List.copyOf(adds);
            neverTarget = neverTarget == null ? List.of() : List.copyOf(neverTarget);
            fieldBosses = fieldBosses == null ? List.of() : List.copyOf(fieldBosses);
            markerItems = markerItems == null ? List.of() : List.copyOf(markerItems);
        }

        /** A boss with no level of its own (minLevel 0: every boss row before P14). */
        public Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                    List<Integer> adds, List<Integer> neverTarget, List<Integer> fieldBosses,
                    boolean neverTargetFakes, List<Integer> markerItems) {
            this(key, label, roles, targetPriority, adds, neverTarget, fieldBosses, neverTargetFakes, markerItems, 0);
        }

        /** The mob id of the named part; throws when the catalog names none, so a typo fails at load. */
        public int role(String name) {
            Integer id = roles.get(name);
            if (id == null || id <= 0) {
                throw new IllegalStateException("boss rule " + key + " names no " + name);
            }
            return id;
        }
    }

    record Catalog(int schemaVersion, List<Boss> bosses) {
    }

    private AgentBossRules() {
    }

    public static List<Boss> all() {
        return bosses;
    }

    /** The rules of boss {@code key} (trimmed, any case), or null when the catalog has none. */
    public static Boss of(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (Boss boss : bosses) {
            if (boss.key().equals(wanted)) {
                return boss;
            }
        }
        return null;
    }

    /**
     * P14: true when boss {@code key}'s rules name {@code mobId} as one of its fight's parts: a role, a target
     * priority part or an add.
     */
    public static boolean names(String key, int mobId) {
        Boss boss = of(key);
        return boss != null && (boss.targetPriority().contains(mobId) || boss.adds().contains(mobId)
                || boss.roles().containsValue(mobId));
    }

    /** True when boss {@code key}'s rules list {@code mobId} as a part its run members never hit. */
    public static boolean neverTarget(String key, int mobId) {
        Boss boss = of(key);
        return boss != null && boss.neverTarget().contains(mobId);
    }

    /**
     * P12 G1: the key of the field group whose {@code fieldBosses} list {@code mobId} (field_victoria for Mano, say),
     * or null when no group lists it. The answer holds only on a map with no event.
     */
    public static String fieldKeyOf(int mobId) {
        for (Boss boss : bosses) {
            if (boss.fieldBosses().contains(mobId)) {
                return boss.key();
            }
        }
        return null;
    }

    /** True when boss {@code key}'s rules say its run members never hit a fake part. */
    public static boolean neverTargetFakes(String key) {
        Boss boss = of(key);
        return boss != null && boss.neverTargetFakes();
    }

    /**
     * The level a companion needs for party boss {@code key} (P14: the event script's minLevel, 70 for Neo City),
     * 1 when the catalog names none. An expedition boss reads its expedition type instead
     * (AgentBossRunDefinition.minLevel).
     */
    public static int minLevel(String key) {
        Boss boss = of(key);
        return boss == null || boss.minLevel() <= 0 ? 1 : boss.minLevel();
    }

    /** The drops boss {@code key}'s run members leave on the floor; empty when the catalog lists none. */
    public static List<Integer> markerItems(String key) {
        Boss boss = of(key);
        return boss == null ? List.of() : boss.markerItems();
    }

    /** What a member grinds first, and what it falls back to. */
    public record Preferences(Set<Integer> preferred, Set<Integer> fallback) {
        public Preferences {
            preferred = Set.copyOf(preferred);
            fallback = Set.copyOf(fallback);
        }

        public boolean isEmpty() {
            return preferred.isEmpty() && fallback.isEmpty();
        }
    }

    /**
     * P12 G3: the target priority of {@code boss} over the mob ids alive now. The first {@code targetPriority} id that
     * is alive is preferred, and every other live id is the fallback (a later priority id, an add, any monster the
     * list does not name). A {@code neverTarget} id is in neither. With no priority id alive, every live id is
     * preferred. Pure.
     */
    public static Preferences preferences(Boss boss, Collection<Integer> liveMobIds) {
        Set<Integer> live = new LinkedHashSet<>(liveMobIds);
        if (boss != null) {
            boss.neverTarget().forEach(live::remove);
        }
        Integer first = boss == null ? null
                : boss.targetPriority().stream().filter(live::contains).findFirst().orElse(null);
        if (first == null) {
            return new Preferences(live, Set.of());
        }
        live.remove(first);
        return new Preferences(Set.of(first), live);
    }

    /** Replaces the catalog with {@code replacement}; tests only (null loads the shipped file again). */
    static void replaceForTest(List<Boss> replacement) {
        bosses = replacement == null ? load() : List.copyOf(replacement);
    }

    private static List<Boss> load() {
        try (InputStream input = AgentBossRules.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing boss rules catalog: " + RESOURCE);
            }
            Catalog catalog = MAPPER.readValue(input, Catalog.class);
            return catalog.bosses() == null ? List.of() : List.copyOf(catalog.bosses());
        } catch (IOException failure) {
            throw new IllegalStateException("could not load boss rules catalog: " + RESOURCE, failure);
        }
    }
}
