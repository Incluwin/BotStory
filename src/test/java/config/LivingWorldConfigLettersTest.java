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
 * Letters (docs/BOT_CONCEPT_PLAN.md F3): off by its kill switch until its live checks pass, the same defaults in code and
 * in the shipped config.yaml, and validate refuses values that would break the digest, the gifts or the asks.
 */
class LivingWorldConfigLettersTest {

    @Test
    void theDefaultsAreThePlansAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertFalse(config.LETTERS_ENABLED);
        assertEquals(5, config.LETTERS_MAX_NOTES);
        assertEquals(10, config.LETTERS_EQUIP_LEVEL_SPAN);
        assertEquals(4, config.LETTERS_GIFT_FRIENDSHIP_LEVEL);
        assertEquals(1, config.LETTERS_GIFTS_PER_WEEK);
        assertEquals(40, config.LETTERS_ASK_WARMTH);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validateRefusesValuesThatBreakTheLetters() {
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_MAX_NOTES = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_GIFT_FRIENDSHIP_LEVEL = 11));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_GIFT_CHANCE = 1.5d));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_ASK_CHANCE = -0.1d));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_ASK_WARMTH = 101));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.LETTERS_DELAY_MS = -1));
        assertDoesNotThrow(() -> with(c -> {
            c.LETTERS_ENABLED = true;
            c.LETTERS_GIFTS_PER_WEEK = 0;
        }));
    }

    @Test
    void configYamlShipsTheSameDefaults() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String line : new String[] {"LETTERS_ENABLED: false", "LETTERS_DELAY_MS: 6000", "LETTERS_MAX_NOTES: 5",
                "LETTERS_EQUIP_LEVEL_SPAN: 10", "LETTERS_GIFT_FRIENDSHIP_LEVEL: 4", "LETTERS_GIFT_CHANCE: 0.03",
                "LETTERS_GIFTS_PER_WEEK: 1", "LETTERS_ASK_WARMTH: 40", "LETTERS_ASK_CHANCE: 0.1", "LETTERS_ASK_GAP_HOURS: 6"}) {
            assertTrue(yaml.contains("\n    " + line + "\n"), line);
        }
        assertTrue(yaml.contains("\n    # Technical: bench work is written as bench.report rows"));
    }

    private static void with(java.util.function.Consumer<LivingWorldConfig> change) {
        LivingWorldConfig config = new LivingWorldConfig();
        change.accept(config);
        config.validate();
    }
}
