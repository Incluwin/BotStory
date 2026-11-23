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
 * Companion traits (docs/WORLD_SYSTEMS_PLAN.md N9): off by its kill switch until live checks T1-T4 pass, the same
 * defaults in code and in the shipped config.yaml with their technical notes, and validate refuses a TRADER bar of 0.
 */
class LivingWorldConfigTraitsTest {

    @Test
    void theDefaultsShipOffAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertFalse(config.TRAITS_ENABLED);
        assertEquals(3_000_000L, config.TRAITS_TRADER_MESO);
        assertDoesNotThrow(config::validate);
        config.TRAITS_TRADER_MESO = 0L;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void configYamlShipsTheSameDefaultsWithTheirNotes() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        assertTrue(yaml.contains("\n    TRAITS_ENABLED: false\n"));
        assertTrue(yaml.contains("\n    TRAITS_TRADER_MESO: 3000000\n"));
        assertTrue(yaml.contains("\n    # Technical: CompanionTraits installs AgentImperfections.Wariness at LivingWorld.start"));
        assertTrue(yaml.contains("\n    # Technical: Traits.of makes a companion a TRADER once its bench.report rows"));
    }
}
