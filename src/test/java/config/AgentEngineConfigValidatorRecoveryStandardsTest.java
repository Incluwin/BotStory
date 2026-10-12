package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS (user decision 2, 2026-10-08) is key=HP/MP pairs of whole HP and MP
 * points, each one an int; a bare number would otherwise pass the numeric check, and a pair the reader cannot parse
 * would quietly fall back to counting potions.
 */
class AgentEngineConfigValidatorRecoveryStandardsTest {
    private static final String KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS";

    @Test
    void keyPointPairsAreAccepted() {
        assertTrue(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=130000/32000"), "the shipped value");
        assertTrue(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=130000/32000,zakum=300000/120000"));
        assertTrue(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=0/0"));
        assertTrue(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=999999999/999999999"));
    }

    @Test
    void anythingElseIsRefused() {
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards(null));
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards(""));
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("130000"), "a bare number");
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=130000"), "no MP");
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=130000/"));
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("Easy_Balrog=130000/32000"),
                "keys are lower case");
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=1.5/2"), "whole points");
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=-1/2"));
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=9999999999/1"), "past an int");
        assertFalse(AgentEngineConfigValidator.validRecoveryStandards("easy_balrog=1/2,"));
    }

    @Test
    void aMalformedValueRefusesStartupWithItsName() {
        String shipped = AgentYamlConfig.config.tuning.put(KEY, "130000");
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> AgentEngineConfigValidator.validate(AgentYamlConfig.config));
            assertTrue(refused.getMessage().contains(KEY), refused.getMessage());
            AgentYamlConfig.config.tuning.put(KEY, "easy_balrog=130000");
            assertThrows(IllegalStateException.class,
                    () -> AgentEngineConfigValidator.validate(AgentYamlConfig.config), "no MP");
        } finally {
            if (shipped == null) {
                AgentYamlConfig.config.tuning.remove(KEY);
            } else {
                AgentYamlConfig.config.tuning.put(KEY, shipped);
            }
        }
    }

    @Test
    void theShippedValueIsValid() {
        assertEquals("easy_balrog=130000/32000,balrog_normal=300000/240000", AgentYamlConfig.config.tuning.get(KEY));
        AgentEngineConfigValidator.validate(AgentYamlConfig.config);
    }
}
