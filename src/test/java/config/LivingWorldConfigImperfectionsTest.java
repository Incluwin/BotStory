package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * living_world.IMPERFECTIONS_ENABLED (2026-10-27, docs/BOT_CONCEPT_PLAN.md F8): the kill switch is off in code and in
 * config.yaml until the live checks pass, its settings are explained, and the caps the user asked for cannot be
 * configured away (a companion AFK over 90 s, a potion point under 0.45).
 */
class LivingWorldConfigImperfectionsTest {

    @Test
    void imperfectionsAreOffByDefaultAndInConfigYaml() throws IOException {
        assertFalse(new LivingWorldConfig().IMPERFECTIONS_ENABLED);
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    IMPERFECTIONS_ENABLED: false\n"));
        String comment = yaml.substring(yaml.indexOf("# Small imperfections (2026-10-27"),
                yaml.indexOf("IMPERFECTIONS_ENABLED:"));
        String technical = comment.substring(comment.indexOf("# Technical:")).replace("\n    #", "");
        assertTrue(technical.length() >= 100, technical);
        for (String said : new String[] {"/runtime/imperfections", "tells-report.ps1", "kill switch"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }

    @Test
    void theUsersCapsCannotBeConfiguredAway() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.validate();
        config.IMPERFECTIONS_COMPANION_AFK_MAX_MS = 120_000;
        assertThrows(IllegalArgumentException.class, config::validate);
        config = new LivingWorldConfig();
        config.IMPERFECTIONS_POTION_FLOOR = 0.30;
        assertThrows(IllegalArgumentException.class, config::validate);
        config = new LivingWorldConfig();
        config.IMPERFECTIONS_LATE_POTION_CHANCE = 1.5;
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
