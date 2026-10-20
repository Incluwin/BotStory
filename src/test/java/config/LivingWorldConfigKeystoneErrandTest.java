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
 * The keystone errand's living_world keys (2026-10-19): COMPANION_KEYSTONE_ERRANDS_ENABLED, the kill switch (on: a
 * companion goes only when its owner asks), and COMPANION_KEYSTONE_ERRAND_MIN_LEVEL, never below Lukan's own 70.
 */
class LivingWorldConfigKeystoneErrandTest {

    @Test
    void keystoneErrandsAreOnFromLevel85() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertTrue(config.COMPANION_KEYSTONE_ERRANDS_ENABLED);
        assertEquals(85, config.COMPANION_KEYSTONE_ERRAND_MIN_LEVEL);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aLevelBelowLukansOwnGateOrPastTheCapFails() {
        LivingWorldConfig low = new LivingWorldConfig();
        low.COMPANION_KEYSTONE_ERRAND_MIN_LEVEL = 69;
        assertThrows(IllegalArgumentException.class, low::validate);
        LivingWorldConfig high = new LivingWorldConfig();
        high.COMPANION_KEYSTONE_ERRAND_MIN_LEVEL = 201;
        assertThrows(IllegalArgumentException.class, high::validate);
    }

    @Test
    void configYamlShipsTheKeystoneErrandKeysAndSaysWhy() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_KEYSTONE_ERRANDS_ENABLED: true\n"));
        assertTrue(yaml.contains("\n    COMPANION_KEYSTONE_ERRAND_MIN_LEVEL: 85\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions earn their own Crimsonwood Keystone"),
                yaml.indexOf("COMPANION_KEYSTONE_ERRANDS_ENABLED:"));
        for (String said : new String[] {"kill switch", "Lukan", "8223", "ledger", "KeystoneErrands"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
