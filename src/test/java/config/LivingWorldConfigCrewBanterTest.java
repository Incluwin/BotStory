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
 * Crew ties and banter (docs/BOT_CONCEPT_PLAN.md F6.4): off by its kill switch until its live checks pass, the same
 * defaults in code and in the shipped config.yaml, and validate refuses values that would break the cap or the ties.
 */
class LivingWorldConfigCrewBanterTest {

    @Test
    void theDefaultsAreThePlansAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertFalse(config.CREW_BANTER_ENABLED);
        assertEquals(10, config.CREW_BANTER_GAP_MINUTES, "at most one exchange per 10 min of party time (F6)");
        assertEquals(20_000L, config.CREW_BANTER_TICK_MS);
        assertEquals(3, config.CREW_TIE_CLEAR_POINTS);
        assertEquals(40, config.CREW_TIE_FRIEND_AFFINITY);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validateRefusesValuesThatBreakTheCapOrTheTies() {
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_BANTER_GAP_MINUTES = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_BANTER_TICK_MS = 1_000L));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_BANTER_CHANCE = 1.5d));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_BANTER_OWNER_IDLE_MS = 10_000L));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_BANTER_CALM_RANGE_PX = 50));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_TIE_CLEAR_POINTS = -1));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.CREW_TIE_FRIEND_AFFINITY = 0));
        assertDoesNotThrow(() -> with(c -> {
            c.CREW_BANTER_ENABLED = true;
            c.CREW_BANTER_CHANCE = 1d;
            c.CREW_TIE_CLEAR_POINTS = 0;
        }));
    }

    @Test
    void configYamlShipsTheSameDefaults() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String line : new String[] {"CREW_BANTER_ENABLED: false", "CREW_BANTER_TICK_MS: 20000",
                "CREW_BANTER_GAP_MINUTES: 10", "CREW_BANTER_CHANCE: 0.25", "CREW_BANTER_AFTER_CLEAR_WINDOW_MS: 300000",
                "CREW_BANTER_OWNER_IDLE_MS: 300000", "CREW_BANTER_CALM_RANGE_PX: 400", "CREW_TIE_CLEAR_POINTS: 3",
                "CREW_TIE_FRIEND_AFFINITY: 40"}) {
            assertTrue(yaml.contains("\n    " + line + "\n"), line);
        }
        assertTrue(yaml.contains("\n    # Technical: server.living.crew.CrewBanter on a CREW_BANTER_TICK_MS companion-ops"));
        assertTrue(yaml.contains("Measuring only and not behind this switch: each companion"),
                "the damage share is named as ungated measuring");
    }

    private static void with(java.util.function.Consumer<LivingWorldConfig> change) {
        LivingWorldConfig config = new LivingWorldConfig();
        change.accept(config);
        config.validate();
    }
}
