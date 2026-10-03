package server.agents.capabilities.shop;

import org.junit.jupiter.api.Test;
import server.ShopItem;
import server.StatEffect;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentShopPotionPolicyTest {
    @Test
    void shouldChooseCheapestPotionInsideRecoveryBand() {
        ShopItem expensive = shopItem(2000, 300);
        ShopItem cheap = shopItem(2001, 100);
        Map<Integer, StatEffect> effects = Map.of(
                2000, effect(300, 0, 0, 0),
                2001, effect(250, 0, 0, 0));

        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectPotionItem(
                List.of(expensive, cheap), 1000, 500, 300, true,
                effects::containsKey, effects::get);

        assertEquals(1, selected.slot());
        assertEquals(cheap, selected.shopItem());
    }

    @Test
    void shouldFallbackToStrongestPotionBelowBand() {
        ShopItem weak = shopItem(2000, 100);
        ShopItem stronger = shopItem(2001, 100);
        Map<Integer, StatEffect> effects = Map.of(
                2000, effect(20, 0, 0, 0),
                2001, effect(80, 0, 0, 0));

        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectPotionItem(
                List.of(weak, stronger), 1000, 500, 300, true,
                effects::containsKey, effects::get);

        assertEquals(1, selected.slot());
        assertEquals(stronger, selected.shopItem());
    }

    @Test
    void shouldFallbackToWeakestPotionAboveBandWhenNoLowerChoiceExists() {
        ShopItem huge = shopItem(2000, 100);
        ShopItem smaller = shopItem(2001, 100);
        Map<Integer, StatEffect> effects = Map.of(
                2000, effect(900, 0, 0, 0),
                2001, effect(700, 0, 0, 0));

        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectPotionItem(
                List.of(huge, smaller), 1000, 500, 300, true,
                effects::containsKey, effects::get);

        assertEquals(1, selected.slot());
        assertEquals(smaller, selected.shopItem());
    }

    @Test
    void shouldFilterFreeNonRecoveryAndWrongResourceItemsButAllowPercentagePotions() {
        ShopItem free = shopItem(2000, 0);
        ShopItem nonRecovery = shopItem(2001, 100);
        ShopItem percentHp = shopItem(2002, 100);
        ShopItem hpPotion = shopItem(2003, 100);
        Map<Integer, StatEffect> effects = Map.of(
                2000, effect(250, 0, 0, 0),
                2001, effect(250, 0, 0, 0),
                2002, effect(0, 0, 0.25, 0),
                2003, effect(250, 0, 0, 0));

        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectPotionItem(
                List.of(free, nonRecovery, percentHp, hpPotion),
                1000,
                500,
                250,
                true,
                itemId -> itemId != 2001,
                effects::get);

        assertEquals(2, selected.slot());
        assertEquals(percentHp, selected.shopItem());
        assertEquals(250, selected.recovery().primary());

        assertNull(AgentShopPotionPolicy.selectPotionItem(
                List.of(hpPotion), 1000, 500, 100, false,
                effects::containsKey, effects::get));
    }

    @Test
    void selectForCountBuysTheStrongestPotionTheBudgetCoversWantOfWhenTheCoveragePickDoesNot() {
        // Lv25 maxHP 1060 with 1080 mesos and 19 HP potions to buy toward the readiness standard
        ShopItem red = shopItem(2000000, 50);
        ShopItem orange = shopItem(2000001, 160);
        ShopItem white = shopItem(2000002, 320);
        Map<Integer, StatEffect> effects = Map.of(
                2000000, effect(50, 0, 0, 0),
                2000001, effect(150, 0, 0, 0),
                2000002, effect(300, 0, 0, 0));
        List<ShopItem> items = List.of(red, orange, white);
        AgentShopPotionPolicy.PotionShopSlot coverage = AgentShopPotionPolicy.selectPotionItem(
                items, 1060, 500, 1060, true, effects::containsKey, effects::get);
        assertEquals(white, coverage.shopItem(), "the reserve's own pick");

        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectForCount(
                items, coverage, 19, 1080, 1060, 500, true, effects::containsKey, effects::get);

        assertEquals(red, selected.shopItem(), "Red @50 x 19 = 950 fits, Orange x 19 = 3040 does not");
        assertEquals(0, selected.slot());
        assertEquals(white, AgentShopPotionPolicy.selectForCount(items, coverage, 3, 1080, 1060, 500, true,
                effects::containsKey, effects::get).shopItem(), "the coverage pick when want of it fit");
        assertEquals(red, AgentShopPotionPolicy.selectForCount(items, coverage, 30, 1080, 1060, 500, true,
                effects::containsKey, effects::get).shopItem(), "nothing fits: the cheapest, as many as it buys");
        assertEquals(50, AgentShopPotionPolicy.cheapestPrice(items, 1060, 500, true,
                effects::containsKey, effects::get));
        assertEquals(-1, AgentShopPotionPolicy.cheapestPrice(items, 1060, 500, false,
                effects::containsKey, effects::get), "no mana potion on sale");
    }

    private static ShopItem shopItem(int itemId, int price) {
        ShopItem item = mock(ShopItem.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getPrice()).thenReturn(price);
        return item;
    }

    private static StatEffect effect(int hp, int mp, double hpRate, double mpRate) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        when(effect.getHpRate()).thenReturn((double) hpRate);
        when(effect.getMpRate()).thenReturn((double) mpRate);
        return effect;
    }
}
