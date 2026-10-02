package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PQ clear time's two living_world keys: COMPANION_PQ_CLEAR_TIME, the kill switch (on by default; off gives the
 * entry NPC's window and lineup exactly as before), and COMPANION_PQ_PACE_EXCLUDED_LEADERS, the leaders whose runs teach
 * the records no leader term. config.yaml lists 2 (ohboy) and 865 (Crewfriend), the characters the scripted test driver
 * leads with, and says why.
 */
class LivingWorldConfigPqClearTimeTest {

    @Test
    void theClearTimeIsOnAndEveryLeaderIsLearnedFromByDefault() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertTrue(config.COMPANION_PQ_CLEAR_TIME);
        assertEquals("", config.COMPANION_PQ_PACE_EXCLUDED_LEADERS);
        assertEquals(Set.of(), config.companionPqPaceExcludedLeaders());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void excludedLeadersAreCharacterIdsWithBlanksAndSpacesIgnored() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_PQ_PACE_EXCLUDED_LEADERS = " 865, 2 ,, ";

        assertEquals(Set.of(2, 865), config.companionPqPaceExcludedLeaders());
        assertDoesNotThrow(config::validate);
        config.COMPANION_PQ_PACE_EXCLUDED_LEADERS = null;
        assertEquals(Set.of(), config.companionPqPaceExcludedLeaders());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void anythingButACharacterIdFailsValidation() {
        for (String bad : new String[] {"2,ohboy", "0", "-3", "2.5", "12345678901"}) {
            LivingWorldConfig config = new LivingWorldConfig();
            config.COMPANION_PQ_PACE_EXCLUDED_LEADERS = bad;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate, bad);
            assertTrue(refused.getMessage().startsWith(
                    "living_world.COMPANION_PQ_PACE_EXCLUDED_LEADERS must list character ids"), refused.getMessage());
        }
    }

    @Test
    void configYamlShipsTheClearTimeOnAndExcludesTheScriptedDriversLeaders() throws IOException {
        LivingWorldConfig living = YamlConfig.config.living_world;
        assertTrue(living.COMPANION_PQ_CLEAR_TIME);
        assertEquals(Set.of(2, 865), living.companionPqPaceExcludedLeaders());
        assertDoesNotThrow(living::validate);

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_PQ_CLEAR_TIME: true\n"), "living_world carries the kill switch, on");
        assertTrue(yaml.contains("\n    COMPANION_PQ_PACE_EXCLUDED_LEADERS: \"2,865\"\n"));
        String comment = yaml.substring(yaml.indexOf("# CLEAR_TIME:"), yaml.indexOf("COMPANION_PQ_ENABLED_KEYS:"));
        for (String said : new String[] {"2 (ohboy)", "865 (Crewfriend)", "scripted test driver", "kill switch"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
