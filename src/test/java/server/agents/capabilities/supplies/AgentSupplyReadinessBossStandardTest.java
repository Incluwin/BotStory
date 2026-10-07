package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The boss potion standard (user decision 8, 2026-10-06): BOSS_STANDARDS gives Easy Balrog 40 HP / 50 MP potions,
 * read after PARTY_QUEST_STANDARDS, so a key listed there wins; KPQ and the default keep 20/25, and a malformed boss
 * pair keeps the default.
 */
class AgentSupplyReadinessBossStandardTest {
    private static final String PQ_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_STANDARDS";
    private static final String BOSS_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_STANDARDS";

    @AfterEach
    void noRule() {
        AgentSupplyReadiness.install(null);
    }

    @Test
    void easyBalrogKeepsFortyHpAndFiftyMpPotionsFromTheYaml() {
        assertEquals("easy_balrog=40/50", AgentYamlConfig.config.tuning.get(BOSS_STANDARDS_KEY));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("easy_balrog"));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor(" Easy_Balrog "),
                "keys are trimmed, any case");
    }

    @Test
    void theDefaultAndKpqKeepTwentyHpAndTwentyFiveMpPotions() {
        assertEquals(new AgentSupplyReadiness.Standard(20, 25),
                AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.PARTY_QUEST));
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("kpq"));
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("hpq"));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("lpq"), "party quest standards unchanged");
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("zakum"), "an unlisted boss: the default");
    }

    @Test
    void aKeyListedInThePartyQuestStandardsWinsOverTheBossStandard() {
        String configured = AgentYamlConfig.config.tuning.get(PQ_STANDARDS_KEY);
        AgentYamlConfig.config.tuning.put(PQ_STANDARDS_KEY, configured + ",easy_balrog=30/35");
        try {
            assertEquals(new AgentSupplyReadiness.Standard(30, 35), standardFor("easy_balrog"));
            assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("lpq"));
        } finally {
            AgentYamlConfig.config.tuning.put(PQ_STANDARDS_KEY, configured);
        }
    }

    @Test
    void aMalformedBossPairKeepsTheDefault() {
        String configured = AgentYamlConfig.config.tuning.put(BOSS_STANDARDS_KEY, "easy_balrog=x/50");
        try {
            assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("easy_balrog"));
            AgentYamlConfig.config.tuning.put(BOSS_STANDARDS_KEY, "easy_balrog=40");
            assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("easy_balrog"), "no slash");
            AgentYamlConfig.config.tuning.put(BOSS_STANDARDS_KEY, "zakum=60/80");
            assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("easy_balrog"), "not listed");
            assertEquals(new AgentSupplyReadiness.Standard(60, 80), standardFor("zakum"));
        } finally {
            AgentYamlConfig.config.tuning.put(BOSS_STANDARDS_KEY, configured);
        }
    }

    @Test
    void theShortfallAndTheBuyTargetFollowTheBossStandard() {
        AgentSupplyReadiness.Activity balrog = AgentSupplyReadiness.Activity.partyQuest("easy_balrog");
        assertEquals(new AgentSupplyReadiness.Shortfall(20, 25), AgentSupplyReadiness.shortfall(20, 25, true, balrog));

        Character priest = mock(Character.class);
        when(priest.getJob()).thenReturn(Job.PRIEST);
        when(priest.getId()).thenReturn(71);
        AgentSupplyReadiness.install(id -> id == 71 ? balrog : null);
        assertEquals(80, AgentSupplyReadiness.buyTarget(priest, true));
        assertEquals(100, AgentSupplyReadiness.buyTarget(priest, false));
    }

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }
}
