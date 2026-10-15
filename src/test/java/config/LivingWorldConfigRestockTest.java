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
    void noBossGateSendsATripByDefaultAndTripMapsAreMapIdsOnly() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals("", config.COMPANION_RESTOCK_TRIP_MAPS, "P12 ships the Papulatus desk trip off; P15 lists it");
        assertFalse(config.companionRestockTripMap(220_080_000));

        config.COMPANION_RESTOCK_TRIP_MAPS = " 220080000 ,, ";
        assertTrue(config.companionRestockTripMap(220_080_000));
        assertFalse(config.companionRestockTripMap(106_021_402));
        assertDoesNotThrow(config::validate);

        for (String bad : new String[] {"papulatus", "0", "-1"}) {
            LivingWorldConfig refused = new LivingWorldConfig();
            refused.COMPANION_RESTOCK_TRIP_MAPS = bad;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, refused::validate, bad);
            assertTrue(failure.getMessage().startsWith("living_world.COMPANION_RESTOCK_TRIP_MAPS must list map ids"));
        }
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
    void fieldBossTownsListGroupsWithTheirTownsAndRefuseAnythingElse() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals("", config.COMPANION_FIELD_BOSS_TOWNS, "none by default");
        assertEquals(java.util.List.of(), config.companionFieldBossKeysAt(240_000_000));

        config.COMPANION_FIELD_BOSS_TOWNS = "field_leafre=240000000,field_temple=240000000,field_zipangu=800000000|801000000";
        assertEquals(java.util.List.of("field_leafre", "field_temple"), config.companionFieldBossKeysAt(240_000_000));
        assertEquals(java.util.List.of("field_zipangu"), config.companionFieldBossKeysAt(801_000_000));
        assertEquals(java.util.List.of(), config.companionFieldBossKeysAt(100_000_000));
        assertDoesNotThrow(config::validate);

        for (String bad : new String[] {"field_pianus", "field_pianus=0", "nosuchboss=230000000",
                "field_pianus=230000000,230000002"}) {
            LivingWorldConfig refused = new LivingWorldConfig();
            refused.COMPANION_FIELD_BOSS_TOWNS = bad;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, refused::validate, bad);
            assertTrue(failure.getMessage().startsWith("living_world.COMPANION_FIELD_BOSS_TOWNS must list"), bad);
        }

        LivingWorldConfig shipped = YamlConfig.config.living_world;
        assertEquals(java.util.List.of("field_pianus"), shipped.companionFieldBossKeysAt(230_000_000), "Aquarium");
        assertEquals(java.util.List.of("field_leafre", "field_temple"), shipped.companionFieldBossKeysAt(240_000_000));
    }

    @Test
    void bossCureTownsListBossesWithTheirTownsAndRefuseAnythingElse() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals("", config.COMPANION_BOSS_CURE_TOWNS, "none by default");
        assertEquals(java.util.List.of(), config.companionCureBossKeysAt(200_000_000));

        config.COMPANION_BOSS_CURE_TOWNS = "horntail=200000000|200000002";
        assertEquals(java.util.List.of("horntail"), config.companionCureBossKeysAt(200_000_002));
        assertEquals(java.util.List.of(), config.companionCureBossKeysAt(240_000_000));
        assertEquals(java.util.List.of(200_000_000, 200_000_002), config.companionCureTownsFor("horntail"));
        assertEquals(java.util.List.of(200_000_000, 200_000_002), config.companionCureTownsFor("HORNTAIL"));
        assertEquals(java.util.List.of(), config.companionCureTownsFor("zakum"));
        assertDoesNotThrow(config::validate);

        for (String bad : new String[] {"horntail", "horntail=0", "nosuchboss=200000000", "horntail=200000000,200000002"}) {
            LivingWorldConfig refused = new LivingWorldConfig();
            refused.COMPANION_BOSS_CURE_TOWNS = bad;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, refused::validate, bad);
            assertTrue(failure.getMessage().startsWith("living_world.COMPANION_BOSS_CURE_TOWNS must list"), bad);
        }

        assertEquals(java.util.List.of("horntail"), YamlConfig.config.living_world.companionCureBossKeysAt(200_000_000),
                "shipped: Orbis, whose store (Edel) sells Holy Water");
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
