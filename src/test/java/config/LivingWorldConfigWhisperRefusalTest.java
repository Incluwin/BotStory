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
 * living_world.COMPANION_WHISPER_REFUSAL_GAP_MS (2026-10-10): the least time between two of one companion's whispered
 * "can't right now" lines to its owner. 5 s in code and in the shipped config.yaml, which says why; never negative.
 */
class LivingWorldConfigWhisperRefusalTest {

    @Test
    void theGapIsFiveSecondsAndNeverNegative() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals(5000, config.COMPANION_WHISPER_REFUSAL_GAP_MS);
        assertDoesNotThrow(config::validate);

        config.COMPANION_WHISPER_REFUSAL_GAP_MS = 0;
        assertDoesNotThrow(config::validate);
        config.COMPANION_WHISPER_REFUSAL_GAP_MS = -1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void configYamlShipsTheGapAndSaysWhy() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_WHISPER_REFUSAL_GAP_MS: 5000\n"));
        String comment = yaml.substring(yaml.indexOf("# An owner's whisper to a companion that reaches no Agent"),
                yaml.indexOf("COMPANION_WHISPER_REFUSAL_GAP_MS:"));
        for (String said : new String[] {"bench slice", "call it back", "Strangers", "spam"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
