package config;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss types the companion-boss kill switch may list (P7: Easy Balrog; P8: Zakum in its own slot; P11: Normal
 * Balrog in its own), and what
 * validation says of any other: "balrog" is no key until a boss's branch adds its own.
 */
class CompanionBossKeysTest {

    @Test
    void easyBalrogThenZakumThenNormalBalrogThenTheFieldGroupsAreTheKeys() {
        assertEquals(List.of("easy_balrog", "zakum", "balrog_normal", "field_victoria", "field_ossyria", "field_ludus",
                "field_mulung", "field_nihal", "latanica", "neo_bergamot", "neo_dunas", "neo_aufheben", "neo_oberon",
                "neo_nibelung", "papulatus", "field_pianus", "field_leafre", "field_temple", "field_zipangu",
                "field_masteria", "scarga", "horntail", "showa", "pinkbean"), CompanionBossKeys.KNOWN);
    }

    /** Every boss run and every field group can be switched on: P15 shipped papulatus's row before its key. */
    @Test
    void everyBossRunAndFieldGroupHasAKeyTheSwitchMayList() {
        for (AgentBossRunDefinition definition : AgentBossRunCatalog.all()) {
            assertTrue(CompanionBossKeys.known(definition.key()), definition.key());
        }
        for (AgentBossRules.Boss boss : AgentBossRules.all()) {
            if (!boss.fieldBosses().isEmpty()) {
                assertTrue(CompanionBossKeys.known(boss.key()), boss.key());
            }
        }
    }

    @Test
    void aKeyIsKnownInAnyCaseWithSpacesAroundIt() {
        assertTrue(CompanionBossKeys.known(" Easy_Balrog "));
        assertTrue(CompanionBossKeys.known("easy_balrog"));
        assertTrue(CompanionBossKeys.known("zakum"));
        assertTrue(CompanionBossKeys.known(" Zakum "));
        assertTrue(CompanionBossKeys.known("balrog_normal"));
        assertTrue(CompanionBossKeys.known(" Balrog_Normal "));
        assertFalse(CompanionBossKeys.known("balrog"));
        assertFalse(CompanionBossKeys.known(""));
        assertFalse(CompanionBossKeys.known(null));
    }

    @Test
    void theMessageNamesEveryKnownKey() {
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog, zakum, balrog_normal,"
                + " field_victoria, field_ossyria, field_ludus, field_mulung, field_nihal, latanica, neo_bergamot, neo_dunas,"
                + " neo_aufheben, neo_oberon, neo_nibelung, papulatus, field_pianus, field_leafre, field_temple,"
                + " field_zipangu, field_masteria, scarga, horntail, showa or pinkbean", CompanionBossKeys.message());
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog",
                CompanionBossKeys.message(List.of("easy_balrog")));
        assertEquals("living_world.COMPANION_BOSS_ENABLED_TYPES must list only easy_balrog, zakum or balrog",
                CompanionBossKeys.message(List.of("easy_balrog", "zakum", "balrog")));
    }
}
