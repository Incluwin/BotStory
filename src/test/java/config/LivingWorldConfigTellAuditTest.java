package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * living_world.TELL_AUDIT_ENABLED (2026-10-26, docs/BOT_CONCEPT_PLAN.md C2): the tell audit's kill switch ships off, in
 * code and in config.yaml, until the coordinator turns it on for the live baseline.
 */
class LivingWorldConfigTellAuditTest {

    @Test
    void theAuditIsOffByDefault() {
        assertFalse(new LivingWorldConfig().TELL_AUDIT_ENABLED);
    }

    @Test
    void configYamlShipsItOffAndSaysHowItWorks() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    TELL_AUDIT_ENABLED: false\n"));
        String comment = yaml.substring(yaml.indexOf("# Tell audit (2026-10-26"), yaml.indexOf("TELL_AUDIT_ENABLED:"));
        String technical = comment.substring(comment.indexOf("# Technical:")).replace("\n    #", "");
        assertTrue(technical.length() >= 100, technical);
        for (String said : new String[] {"/runtime/tells", "audit.tells", "tells-report.ps1", "kill switch"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
