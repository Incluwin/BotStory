package server.mods;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The server mod registry (docs/CLIENT_FEATURE_HANDSHAKE.md): every server mod is one line in {@link #ALL}, its switch
 * comes from config.yaml {@code mods.SWITCHES} at boot (a missing line = the mod's default; a {@code MOD_<ID>}
 * environment variable, from .env or docker-compose, wins over the line), and {@code !mods <id> on|off} flips it until
 * the next restart.
 */
public final class ServerMods {
    private static final Logger log = LoggerFactory.getLogger(ServerMods.class);

    /** The login feature handshake applies {@code mods.FEATURES}; off, every client feature is allowed. */
    public static final ServerMod CLIENT_FEATURE_POLICY = new ServerMod("client_feature_policy",
            "Client feature policy at login (mods.FEATURES)", true);

    /**
     * Every equip the server sends ends with its Fusion Anvil transmog id, which only the BotStory build of kaentake
     * reads (GW_ItemSlotBase::Decode hook); any other client misreads every item and closes on entering the game. Off,
     * the id is not sent and the Fusion Anvil cannot be used. Boot only: a flip would change the item layout under
     * clients that are already connected.
     */
    public static final ServerMod FUSION_ANVIL = new ServerMod("fusion_anvil",
            "Fusion Anvil id after every equip (only the BotStory kaentake DLL reads it)", false, true);

    /**
     * The clients have BotStory's WZ files: characters wear an Evolving Ring (1112499-1112502, not in the Cosmic WZ
     * files) and the Hunter's Codex sits in the cash medal slot (-149, which needs cash=1 on 1142165). Off, neither is
     * granted or moved, so a client on public WZ files can enter the game, and the GM item commands, the dressing room
     * and SoloMapling's bots leave out every equip only BotStory's WZ files have ({@link BotStoryWzEquips}).
     */
    public static final ServerMod BOTSTORY_WZ = new ServerMod("botstory_wz",
            "Clients have BotStory's WZ files (Evolving Ring, Hunter's Codex as a cash medal)", false);

    /**
     * A Beginner whose client has the {@code beginner_ap} feature active keeps the AP of each level-up to place
     * (Character.levelUp); without it USE_AUTOASSIGN_STARTERS_AP spends a Beginner's AP for them, as before.
     */
    public static final ServerMod BEGINNER_AP = new ServerMod("beginner_ap",
            "Beginners place their own AP (client feature beginner_ap)", false);

    /**
     * A client with the {@code set_items} feature active is sent its job's sets (scripts/config/set-items.yml) and
     * what it wears of them, when its features apply and on every equip change ({@code server.set.SetItemSync}), so
     * the equip tooltip can show them. Display only: the bonus itself is the server's either way.
     */
    public static final ServerMod SET_ITEMS = new ServerMod("set_items",
            "Set item tooltips (client feature set_items)", true);

    /**
     * The Storage Bags (server.bag, docs/STORAGE_BAG.md): four 200-slot bags per character for ores, scrolls, chairs
     * and mounts, used through the client's storage_bag window, which mods.FEATURES allows. Off, the window's requests
     * are ignored and Auto stops filling the bags; bags already loaded keep saving.
     */
    public static final ServerMod STORAGE_BAG = new ServerMod("storage_bag",
            "Storage Bags (client feature storage_bag)", true);

    /**
     * The maps only the Community Repack client has ({@link RepackMaps}, docs/REPACK_IMPORT_PLAN.md): a client known to
     * have the Repack files may enter them, through the entry NPCs or any warp; any other is refused. Off, nobody
     * enters, the entry NPCs are not spawned on maps loaded after the flip, and a character inside starts outside at
     * its next login.
     */
    public static final ServerMod REPACK_MAPS = new ServerMod("repack_maps",
            "Repack-only maps for clients on the Community Repack files", true);

    /** Every server mod, one line each; a ported mod adds its line here. */
    public static final List<ServerMod> ALL = List.of(CLIENT_FEATURE_POLICY, FUSION_ANVIL, BOTSTORY_WZ, BEGINNER_AP,
            SET_ITEMS, STORAGE_BAG, REPACK_MAPS);

    private static volatile ServerMods instance;

    private final List<ServerMod> mods;
    private final Map<String, Boolean> on = new ConcurrentHashMap<>();
    /** Mods whose value came from a {@code MOD_<ID>} variable and has not been flipped since. */
    private final Set<String> fromEnvironment = ConcurrentHashMap.newKeySet();

    public ServerMods(List<ServerMod> mods, Map<String, String> switches) {
        this(mods, switches, Map.of());
    }

    /** {@code environment}: a {@code MOD_<ID>} variable that is set wins over that mod's config.yaml line. */
    public ServerMods(List<ServerMod> mods, Map<String, String> switches, Map<String, String> environment) {
        this.mods = List.copyOf(mods);
        Map<String, Boolean> parsed = parseSwitches(switches);
        for (ServerMod mod : this.mods) {
            String variable = environmentName(mod);
            String override = environment.get(variable);
            if (override != null && !override.isBlank()) {
                Boolean value = parseSwitch(override);
                if (value == null) {
                    throw new IllegalArgumentException(variable + " must be on or off");
                }
                on.put(mod.id(), value);
                fromEnvironment.add(mod.id());
            } else {
                on.put(mod.id(), parsed.getOrDefault(mod.id(), mod.defaultOn()));
            }
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
                    mods = new ServerMods(ALL, YamlConfig.config.mods.SWITCHES, System.getenv());
                    instance = mods;
                }
            }
        }
        return mods;
    }

    /** The environment variable that overrides a mod's config.yaml line: {@code MOD_} and the id in capitals. */
    public static String environmentName(ServerMod mod) {
        return "MOD_" + mod.id().toUpperCase(Locale.ROOT);
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
        fromEnvironment.remove(mod.id());
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

    /** "id on, id off (MOD_ID)", in registry order; the variable is named when it set the value. */
    public String summary() {
        return mods.stream().map(this::describe).collect(Collectors.joining(", "));
    }

    /** "id on", or "id on (MOD_ID)" when the environment set it. */
    public String describe(ServerMod mod) {
        return mod.id() + (isOn(mod) ? " on" : " off")
                + (fromEnvironment.contains(mod.id()) ? " (" + environmentName(mod) + ")" : "");
    }
}
