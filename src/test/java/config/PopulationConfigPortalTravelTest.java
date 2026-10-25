package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The population keys for travel on foot (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md 2.4): PORTAL_TRAVEL_ENABLED, the kill
 * switch (on, user decision 2026-10-25); CHECK_MS, how often a move on foot is checked; HOP_TIMEOUT_MS and MAX_MS, when a
 * walk gives up its way and when a move on foot waits for nobody to watch; TOWNLIFE_GRACE_MS and BOARD_MS. config.yaml
 * ships them and says why.
 */
class PopulationConfigPortalTravelTest {

    @Test
    void travelOnFootIsOnByDefault() {
        PopulationConfig config = new PopulationConfig();

        assertTrue(config.PORTAL_TRAVEL_ENABLED);
        assertEquals(1_000, config.PORTAL_TRAVEL_CHECK_MS);
        assertEquals(120_000, config.PORTAL_TRAVEL_HOP_TIMEOUT_MS);
        assertEquals(600_000, config.PORTAL_TRAVEL_MAX_MS);
        assertEquals(30_000, config.PORTAL_TRAVEL_TOWNLIFE_GRACE_MS);
        assertEquals(2_000, config.PORTAL_TRAVEL_BOARD_MS);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aCheckTooOftenOrNegativeTimesFail() {
        PopulationConfig check = new PopulationConfig();
        check.PORTAL_TRAVEL_CHECK_MS = 50;
        assertThrows(IllegalArgumentException.class, check::validate);
        PopulationConfig hop = new PopulationConfig();
        hop.PORTAL_TRAVEL_HOP_TIMEOUT_MS = 0;
        assertThrows(IllegalArgumentException.class, hop::validate);
        PopulationConfig max = new PopulationConfig();
        max.PORTAL_TRAVEL_MAX_MS = 0;
        assertThrows(IllegalArgumentException.class, max::validate);
        PopulationConfig grace = new PopulationConfig();
        grace.PORTAL_TRAVEL_TOWNLIFE_GRACE_MS = -1;
        assertThrows(IllegalArgumentException.class, grace::validate);
        PopulationConfig board = new PopulationConfig();
        board.PORTAL_TRAVEL_BOARD_MS = -1;
        assertThrows(IllegalArgumentException.class, board::validate);
    }

    @Test
    void configYamlShipsTheTravelKeysAndSaysWhy() throws IOException {
        assertTrue(YamlConfig.config.population.PORTAL_TRAVEL_ENABLED);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_ENABLED: true\n"));
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_CHECK_MS: 1000\n"));
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_HOP_TIMEOUT_MS: 120000\n"));
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_MAX_MS: 600000\n"));
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_TOWNLIFE_GRACE_MS: 30000\n"));
        assertTrue(yaml.contains("\n    PORTAL_TRAVEL_BOARD_MS: 2000\n"));
        String comment = yaml.substring(yaml.indexOf("# Citizens travel on foot while someone can see them"),
                yaml.indexOf("PORTAL_TRAVEL_ENABLED:"));
        for (String said : new String[] {"kill switch", "portal", "boarding NPC", "cheap way", "never vanishes",
                "CITIZEN_PORTAL_TRAVEL_PLAN"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
