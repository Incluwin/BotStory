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
 * The companions' buddy requests' living_world keys (P9): COMPANION_BUDDY_REQUESTS, off in code and on in the shipped
 * config.yaml since its live check passed (2026-10-07); FRIENDSHIP_LEVEL, the friendship a Resident asks at (4,
 * "Friend"); HIRE_PARTY_MINUTES, the party time a hired hand asks after (30, as a citizen). config.yaml ships them and
 * says why.
 */
class LivingWorldConfigBuddyTest {

    @Test
    void buddyRequestsAreOffByDefaultAtFriendAndHalfAnHour() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertFalse(config.COMPANION_BUDDY_REQUESTS);
        assertEquals(4, config.COMPANION_BUDDY_FRIENDSHIP_LEVEL);
        assertEquals(30, config.COMPANION_BUDDY_HIRE_PARTY_MINUTES);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aLevelOutsideTheMeterOrNoMinutesFail() {
        for (int level : new int[] {0, 11}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_BUDDY_FRIENDSHIP_LEVEL = level;
            assertThrows(IllegalArgumentException.class, config::validate, "level " + level);
        }
        LivingWorldConfig minutes = new LivingWorldConfig();
        minutes.COMPANION_BUDDY_HIRE_PARTY_MINUTES = 0;
        assertThrows(IllegalArgumentException.class, minutes::validate);
    }

    @Test
    void configYamlShipsTheBuddyKeysAndSaysWhy() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_BUDDY_REQUESTS: true\n"));
        assertTrue(yaml.contains("\n    COMPANION_BUDDY_FRIENDSHIP_LEVEL: 4\n"));
        assertTrue(yaml.contains("\n    COMPANION_BUDDY_HIRE_PARTY_MINUTES: 30\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions ask you to be buddies"),
                yaml.indexOf("COMPANION_BUDDY_REQUESTS:"));
        for (String said : new String[] {"kill switch", "Friend", "hired hand", "Crew", "offline"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
