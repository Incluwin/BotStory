package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.shop.AgentPotionPurchasePolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The boss potion standard (user decision 8, 2026-10-06, raised after the live run of 2026-10-07): BOSS_STANDARDS
 * gives Easy Balrog 100 HP / 60 MP potions, read after PARTY_QUEST_STANDARDS, so a key listed there wins; KPQ and the
 * default keep 20/25, and a malformed boss pair keeps the default. P8 (user decision 2026-10-08): Zakum keeps 500 HP /
 * 400 MP potions and 4000 projectiles (AMMO_STANDARDS), above the 200-potion carry limit and the 100-potion visit limit,
 * which a readiness standard raises to itself; Easy Balrog keeps what it had.
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
    void easyBalrogKeepsAHundredHpAndSixtyMpPotionsFromTheYaml() {
        assertEquals("easy_balrog=100/60,zakum=500/400", AgentYamlConfig.config.tuning.get(BOSS_STANDARDS_KEY));
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
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("horntail"),
                "an unlisted boss: the default");
    }

    @Test
    void zakumKeepsFiveHundredHpAndFourHundredMpPotionsAndFourThousandProjectiles() {
        // user decision 2026-10-08: "zakum will require much more than 200 potions"
        AgentSupplyReadiness.Activity zakum = AgentSupplyReadiness.Activity.partyQuest("zakum");
        assertEquals(new AgentSupplyReadiness.Standard(500, 400), standardFor("zakum"));
        assertEquals(new AgentSupplyReadiness.Standard(500, 400), standardFor(" ZAKUM "), "keys are trimmed, any case");
        assertEquals(4_000, AgentSupplyReadiness.ammoStandard(zakum));
        assertEquals(4_000, AgentSupplyReadiness.ammoTarget(zakum), "above the low-ammo warning already");
        assertEquals(new AgentSupplyReadiness.Shortfall(500, 400), AgentSupplyReadiness.shortfall(0, 0, true, zakum));
        assertEquals(new AgentSupplyReadiness.Shortfall(0, 0), AgentSupplyReadiness.shortfall(500, 400, true, zakum));

        assertEquals(new AgentSupplyReadiness.Standard(100, 60), standardFor("easy_balrog"), "Easy Balrog unchanged");
        assertEquals(config.AgentTuning.intValue(
                        "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_AMMO"),
                AgentSupplyReadiness.ammoStandard(AgentSupplyReadiness.Activity.partyQuest("easy_balrog")),
                "Easy Balrog keeps the default ammo standard");
    }

    @Test
    void zakumsStandardIsAboveTheCarryAndVisitLimitsSoOneVisitBuysAllOfItAndTheTopUpNothingMore() {
        int carryLimit = config.AgentTuning.intValue(
                "server.agents.capabilities.shop.AgentPotionPurchasePolicy.MAX_CARRIED_QUANTITY");
        int visitLimit = config.AgentTuning.intValue(
                "server.agents.capabilities.shop.AgentPotionPurchasePolicy.MAX_PURCHASE_QUANTITY_PER_VISIT");
        AgentSupplyReadiness.Standard zakum = standardFor("zakum");
        assertTrue(zakum.hp() > carryLimit && zakum.mp() > carryLimit && zakum.mp() > visitLimit,
                "the limits it raises: carry " + carryLimit + ", visit " + visitLimit);

        Character priest = mock(Character.class);
        when(priest.getJob()).thenReturn(Job.PRIEST);
        when(priest.getId()).thenReturn(71);
        AgentSupplyReadiness.install(id -> id == 71 ? AgentSupplyReadiness.Activity.partyQuest("zakum") : null);
        int hpTopUp = AgentSupplyReadiness.buyTarget(priest, true);
        int mpTopUp = AgentSupplyReadiness.buyTarget(priest, false);
        assertEquals(1_000, hpTopUp, "BUY_TO_PERCENT 200");
        assertEquals(800, mpTopUp);

        // the counter's readiness floor (AgentShopService.buyReadinessFloor) sizes each kind with floorQuantity
        assertEquals(500, AgentPotionPurchasePolicy.floorQuantity(zakum.hp(), 0, 0, zakum.hp()), "all 500 at once");
        assertEquals(400, AgentPotionPurchasePolicy.floorQuantity(zakum.mp(), 0, 0, zakum.mp()), "all 400 at once");
        assertEquals(380, AgentPotionPurchasePolicy.floorQuantity(zakum.mp(), 20, 0, zakum.mp()));
        assertEquals(0, AgentPotionPurchasePolicy.floorQuantity(hpTopUp, 500, 500, zakum.hp()),
                "the top-up finds the carry room used: nothing past the standard");
        assertEquals(0, AgentPotionPurchasePolicy.floorQuantity(mpTopUp, 400, 400, zakum.mp()));
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

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }
}
