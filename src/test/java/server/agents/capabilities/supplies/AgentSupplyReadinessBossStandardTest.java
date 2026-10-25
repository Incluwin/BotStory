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
    private static final int REINDEER_MILK = 2_020_013;
    private static final int HONSTER = 2_002_021;
    private static final int ICE_CREAM_POP = 2_001_001;
    private static final int MELTING_CHEESE = 2_020_012;
    private static final String FLOOR_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.BOSS_POTION_FLOOR_BPS";
    private static final AgentSupplyReadiness.Activity HORNTAIL =
            AgentSupplyReadiness.Activity.partyQuest("horntail");
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");

    @AfterEach
    void noRule() {
        AgentSupplyReadiness.install(null);
    }

    @Test
    void easyBalrogKeepsAHundredHpAndSixtyMpPotionsFromTheYaml() {
        assertEquals("easy_balrog=100/60,zakum=500/400,balrog_normal=300/300,latanica=150/100,neo_bergamot=150/100,"
                        + "neo_dunas=150/100,neo_aufheben=200/150,neo_oberon=200/150,neo_nibelung=250/250,"
                        + "papulatus=450/300,scarga=900/500,horntail=900/900,showa=600/600,pinkbean=1500/600,"
                        + "field_pianus=500/300,field_leafre=300/200,field_temple=500/300,field_zipangu=500/300,"
                        + "field_masteria=400/300,cwkpq=600/600",
                AgentYamlConfig.config.tuning.get(BOSS_STANDARDS_KEY));
        assertEquals(new AgentSupplyReadiness.Standard(100, 60), standardFor("easy_balrog"));
        assertEquals(new AgentSupplyReadiness.Standard(500, 300), standardFor("field_pianus"),
                "a high-level field group keeps its own standard (user decision 2026-10-13)");
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
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("chaos_horntail"),
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
        assertEquals(partyQuestAmmo, ammoStandardFor("chaos_horntail"), "an unlisted boss: the default");
        assertEquals("lpq=1600,epq=1600,easy_balrog=2000,zakum=4000,balrog_normal=2500,latanica=1000,neo_bergamot=1000,"
                        + "neo_dunas=1000,neo_aufheben=1500,neo_oberon=1500,neo_nibelung=2000,papulatus=2500,"
                        + "scarga=5000,horntail=4800,showa=4000,pinkbean=4800,cwkpq=4800",
                AgentYamlConfig.config.tuning.get(AMMO_STANDARDS_KEY));
        assertEquals(2500, ammoStandardFor("balrog_normal"), "P11: 25 minutes at 100 a minute");
        assertEquals(new AgentSupplyReadiness.Standard(600, 600), standardFor("cwkpq"),
                "CWKPQ C9 live: three companions died in the Council Hall on the party quest default");
        assertEquals(4800, ammoStandardFor("cwkpq"), "a Night Lord threw 2,400 stars in 18 minutes there");
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
    void horntailCountsOnlyPotionsThatRestoreFifteenPercentOfMaximumHp() {
        // P18 rerun 2026-10-14: Ingrid carried 359 White Potions toward Horntail's 600 HP potions (900 since the touch-fence rerun)
        Map<Integer, StatEffect> effects = Map.of(WHITE, effect(300, 0), REINDEER_MILK, effect(5_000, 0),
                HONSTER, rateEffect(0.6), PURE_WATER, effect(0, 800));
        try (MockedStatic<AgentUseItemClassificationPolicy> classification = potionEffects(effects)) {
            Character hero = mock(Character.class);
            when(hero.getId()).thenReturn(877);
            when(hero.getJob()).thenReturn(Job.HERO);
            when(hero.getCurrentMaxHp()).thenReturn(19_776);
            when(hero.getCurrentMaxMp()).thenReturn(3_000);
            Inventory use = new Inventory(hero, InventoryType.USE, (byte) 24);
            use.addItem(Items.itemWithQuantity(WHITE, 359));
            use.addItem(Items.itemWithQuantity(REINDEER_MILK, 166));
            use.addItem(Items.itemWithQuantity(HONSTER, 50));
            use.addItem(Items.itemWithQuantity(PURE_WATER, 600));
            when(hero.getInventory(InventoryType.USE)).thenReturn(use);

            assertEquals(new AgentSupplyReadiness.PotionFloor(1_500, 0), AgentSupplyReadiness.potionFloor(HORNTAIL));
            assertEquals(List.of(216, 600), List.of(AgentSupplyReadiness.countFor(hero, HORNTAIL)[0],
                    AgentSupplyReadiness.countFor(hero, HORNTAIL)[1]), "Reindeer Milk and Honsters; no MP floor");
            assertEquals(684, AgentSupplyReadiness.shortfall(hero, HORNTAIL).hp(), "900 - 216");
            assertEquals(575, AgentSupplyReadiness.countFor(hero, AgentSupplyReadiness.Activity.partyQuest("scarga"))[0],
                    "no floor: every HP potion");

            // P18 run 5: 10% gear put Thane just past 20,000 HP, where a quarter no longer let Reindeer Milk count
            when(hero.getCurrentMaxHp()).thenReturn(20_400);
            assertEquals(216, AgentSupplyReadiness.countFor(hero, HORNTAIL)[0], "Reindeer Milk still counts");
        }
    }

    @Test
    void pinkBeanCountsOnlyPotionsThatRestoreFifteenPercentOfMaximumHpToo() {
        // P20 run 10 (2026-10-15): Ingrid bought 556 White Potions at Leafre toward Pink Bean's 600 and drank all 600
        // in under three minutes
        AgentSupplyReadiness.Activity pinkBean = AgentSupplyReadiness.Activity.partyQuest("pinkbean");
        Map<Integer, StatEffect> effects = Map.of(WHITE, effect(300, 0), REINDEER_MILK, effect(5_000, 0));
        try (MockedStatic<AgentUseItemClassificationPolicy> classification = potionEffects(effects)) {
            Character ingrid = mock(Character.class);
            when(ingrid.getId()).thenReturn(877);
            when(ingrid.getJob()).thenReturn(Job.DARKKNIGHT);
            when(ingrid.getCurrentMaxHp()).thenReturn(19_776);
            when(ingrid.getCurrentMaxMp()).thenReturn(2_924);
            Inventory use = new Inventory(ingrid, InventoryType.USE, (byte) 24);
            use.addItem(Items.itemWithQuantity(WHITE, 556));
            use.addItem(Items.itemWithQuantity(REINDEER_MILK, 44));
            when(ingrid.getInventory(InventoryType.USE)).thenReturn(use);

            assertEquals(new AgentSupplyReadiness.PotionFloor(1_500, 0), AgentSupplyReadiness.potionFloor(pinkBean));
            assertEquals(44, AgentSupplyReadiness.countFor(ingrid, pinkBean)[0], "the White Potions do not count");
            assertEquals(1_456, AgentSupplyReadiness.shortfall(ingrid, pinkBean).hp(), "1,500 - 44");
        }
    }

    @Test
    void showaCountsOnlyPotionsThatRestoreFifteenPercentOfMaximumHpToo() {
        // 2026-10-16: Showa gets Horntail's and Pink Bean's floor (Bodyguard A touches for about 16,800). Showa Town's
        // store (9120002) sells White Potions, Ice Cream Pops (2,000 HP) and Melting Cheese (4,000 HP); only the cheese
        // meets 15%, and only up to 26,666 maximum HP
        AgentSupplyReadiness.Activity showa = AgentSupplyReadiness.Activity.partyQuest("showa");
        Map<Integer, StatEffect> effects = Map.of(WHITE, effect(300, 0), ICE_CREAM_POP, effect(2_000, 0),
                MELTING_CHEESE, effect(4_000, 0));
        try (MockedStatic<AgentUseItemClassificationPolicy> classification = potionEffects(effects)) {
            Character gunnar = mock(Character.class);
            when(gunnar.getId()).thenReturn(1_005);
            when(gunnar.getJob()).thenReturn(Job.HERO);
            when(gunnar.getCurrentMaxHp()).thenReturn(19_776);
            when(gunnar.getCurrentMaxMp()).thenReturn(3_000);
            Inventory use = new Inventory(gunnar, InventoryType.USE, (byte) 24);
            use.addItem(Items.itemWithQuantity(WHITE, 300));
            use.addItem(Items.itemWithQuantity(ICE_CREAM_POP, 200));
            use.addItem(Items.itemWithQuantity(MELTING_CHEESE, 100));
            when(gunnar.getInventory(InventoryType.USE)).thenReturn(use);

            assertEquals(new AgentSupplyReadiness.PotionFloor(1_500, 0), AgentSupplyReadiness.potionFloor(showa));
            assertEquals(100, AgentSupplyReadiness.countFor(gunnar, showa)[0], "Melting Cheese only");
            assertEquals(500, AgentSupplyReadiness.shortfall(gunnar, showa).hp(), "600 - 100");

            when(gunnar.getCurrentMaxHp()).thenReturn(26_667);
            assertEquals(0, AgentSupplyReadiness.countFor(gunnar, showa)[0], "past 26,666 HP no Showa potion counts");
        }
    }

    @Test
    void aMalformedOrZeroPotionFloorCountsEveryPotion() {
        String configured = AgentYamlConfig.config.tuning.put(FLOOR_KEY, "horntail=x/0");
        try {
            assertNull(AgentSupplyReadiness.potionFloor(HORNTAIL), "malformed");
            AgentYamlConfig.config.tuning.put(FLOOR_KEY, "horntail=0/0");
            assertNull(AgentSupplyReadiness.potionFloor(HORNTAIL), "0/0");
            AgentYamlConfig.config.tuning.put(FLOOR_KEY, "pinkbean=3000/1000");
            assertNull(AgentSupplyReadiness.potionFloor(HORNTAIL), "not listed");
            assertEquals(new AgentSupplyReadiness.PotionFloor(3_000, 1_000),
                    AgentSupplyReadiness.potionFloor(AgentSupplyReadiness.Activity.partyQuest("pinkbean")));
        } finally {
            AgentYamlConfig.config.tuning.put(FLOOR_KEY, configured);
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
            // 2026-10-17: LPQ also has seal and darkness cure standards (10 + 10), which these bags lack
            AgentSupplyReadiness.Shortfall weak = AgentSupplyReadiness.shortfall(dragonKnight(RED, 40, BLUE, 50), lpq);
            assertTrue(weak.potionsNone() && weak.ammo() == 0,
                    "40 Red and 50 Blue Potions: LPQ's 40 / 50, however weak");
            assertEquals(new AgentSupplyReadiness.Shortfall(1, 0, 0, 20),
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
        return potionEffects(Map.of(RED, effect(50, 0), WHITE, effect(300, 0), UNAGI, effect(1_000, 0),
                BLUE, effect(0, 100), PURE_WATER, effect(0, 800)));
    }

    private static MockedStatic<AgentUseItemClassificationPolicy> potionEffects(Map<Integer, StatEffect> effects) {
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

    /** A potion restoring {@code hpRate} of maximum HP (a Honster's 0.6). */
    private static StatEffect rateEffect(double hpRate) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHpRate()).thenReturn(hpRate);
        return effect;
    }

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }

    @Test
    void theHorntailCureKeyCountsHolyWaterAndAllCureAndNoOtherKeyHasACureStandard() {
        // 2026-10-14 (P18): Horntail's Right Hand seals the whole altar; companions buy Holy Water in Orbis
        Character hero = mock(Character.class);
        Inventory use = new Inventory(hero, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2_050_003, 100));
        use.addItem(Items.itemWithQuantity(2_050_004, 20));
        when(hero.getInventory(InventoryType.USE)).thenReturn(use);
        AgentSupplyReadiness.Activity cure = AgentSupplyReadiness.Activity.partyQuest("horntail_cure");

        assertEquals(150, AgentSupplyReadiness.cureStandard(cure));
        assertEquals(120, AgentSupplyReadiness.carriedCures(hero));
        assertEquals(30, AgentSupplyReadiness.cureShortfall(hero, cure));
        assertEquals(0, AgentSupplyReadiness.cureStandard(HORNTAIL), "Leafre's store sells none: not at the desk");
        assertEquals(0, AgentSupplyReadiness.cureShortfall(hero, AgentSupplyReadiness.Activity.PARTY_QUEST));
        assertFalse(new AgentSupplyReadiness.Shortfall(0, 0, 0, 30).none(), "a cure shortfall is a shortfall");
        assertTrue(new AgentSupplyReadiness.Shortfall(0, 0, 0, 30).potionsNone());
    }

    @Test
    void partyQuestBossesGetSealAndDarknessStandardsAndAnAllCureCountsTowardBoth() {
        // 2026-10-17: Alishar and Papa Pixie seal and blind, Frankenroid and the Poison Golems blind (Mob.wz skills
        // 120 and 121); a desk's town sells the cure its standard lists
        Character hero = mock(Character.class);
        Inventory use = new Inventory(hero, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2_050_003, 4));
        use.addItem(Items.itemWithQuantity(2_050_001, 2));
        use.addItem(Items.itemWithQuantity(2_050_004, 3));
        when(hero.getInventory(InventoryType.USE)).thenReturn(use);
        AgentSupplyReadiness.Activity lpq = AgentSupplyReadiness.Activity.partyQuest("lpq");
        AgentSupplyReadiness.Activity epq = AgentSupplyReadiness.Activity.partyQuest("epq");
        AgentSupplyReadiness.CureKind seal = AgentSupplyReadiness.CureKind.SEAL;
        AgentSupplyReadiness.CureKind darkness = AgentSupplyReadiness.CureKind.DARKNESS;

        assertEquals(10, AgentSupplyReadiness.cureStandard(seal, lpq));
        assertEquals(10, AgentSupplyReadiness.cureStandard(darkness, lpq));
        assertEquals(7, AgentSupplyReadiness.carriedCures(hero, seal), "4 Holy Water and 3 All Cure");
        assertEquals(5, AgentSupplyReadiness.carriedCures(hero, darkness), "2 Eyedrops and 3 All Cure");
        assertEquals(3 + 5, AgentSupplyReadiness.cureShortfall(hero, lpq), "every kind together");
        assertEquals(0, AgentSupplyReadiness.cureStandard(seal, epq), "Ellin Forest's store sells no Holy Water");
        assertEquals(5, AgentSupplyReadiness.cureShortfall(hero, epq));
        for (String key : List.of("opq", "rjpq", "rjpqj")) {
            assertEquals(10, AgentSupplyReadiness.cureStandard(darkness, AgentSupplyReadiness.Activity.partyQuest(key)),
                    key);
        }
        assertEquals(0, AgentSupplyReadiness.cureStandard(seal, AgentSupplyReadiness.Activity.partyQuest("rjpq")),
                "Frankenroid never seals");
        for (String key : List.of("kpq", "hpq", "lmpq", "ppq", "horntail_cure")) {
            assertEquals(0, AgentSupplyReadiness.cureStandard(darkness, AgentSupplyReadiness.Activity.partyQuest(key)),
                    key);
        }
    }

    private static int ammoStandardFor(String key) {
        return AgentSupplyReadiness.ammoStandard(AgentSupplyReadiness.Activity.partyQuest(key));
    }
}
