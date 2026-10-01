package server.living.roster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.AgentBuffState;
import server.agents.capabilities.dialogue.AgentChatToggleHook;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.runtime.AgentRuntimeConfig;

import java.util.Locale;
import java.util.Set;

/**
 * One companion's own settings (docs/BOT_MANAGEMENT_PLAN.md P3b, "Companion settings"): the HP and MP percentages it
 * drinks a potion below, how it loots, the support it gives and its own place in its owner's formation. Kept as JSON
 * in living_roster.settings_json. The defaults are today's behaviour: the configured AUTOPOT thresholds (70 / 50),
 * everything the loot rules allow, boss drops left to the humans (the plan's NO_BOSS default), party buffs and heals
 * on, buff potions off, and the crew's formation ({@code formation} null). A field at its default is not written, but
 * the potion percentages are written once the owner set them ({@code potionsSet}), even to the configured values: an
 * untouched pair keeps following the configuration, a chosen one never moves with it. Parsing ignores unknown keys and
 * takes the default for a missing or bad value.
 */
public record CompanionSettings(int hpPercent, int mpPercent, AgentLootPolicy.Mode loot, boolean leaveBossDrops,
                                boolean buffs, boolean heals, boolean buffPotions, String formation,
                                boolean potionsSet) {
    /** The lowest and highest potion percentage a companion may be set to drink below. */
    public static final int MIN_POTION_PERCENT = 10;
    public static final int MAX_POTION_PERCENT = 90;

    private static final Logger log = LoggerFactory.getLogger(CompanionSettings.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A support toggle, as the Agent's chat toggles (AgentChatToggleFlow) turn it on and off. */
    public enum Support {
        /** Its skill buffs (the party's among them). */
        BUFFS(AgentChatToggleHook.Toggle.SKILL_BUFFS),
        /** Its heals for the party (a healer's). */
        HEALS(AgentChatToggleHook.Toggle.SUPPORT_HEALS),
        /** Buff potions from its own bag. */
        BUFF_POTIONS(AgentChatToggleHook.Toggle.BUFF_CONSUMABLES);

        /** Second jobs whose line has a party buff from then on (Rage, Iron Will, Meditation, Bless, Haste). */
        private static final Set<Integer> PARTY_BUFF_LINES = Set.of(110, 130, 210, 220, 230, 410, 420);

        private final AgentChatToggleHook.Toggle chat;

        Support(AgentChatToggleHook.Toggle chat) {
            this.chat = chat;
        }

        /** The toggle a chat order turned on or off. */
        public static Support of(AgentChatToggleHook.Toggle toggle) {
            for (Support support : values()) {
                if (support.chat == toggle) {
                    return support;
                }
            }
            throw new IllegalArgumentException(String.valueOf(toggle));
        }

        /**
         * True when a companion of this job (a Job id; -1 when unknown) has this to give, so the Fighting style page
         * offers it: heals only the Cleric line, party buffs a line that has one (from its second job, or Maple
         * Warrior at its fourth), buff potions everyone.
         */
        public boolean offeredTo(int jobId) {
            return switch (this) {
                case HEALS -> jobId / 10 == 23;
                case BUFFS -> jobId > 0 && jobId < 600 && jobId % 100 != 0
                        && (jobId % 10 == 2 || PARTY_BUFF_LINES.contains(jobId - jobId % 10));
                case BUFF_POTIONS -> true;
            };
        }
    }

    /**
     * Today's behaviour, with the potion percentages the configuration holds now (within the ones a companion may be
     * set to, so the page never shows one its number box refuses).
     */
    public static CompanionSettings defaults() {
        return new CompanionSettings(percent(AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH),
                percent(AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH), AgentLootPolicy.Mode.ALL, true, true, true,
                AgentBuffState.consumablesEnabledByDefault(), null, false);
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
        int hp = potion(node.get("hp"), -1);
        int mp = potion(node.get("mp"), -1);
        return new CompanionSettings(hp < 0 ? defaults.hpPercent() : hp, mp < 0 ? defaults.mpPercent() : mp,
                loot(node.get("loot"), defaults.loot()),
                flag(node.get("leaveBossDrops"), defaults.leaveBossDrops()), flag(node.get("buffs"), defaults.buffs()),
                flag(node.get("heals"), defaults.heals()), flag(node.get("buffPotions"), defaults.buffPotions()),
                formation(node.get("formation")), hp >= 0 || mp >= 0);
    }

    /** The JSON of the potion percentages once set, and of every other field not at its default ("{}": defaults). */
    public String toJson() {
        CompanionSettings defaults = defaults();
        ObjectNode node = JSON.createObjectNode();
        if (potionsSet) {
            node.put("hp", hpPercent);
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

    /** True while the owner never set the potion percentages: the Agent drinks as configured. */
    public boolean potionsAsConfigured() {
        return !potionsSet;
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

    /** The potion percentages the owner chose: kept as they are from now on, whatever the configuration says. */
    public CompanionSettings withPotions(int hp, int mp) {
        return new CompanionSettings(hp, mp, loot, leaveBossDrops, buffs, heals, buffPotions, formation, true);
    }

    public CompanionSettings withLoot(AgentLootPolicy.Mode mode) {
        return new CompanionSettings(hpPercent, mpPercent, mode, leaveBossDrops, buffs, heals, buffPotions, formation,
                potionsSet);
    }

    public CompanionSettings withLeaveBossDrops(boolean leave) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leave, buffs, heals, buffPotions, formation,
                potionsSet);
    }

    public CompanionSettings withSupport(Support toggle, boolean enabled) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leaveBossDrops,
                toggle == Support.BUFFS ? enabled : buffs, toggle == Support.HEALS ? enabled : heals,
                toggle == Support.BUFF_POTIONS ? enabled : buffPotions, formation, potionsSet);
    }

    /** {@code token}: one of CompanionManager.FORMATIONS' tokens, or null for the crew's formation. */
    public CompanionSettings withFormation(String token) {
        return new CompanionSettings(hpPercent, mpPercent, loot, leaveBossDrops, buffs, heals, buffPotions, token,
                potionsSet);
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
        return Math.max(MIN_POTION_PERCENT, Math.min(MAX_POTION_PERCENT, Math.round(fraction * 100f)));
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
