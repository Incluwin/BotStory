package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.AgentChatToggleHook;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.runtime.AgentRuntimeConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion's settings as settings_json (docs/BOT_MANAGEMENT_PLAN.md P3b): the defaults are today's behaviour and
 * write nothing, every setting survives the round trip, potion percentages the owner chose are kept whatever the
 * configuration says later, and parsing is defensive (unknown keys ignored, a bad value takes its default, unreadable
 * JSON is the defaults). The support toggles are the Agent chat toggles', offered only to a job that has them.
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
        assertTrue(defaults.buffPotions(), "buff potions are on for every Agent (agent-engine.yaml)");
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
                .withSupport(CompanionSettings.Support.BUFF_POTIONS, false)
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
    void onlyWhatDiffersFromTheDefaultsIsWrittenButChosenPotionsAlways() {
        assertEquals("{\"hp\":40,\"mp\":50}", CompanionSettings.defaults().withPotions(40, 50).toJson());
        assertEquals("{\"loot\":\"NONE\"}", CompanionSettings.defaults().withLoot(AgentLootPolicy.Mode.NONE).toJson());
        assertEquals("{\"leaveBossDrops\":false}", CompanionSettings.defaults().withLeaveBossDrops(false).toJson());
        assertEquals("{\"hp\":70,\"mp\":50}", CompanionSettings.defaults().withPotions(70, 50).toJson(),
                "chosen, even at the configured values");
        assertFalse(CompanionSettings.defaults().withPotions(70, 50).potionsAsConfigured());
        assertTrue(CompanionSettings.parse("{\"loot\":\"NONE\"}").potionsAsConfigured(), "never chosen");
    }

    @Test
    void chosenPotionsStayWhenTheConfigurationChangesAndUntouchedOnesFollowIt() {
        float hp = AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH;
        float mp = AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH;
        String chosen = CompanionSettings.defaults().withPotions(70, 50).toJson();
        String other = CompanionSettings.defaults().withPotions(60, 40).withLoot(AgentLootPolicy.Mode.NONE).toJson();
        try {
            AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH = 0.6f; // the tuning moves the default onto one of them
            AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH = 0.4f;

            CompanionSettings kept = CompanionSettings.parse(chosen);
            assertEquals(70, kept.hpPercent(), "the owner's 70% stays 70%");
            assertEquals(50, kept.mpPercent());
            assertFalse(kept.potionsAsConfigured());
            CompanionSettings resaved = CompanionSettings.parse(CompanionSettings.parse(other)
                    .withLoot(AgentLootPolicy.Mode.ALL).toJson());
            assertEquals(60, resaved.hpPercent(), "another field's save keeps a choice the new default equals");
            assertEquals(40, resaved.mpPercent());
            assertFalse(resaved.potionsAsConfigured());
            assertEquals(60, CompanionSettings.parse("{}").hpPercent(), "untouched: follows the configuration");
        } finally {
            AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH = hp;
            AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH = mp;
        }
    }

    @Test
    void theDefaultsStayWithinWhatTheNumberBoxTakes() {
        float hp = AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH;
        float mp = AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH;
        try {
            AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH = 0.95f;
            AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH = 0.05f;

            CompanionSettings defaults = CompanionSettings.defaults();
            assertEquals(CompanionSettings.MAX_POTION_PERCENT, defaults.hpPercent());
            assertEquals(CompanionSettings.MIN_POTION_PERCENT, defaults.mpPercent());
            assertTrue(CompanionSettings.potionPercent(defaults.hpPercent()), "changing only MP is never refused");
            assertTrue(defaults.potionsAsConfigured(), "still drinks as configured until the owner chooses");
        } finally {
            AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH = hp;
            AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH = mp;
        }
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
    void theSupportTogglesAreTheAgentChatTogglesOwn() {
        assertEquals(CompanionSettings.Support.BUFFS, CompanionSettings.Support.of(AgentChatToggleHook.Toggle.SKILL_BUFFS));
        assertEquals(CompanionSettings.Support.HEALS, CompanionSettings.Support.of(AgentChatToggleHook.Toggle.SUPPORT_HEALS));
        assertEquals(CompanionSettings.Support.BUFF_POTIONS,
                CompanionSettings.Support.of(AgentChatToggleHook.Toggle.BUFF_CONSUMABLES));
    }

    @Test
    void healsAreOfferedToTheClericLineAndPartyBuffsToAJobThatHasOne() {
        CompanionSettings.Support heals = CompanionSettings.Support.HEALS;
        CompanionSettings.Support buffs = CompanionSettings.Support.BUFFS;
        for (Job healer : new Job[]{Job.CLERIC, Job.PRIEST, Job.BISHOP}) {
            assertTrue(heals.offeredTo(healer.getId()), healer.name());
        }
        for (Job other : new Job[]{Job.BEGINNER, Job.WARRIOR, Job.FIGHTER, Job.MAGICIAN, Job.FP_WIZARD, Job.HUNTER,
                Job.ASSASSIN, Job.BRAWLER}) {
            assertFalse(heals.offeredTo(other.getId()), other.name());
        }
        // Rage, Iron Will and Hyper Body, Meditation, Bless, Haste from the second job; Maple Warrior at the fourth
        for (Job buffer : new Job[]{Job.FIGHTER, Job.CRUSADER, Job.HERO, Job.SPEARMAN, Job.FP_WIZARD, Job.IL_WIZARD,
                Job.CLERIC, Job.ASSASSIN, Job.HERMIT, Job.BANDIT, Job.PALADIN, Job.BOWMASTER, Job.MARKSMAN, Job.BUCCANEER,
                Job.CORSAIR}) {
            assertTrue(buffs.offeredTo(buffer.getId()), buffer.name());
        }
        for (Job none : new Job[]{Job.BEGINNER, Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT, Job.MAGICIAN, Job.HUNTER,
                Job.RANGER, Job.CROSSBOWMAN, Job.SNIPER, Job.THIEF, Job.PIRATE, Job.BRAWLER, Job.MARAUDER, Job.GUNSLINGER,
                Job.OUTLAW}) {
            assertFalse(buffs.offeredTo(none.getId()), none.name());
        }
        for (CompanionSettings.Support toggle : CompanionSettings.Support.values()) {
            assertEquals(toggle == CompanionSettings.Support.BUFF_POTIONS, toggle.offeredTo(-1), "unknown job: " + toggle);
        }
    }
}
