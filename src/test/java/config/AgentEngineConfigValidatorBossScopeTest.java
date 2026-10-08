package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentBossScope's lists (P8 step 4): EXPEDITION_TYPES is ExpeditionType names, BOSS_IDS is "none" or mob ids and id
 * ranges. Neither may be blank (F15), and a bare number would otherwise pass the numeric check.
 */
class AgentEngineConfigValidatorBossScopeTest {
    private static final String EXPEDITION_TYPES =
            "server.agents.capabilities.mobcontrol.AgentBossScope.EXPEDITION_TYPES";
    private static final String BOSS_IDS = "server.agents.capabilities.mobcontrol.AgentBossScope.BOSS_IDS";

    @Test
    void expeditionTypeNamesSeparatedByCommasAreAccepted() {
        assertTrue(AgentEngineConfigValidator.validExpeditionTypes("ZAKUM"));
        assertTrue(AgentEngineConfigValidator.validExpeditionTypes("ZAKUM,HORNTAIL"));
        assertTrue(AgentEngineConfigValidator.validExpeditionTypes("BALROG_EASY,BALROG_NORMAL,CHAOS_ZAKUM"));
    }

    @Test
    void anythingElseIsRefusedAsExpeditionTypes() {
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes(null));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes(""));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes(" "));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("5"), "a bare number");
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("zakum"), "names are upper case");
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("ZAKUMM"), "not an ExpeditionType");
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("none"));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("ZAKUM,"));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("ZAKUM, HORNTAIL"));
        assertFalse(AgentEngineConfigValidator.validExpeditionTypes("TRUE"));
    }

    @Test
    void noneOrIdsAndRangesAreAcceptedAsBossIds() {
        assertTrue(AgentEngineConfigValidator.validBossIds("none"));
        assertTrue(AgentEngineConfigValidator.validBossIds("8800003"));
        assertTrue(AgentEngineConfigValidator.validBossIds("8800003-8800010"));
        assertTrue(AgentEngineConfigValidator.validBossIds("8800003-8800010,8810002"));
        assertTrue(AgentEngineConfigValidator.validBossIds("8800003-8800003"), "a range of one id");
    }

    @Test
    void anythingElseIsRefusedAsBossIds() {
        assertFalse(AgentEngineConfigValidator.validBossIds(null));
        assertFalse(AgentEngineConfigValidator.validBossIds(""), "blank refuses startup (F15)");
        assertFalse(AgentEngineConfigValidator.validBossIds(" "));
        assertFalse(AgentEngineConfigValidator.validBossIds("NONE"), "none is lower case");
        assertFalse(AgentEngineConfigValidator.validBossIds("none,8800003"));
        assertFalse(AgentEngineConfigValidator.validBossIds("8800010-8800003"), "a reversed range");
        assertFalse(AgentEngineConfigValidator.validBossIds("-1"));
        assertFalse(AgentEngineConfigValidator.validBossIds("8800003,"));
        assertFalse(AgentEngineConfigValidator.validBossIds("8800003-"));
        assertFalse(AgentEngineConfigValidator.validBossIds("8800003 - 8800010"));
        assertFalse(AgentEngineConfigValidator.validBossIds("8800003.5"));
        assertFalse(AgentEngineConfigValidator.validBossIds("99999999999"), "past an int");
        assertFalse(AgentEngineConfigValidator.validBossIds("ZAKUM"));
    }

    @Test
    void theShippedValuesPassAndABadValueRefusesStartupByName() {
        assertDoesNotThrow(() -> AgentEngineConfigValidator.validate(AgentYamlConfig.config));
        assertRefusedByName(EXPEDITION_TYPES, "5");
        assertRefusedByName(EXPEDITION_TYPES, "zakum");
        assertRefusedByName(EXPEDITION_TYPES, "true");
        assertRefusedByName(BOSS_IDS, "8800003-");
        assertRefusedByName(BOSS_IDS, "false");
    }

    private static void assertRefusedByName(String key, String value) {
        String shipped = AgentYamlConfig.config.tuning.put(key, value);
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> AgentEngineConfigValidator.validate(AgentYamlConfig.config), key + "=" + value);
            assertTrue(refused.getMessage().contains(key), refused.getMessage());
        } finally {
            if (shipped == null) {
                AgentYamlConfig.config.tuning.remove(key);
            } else {
                AgentYamlConfig.config.tuning.put(key, shipped);
            }
        }
    }
}
