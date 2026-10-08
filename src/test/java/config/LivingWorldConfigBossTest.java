package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P7 kill switch (docs/BOT_MANAGEMENT_PLAN.md flow 4): boss expeditions with companions are off unless
 * living_world.COMPANION_BOSS_ENABLED_TYPES lists the boss, and only easy_balrog (Mu Young) or zakum (Adobis, P8) may
 * be listed. COMPANION_BOSS_SERVER_AUTHORITY (on) lets the server move the boss while companions fight it. The code
 * default is off; config.yaml ships easy_balrog on since its live checks began (2026-10-07), and says why, and zakum
 * off until its live checks pass. Part B: bench allies
 * need COMPANION_BOSS_ALLY_TYPES to list the boss as well, and COMPANION_BOSS_OWNERS_MAX ships at 1 (Part A's
 * one-owner rule).
 */
class LivingWorldConfigBossTest {

    @Test
    void defaultsAreOffWithServerAuthorityOn() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertEquals("", config.COMPANION_BOSS_ENABLED_TYPES);
        assertFalse(config.companionBossEnabled("easy_balrog"));
        assertTrue(config.COMPANION_BOSS_SERVER_AUTHORITY);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aListedTypeIsEnabledInAnyCaseAndSpacing() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";

        assertTrue(config.companionBossEnabled("easy_balrog"));
        assertTrue(config.companionBossEnabled(" Easy_Balrog "));
        assertFalse(config.companionBossEnabled("zakum"), "a known boss the switch does not list stays off");
        assertFalse(config.companionBossEnabled(null));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = " EASY_BALROG ,, ";
        assertTrue(config.companionBossEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,zakum";
        assertTrue(config.companionBossEnabled("easy_balrog"));
        assertTrue(config.companionBossEnabled(" Zakum "));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        assertTrue(config.companionBossEnabled("zakum"));
        assertFalse(config.companionBossEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = null;
        assertFalse(config.companionBossEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anyOtherBossFailsValidation() {
        for (String bad : new String[] {"balrog", "easy_balrog,balrog", "zakum,horntail", "zakum_easy"}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_ENABLED_TYPES = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, bad);
            assertEquals(CompanionBossKeys.message(), refused.getMessage());
        }
    }

    @Test
    void configYamlShipsEasyBalrogOnWithServerAuthorityAndSaysWhy() throws IOException {
        LivingWorldConfig living = YamlConfig.config.living_world;
        assertEquals("easy_balrog", living.COMPANION_BOSS_ENABLED_TYPES);
        assertTrue(living.companionBossEnabled("easy_balrog"), "on for the Easy Balrog live checks");
        assertTrue(living.COMPANION_BOSS_SERVER_AUTHORITY);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_BOSS_ENABLED_TYPES: \"easy_balrog\"\n"));
        assertTrue(yaml.contains("\n    COMPANION_BOSS_SERVER_AUTHORITY: true\n"));
        String comment = yaml.substring(yaml.indexOf("# Boss expeditions with companions"),
                yaml.indexOf("COMPANION_BOSS_ENABLED_TYPES:"));
        for (String said : new String[] {"kill switch", "Mu Young", "Poison Golem"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }

    // P7 Part B: bench allies and second owners. Both ship off, so every Part A path stays as it is.

    @Test
    void allyKeysShipOffWithOneOwner() {
        LivingWorldConfig shipped = YamlConfig.config.living_world;
        for (LivingWorldConfig config : new LivingWorldConfig[] {new LivingWorldConfig(), shipped}) {
            assertEquals("", config.COMPANION_BOSS_ALLY_TYPES);
            assertEquals(1, config.COMPANION_BOSS_OWNERS_MAX, "Part A's one-owner rule");
            assertEquals(2, config.COMPANION_BOSS_HUMAN_SEATS);
            assertEquals(20000, config.COMPANION_BOSS_ALLY_WALK_OUT_MS);
            assertFalse(config.companionBossAlliesEnabled("easy_balrog"));
            assertDoesNotThrow(config::validate);
        }
    }

    @Test
    void alliesNeedBothSwitches() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_BOSS_ALLY_TYPES = "easy_balrog";
        assertFalse(config.companionBossAlliesEnabled("easy_balrog"), "allies listed, the boss itself off");

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        config.COMPANION_BOSS_ALLY_TYPES = "";
        assertFalse(config.companionBossAlliesEnabled("easy_balrog"), "the boss on, allies off");
        assertTrue(config.companionBossEnabled("easy_balrog"));

        config.COMPANION_BOSS_ALLY_TYPES = " Easy_Balrog ,, ";
        assertTrue(config.companionBossAlliesEnabled("easy_balrog"));
        assertTrue(config.companionBossAlliesEnabled(" EASY_BALROG "));
        assertFalse(config.companionBossAlliesEnabled("zakum"));
        assertFalse(config.companionBossAlliesEnabled(null));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ALLY_TYPES = null;
        assertFalse(config.companionBossAlliesEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anAllyTypeNotEnabledIsRejected() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_BOSS_ALLY_TYPES = " easy_balrog ";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("living_world.COMPANION_BOSS_ALLY_TYPES must list only bosses COMPANION_BOSS_ENABLED_TYPES"
                + " also lists, not \"easy_balrog\"", refused.getMessage());

        config.COMPANION_BOSS_ENABLED_TYPES = "EASY_BALROG";
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ALLY_TYPES = "easy_balrog,zakum";
        refused = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("living_world.COMPANION_BOSS_ALLY_TYPES must list only bosses COMPANION_BOSS_ENABLED_TYPES"
                + " also lists, not \"zakum\"", refused.getMessage());

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,zakum";
        assertDoesNotThrow(config::validate);
        assertTrue(config.companionBossAlliesEnabled("zakum"));
    }

    @Test
    void anUnknownAllyTypeIsRejected() {
        for (String bad : new String[] {"balrog", "easy_balrog,balrog", "horntail"}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
            config.COMPANION_BOSS_ALLY_TYPES = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, bad);
            String unknown = bad.substring(bad.lastIndexOf(',') + 1);
            assertEquals("living_world.COMPANION_BOSS_ALLY_TYPES must list boss keys (" + String.join(", ",
                    CompanionBossKeys.KNOWN) + "), not \"" + unknown + "\"", refused.getMessage());
        }
    }

    @Test
    void ownersMaxOutsideOneToSixIsRejected() {
        for (int bad : new int[] {-1, 0, 7}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_OWNERS_MAX = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, "" + bad);
            assertEquals("living_world.COMPANION_BOSS_OWNERS_MAX must be within 1..6", refused.getMessage());
        }
        for (int good : new int[] {1, 2, 6}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_OWNERS_MAX = good;
            assertDoesNotThrow(config::validate, "" + good);
        }
    }

    @Test
    void humanSeatsOutsideZeroTo25IsRejected() {
        for (int bad : new int[] {-1, 26}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_HUMAN_SEATS = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, "" + bad);
            assertEquals("living_world.COMPANION_BOSS_HUMAN_SEATS must be within 0..25", refused.getMessage());
        }
        for (int good : new int[] {0, 2, 25}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_HUMAN_SEATS = good;
            assertDoesNotThrow(config::validate, "" + good);
        }
    }

    @Test
    void allyWalkOutOutsideRangeIsRejected() {
        for (int bad : new int[] {0, 999, 120001}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_ALLY_WALK_OUT_MS = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, "" + bad);
            assertEquals("living_world.COMPANION_BOSS_ALLY_WALK_OUT_MS must be within 1000..120000", refused.getMessage());
        }
        for (int good : new int[] {1000, 20000, 120000}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BOSS_ALLY_WALK_OUT_MS = good;
            assertDoesNotThrow(config::validate, "" + good);
        }
    }

    @Test
    void theYamlCarriesTheFourKeys() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        String[] keys = {"\n    COMPANION_BOSS_ALLY_TYPES: \"\"\n", "\n    COMPANION_BOSS_OWNERS_MAX: 1\n",
                "\n    COMPANION_BOSS_HUMAN_SEATS: 2\n", "\n    COMPANION_BOSS_ALLY_WALK_OUT_MS: 20000\n"};
        int after = yaml.indexOf("\n    COMPANION_BOSS_SERVER_AUTHORITY: true\n");
        assertTrue(after > 0);
        for (String key : keys) {
            int at = yaml.indexOf(key);
            assertTrue(at > after, key.trim() + " appended after Part A's keys");
            after = at;
        }

        String comment = yaml.substring(yaml.indexOf("# Bench allies and second owners"),
                yaml.indexOf("COMPANION_BOSS_ALLY_TYPES:")).replace("\n    #", "");
        for (String said : new String[] {"Call allies from my bench...", "COMPANION_BOSS_ENABLED_TYPES", "ROSTER_MAX - 5",
                "RECALL_MAX_ETA_MS", "EXPEDITION_BURST_MAX", "kill switch", "OWNERS_MAX:", "Part A's one-owner rule",
                "HUMAN_SEATS:", "ALLY_WALK_OUT_MS:", "outPerrion"}) {
            assertTrue(comment.contains(said), said + " in: " + comment);
        }
    }
}
