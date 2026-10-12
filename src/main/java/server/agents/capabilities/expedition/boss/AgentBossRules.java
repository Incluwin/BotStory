package server.agents.capabilities.expedition.boss;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The boss rules catalog (agents/catalogs/boss-rules.json, P11): per boss key, the mob ids its companion fight reads
 * instead of static constants. Normal Balrog (balrog_normal) is the first entry; P12 extends the file to every boss.
 *
 * <ul>
 * <li>{@code roles}: the fight's named parts (Normal Balrog: body, releasedClaw, initialClaw, releaseSeal).</li>
 * <li>{@code targetPriority}: the parts a member hits, first first (a part hits only once it is up).</li>
 * <li>{@code adds}: the boss's summons, which members also hit.</li>
 * <li>{@code neverTarget}: parts a run member never hits, not even as a secondary or projectile target
 * ({@link AgentBossRunRegistry#neverTarget}): dummies, helpers and seals whose death would change the fight.</li>
 * </ul>
 */
public final class AgentBossRules {
    private static final String RESOURCE = "/agents/catalogs/boss-rules.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final List<Boss> BOSSES = load();

    /** One boss's rules. */
    public record Boss(String key, String label, Map<String, Integer> roles, List<Integer> targetPriority,
                       List<Integer> adds, List<Integer> neverTarget) {
        public Boss {
            if (key == null || key.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("a boss rule needs a key and a label");
            }
            key = key.trim().toLowerCase(Locale.ROOT);
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            targetPriority = targetPriority == null ? List.of() : List.copyOf(targetPriority);
            adds = adds == null ? List.of() : List.copyOf(adds);
            neverTarget = neverTarget == null ? List.of() : List.copyOf(neverTarget);
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
        return BOSSES;
    }

    /** The rules of boss {@code key} (trimmed, any case), or null when the catalog has none. */
    public static Boss of(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (Boss boss : BOSSES) {
            if (boss.key().equals(wanted)) {
                return boss;
            }
        }
        return null;
    }

    /** True when boss {@code key}'s rules list {@code mobId} as a part its run members never hit. */
    public static boolean neverTarget(String key, int mobId) {
        Boss boss = of(key);
        return boss != null && boss.neverTarget().contains(mobId);
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
