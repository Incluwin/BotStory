package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentSupplyReadiness.AMMO_STANDARDS is key=count pairs with counts up to what one Victoria visit buys
 * (AMMO_LOW_WARN x AMMO_TARGET_THRESHOLD, 5000 today); a bare number would otherwise pass the numeric check.
 */
class AgentEngineConfigValidatorAmmoStandardsTest {
    private static final long BOUND = 5_000L;

    @Test
    void keyCountPairsUpToTheBoundAreAccepted() {
        assertTrue(AgentEngineConfigValidator.validAmmoStandards("lpq=1600,epq=1600", BOUND));
        assertTrue(AgentEngineConfigValidator.validAmmoStandards("epq=0", BOUND));
        assertTrue(AgentEngineConfigValidator.validAmmoStandards("epq=5000", BOUND));
        assertTrue(AgentEngineConfigValidator.validAmmoStandards("easy_balrog=800", BOUND));
    }

    @Test
    void anythingElseIsRefused() {
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("", BOUND));
        assertFalse(AgentEngineConfigValidator.validAmmoStandards(" ", BOUND));
        assertFalse(AgentEngineConfigValidator.validAmmoStandards(null, BOUND));
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("1600", BOUND), "a bare number");
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("epq=16/00", BOUND));
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("EPQ=1600", BOUND), "keys are lower case");
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("epq=5001", BOUND), "past one Victoria visit");
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("epq=99999999999", BOUND));
        assertFalse(AgentEngineConfigValidator.validAmmoStandards("epq=9999999999999999999999", BOUND));
    }

    @Test
    void theShippedValueIsValid() {
        AgentEngineConfigValidator.validate(AgentYamlConfig.config);
    }
}
