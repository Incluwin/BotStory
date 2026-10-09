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
 * The boss potion standard (user decision 8, 2026-10-06, raised after the live run of 2026-10-07): BOSS_STANDARDS
 * gives Easy Balrog 100 HP / 60 MP potions, read after PARTY_QUEST_STANDARDS, so a key listed there wins; KPQ and the
 * default keep 20/25, and a malformed boss pair keeps the default. Its ammo standard (AMMO_STANDARDS, user decision 1
 * of 2026-10-08) is 2000 projectiles; LPQ and EPQ keep 1600 and every other key PARTY_QUEST_AMMO.
 */
class AgentSupplyReadinessBossStandardTest {
    private static final String PQ_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_STANDARDS";
    private static final String BOSS_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_STANDARDS";
    private static final String AMMO_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.AMMO_STANDARDS";

    @AfterEach
    void noRule() {
        AgentSupplyReadiness.install(null);
    }

    @Test
    void easyBalrogKeepsAHundredHpAndSixtyMpPotionsFromTheYaml() {
        assertEquals("easy_balrog=100/60", AgentYamlConfig.config.tuning.get(BOSS_STANDARDS_KEY));
        assertEquals(new AgentSupplyReadiness.Standard(100, 60), standardFor("easy_balrog"));
        assertEquals(new AgentSupplyReadiness.Standard(100, 60), standardFor(" Easy_Balrog "),
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
        assertEquals(new AgentSupplyReadiness.Shortfall(80, 35), AgentSupplyReadiness.shortfall(20, 25, true, balrog));

        Character priest = mock(Character.class);
        when(priest.getJob()).thenReturn(Job.PRIEST);
        when(priest.getId()).thenReturn(71);
        AgentSupplyReadiness.install(id -> id == 71 ? balrog : null);
        assertEquals(200, AgentSupplyReadiness.buyTarget(priest, true), "BUY_TO_PERCENT 200");
        assertEquals(120, AgentSupplyReadiness.buyTarget(priest, false));
    }

    @Test
    void easyBalrogAsksTwoThousandProjectilesAndThePartyQuestsKeepSixteenHundred() {
        // user decision 1 (2026-10-08): in the lost run of that day Flint, a gunner, stopped attacking at 20:01:39
        // with full MP; Double Shot uses 2 bullets per cast and the body phase lasted 15 minutes
        AgentSupplyReadiness.Activity balrog = AgentSupplyReadiness.Activity.partyQuest("easy_balrog");
        assertEquals(2000, AgentSupplyReadiness.ammoStandard(balrog));
        assertEquals(2000, AgentSupplyReadiness.ammoTarget(balrog), "above the low-ammo warning already");
        assertEquals(4000, AgentSupplyReadiness.ammoBuyTarget(balrog), "AMMO_BUY_TO_PERCENT 200, under the 5000 bound");
        assertEquals(1, AgentSupplyReadiness.ammoShortfall(1999, balrog));
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(2000, balrog));
        assertEquals(2000, ammoStandardFor(" Easy_Balrog "), "keys are trimmed, any case");

        assertEquals(1600, ammoStandardFor("lpq"));
        assertEquals(1600, ammoStandardFor("epq"));
        int partyQuestAmmo = config.AgentTuning.intValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_AMMO");
        assertEquals(partyQuestAmmo, ammoStandardFor("kpq"));
        assertEquals(partyQuestAmmo, ammoStandardFor("zakum"), "an unlisted boss: the default");
        assertEquals("lpq=1600,epq=1600,easy_balrog=2000", AgentYamlConfig.config.tuning.get(AMMO_STANDARDS_KEY));
    }

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }

    private static int ammoStandardFor(String key) {
        return AgentSupplyReadiness.ammoStandard(AgentSupplyReadiness.Activity.partyQuest(key));
    }
}
