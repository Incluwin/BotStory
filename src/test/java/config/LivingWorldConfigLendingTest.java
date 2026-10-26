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
 * Lending and the contracts' loot terms (docs/COMPANION_LENDING_PLAN.md, section 5): both on with their kill switches,
 * the same defaults in code and in the shipped config.yaml, and validate refuses limits that would break them.
 */
class LivingWorldConfigLendingTest {

    @Test
    void theDefaultsAreThePlansAndValidate() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertTrue(config.COMPANION_LENDING_ENABLED);
        assertEquals(1, config.COMPANION_LOANS_PER_BORROWER);
        assertEquals(2, config.COMPANION_LOANS_PER_OWNER);
        assertEquals(120, config.COMPANION_LOAN_MAX_MINUTES);
        assertEquals(60000, config.COMPANION_LOAN_OFFER_MS);
        assertTrue(config.COMPANION_LOOT_TERMS_ENABLED);
        assertEquals(1000, config.COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL);
        assertEquals(60000, config.COMPANION_LOOT_SETTLE_MS);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void validateRefusesLimitsThatBreakALoanOrTheSettle() {
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOANS_PER_BORROWER = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOANS_PER_OWNER = 0));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOAN_MAX_MINUTES = 29));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOAN_OFFER_MS = 4999));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL = -1));
        assertThrows(IllegalArgumentException.class, () -> with(c -> c.COMPANION_LOOT_SETTLE_MS = 999));
        assertDoesNotThrow(() -> with(c -> c.COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL = 0));
        assertDoesNotThrow(() -> with(c -> {
            c.COMPANION_LENDING_ENABLED = false;
            c.COMPANION_LOOT_TERMS_ENABLED = false;
        }));
    }

    @Test
    void configYamlShipsTheSameDefaults() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII).replace("\r\n", "\n");
        for (String line : new String[] {"COMPANION_LENDING_ENABLED: true", "COMPANION_LOANS_PER_BORROWER: 1",
                "COMPANION_LOANS_PER_OWNER: 2", "COMPANION_LOAN_MAX_MINUTES: 120", "COMPANION_LOAN_OFFER_MS: 60000",
                "COMPANION_LOOT_TERMS_ENABLED: true", "COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL: 1000",
                "COMPANION_LOOT_SETTLE_MS: 60000"}) {
            assertTrue(yaml.contains("\n    " + line + "\n"), line);
        }
    }

    private static void with(java.util.function.Consumer<LivingWorldConfig> change) {
        LivingWorldConfig config = new LivingWorldConfig();
        change.accept(config);
        config.validate();
    }
}
