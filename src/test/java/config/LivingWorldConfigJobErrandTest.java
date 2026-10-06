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
 * The companion job errand's living_world keys: COMPANION_JOB_ERRANDS_ENABLED, the kill switch (on); TALK_MS, how long
 * it stands with its instructor; WALK_MAX_MS, how long it walks up to them at most; RETRY_MS, how long one its owner
 * called back waits before it sets off again; SWEEP_MS, how often the errands out are looked at. config.yaml ships them
 * and says why.
 */
class LivingWorldConfigJobErrandTest {

    @Test
    void jobErrandsAreOnByDefault() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertTrue(config.COMPANION_JOB_ERRANDS_ENABLED);
        assertEquals(5_000, config.COMPANION_JOB_ERRAND_TALK_MS);
        assertEquals(45_000, config.COMPANION_JOB_ERRAND_WALK_MAX_MS);
        assertEquals(1_800_000, config.COMPANION_JOB_ERRAND_RETRY_MS);
        assertEquals(30_000, config.COMPANION_JOB_ERRAND_SWEEP_MS);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void negativeTimesAndANonPositiveSweepFail() {
        LivingWorldConfig talk = new LivingWorldConfig();
        talk.COMPANION_JOB_ERRAND_TALK_MS = -1;
        assertThrows(IllegalArgumentException.class, talk::validate);
        LivingWorldConfig walk = new LivingWorldConfig();
        walk.COMPANION_JOB_ERRAND_WALK_MAX_MS = -1;
        assertThrows(IllegalArgumentException.class, walk::validate);
        LivingWorldConfig retry = new LivingWorldConfig();
        retry.COMPANION_JOB_ERRAND_RETRY_MS = -1;
        assertThrows(IllegalArgumentException.class, retry::validate);
        LivingWorldConfig sweep = new LivingWorldConfig();
        sweep.COMPANION_JOB_ERRAND_SWEEP_MS = 0;
        assertThrows(IllegalArgumentException.class, sweep::validate);
    }

    @Test
    void configYamlShipsTheJobErrandKeysAndSaysWhy() throws IOException {
        LivingWorldConfig living = YamlConfig.config.living_world;
        assertTrue(living.COMPANION_JOB_ERRANDS_ENABLED);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_JOB_ERRANDS_ENABLED: true\n"));
        assertTrue(yaml.contains("\n    COMPANION_JOB_ERRAND_TALK_MS: 5000\n"));
        assertTrue(yaml.contains("\n    COMPANION_JOB_ERRAND_WALK_MAX_MS: 45000\n"));
        assertTrue(yaml.contains("\n    COMPANION_JOB_ERRAND_RETRY_MS: 1800000\n"));
        assertTrue(yaml.contains("\n    COMPANION_JOB_ERRAND_SWEEP_MS: 30000\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions go for their next job by themselves"),
                yaml.indexOf("COMPANION_JOB_ERRANDS_ENABLED:"));
        for (String said : new String[] {"kill switch", "persona", "instructor", "owner", "AgentJobPath"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
