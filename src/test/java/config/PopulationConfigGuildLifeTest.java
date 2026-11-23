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
 * Guild life (docs/WORLD_SYSTEMS_PLAN.md N5): its three kill switches ship off until live checks U1-U3 pass, the
 * same defaults in code and in the shipped config.yaml with their technical notes, and validate refuses a goal of 0.
 */
class PopulationConfigGuildLifeTest {

    @Test
    void theSwitchesShipOffAndTheKnobsValidate() {
        PopulationConfig config = new PopulationConfig();
        assertFalse(config.GUILD_CHAT_ENABLED);
        assertFalse(config.GUILD_GOALS_ENABLED);
        assertFalse(config.GUILD_LADDER_ENABLED);
        assertEquals(300, config.GUILD_GOAL_GP);
        assertDoesNotThrow(config::validate);
        config.GUILD_GOAL_CLEARS = 0;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.GUILD_GOAL_CLEARS = 6;
        config.GUILD_GOAL_GP = -1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void configYamlShipsTheSameDefaultsWithTheirNotes() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String key : new String[]{"GUILD_CHAT_ENABLED: false", "GUILD_GOALS_ENABLED: false",
                "GUILD_GOAL_GP: 300", "GUILD_GOAL_CLEARS: 6", "GUILD_GOAL_NEEDS: 4", "GUILD_GOAL_WINS: 4",
                "GUILD_GOAL_CHECK_MINUTES: 10"}) {
            int at = yaml.indexOf("\n    " + key + "\n");
            assertTrue(at > 0, key);
            String before = yaml.substring(yaml.lastIndexOf('\n', at - 1) + 1, at);
            assertTrue(before.startsWith("    # Technical: ") && before.length() >= 100, key + ": " + before);
        }
    }
}
