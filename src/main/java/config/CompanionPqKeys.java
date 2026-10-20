package config;

import java.util.ArrayList;
import java.util.List;

/**
 * The party quests living_world.COMPANION_PQ_ENABLED_KEYS may list: each one whose entry NPC offers its party leader a
 * lineup of the party and their companions. A quest's branch adds its key in its own slot of {@link #knownKeys()}.
 */
public final class CompanionPqKeys {
    /** Every key the kill switch may list, KPQ's first. */
    public static final List<String> KNOWN = knownKeys();

    private CompanionPqKeys() {
    }

    private static List<String> knownKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("kpq");

        keys.add("hpq");

        keys.add("lpq");

        keys.add("epq");

        // slot: lmpq

        // slot: opq
        keys.add("opq");

        // slot: ppq
        keys.add("ppq");

        // slot: rjpq
        keys.add("rjpq");
        keys.add("rjpqj");
        return List.copyOf(keys);
    }

    /** True when {@code key}, in any case and with spaces around it ignored, is one the kill switch may list. */
    public static boolean known(String key) {
        if (key == null) {
            return false;
        }
        for (String known : KNOWN) {
            if (known.equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    /** What validation says of a key the kill switch may not list. */
    public static String message() {
        return message(KNOWN);
    }

    /** "... must list only a or b" for two keys, "... must list only a, b, ... or z" for three or more. */
    static String message(List<String> keys) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                list.append(i == keys.size() - 1 ? " or " : ", ");
            }
            list.append(keys.get(i));
        }
        return "living_world.COMPANION_PQ_ENABLED_KEYS must list only " + list;
    }
}
