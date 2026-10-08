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
 * living_world.COMPANION_BOSS_ENABLED_TYPES lists the boss, and only easy_balrog (Mu Young) may be listed.
 * COMPANION_BOSS_SERVER_AUTHORITY (on) lets the server move the boss while companions fight it. The code default is
 * off; config.yaml ships easy_balrog on since its live checks began (2026-10-07), and says why.
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
        assertFalse(config.companionBossEnabled("zakum"));
        assertFalse(config.companionBossEnabled(null));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = " EASY_BALROG ,, ";
        assertTrue(config.companionBossEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);

        config.COMPANION_BOSS_ENABLED_TYPES = null;
        assertFalse(config.companionBossEnabled("easy_balrog"));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anyOtherBossFailsValidation() {
        for (String bad : new String[] {"zakum", "easy_balrog,zakum", "balrog"}) {
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
}
