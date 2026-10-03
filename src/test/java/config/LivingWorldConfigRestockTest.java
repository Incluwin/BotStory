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
 * The companion restock's living_world keys: COMPANION_RESTOCK_ENABLED, the kill switch (on); ASK_EVERY_MS, how often
 * a companion that cannot afford its potions says so on its own (half an hour); BOSS_MAPS, the party-boss gates
 * (Papulatus, King Pepe) where a short companion says there is no shop. config.yaml ships them and says why.
 */
class LivingWorldConfigRestockTest {

    @Test
    void restockIsOnWithTheTwoPartyBossGatesByDefault() {
        LivingWorldConfig config = new LivingWorldConfig();

        assertTrue(config.COMPANION_RESTOCK_ENABLED);
        assertEquals(1_800_000, config.COMPANION_RESTOCK_ASK_EVERY_MS);
        assertTrue(config.companionRestockBossMap(220_080_000));
        assertTrue(config.companionRestockBossMap(106_021_402));
        assertFalse(config.companionRestockBossMap(103_000_000));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void bossMapsAreMapIdsWithBlanksAndSpacesIgnoredAndAnythingElseFails() {
        LivingWorldConfig config = new LivingWorldConfig();
        config.COMPANION_RESTOCK_BOSS_MAPS = " 220080000 ,, ";
        assertTrue(config.companionRestockBossMap(220_080_000));
        assertDoesNotThrow(config::validate);
        config.COMPANION_RESTOCK_BOSS_MAPS = null;
        assertFalse(config.companionRestockBossMap(220_080_000));
        assertDoesNotThrow(config::validate);

        for (String bad : new String[] {"papulatus", "0", "-1", "2.5"}) {
            LivingWorldConfig refused = new LivingWorldConfig();
            refused.COMPANION_RESTOCK_BOSS_MAPS = bad;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, refused::validate, bad);
            assertTrue(failure.getMessage().startsWith("living_world.COMPANION_RESTOCK_BOSS_MAPS must list map ids"));
        }
        LivingWorldConfig negative = new LivingWorldConfig();
        negative.COMPANION_RESTOCK_ASK_EVERY_MS = -1;
        assertThrows(IllegalArgumentException.class, negative::validate);
    }

    @Test
    void configYamlShipsTheRestockKeysAndSaysWhy() throws IOException {
        LivingWorldConfig living = YamlConfig.config.living_world;
        assertTrue(living.COMPANION_RESTOCK_ENABLED);
        assertEquals(1_800_000, living.COMPANION_RESTOCK_ASK_EVERY_MS);
        assertTrue(living.companionRestockBossMap(220_080_000));
        assertTrue(living.companionRestockBossMap(106_021_402));

        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_RESTOCK_ENABLED: true\n"));
        assertTrue(yaml.contains("\n    COMPANION_RESTOCK_ASK_EVERY_MS: 1800000\n"));
        assertTrue(yaml.contains("\n    COMPANION_RESTOCK_BOSS_MAPS: \"220080000,106021402\"\n"));
        String comment = yaml.substring(yaml.indexOf("# Companions restock their own potions"),
                yaml.indexOf("COMPANION_RESTOCK_ENABLED:"));
        for (String said : new String[] {"kill switch", "Papulatus", "never refused", "AgentSupplyReadiness"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
