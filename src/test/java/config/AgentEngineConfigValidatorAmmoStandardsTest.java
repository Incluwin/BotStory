package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(AgentEngineConfigValidator.validAmmoStandards("lpq=1600,epq=1600,easy_balrog=2000", BOUND),
                "the shipped value (2026-10-08)");
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
    void theAmmoBuyToPercentMayBuyUpToFiveTimesTheStandard() {
        assertEquals(500.0d, AgentEngineConfigValidator.maximumPercent(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.AMMO_BUY_TO_PERCENT"));
        assertEquals(500.0d, AgentEngineConfigValidator.maximumPercent(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.BUY_TO_PERCENT"));
        assertEquals(100.0d, AgentEngineConfigValidator.maximumPercent("server.agents.SOME_SHARE_PERCENT"));
    }

    @Test
    void partyQuestAmmoIsAWholeCountUpToTheBound() {
        // review: its Values line said "a whole projectile count from 0 to 5000" while only non-negative was checked
        assertTrue(AgentEngineConfigValidator.validProjectileCount("500", BOUND));
        assertTrue(AgentEngineConfigValidator.validProjectileCount("0", BOUND));
        assertTrue(AgentEngineConfigValidator.validProjectileCount("5000", BOUND));
        assertFalse(AgentEngineConfigValidator.validProjectileCount("5001", BOUND));
        assertFalse(AgentEngineConfigValidator.validProjectileCount("500.5", BOUND), "a fraction");
        assertFalse(AgentEngineConfigValidator.validProjectileCount("-1", BOUND));
        assertFalse(AgentEngineConfigValidator.validProjectileCount("1e3", BOUND));
        assertFalse(AgentEngineConfigValidator.validProjectileCount(null, BOUND));
    }

    @Test
    void aFractionalPartyQuestAmmoRefusesStartup() {
        String key = "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_AMMO";
        String shipped = AgentYamlConfig.config.tuning.put(key, "500.5");
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> AgentEngineConfigValidator.validate(AgentYamlConfig.config));
            assertTrue(refused.getMessage().contains(key), refused.getMessage());
        } finally {
            AgentYamlConfig.config.tuning.put(key, shipped);
        }
    }

    @Test
    void aMissingOrMalformedBoundKeyIsNamedNotReadAsZero() {
        // review: a missing key made the bound 0 and the error said "counts from 0 to 0" without naming the cause
        String lowWarn = "server.agents.capabilities.supplies.AgentSupplyConfig.AMMO_LOW_WARN";
        String threshold = "server.agents.capabilities.shop.AgentShopService.AMMO_TARGET_THRESHOLD";
        java.util.Map<String, String> tuning = new java.util.HashMap<>(java.util.Map.of(threshold, "10"));

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> AgentEngineConfigValidator.ammoStandardsBound(tuning));
        assertTrue(missing.getMessage().contains(lowWarn) && missing.getMessage().contains("missing"),
                missing.getMessage());
        tuning.put(lowWarn, "5x0");
        IllegalStateException malformed = assertThrows(IllegalStateException.class,
                () -> AgentEngineConfigValidator.ammoStandardsBound(tuning));
        assertTrue(malformed.getMessage().contains(lowWarn) && malformed.getMessage().contains("'5x0'"),
                malformed.getMessage());
        tuning.put(lowWarn, "500");
        assertEquals(5_000L, AgentEngineConfigValidator.ammoStandardsBound(tuning));
    }

    @Test
    void theShippedValueIsValid() {
        AgentEngineConfigValidator.validate(AgentYamlConfig.config);
    }
}
