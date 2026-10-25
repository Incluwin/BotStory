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
 * The ticket errand's living_world keys (2026-10-25): COMPANION_TICKET_ERRANDS_ENABLED, the kill switch (on: a
 * companion goes only when its owner asks), and COMPANION_TICKET_ERRAND_MIN_LEVEL, never below the Amorian Challenge's
 * own 40.
 */
class LivingWorldConfigTicketErrandTest {

    @Test
    void ticketErrandsAreOnFromLevel40() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertTrue(config.COMPANION_TICKET_ERRANDS_ENABLED);
        assertEquals(40, config.COMPANION_TICKET_ERRAND_MIN_LEVEL);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aLevelBelowTheChallengesOwnOrPastTheCapFails() {
        LivingWorldConfig low = new LivingWorldConfig();
        low.COMPANION_TICKET_ERRAND_MIN_LEVEL = 39;
        assertThrows(IllegalArgumentException.class, low::validate);
        LivingWorldConfig high = new LivingWorldConfig();
        high.COMPANION_TICKET_ERRAND_MIN_LEVEL = 201;
        assertThrows(IllegalArgumentException.class, high::validate);
    }

    @Test
    void configYamlShipsTheTicketErrandKeysAndSaysWhy() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_TICKET_ERRANDS_ENABLED: true\n"));
        assertTrue(yaml.contains("\n    COMPANION_TICKET_ERRAND_MIN_LEVEL: 40\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions earn their own Entrance Ticket"),
                yaml.indexOf("COMPANION_TICKET_ERRANDS_ENABLED:"));
        for (String said : new String[] {"kill switch", "Amos the Strong", "Lip Lock Keys", "ledger", "TicketErrands"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
