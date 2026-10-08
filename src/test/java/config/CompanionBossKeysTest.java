package config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss types the companion-boss kill switch may list (P7: Easy Balrog; P8: Zakum in its own slot), and what
 * validation says of any other: "balrog" is no key until a boss's branch adds its own.
 */
class CompanionBossKeysTest {

    @Test
    void easyBalrogThenZakumAreTheKeys() {
        assertEquals(List.of("easy_balrog", "zakum"), CompanionBossKeys.KNOWN);
    }

    @Test
    void aKeyIsKnownInAnyCaseWithSpacesAroundIt() {
        assertTrue(CompanionBossKeys.known(" Easy_Balrog "));
        assertTrue(CompanionBossKeys.known("easy_balrog"));
        assertTrue(CompanionBossKeys.known("zakum"));
        assertTrue(CompanionBossKeys.known(" Zakum "));
        assertFalse(CompanionBossKeys.known("balrog"));
        assertFalse(CompanionBossKeys.known(""));
        assertFalse(CompanionBossKeys.known(null));
    }

    @Test
    void theMessageNamesEveryKnownKey() {
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog or zakum",
                CompanionBossKeys.message());
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog",
                CompanionBossKeys.message(List.of("easy_balrog")));
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog, zakum or balrog",
                CompanionBossKeys.message(List.of("easy_balrog", "zakum", "balrog")));
    }
}
