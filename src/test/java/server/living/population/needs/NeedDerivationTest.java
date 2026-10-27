package server.living.population.needs;

import client.Character;
import client.inventory.Equip;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.supplies.AgentSupplyReadiness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Needs from real state (docs/BOT_CONCEPT_PLAN.md F1): a citizen short of its Balrog standard asks for the potion its
 * store would sell it, as many as the standard's points take and its purse pays, at the shop price plus the premium; a
 * basket scroll for the piece it wears that still takes one, weapon first; the D4 gear want for about 30% of picks, the
 * same each load; and a boss party without a cleric asks for one.
 */
class NeedDerivationTest {
    private static final int RED = 2000000;
    private static final int ORANGE = 2000001;
    private static final int WHITE = 2000002;
    private static final int CLAW_ATT = 2044701;
    private static final int GLOVE_ATT = 2040804;
    private static final int CHAOS = 2049100;
    private static final int GARNIER = 1472030;
    private static final int WORK_GLOVES = 1082149;

    private static final List<ShopItem> ANT_TUNNEL = List.of(new ShopItem((short) 1, RED, 50, 0),
            new ShopItem((short) 1, ORANGE, 160, 0), new ShopItem((short) 1, WHITE, 300, 0));

    /** White, Orange and Red Potion as their WZ effects restore (no item data in a unit test). */
    private static MockedStatic<AgentUseItemClassificationPolicy> potions() {
        StatEffect red = heals(50);
        StatEffect orange = heals(150);
        StatEffect white = heals(300);
        MockedStatic<AgentUseItemClassificationPolicy> potions = mockStatic(AgentUseItemClassificationPolicy.class,
                Mockito.CALLS_REAL_METHODS);
        potions.when(() -> AgentUseItemClassificationPolicy.itemEffect(RED)).thenReturn(red);
        potions.when(() -> AgentUseItemClassificationPolicy.itemEffect(ORANGE)).thenReturn(orange);
        potions.when(() -> AgentUseItemClassificationPolicy.itemEffect(WHITE)).thenReturn(white);
        return potions;
    }

    private static StatEffect heals(int hp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        return effect;
    }

    private static Character lv60() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(5001);
        when(agent.getCurrentMaxHp()).thenReturn(3000);
        when(agent.getCurrentMaxMp()).thenReturn(1200);
        return agent;
    }

    @Test
    void aCitizenShortOfItsBalrogPotionsAsksForThePotionItsStoreSellsAtTheShopPrice() {
        Character agent = lv60();
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potions();
             MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class, Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.shortfall(eq(agent), any(AgentSupplyReadiness.Activity.class)))
                    .thenReturn(new AgentSupplyReadiness.Shortfall(30_000, 0)); // 30,000 HP points short of 130,000

            List<NeedDerivation.Proposal> needs = NeedDerivation.supply(agent, NeedLedger.SUPPLY_BOSS, ANT_TUNNEL,
                    1_000_000L, 1.15d);

            assertEquals(1, needs.size(), String.valueOf(needs));
            NeedDerivation.Proposal white = needs.get(0);
            assertEquals(NeedKind.SUPPLY, white.kind());
            assertEquals(WHITE, white.itemId(), "a 3,000 HP citizen buys White Potions, not Reds");
            assertEquals(100, white.quantity(), "30,000 points at 300 a White Potion");
            assertEquals(345L, white.offer(), "the shop's 300 plus 15%, rounded up");
            assertEquals("easy_balrog", white.detail());
        }
    }

    @Test
    void itAsksForNoMoreThanItsPurseAndNothingWhenItCarriesEnough() {
        Character agent = lv60();
        try (MockedStatic<AgentUseItemClassificationPolicy> effects = potions();
             MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class, Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.shortfall(eq(agent), any(AgentSupplyReadiness.Activity.class)))
                    .thenReturn(new AgentSupplyReadiness.Shortfall(30_000, 0));
            assertEquals(28, NeedDerivation.supply(agent, NeedLedger.SUPPLY_BOSS, ANT_TUNNEL, 10_000L, 1.15d).get(0).quantity(),
                    "10,000 meso pays for 28 at 345");
            assertTrue(NeedDerivation.supply(agent, NeedLedger.SUPPLY_BOSS, ANT_TUNNEL, 100L, 1.15d).isEmpty(), "broke");

            readiness.when(() -> AgentSupplyReadiness.shortfall(eq(agent), any(AgentSupplyReadiness.Activity.class)))
                    .thenReturn(AgentSupplyReadiness.Shortfall.NONE);
            assertTrue(NeedDerivation.supply(agent, NeedLedger.SUPPLY_BOSS, ANT_TUNNEL, 1_000_000L, 1.15d).isEmpty());
        }
    }

    @Test
    void aBasketScrollForTheWeaponFirstWhenThePieceStillTakesOneAndThePurseCovers() {
        Equip claw = equip(GARNIER, (short) -11, 3);
        Equip gloves = equip(WORK_GLOVES, (short) -8, 5);
        NeedDerivation.ScrollFit fits = (scroll, piece) -> scroll == CLAW_ATT && piece == GARNIER
                || scroll == GLOVE_ATT && piece == WORK_GLOVES || scroll == CHAOS;
        List<Integer> basket = List.of(CHAOS, GLOVE_ATT, CLAW_ATT);

        NeedDerivation.Proposal want = NeedDerivation.scroll(List.of(gloves, claw), basket, fits, id -> 2_500_000L, 3_000_000L);
        assertEquals(CLAW_ATT, want.itemId(), "the weapon before the gloves; never Chaos");
        assertEquals(String.valueOf(GARNIER), want.detail(), "the piece it is for");
        assertEquals(2_500_000L, want.offer(), "the index price");

        assertEquals(GLOVE_ATT, NeedDerivation.scroll(List.of(gloves, equip(GARNIER, (short) -11, 0)), basket, fits,
                id -> 900_000L, 1_000_000L).itemId(), "a claw with no slots left: the gloves");
        assertNull(NeedDerivation.scroll(List.of(gloves, claw), basket, fits, id -> 2_500_000L, 1_000_000L),
                "nothing it can pay for");
    }

    @Test
    void aboutAThirdOfFreeUpgradesBecomeWantsTheSameEveryLoad() {
        int wanted = 0;
        for (int pick = 1_302_000; pick < 1_302_400; pick++) {
            boolean first = NeedDerivation.wanted(5001, pick, 0.3d);
            assertEquals(first, NeedDerivation.wanted(5001, pick, 0.3d), "stable per citizen and pick");
            wanted += first ? 1 : 0;
        }
        assertTrue(wanted > 90 && wanted < 150, wanted + " of 400");
        assertEquals(0, countWanted(0.0d));
    }

    private static int countWanted(double share) {
        int wanted = 0;
        for (int pick = 1_302_000; pick < 1_302_100; pick++) {
            wanted += NeedDerivation.wanted(5001, pick, share) ? 1 : 0;
        }
        return wanted;
    }

    @Test
    void aBossPartyWithoutAClericAsksForOne() {
        assertEquals("cleric", NeedDerivation.role(List.of(110, 410, 320), true));
        assertEquals("", NeedDerivation.role(List.of(110, 231), true), "a priest is there");
        assertEquals("", NeedDerivation.role(List.of(110), false), "a party quest asks for more, any job");
    }

    @Test
    void itemsAreSpokenAsPlayersTypeThem() {
        assertEquals("claw att 60%", NeedLedger.spoken("scroll for claw for att", 60));
        assertEquals("gloves att 10%", NeedLedger.spoken("scroll for gloves for att", 10));
        assertEquals("wand matk 60%", NeedLedger.spoken("scroll for wand for magic att.", 60));
        assertEquals("knuckler att 60%", NeedLedger.spoken("scroll for knuckler for attack 60%", 60));
        assertEquals("white potion", NeedLedger.spoken("white potion", 0));
        assertEquals(NeedKind.SUPPLY, NeedLedger.kindOf("need.supply.fm"));
        assertNull(NeedLedger.kindOf("needs.got"));
        assertNull(NeedLedger.kindOf("fm.buy"));
    }

    private static Equip equip(int itemId, short position, int slots) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getPosition()).thenReturn(position);
        when(equip.getUpgradeSlots()).thenReturn((byte) slots);
        return equip;
    }
}
