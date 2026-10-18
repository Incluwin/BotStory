package server.mods;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The server mod registry (docs/CLIENT_FEATURE_HANDSHAKE.md): every server mod is one line in {@link #ALL}, its switch
 * comes from config.yaml {@code mods.SWITCHES} at boot (a missing line = the mod's default), and {@code !mods <id>
 * on|off} flips it until the next restart.
 */
public final class ServerMods {
    private static final Logger log = LoggerFactory.getLogger(ServerMods.class);

    /** The login feature handshake applies {@code mods.FEATURES}; off, every client feature is allowed. */
    public static final ServerMod CLIENT_FEATURE_POLICY = new ServerMod("client_feature_policy",
            "Client feature policy at login (mods.FEATURES)", true);

    /**
     * A Beginner whose client has the {@code beginner_ap} feature active keeps the AP of each level-up to place
     * (Character.levelUp); without it USE_AUTOASSIGN_STARTERS_AP spends a Beginner's AP for them, as before.
     */
    public static final ServerMod BEGINNER_AP = new ServerMod("beginner_ap",
            "Beginners place their own AP (client feature beginner_ap)", false);

    /** Every server mod, one line each; a ported mod adds its line here. */
    public static final List<ServerMod> ALL = List.of(CLIENT_FEATURE_POLICY, BEGINNER_AP);

    private static volatile ServerMods instance;

    private final List<ServerMod> mods;
    private final Map<String, Boolean> on = new ConcurrentHashMap<>();

    public ServerMods(List<ServerMod> mods, Map<String, String> switches) {
        this.mods = List.copyOf(mods);
        Map<String, Boolean> parsed = parseSwitches(switches);
        for (ServerMod mod : this.mods) {
            on.put(mod.id(), parsed.getOrDefault(mod.id(), mod.defaultOn()));
        }
        for (String id : parsed.keySet()) {
            if (find(id) == null) {
                log.warn("config.yaml mods.SWITCHES.{} names no server mod; ignored", id);
            }
        }
    }

    /** The registry with config.yaml's switches, made on first use. */
    public static ServerMods get() {
        ServerMods mods = instance;
        if (mods == null) {
            synchronized (ServerMods.class) {
                mods = instance;
                if (mods == null) {
                    mods = new ServerMods(ALL, YamlConfig.config.mods.SWITCHES);
                    instance = mods;
                }
            }
        }
        return mods;
    }

    /** Server start: reads the switches and logs them. */
    public static void boot() {
        log.info("Server mods: {}", get().summary());
    }

    /**
     * config.yaml {@code mods.SWITCHES} with lower-case ids; a value other than on|off|true|false|yes|no fails.
     */
    public static Map<String, Boolean> parseSwitches(Map<String, String> switches) {
        Map<String, Boolean> parsed = new LinkedHashMap<>();
        if (switches == null) {
            return parsed;
        }
        for (Map.Entry<String, String> entry : switches.entrySet()) {
            String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
            Boolean value = parseSwitch(entry.getValue());
            if (value == null) {
                throw new IllegalArgumentException("mods.SWITCHES." + id + " must be on or off");
            }
            parsed.put(id, value);
        }
        return parsed;
    }

    /** on|true|yes -> true, off|false|no -> false, anything else -> null. */
    public static Boolean parseSwitch(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "on", "true", "yes" -> Boolean.TRUE;
            case "off", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    public boolean isOn(ServerMod mod) {
        return on.getOrDefault(mod.id(), mod.defaultOn());
    }

    /** Until the next restart (config.yaml keeps its own value). */
    public void set(ServerMod mod, boolean value) {
        on.put(mod.id(), value);
    }

    /** The mod with this id (any case), or null. */
    public ServerMod find(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (ServerMod mod : mods) {
            if (mod.id().equals(key)) {
                return mod;
            }
        }
        return null;
    }

    public List<ServerMod> mods() {
        return mods;
    }

    /** "id on, id off", in registry order. */
    public String summary() {
        return mods.stream().map(mod -> mod.id() + (isOn(mod) ? " on" : " off")).collect(Collectors.joining(", "));
    }
}
