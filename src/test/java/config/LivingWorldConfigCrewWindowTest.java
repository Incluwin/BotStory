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
 * The native Crew window's switch (P10, docs/BOT_MANAGEMENT_P10_PLAN.md safety rule 3): living_world.CREW_WINDOW_NATIVE
 * is off in code and in the shipped config.yaml; "gm" lets GM characters use the window, "all" everyone; anything else
 * fails validation. The server section ships the window's packet budget, PACKET_CREW_RATE_PER_SECOND.
 */
class LivingWorldConfigCrewWindowTest {

    @Test
    void theWindowIsOffInCodeAndGmOrAllTurnItOn() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals("off", config.CREW_WINDOW_NATIVE);
        assertFalse(config.crewWindowNative(false));
        assertFalse(config.crewWindowNative(true));
        assertDoesNotThrow(config::validate);

        config.CREW_WINDOW_NATIVE = "gm";
        assertTrue(config.crewWindowNative(true));
        assertFalse(config.crewWindowNative(false));
        assertDoesNotThrow(config::validate);

        config.CREW_WINDOW_NATIVE = " ALL ";
        assertTrue(config.crewWindowNative(true));
        assertTrue(config.crewWindowNative(false));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anyOtherValueFailsValidation() {
        for (String bad : new String[] {"on", "true", "", "gms"}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.CREW_WINDOW_NATIVE = bad;
            assertFalse(config.crewWindowNative(true), bad);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::validate, bad);
            assertEquals("living_world.CREW_WINDOW_NATIVE must be off, gm or all", failure.getMessage());
        }
        LivingWorldConfig missing = new LivingWorldConfig();
        missing.CREW_WINDOW_NATIVE = null;
        assertFalse(missing.crewWindowNative(true));
        assertThrows(IllegalArgumentException.class, missing::validate);
    }

    @Test
    void configYamlShipsTheWindowOffAndItsPacketBudget() throws IOException {
        assertEquals("off", YamlConfig.config.living_world.CREW_WINDOW_NATIVE);
        assertEquals(10, YamlConfig.config.server.PACKET_CREW_RATE_PER_SECOND);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    CREW_WINDOW_NATIVE: \"off\"\n"));
        assertTrue(yaml.contains("\n    PACKET_CREW_RATE_PER_SECOND: 10 "));
        String comment = yaml.substring(yaml.indexOf("# Native Crew window (P10"), yaml.indexOf("CREW_WINDOW_NATIVE:"));
        for (String said : new String[] {"gm = GM characters only", "!world crewwindow", "work as before"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
