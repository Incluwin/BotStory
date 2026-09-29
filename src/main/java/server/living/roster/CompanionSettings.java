package server.living.roster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.runtime.AgentRuntimeConfig;

import java.util.Locale;

/**
 * One companion's own settings (docs/BOT_MANAGEMENT_PLAN.md P3b, "Companion settings"): the HP and MP percentages it
 * drinks a potion below, how it loots, the support it gives and its own place in its owner's formation. Kept as JSON
 * in living_roster.settings_json. The defaults are today's behaviour: the configured AUTOPOT thresholds (70 / 50),
 * everything the loot rules allow, boss drops left to the humans (the plan's NO_BOSS default), party buffs and heals
 * on, buff potions off, and the crew's formation ({@code formation} null). A field at its default is not written, so
 * an untouched percentage keeps following the configuration; parsing ignores unknown keys and takes the default for
 * a missing or bad value.
 */
public record CompanionSettings(int hpPercent, int mpPercent, AgentLootPolicy.Mode loot, boolean leaveBossDrops,
                                boolean buffs, boolean heals, boolean buffPotions, String formation) {
    /** The lowest and highest potion percentage a companion may be set to drink below. */
    public static final int MIN_POTION_PERCENT = 10;
    public static final int MAX_POTION_PERCENT = 90;

    private static final Logger log = LoggerFactory.getLogger(CompanionSettings.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A support toggle, with the Agent chat phrases that turn it on and off (AgentChatToggleFlow). */
    public enum Support {
        /** Its skill buffs for the party. */
        BUFFS("support on", "support off"),
        /** Its heals for the party (a healer's). */
        HEALS("heals on", "heals off"),
        /** Buff potions from its own bag. */
        BUFF_POTIONS("buff pots on", "buff pots off");

        private final String on;
        private final String off;

        Support(String on, String off) {
            this.on = on;
            this.off = off;
        }

        /** What its owner says to it to turn this on or off. */
        public String phrase(boolean enabled) {
            return enabled ? on : off;
        }
    }

    /** Today's behaviour, with the potion percentages the configuration holds now. */
    public static CompanionSettings defaults() {
        return new CompanionSettings(percent(AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH),
                percent(AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH), AgentLootPolicy.Mode.ALL, true, true, true, false,
                null);
    }

    /** The settings in {@code json}: the defaults for null, blank or unreadable JSON, and for each bad value. */
    public static CompanionSettings parse(String json) {
        CompanionSettings defaults = defaults();
        if (json == null || json.isBlank()) {
            return defaults;
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception failure) {
            log.warn("unreadable companion settings {}: {}", json, failure.getMessage());
            return defaults;
        }
        if (node == null || !node.isObject()) {
            return defaults;
        }
        return new CompanionSettings(potion(node.get("hp"), defaults.hpPercent()),
                potion(node.get("mp"), defaults.mpPercent()), loot(node.get("loot"), defaults.loot()),
                flag(node.get("leaveBossDrops"), defaults.leaveBossDrops()), flag(node.get("buffs"), defaults.buffs()),
                flag(node.get("heals"), defaults.heals()), flag(node.get("buffPotions"), defaults.buffPotions()),
                formation(node.get("formation")));
    }

    /** The JSON of every field not at its default ("{}" for the defaults). */
    public String toJson() {
        CompanionSettings defaults = defaults();
        ObjectNode node = JSON.createObjectNode();
        if (hpPercent != defaults.hpPercent()) {
            node.put("hp", hpPercent);
        }
        if (mpPercent != defaults.mpPercent()) {
            node.put("mp", mpPercent);
        }
        if (loot != defaults.loot()) {
            node.put("loot", loot.name());
        }
        if (leaveBossDrops != defaults.leaveBossDrops()) {
            node.put("leaveBossDrops", leaveBossDrops);
        }
        if (buffs != defaults.buffs()) {
            node.put("buffs", buffs);
        }
        if (heals != defaults.heals()) {
            node.put("heals", heals);
        }
        if (buffPotions != defaults.buffPotions()) {
            node.put("buffPotions", buffPotions);
        }
        if (formation != null) {
            node.put("formation", formation);
        }
        return node.toString();
    }

    /** True when both potion percentages are the configured ones: the Agent drinks as configured. */
    public boolean potionsAsConfigured() {
        CompanionSettings defaults = defaults();
        return hpPercent == defaults.hpPercent() && mpPercent == defaults.mpPercent();
    }

    /** True when the support toggles are the Agent's own defaults: nothing to set on a loaded companion. */
    public boolean supportAsDefault() {
        CompanionSettings defaults = defaults();
        return buffs == defaults.buffs() && heals == defaults.heals() && buffPotions == defaults.buffPotions();
    }

    public boolean support(Support toggle) {
        return switch (toggle) {
            case BUFFS -> buffs;
            case HEALS -> heals;
            case BUFF_POTIONS -> buffPotions;
        };
    }

    public CompanionSettings withPotions(int hp, int mp) {
        return new CompanionSettings(hp, mp, loot, leaveBossDrops, buffs, heals, buffPotions, formation);
    }

    public CompanionSettings withLoot(AgentLootPolicy.Mode mode) {
        return new CompanionSettings(hpPercent, mpPercent, mode, leaveBossDrops, buffs, heals, buffPotions, formation);
    }

    public CompanionSettings withLeaveBossDrops(boolean leave) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leave, buffs, heals, buffPotions, formation);
    }

    public CompanionSettings withSupport(Support toggle, boolean enabled) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leaveBossDrops,
                toggle == Support.BUFFS ? enabled : buffs, toggle == Support.HEALS ? enabled : heals,
                toggle == Support.BUFF_POTIONS ? enabled : buffPotions, formation);
    }

    /** {@code token}: one of CompanionManager.FORMATIONS' tokens, or null for the crew's formation. */
    public CompanionSettings withFormation(String token) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leaveBossDrops, buffs, heals, buffPotions, token);
    }

    /** True when {@code percent} is a potion percentage a companion may be set to. */
    public static boolean potionPercent(int percent) {
        return percent >= MIN_POTION_PERCENT && percent <= MAX_POTION_PERCENT;
    }

    /** True when {@code token} is a formation the menu offers (CompanionManager.FORMATIONS). */
    public static boolean knownFormation(String token) {
        return token != null && CompanionManager.FORMATIONS.stream().anyMatch(known -> known.token().equals(token));
    }

    private static int percent(float fraction) {
        return Math.round(fraction * 100f);
    }

    private static int potion(JsonNode value, int fallback) {
        return value != null && value.isInt() && potionPercent(value.intValue()) ? value.intValue() : fallback;
    }

    private static boolean flag(JsonNode value, boolean fallback) {
        return value != null && value.isBoolean() ? value.booleanValue() : fallback;
    }

    private static AgentLootPolicy.Mode loot(JsonNode value, AgentLootPolicy.Mode fallback) {
        if (value == null || !value.isTextual()) {
            return fallback;
        }
        try {
            return AgentLootPolicy.Mode.valueOf(value.textValue().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    private static String formation(JsonNode value) {
        return value != null && value.isTextual() && knownFormation(value.textValue()) ? value.textValue() : null;
    }
}
