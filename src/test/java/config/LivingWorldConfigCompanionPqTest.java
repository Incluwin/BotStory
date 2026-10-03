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
 * The P6 kill switch (docs/BOT_MANAGEMENT_PLAN.md P6): party quests with companions are off unless
 * living_world.COMPANION_PQ_ENABLED_KEYS lists the quest, and only kpq (Lakelis) and hpq (Tory, P6b HPQ step 6) may be
 * listed. config.yaml ships both keys, still with kpq only until the HPQ live checks pass, and stays LF and US-ASCII
 * (yamlbeans is strict: a key without its field would not parse).
 */
class LivingWorldConfigCompanionPqTest {

    @Test
    void defaultsAreOff() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertEquals("", config.COMPANION_PQ_ENABLED_KEYS);
        assertFalse(config.companionPqEnabled("kpq"));
        assertTrue(config.COMPANION_PQ_GUIDANCE);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void aListedKeyIsEnabledInAnyCaseAndSpacing() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_PQ_ENABLED_KEYS = " KPQ ";

        assertTrue(config.companionPqEnabled("kpq"));
        assertFalse(config.companionPqEnabled("hpq"));
        assertFalse(config.companionPqEnabled(null));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void hpqMayBeListedWithKpqOrAlone() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_PQ_ENABLED_KEYS = "kpq, HPQ";

        assertDoesNotThrow(config::validate);
        assertTrue(config.companionPqEnabled("kpq"));
        assertTrue(config.companionPqEnabled("hpq"));

        config.COMPANION_PQ_ENABLED_KEYS = "hpq";

        assertDoesNotThrow(config::validate);
        assertFalse(config.companionPqEnabled("kpq"));
        assertTrue(config.companionPqEnabled("hpq"));
    }

    @Test
    void anyOtherQuestFailsValidation() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq,lpq";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("living_world.COMPANION_PQ_ENABLED_KEYS must list only kpq or hpq", refused.getMessage());
    }

    @Test
    void configYamlShipsKpqOnAndStaysAsciiAndLf() throws IOException {
        LivingWorldConfig living = YamlConfig.config.living_world;
        assertEquals("kpq", living.COMPANION_PQ_ENABLED_KEYS);
        assertTrue(living.companionPqEnabled("kpq"));
        assertFalse(living.companionPqEnabled("hpq"), "Tory stays stock until the user switches her on");
        assertTrue(living.COMPANION_PQ_GUIDANCE);

        byte[] bytes = Files.readAllBytes(Path.of("config.yaml"));
        String yaml = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_PQ_ENABLED_KEYS: \"kpq\"\n"), "living_world carries the kill switch, on for Lakelis");
        assertTrue(yaml.contains("\n    COMPANION_PQ_GUIDANCE: true\n"));
        for (byte b : bytes) {
            assertTrue(b >= 0, "config.yaml stays US-ASCII");
            assertTrue(b != '\r', "config.yaml stays LF");
        }
    }
}
