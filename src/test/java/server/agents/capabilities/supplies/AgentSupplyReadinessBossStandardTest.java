package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.shop.AgentPotionPurchasePolicy;
import testutil.Items;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The boss potion standard (user decision 8, 2026-10-06, raised after the live run of 2026-10-07): BOSS_STANDARDS
 * gives Easy Balrog 100 HP / 60 MP potions, read after PARTY_QUEST_STANDARDS, so a key listed there wins; KPQ and the
 * default keep 20/25, and a malformed boss pair keeps the default. Its ammo standard (AMMO_STANDARDS, user decision 1
 * of 2026-10-08) is 2000 projectiles; LPQ and EPQ keep 1600 and every other key PARTY_QUEST_AMMO. Since user decision 2
 * of 2026-10-08 a companion's own count for Easy Balrog measures HP and MP points (BOSS_RECOVERY_STANDARDS,
 * 130,000 / 32,000): what its recovery potions restore, not how many it carries; the party quests still count potions.
 * P8 (user decision 2026-10-08): Zakum keeps 500 HP / 400 MP potions, counted (no points standard), and 4000
 * projectiles (AMMO_STANDARDS), above the 200-potion carry limit and the 100-potion visit limit, which a readiness
 * standard raises to itself; Easy Balrog keeps what it had.
 */
class AgentSupplyReadinessBossStandardTest {
    private static final String PQ_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_STANDARDS";
    private static final String BOSS_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_STANDARDS";
    private static final String AMMO_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.AMMO_STANDARDS";
    private static final String RECOVERY_STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_RECOVERY_STANDARDS";
    private static final int RED = 2_000_000;
    private static final int WHITE = 2_000_002;
    private static final int BLUE = 2_000_003;
    private static final int PURE_WATER = 2_022_000;
    private static final int UNAGI = 2_022_003;
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");

    @AfterEach
    void noRule() {
        AgentSupplyReadiness.install(null);
    }

    @Test
    void easyBalrogKeepsAHundredHpAndSixtyMpPotionsFromTheYaml() {
        assertEquals("easy_balrog=100/60,zakum=500/400,balrog_normal=300/300,latanica=150/100,neo_bergamot=150/100,"
                        + "neo_dunas=150/100,neo_aufheben=200/150,neo_oberon=200/150,neo_nibelung=250/250",
                AgentYamlConfig.config.tuning.get(BOSS_STANDARDS_KEY));
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
        assertEquals(2_000, AgentSupplyReadiness.ammoStandard(AgentSupplyReadiness.Activity.partyQuest("easy_balrog")),
                "Easy Balrog keeps its own ammo standard");
        assertNull(AgentSupplyReadiness.recoveryStandard(zakum), "Zakum counts potions, not points");
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
        assertEquals(partyQuestAmmo, ammoStandardFor("horntail"), "an unlisted boss: the default");
        assertEquals("lpq=1600,epq=1600,easy_balrog=2000,zakum=4000,balrog_normal=2500,latanica=1000,neo_bergamot=1000,"
                        + "neo_dunas=1000,neo_aufheben=1500,neo_oberon=1500,neo_nibelung=2000",
                AgentYamlConfig.config.tuning.get(AMMO_STANDARDS_KEY));
        assertEquals(2500, ammoStandardFor("balrog_normal"), "P11: 25 minutes at 100 a minute");
    }

    @Test
    void easyBalrogsRecoveryStandardIsAHundredThirtyThousandHpAndThirtyTwoThousandMpPoints() {
        // user decision 2 (2026-10-08): sized for a 20-minute fight at about 6.5k HP a minute on the melee station
        assertEquals("easy_balrog=130000/32000,balrog_normal=300000/240000",
                AgentYamlConfig.config.tuning.get(RECOVERY_STANDARDS_KEY));
        assertEquals(new AgentSupplyReadiness.RecoveryStandard(300_000L, 240_000L),
                AgentSupplyReadiness.recoveryStandard(AgentSupplyReadiness.Activity.partyQuest("balrog_normal")),
                "P11: 300 Unagi and 300 Pure Water, MP equal to HP for the body's deadly attack");
        assertEquals(new AgentSupplyReadiness.RecoveryStandard(130_000L, 32_000L),
                AgentSupplyReadiness.recoveryStandard(EASY_BALROG));
        assertEquals(new AgentSupplyReadiness.RecoveryStandard(130_000L, 32_000L),
                AgentSupplyReadiness.recoveryStandard(AgentSupplyReadiness.Activity.partyQuest(" Easy_Balrog ")),
                "keys are trimmed, any case");
        for (String key : List.of("kpq", "hpq", "lpq", "epq", "zakum")) {
            assertNull(AgentSupplyReadiness.recoveryStandard(AgentSupplyReadiness.Activity.partyQuest(key)),
                    key + " keeps counting potions");
        }
        assertNull(AgentSupplyReadiness.recoveryStandard(AgentSupplyReadiness.Activity.PARTY_QUEST));
        assertNull(AgentSupplyReadiness.recoveryStandard(null));
    }

    @Test
    void aMalformedRecoveryPairCountsPotionsAgain() {
        String configured = AgentYamlConfig.config.tuning.put(RECOVERY_STANDARDS_KEY, "easy_balrog=x/32000");
        try {
            assertNull(AgentSupplyReadiness.recoveryStandard(EASY_BALROG));
            AgentYamlConfig.config.tuning.put(RECOVERY_STANDARDS_KEY, "easy_balrog=130000");
            assertNull(AgentSupplyReadiness.recoveryStandard(EASY_BALROG), "no slash");
            AgentYamlConfig.config.tuning.put(RECOVERY_STANDARDS_KEY, "zakum=300000/120000");
            assertNull(AgentSupplyReadiness.recoveryStandard(EASY_BALROG), "not listed");
            assertEquals(new AgentSupplyReadiness.RecoveryStandard(300_000L, 120_000L),
                    AgentSupplyReadiness.recoveryStandard(AgentSupplyReadiness.Activity.partyQuest("zakum")));
        } finally {
            AgentYamlConfig.config.tuning.put(RECOVERY_STANDARDS_KEY, configured);
        }
    }

    @Test
    void threeHundredSeventeenWhitePotionsAreShortOfEasyBalrogsHp() {
        // 317 White Potions hold 95,100 HP: by count they were plenty (BOSS_STANDARDS asks 100), and in the run of
        // 2026-10-08 every member ran out of HP potions before the body died
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potionEffects()) {
            Character ingrid = dragonKnight(WHITE, 317, PURE_WATER, 40);

            assertEquals(new AgentSupplyReadiness.Shortfall(34_900, 0, 0),
                    AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG), "130,000 - 95,100 HP points");
            assertFalse(AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG).potionsNone());
        }
    }

    @Test
    void aHundredThirtyUnagiAndFortyPureWaterAreNotShort() {
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potionEffects()) {
            assertTrue(AgentSupplyReadiness.shortfall(dragonKnight(UNAGI, 100, UNAGI, 30, PURE_WATER, 40),
                    EASY_BALROG).none(), "130,000 HP and 32,000 MP");
            assertEquals(new AgentSupplyReadiness.Shortfall(1_000, 0, 0),
                    AgentSupplyReadiness.shortfall(dragonKnight(UNAGI, 100, UNAGI, 29, PURE_WATER, 40), EASY_BALROG),
                    "one Unagi short: its points");
        }
    }

    @Test
    void sixtyFiveBluePotionsAreShortOfItsMpAndAFighterWithoutSkillsNeedsNone() {
        // live 2026-10-08: Ingrid went in with 65 MP potions against a count of 60 and was dry at 20:01:47
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potionEffects()) {
            assertEquals(new AgentSupplyReadiness.Shortfall(0, 25_500, 0),
                    AgentSupplyReadiness.shortfall(dragonKnight(UNAGI, 100, UNAGI, 30, BLUE, 65), EASY_BALROG),
                    "65 Blue Potions hold 6,500 of 32,000 MP");

            Character beginner = dragonKnight(UNAGI, 100, UNAGI, 30);
            when(beginner.getJob()).thenReturn(Job.BEGINNER);
            assertTrue(AgentSupplyReadiness.shortfall(beginner, EASY_BALROG).none(), "no skills, no mana");
        }
    }

    @Test
    void lpqStillCountsPotions() {
        AgentSupplyReadiness.Activity lpq = AgentSupplyReadiness.Activity.partyQuest("lpq");
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potionEffects()) {
            assertTrue(AgentSupplyReadiness.shortfall(dragonKnight(RED, 40, BLUE, 50), lpq).none(),
                    "40 Red and 50 Blue Potions: LPQ's 40 / 50, however weak");
            assertEquals(new AgentSupplyReadiness.Shortfall(1, 0, 0),
                    AgentSupplyReadiness.shortfall(dragonKnight(UNAGI, 39, PURE_WATER, 50), lpq),
                    "one potion short, however strong");
        }
    }

    @Test
    void aShopVisitTopsUpTowardTwiceThePointsAndThePotionsForAGapRoundUp() {
        Character ingrid = dragonKnight();
        assertEquals(260_000L, AgentSupplyReadiness.recoveryBuyTarget(EASY_BALROG, ingrid, true), "BUY_TO_PERCENT 200");
        assertEquals(64_000L, AgentSupplyReadiness.recoveryBuyTarget(EASY_BALROG, ingrid, false));
        assertEquals(0L, AgentSupplyReadiness.recoveryBuyTarget(AgentSupplyReadiness.Activity.partyQuest("lpq"),
                ingrid, true), "a count standard: no points");
        when(ingrid.getJob()).thenReturn(Job.BEGINNER);
        assertEquals(0L, AgentSupplyReadiness.recoveryBuyTarget(EASY_BALROG, ingrid, false), "no skills, no mana");

        assertEquals(100, AgentSupplyReadiness.potionsFor(100_000L, 1_000), "100,000 HP in Unagi");
        assertEquals(117, AgentSupplyReadiness.potionsFor(34_900L, 300), "rounded up");
        assertEquals(0, AgentSupplyReadiness.potionsFor(0L, 300));
        assertEquals(0, AgentSupplyReadiness.potionsFor(-5L, 300));
        assertEquals(0, AgentSupplyReadiness.potionsFor(100L, 0), "no potion to count in");
    }

    /** Ingrid, a Lv70 Dragon Knight (5,000 HP, 1,500 MP), with these {item, count} stacks in her Use tab. */
    private static Character dragonKnight(int... stacks) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(1_009);
        when(agent.getJob()).thenReturn(Job.DRAGONKNIGHT);
        when(agent.getCurrentMaxHp()).thenReturn(5_000);
        when(agent.getCurrentMaxMp()).thenReturn(1_500);
        Inventory use = new Inventory(agent, InventoryType.USE, (byte) 24);
        for (int i = 0; i + 1 < stacks.length; i += 2) {
            use.addItem(Items.itemWithQuantity(stacks[i], stacks[i + 1]));
        }
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        return agent;
    }

    /** Red 50 HP, White 300 HP, Unagi 1,000 HP, Blue 100 MP, Pure Water 800 MP. */
    private static MockedStatic<AgentUseItemClassificationPolicy> potionEffects() {
        Map<Integer, StatEffect> effects = Map.of(RED, effect(50, 0), WHITE, effect(300, 0), UNAGI, effect(1_000, 0),
                BLUE, effect(0, 100), PURE_WATER, effect(0, 800));
        MockedStatic<AgentUseItemClassificationPolicy> classification =
                mockStatic(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        return classification;
    }

    private static StatEffect effect(int hp, int mp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        return effect;
    }

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }

    private static int ammoStandardFor(String key) {
        return AgentSupplyReadiness.ammoStandard(AgentSupplyReadiness.Activity.partyQuest(key));
    }
}
