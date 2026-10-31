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
 * Personal story arcs and attended job errands (docs/BOT_CONCEPT_PLAN.md F6.1-F6.3, F6.6): both off by their kill
 * switches until their live checks pass, the same defaults in code and in the shipped config.yaml, and validate refuses
 * values that would break a scene, the race or the ask.
 */
class LivingWorldConfigStoryArcsTest {

    @Test
    void theDefaultsAreOffAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertFalse(config.PERSONAL_ARCS_ENABLED);
        assertFalse(config.JOB_ERRAND_ATTEND_ENABLED);
        assertEquals(15, config.STORY_SCENE_MAX_MINUTES);
        assertEquals(10, config.STORY_RACE_EVEN_PERCENT);
        assertEquals(60, config.JOB_ERRAND_ATTEND_MAX_MINUTES);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validateRefusesValuesThatBreakASceneTheRaceOrTheAsk() {
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.STORY_SCENE_MAX_MINUTES = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.STORY_SCENE_MAX_MINUTES = 61));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.STORY_LINE_MIN_MS = 100));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.STORY_RACE_EVEN_PERCENT = 51));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.STORY_CALM_RANGE_PX = -1));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.JOB_ERRAND_ATTEND_MAX_MINUTES = 4));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.JOB_ERRAND_ATTEND_ASK_TRIES = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.JOB_ERRAND_ATTEND_ASK_RETRY_MS = 500));
        assertDoesNotThrow(() -> with(c -> {
            c.PERSONAL_ARCS_ENABLED = true;
            c.JOB_ERRAND_ATTEND_ENABLED = true;
            c.STORY_RACE_EVEN_PERCENT = 0;
        }));
    }

    @Test
    void configYamlShipsTheSameDefaults() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String line : new String[] {"PERSONAL_ARCS_ENABLED: false", "STORY_CALM_RANGE_PX: 400",
                "STORY_LINE_MIN_MS: 1800", "STORY_SCENE_MAX_MINUTES: 15", "STORY_RACE_EVEN_PERCENT: 10",
                "JOB_ERRAND_ATTEND_ENABLED: false", "JOB_ERRAND_ATTEND_MAX_MINUTES: 60", "JOB_ERRAND_ATTEND_ASK_TRIES: 6",
                "JOB_ERRAND_ATTEND_ASK_RETRY_MS: 5000"}) {
            assertTrue(yaml.contains("\n    " + line + "\n"), line);
        }
        assertTrue(yaml.contains("\n    # Technical: server.living.story (StoryArcs, StoryState, StoryScenes)"));
        assertTrue(yaml.contains("\n    # Technical: server.living.story.ErrandAttend at JobErrands.leave"));
    }

    private static void with(java.util.function.Consumer<LivingWorldConfig> change) {
        LivingWorldConfig config = new LivingWorldConfig();
        change.accept(config);
        config.validate();
    }
}
