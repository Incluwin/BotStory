package server.agents.capabilities.shop;

import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.supplies.AgentPotionRecoveryPolicy;
import server.agents.capabilities.supplies.AgentPotionRecoveryPolicy.Recovery;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

public final class AgentShopPotionPolicy {
    private static final int IDEAL_MIN_COVERAGE_BPS = config.AgentTuning.intValue(
            "server.agents.capabilities.shop.AgentShopPotionPolicy.IDEAL_MIN_COVERAGE_BPS");
    private static final int IDEAL_MAX_COVERAGE_BPS = config.AgentTuning.intValue(
            "server.agents.capabilities.shop.AgentShopPotionPolicy.IDEAL_MAX_COVERAGE_BPS");

    private AgentShopPotionPolicy() {
    }

    public record PotionShopSlot(short slot, ShopItem shopItem, Recovery recovery) {
    }

    public static PotionShopSlot selectPotionItem(List<ShopItem> items,
                                                  int maxHp,
                                                  int maxMp,
                                                  int targetDeficit,
                                                  boolean forHp,
                                                  IntPredicate recoveryPotion,
                                                  IntFunction<StatEffect> effectLookup) {
        PotionShopSlot best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem item = items.get(i);
            if (item.getPrice() <= 0) {
                continue;
            }
            int itemId = item.getItemId();
            if (!recoveryPotion.test(itemId)) {
                continue;
            }

            StatEffect effect = effectLookup.apply(itemId);
            if (effect == null) {
                continue;
            }
            Recovery recovery =
                    AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, forHp);
            if (recovery == null) {
                continue;
            }
            PotionShopSlot candidate = new PotionShopSlot((short) i, item, recovery);
            if (better(candidate, best, targetDeficit)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The potion to buy {@code want} of for a count (a readiness standard), within {@code budget} mesos: the coverage
     * pick ({@link #selectPotionItem}) when {@code want} of it fit the budget; else the strongest non-mixed potion of
     * the category whose {@code want} fit; else the cheapest potion of the category, of which the budget buys what it
     * can. Null when the shop sells no potion of the category.
     */
    public static PotionShopSlot selectForCount(List<ShopItem> items,
                                                PotionShopSlot coveragePick,
                                                int want,
                                                long budget,
                                                int maxHp,
                                                int maxMp,
                                                boolean forHp,
                                                IntPredicate recoveryPotion,
                                                IntFunction<StatEffect> effectLookup) {
        if (coveragePick != null && (long) coveragePick.shopItem().getPrice() * want <= budget) {
            return coveragePick;
        }
        PotionShopSlot strongest = null;
        PotionShopSlot cheapest = coveragePick;
        for (int i = 0; i < items.size(); i++) {
            ShopItem item = items.get(i);
            if (item.getPrice() <= 0 || !recoveryPotion.test(item.getItemId())) {
                continue;
            }
            StatEffect effect = effectLookup.apply(item.getItemId());
            Recovery recovery = effect == null ? null
                    : AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, forHp);
            if (recovery == null) {
                continue;
            }
            PotionShopSlot candidate = new PotionShopSlot((short) i, item, recovery);
            if (!recovery.mixed() && (long) item.getPrice() * want <= budget
                    && (strongest == null || recovery.primary() > strongest.recovery().primary())) {
                strongest = candidate;
            }
            if (cheapest == null || item.getPrice() < cheapest.shopItem().getPrice()) {
                cheapest = candidate;
            }
        }
        return strongest != null ? strongest : cheapest;
    }

    /** The cheapest price of a recovery potion of the category on sale, or -1 when the shop sells none. */
    public static int cheapestPrice(List<ShopItem> items,
                                    int maxHp,
                                    int maxMp,
                                    boolean forHp,
                                    IntPredicate recoveryPotion,
                                    IntFunction<StatEffect> effectLookup) {
        int cheapest = -1;
        for (ShopItem item : items) {
            if (item.getPrice() <= 0 || !recoveryPotion.test(item.getItemId())) {
                continue;
            }
            StatEffect effect = effectLookup.apply(item.getItemId());
            if (effect != null && AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, forHp) != null
                    && (cheapest < 0 || item.getPrice() < cheapest)) {
                cheapest = item.getPrice();
            }
        }
        return cheapest;
    }

    private static boolean better(
            PotionShopSlot candidate, PotionShopSlot current, int targetDeficit) {
        if (current == null) {
            return true;
        }
        int candidateBand = band(candidate.recovery.primary(), targetDeficit);
        int currentBand = band(current.recovery.primary(), targetDeficit);
        if (candidateBand != currentBand) {
            return candidateBand < currentBand;
        }
        if (candidate.recovery.mixed() != current.recovery.mixed()) {
            return !candidate.recovery.mixed();
        }
        if (candidateBand == 1
                && candidate.recovery.primary() != current.recovery.primary()) {
            return candidate.recovery.primary() > current.recovery.primary();
        }
        if (candidateBand == 2
                && candidate.recovery.primary() != current.recovery.primary()) {
            return candidate.recovery.primary() < current.recovery.primary();
        }
        long candidateCost = (long) candidate.shopItem.getPrice()
                * Math.max(1, current.recovery.weightedRecovery());
        long currentCost = (long) current.shopItem.getPrice()
                * Math.max(1, candidate.recovery.weightedRecovery());
        if (candidateCost != currentCost) {
            return candidateCost < currentCost;
        }
        int candidateDistance = Math.abs(candidate.recovery.primary() - targetDeficit);
        int currentDistance = Math.abs(current.recovery.primary() - targetDeficit);
        if (candidateDistance != currentDistance) {
            return candidateDistance < currentDistance;
        }
        return candidate.shopItem.getItemId() < current.shopItem.getItemId();
    }

    private static int band(int recovery, int targetDeficit) {
        int coverage = AgentPotionRecoveryPolicy.coverageBasisPoints(recovery, targetDeficit);
        if (coverage >= IDEAL_MIN_COVERAGE_BPS && coverage <= IDEAL_MAX_COVERAGE_BPS) {
            return 0;
        }
        return coverage < IDEAL_MIN_COVERAGE_BPS ? 1 : 2;
    }
}
