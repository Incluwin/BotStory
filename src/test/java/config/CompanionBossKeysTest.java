package config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss types the companion-boss kill switch may list (P7 Part A: Easy Balrog only), and what validation says of
 * any other: "balrog" and "zakum" are not keys until a boss's branch adds its own.
 */
class CompanionBossKeysTest {

    @Test
    void easyBalrogIsTheOnlyKey() {
        assertEquals(List.of("easy_balrog"), CompanionBossKeys.KNOWN);
    }

    @Test
    void aKeyIsKnownInAnyCaseWithSpacesAroundIt() {
        assertTrue(CompanionBossKeys.known(" Easy_Balrog "));
        assertTrue(CompanionBossKeys.known("easy_balrog"));
        assertFalse(CompanionBossKeys.known("balrog"));
        assertFalse(CompanionBossKeys.known("zakum"));
        assertFalse(CompanionBossKeys.known(""));
        assertFalse(CompanionBossKeys.known(null));
    }

    @Test
    void theMessageNamesEveryKnownKey() {
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog", CompanionBossKeys.message());
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog or zakum",
                CompanionBossKeys.message(List.of("easy_balrog", "zakum")));
    }
}
