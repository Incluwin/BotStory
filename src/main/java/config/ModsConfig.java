package config;

import server.expeditions.ExpeditionType;
import server.mods.ServerMods;
import server.mods.features.FeaturePolicy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code mods:} in config.yaml (docs/CLIENT_FEATURE_HANDSHAKE.md; P5 of the mod intake plan): one switch per server mod,
 * and what the server answers a kaentake client's feature list at login. Read at boot; {@code !mods} changes either
 * until the next restart. ASCII only, like the rest of config.yaml.
 */
public class ModsConfig {
    /** Server mod id ({@link ServerMods}) -> on|off (also true|false). A mod with no line takes its own default. */
    public Map<String, String> SWITCHES = new LinkedHashMap<>();
    /**
     * kaentake feature id (its feature_list.h id) -> allow, deny or require, optionally followed by a minimum version
     * ("require 2"). A feature with no line is allowed, unless the client marks it server_gated: then
     * {@link #SERVER_GATED_DEFAULT} answers.
     */
    public Map<String, String> FEATURES = new LinkedHashMap<>();
    /** The answer for a server_gated (gameplay) feature that {@link #FEATURES} does not name: allow or deny. */
    public String SERVER_GATED_DEFAULT = "deny";
    /**
     * Expedition type (lower case, e.g. zakum) -> the deaths one boss run may take while the death_count mod is on.
     * A missing line or 0 leaves that boss stock.
     */
    public Map<String, Integer> DEATH_COUNTS = new LinkedHashMap<>();

    public void validate() {
        ServerMods.parseSwitches(SWITCHES);
        FeaturePolicy.parse(FEATURES, SERVER_GATED_DEFAULT);
        if (DEATH_COUNTS != null) {
            Set<String> types = new HashSet<>();
            for (ExpeditionType type : ExpeditionType.values()) {
                types.add(type.name().toLowerCase(Locale.ROOT));
            }
            for (Map.Entry<String, Integer> entry : DEATH_COUNTS.entrySet()) {
                if (!types.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) {
                    throw new IllegalArgumentException("mods.DEATH_COUNTS." + entry.getKey()
                            + " must be an expedition type (lower case) with 0 or more deaths");
                }
            }
        }
    }
}
