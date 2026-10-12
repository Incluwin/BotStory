package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss attack mirror's mode (P8 step 7) is OFF, OBSERVE or APPLY. A bare number or a boolean would otherwise pass
 * the generic checks, so the key has its own branch. DAMAGE_PERCENT keeps the generic 0-100 percent rule.
 */
class AgentEngineConfigValidatorBossMirrorTest {
    private static final String PREFIX = "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.";
    private static final String MODE = PREFIX + "MODE";
    private static final String BLAST_MODE = PREFIX + "BLAST_MODE";
    private static final String DAMAGE_PERCENT = PREFIX + "DAMAGE_PERCENT";

    @Test
    void theThreeModesAreAcceptedInAnyCase() {
        assertTrue(AgentEngineConfigValidator.validMirrorMode("OFF"));
        assertTrue(AgentEngineConfigValidator.validMirrorMode("OBSERVE"));
        assertTrue(AgentEngineConfigValidator.validMirrorMode("APPLY"));
        assertTrue(AgentEngineConfigValidator.validMirrorMode("observe"));
    }

    @Test
    void anythingElseIsRefusedAsAMode() {
        assertFalse(AgentEngineConfigValidator.validMirrorMode(null));
        assertFalse(AgentEngineConfigValidator.validMirrorMode(""));
        assertFalse(AgentEngineConfigValidator.validMirrorMode("ON"));
        assertFalse(AgentEngineConfigValidator.validMirrorMode("SHADOW"));
        assertFalse(AgentEngineConfigValidator.validMirrorMode("true"));
        assertFalse(AgentEngineConfigValidator.validMirrorMode("1"));
    }

    @Test
    void theShippedValuesPassAndABadValueRefusesStartupByName() {
        assertDoesNotThrow(() -> AgentEngineConfigValidator.validate(AgentYamlConfig.config));
        assertRefusedByName(MODE, "true");
        assertRefusedByName(MODE, "2");
        assertRefusedByName(MODE, "ACTIVE");
        assertRefusedByName(BLAST_MODE, "true");
        assertRefusedByName(BLAST_MODE, "ON");
        assertRefusedByName(DAMAGE_PERCENT, "101");
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
