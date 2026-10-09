package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zakum fight's platform anchors (P8 step 10) are "none" or x,y points separated by "|". A bare number or a boolean
 * would otherwise pass the generic checks, so the key has its own branch; a blank value is refused like every other.
 */
class AgentEngineConfigValidatorZakumAnchorsTest {
    private static final String ANCHORS =
            "server.agents.capabilities.expedition.zakum.AgentZakumScenario.PLATFORM_ANCHORS";

    @Test
    void noneOrPointsSeparatedByBarsAreAccepted() {
        assertTrue(AgentEngineConfigValidator.validPlatformAnchors("none"));
        assertTrue(AgentEngineConfigValidator.validPlatformAnchors("-345,-488"));
        assertTrue(AgentEngineConfigValidator.validPlatformAnchors("-345,-488|446,-530"));
        assertTrue(AgentEngineConfigValidator.validPlatformAnchors("-345,-488|-373,-556|446,-530|473,-391"));
        assertTrue(AgentEngineConfigValidator.validPlatformAnchors("0,0"));
    }

    @Test
    void anythingElseIsRefusedAsAnchors() {
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors(null));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors(""));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("true"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("12"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("-345"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("-345,-488|"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("-345,-488,1"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("-345, -488"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("-345,-488;446,-530"));
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("1234567,0"), "more than six digits");
        assertFalse(AgentEngineConfigValidator.validPlatformAnchors("None"));
    }

    @Test
    void theShippedValuePassesAndABadValueRefusesStartupByName() {
        assertDoesNotThrow(() -> AgentEngineConfigValidator.validate(AgentYamlConfig.config));
        assertRefusedByName("true");
        assertRefusedByName("12");
        assertRefusedByName("-345,-488|");
    }

    private static void assertRefusedByName(String value) {
        String shipped = AgentYamlConfig.config.tuning.put(ANCHORS, value);
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> AgentEngineConfigValidator.validate(AgentYamlConfig.config), ANCHORS + "=" + value);
            assertTrue(refused.getMessage().contains(ANCHORS), refused.getMessage());
        } finally {
            if (shipped == null) {
                AgentYamlConfig.config.tuning.remove(ANCHORS);
            } else {
                AgentYamlConfig.config.tuning.put(ANCHORS, shipped);
            }
        }
    }
}
