package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mentor and apprentice (docs/BOT_CONCEPT_PLAN.md F4): both off by their kill switches until their live checks pass, the
 * same defaults in code and in the shipped config.yaml, and validate refuses values that would break the offer, the
 * lead, the gift or the adoption.
 */
class LivingWorldConfigMentorTest {

    @Test
    void theDefaultsAreThePlansAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertFalse(config.MENTOR_ENABLED);
        assertFalse(config.APPRENTICE_ENABLED);
        assertEquals(3, config.MENTOR_LEAD_MIN);
        assertEquals(5, config.MENTOR_LEAD_MAX);
        assertEquals(20_000L, config.MENTOR_LEAD_WAIT_MS, "about 20 s beyond a portal (F4.3)");
        assertEquals(10, config.APPRENTICE_ADOPT_MAX_LEVEL);
        List<int[]> gift = config.mentorGiftItems();
        assertEquals(2, gift.size());
        assertArrayEquals(new int[] {2000002, 50}, gift.get(0));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validateRefusesValuesThatBreakTheMentorOrTheApprentice() {
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LEAD_MIN = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LEAD_MAX = 2));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LEAD_MAX = 11));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_OFFER_RETRY_MS = 500));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LEAD_WAIT_MS = 1_000));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LEVEL_DELAY_MAX_MS = 1_000));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_LOW_HP_PERCENT = 95));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_GIFT_ITEMS = "2000002:x"));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.MENTOR_GIFT_ITEMS = "2000002:0"));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.APPRENTICE_ADOPT_MAX_LEVEL = 31));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.APPRENTICE_NEED_CHANCE = 1.5d));
        assertDoesNotThrow(() -> with(c -> {
            c.MENTOR_ENABLED = true;
            c.APPRENTICE_ENABLED = true;
            c.MENTOR_GIFT_ITEMS = "";
            c.MENTOR_TIPS_PER_HOUR = 0;
        }));
        LivingWorldConfig none = new LivingWorldConfig();
        none.MENTOR_GIFT_ITEMS = " ";
        assertTrue(none.mentorGiftItems().isEmpty(), "no gift");
    }

    @Test
    void configYamlShipsTheSameDefaults() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String line : new String[] {"MENTOR_ENABLED: false", "MENTOR_OFFER_DELAY_MS: 8000",
                "MENTOR_OFFER_RETRY_MS: 4000", "MENTOR_OFFER_TRIES: 8", "MENTOR_LEAD_MIN: 3", "MENTOR_LEAD_MAX: 5",
                "MENTOR_LEVEL_DELAY_MIN_MS: 30000", "MENTOR_LEVEL_DELAY_MAX_MS: 300000", "MENTOR_TIPS_PER_HOUR: 4",
                "MENTOR_LOW_HP_PERCENT: 35", "MENTOR_LEAD_WAIT_MS: 20000", "MENTOR_LEAD_TRIES: 2",
                "MENTOR_OUTGROWN_LEVELS: 2", "MENTOR_GIFT_ITEMS: \"2000002:50,2000006:50\"", "MENTOR_ABANDON_DAYS: 14",
                "APPRENTICE_ENABLED: false", "APPRENTICE_ADOPT_MAX_LEVEL: 10", "APPRENTICE_ADOPT_MIN_OWNER_LEVEL: 30",
                "APPRENTICE_NEEDS_MAX: 2", "APPRENTICE_NEED_CHANCE: 0.1"}) {
            assertTrue(yaml.contains("\n    " + line + "\n"), line);
        }
        assertTrue(yaml.contains("\n    # Technical: the mentor is a hire:<characterId> row with living_roster.role MENTOR"));
        assertTrue(yaml.contains("\n    # Technical: living_roster.role APPRENTICE and role_target"));
    }

    private static void with(java.util.function.Consumer<LivingWorldConfig> change) {
        LivingWorldConfig config = new LivingWorldConfig();
        change.accept(config);
        config.validate();
    }
}
