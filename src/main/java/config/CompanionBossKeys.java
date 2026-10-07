package config;

import java.util.ArrayList;
import java.util.List;

/**
 * The boss expeditions living_world.COMPANION_BOSS_ENABLED_TYPES may list: each one whose entry NPC offers an owner who
 * creates or joins the expedition "My party will register too.". A boss's branch adds its key in its own slot of
 * {@link #knownKeys()}.
 */
public final class CompanionBossKeys {
    /** Every key the kill switch may list, Easy Balrog's first. */
    public static final List<String> KNOWN = knownKeys();

    private CompanionBossKeys() {
    }

    private static List<String> knownKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("easy_balrog");

        // slot: zakum
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

    /** "... must list only a" for one key, "... only a or b" for two, "... only a, b, ... or z" for three or more. */
    static String message(List<String> keys) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                list.append(i == keys.size() - 1 ? " or " : ", ");
            }
            list.append(keys.get(i));
        }
        return "living_world.COMPANION_BOSS_ENABLED_TYPES must list only " + list;
    }
}
