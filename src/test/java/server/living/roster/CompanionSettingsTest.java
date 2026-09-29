package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.AgentChatCommandClassifier;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.runtime.AgentRuntimeConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion's settings as settings_json (docs/BOT_MANAGEMENT_PLAN.md P3b): the defaults are today's behaviour and
 * write nothing, every setting survives the round trip, and parsing is defensive (unknown keys ignored, a bad value
 * takes its default, unreadable JSON is the defaults). The support phrases are the Agent toggles' own.
 */
class CompanionSettingsTest {
    @Test
    void theDefaultsAreTodaysBehaviourAndWriteNothing() {
        CompanionSettings defaults = CompanionSettings.defaults();

        assertEquals(Math.round(AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH * 100f), defaults.hpPercent());
        assertEquals(Math.round(AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH * 100f), defaults.mpPercent());
        assertEquals(70, defaults.hpPercent());
        assertEquals(50, defaults.mpPercent());
        assertEquals(AgentLootPolicy.Mode.ALL, defaults.loot());
        assertTrue(defaults.leaveBossDrops(), "the plan's NO_BOSS default");
        assertTrue(defaults.buffs());
        assertTrue(defaults.heals());
        assertFalse(defaults.buffPotions());
        assertEquals(null, defaults.formation(), "the crew's formation");
        assertTrue(defaults.potionsAsConfigured());
        assertTrue(defaults.supportAsDefault());
        assertEquals("{}", defaults.toJson());
        for (String nothing : new String[]{null, "", "  ", "{}", "not json", "[1,2]", "42"}) {
            assertEquals(defaults, CompanionSettings.parse(nothing), String.valueOf(nothing));
        }
    }

    @Test
    void everySettingSurvivesTheRoundTrip() {
        CompanionSettings changed = CompanionSettings.defaults()
                .withPotions(40, 25)
                .withLoot(AgentLootPolicy.Mode.QUEST_ONLY)
                .withLeaveBossDrops(false)
                .withSupport(CompanionSettings.Support.BUFFS, false)
                .withSupport(CompanionSettings.Support.HEALS, false)
                .withSupport(CompanionSettings.Support.BUFF_POTIONS, true)
                .withFormation("left");

        String json = changed.toJson();

        assertEquals(changed, CompanionSettings.parse(json));
        assertTrue(json.length() <= 1024, "settings_json is VARCHAR(1024)");
        assertTrue(json.chars().allMatch(c -> c < 128), "ASCII");
        assertFalse(changed.potionsAsConfigured());
        assertFalse(changed.supportAsDefault());
        for (AgentLootPolicy.Mode mode : AgentLootPolicy.Mode.values()) {
            CompanionSettings looting = CompanionSettings.defaults().withLoot(mode);
            assertEquals(looting, CompanionSettings.parse(looting.toJson()), mode.name());
        }
        for (CompanionManager.Formation formation : CompanionManager.FORMATIONS) {
            CompanionSettings placed = CompanionSettings.defaults().withFormation(formation.token());
            assertEquals(placed, CompanionSettings.parse(placed.toJson()), formation.token());
        }
    }

    @Test
    void onlyWhatDiffersFromTheDefaultsIsWritten() {
        assertEquals("{\"hp\":40}", CompanionSettings.defaults().withPotions(40, 50).toJson());
        assertEquals("{\"loot\":\"NONE\"}", CompanionSettings.defaults().withLoot(AgentLootPolicy.Mode.NONE).toJson());
        assertEquals("{\"leaveBossDrops\":false}", CompanionSettings.defaults().withLeaveBossDrops(false).toJson());
        assertEquals("{}", CompanionSettings.defaults().withPotions(70, 50).toJson(), "70/50 keeps following the config");
    }

    @Test
    void parsingIgnoresUnknownKeysAndTakesTheDefaultForEachBadValue() {
        CompanionSettings defaults = CompanionSettings.defaults();

        CompanionSettings parsed = CompanionSettings.parse("{\"hp\":40,\"colour\":\"red\",\"mp\":\"lots\",\"loot\":\"own_kills\","
                + "\"leaveBossDrops\":\"yes\",\"buffs\":false,\"heals\":1,\"buffPotions\":null,\"formation\":\"zigzag\"}");

        assertEquals(40, parsed.hpPercent());
        assertEquals(defaults.mpPercent(), parsed.mpPercent(), "not a number");
        assertEquals(AgentLootPolicy.Mode.OWN_KILLS, parsed.loot(), "any case");
        assertEquals(defaults.leaveBossDrops(), parsed.leaveBossDrops(), "not a boolean");
        assertFalse(parsed.buffs());
        assertEquals(defaults.heals(), parsed.heals());
        assertEquals(defaults.buffPotions(), parsed.buffPotions());
        assertEquals(null, parsed.formation(), "a formation the menu does not offer");

        assertEquals(defaults.hpPercent(), CompanionSettings.parse("{\"hp\":5}").hpPercent(), "under the least");
        assertEquals(defaults.hpPercent(), CompanionSettings.parse("{\"hp\":95}").hpPercent(), "over the most");
        assertEquals(defaults.hpPercent(), CompanionSettings.parse("{\"hp\":40.5}").hpPercent(), "not a whole percent");
        assertEquals(defaults.loot(), CompanionSettings.parse("{\"loot\":\"EVERYTHING\"}").loot());
        assertEquals(defaults, CompanionSettings.parse("{\"hp\":"), "cut short");
    }

    @Test
    void theSupportPhrasesAreTheAgentTogglesOwn() {
        assertTrue(AgentChatCommandClassifier.isSupportOnCommand(CompanionSettings.Support.BUFFS.phrase(true)));
        assertTrue(AgentChatCommandClassifier.isSupportOffCommand(CompanionSettings.Support.BUFFS.phrase(false)));
        assertTrue(AgentChatCommandClassifier.isHealsOnCommand(CompanionSettings.Support.HEALS.phrase(true)));
        assertTrue(AgentChatCommandClassifier.isHealsOffCommand(CompanionSettings.Support.HEALS.phrase(false)));
        assertTrue(AgentChatCommandClassifier.isBuffConsumablesOnCommand(CompanionSettings.Support.BUFF_POTIONS.phrase(true)));
        assertTrue(AgentChatCommandClassifier.isBuffConsumablesOffCommand(CompanionSettings.Support.BUFF_POTIONS.phrase(false)));
        for (CompanionSettings.Support toggle : CompanionSettings.Support.values()) {
            for (boolean on : new boolean[]{true, false}) {
                String phrase = toggle.phrase(on);
                // the chat flows ahead of the toggles (supply requests) never take them
                assertFalse(AgentChatCommandClassifier.isNeedHpPotCommand(phrase), phrase);
                assertFalse(AgentChatCommandClassifier.isNeedMpPotCommand(phrase), phrase);
                assertFalse(AgentChatCommandClassifier.isNeedPotCommand(phrase), phrase);
                assertFalse(AgentChatCommandClassifier.isNeedAmmoCommand(phrase), phrase);
            }
        }
    }
}
