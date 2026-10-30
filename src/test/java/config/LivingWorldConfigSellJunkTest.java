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
 * Companions sell their junk at every shop visit (user decision 2026-10-10, "yes" to selling at every visit and to a
 * full Equip tab ending a TRAIN bench slice for a selling shop slice): living_world.COMPANION_SELL_JUNK_EVERY_VISIT, on
 * in code and in the shipped config.yaml, and BENCH_SELL_FREE_EQUIP_SLOTS, 4, never negative.
 */
class LivingWorldConfigSellJunkTest {

    @Test
    void sellingIsOnAndATrainSliceSellsAtFourFreeSlots() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertTrue(config.COMPANION_SELL_JUNK_EVERY_VISIT);
        assertEquals(4, config.BENCH_SELL_FREE_EQUIP_SLOTS);
        assertDoesNotThrow(config::validate);

        config.BENCH_SELL_FREE_EQUIP_SLOTS = 0;
        assertDoesNotThrow(config::validate);
        config.BENCH_SELL_FREE_EQUIP_SLOTS = -1;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void configYamlShipsBothAndSaysWhatIsNeverSold() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_SELL_JUNK_EVERY_VISIT: true\n"));
        assertTrue(yaml.contains("\n    BENCH_SELL_FREE_EQUIP_SLOTS: 4\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions sell their junk at every shop visit"),
                yaml.indexOf("COMPANION_SELL_JUNK_EVERY_VISIT:"));
        for (String said : new String[] {"2026-10-10", "purse", "what it wears", "boss run", "TRAIN", "sell junk"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
