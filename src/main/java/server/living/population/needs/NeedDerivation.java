package server.living.population.needs;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ShopItem;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.shop.AgentShopPotionPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntToLongFunction;

/**
 * What a citizen lacks, from its real state (docs/BOT_CONCEPT_PLAN.md F1): the potion its boss store would sell it,
 * short of the boss's standard; a basket scroll for a piece it wears; whether a free gear upgrade becomes a want (D4);
 * the role a forming party lacks. Nothing here is stored or said: the ledger turns proposals into requests.
 */
final class NeedDerivation {
    /** Clean Slate and Chaos (2049xxx) change a piece rather than build it: nobody posts them for one piece. */
    private static final int MODIFIER_SCROLLS = 2049;
    /** Worn pieces in the order a player scrolls them: weapon, gloves, then the rest. */
    private static final List<Short> SCROLL_ORDER = List.of((short) -11, (short) -8, (short) -1, (short) -5, (short) -7,
            (short) -9, (short) -6, (short) -10);
    private static final int CLERIC_LINE = 23;

    /** A request to open: {@code quantity} of {@code itemId} at {@code offer} a unit, for {@code detail}. */
    record Proposal(NeedKind kind, int itemId, int quantity, long offer, String detail) { }

    private NeedDerivation() {
    }

    /**
     * The potions {@code agent} lacks for {@code bossKey}, as requests (HP first, then MP): each the pick its store would
     * sell it ({@code shop}), as many as the standard lacks (points turned into that potion's count), at its shop price
     * times {@code premium}, and no more than {@code purse} pays for. Empty when it carries enough.
     */
    static List<Proposal> supply(Character agent, String bossKey, List<ShopItem> shop, long purse, double premium) {
        List<Proposal> out = new ArrayList<>();
        AgentSupplyReadiness.Activity activity = AgentSupplyReadiness.Activity.partyQuest(bossKey);
        AgentSupplyReadiness.Shortfall shortfall = AgentSupplyReadiness.shortfall(agent, activity);
        boolean points = AgentSupplyReadiness.recoveryStandard(activity) != null;
        long left = purse;
        for (boolean forHp : new boolean[]{true, false}) {
            int lacking = forHp ? shortfall.hp() : shortfall.mp();
            if (lacking <= 0) {
                continue;
            }
            int max = forHp ? agent.getCurrentMaxHp() : agent.getCurrentMaxMp();
            float threshold = forHp ? AgentPotionService.hpThreshold(agent) : AgentPotionService.mpThreshold(agent);
            AgentShopPotionPolicy.PotionShopSlot pick = AgentShopPotionPolicy.selectPotionItem(shop, agent.getCurrentMaxHp(),
                    agent.getCurrentMaxMp(), Math.max(1, Math.round(max * (1.0f - threshold))), forHp,
                    AgentUseItemClassificationPolicy::isRecoveryPotion, AgentUseItemClassificationPolicy::itemEffect);
            if (pick == null) {
                continue;
            }
            int want = points ? AgentSupplyReadiness.potionsFor(lacking, pick.recovery().primary()) : lacking;
            long unit = unitOffer(pick.shopItem().getPrice(), premium);
            int count = affordable(want, unit, left);
            if (count > 0) {
                out.add(new Proposal(NeedKind.SUPPLY, pick.shopItem().getItemId(), count, unit, bossKey));
                left -= unit * count;
            }
        }
        return out;
    }

    /** A shop good's offer: its NPC price times the premium, rounded up; at least 1. */
    static long unitOffer(int shopPrice, double premium) {
        return Math.max(1L, (long) Math.ceil(Math.max(0, shopPrice) * premium));
    }

    /** How many of {@code want} at {@code unit} a purse of {@code purse} pays for. */
    static int affordable(int want, long unit, long purse) {
        if (want <= 0 || unit <= 0L || purse <= 0L) {
            return 0;
        }
        return (int) Math.min(want, purse / unit);
    }

    /**
     * A basket scroll for a piece the citizen wears that still takes scrolls (weapon first, then gloves, then the rest),
     * at its index price, when its purse pays for it; null otherwise. {@code fits(scroll, equip)} is the scroll rule.
     */
    static Proposal scroll(List<Equip> worn, List<Integer> basket, ScrollFit fits, IntToLongFunction price, long purse) {
        List<Equip> pieces = new ArrayList<>(worn);
        pieces.sort(Comparator.comparingInt(equip -> {
            int order = SCROLL_ORDER.indexOf(equip.getPosition());
            return order < 0 ? SCROLL_ORDER.size() : order;
        }));
        for (Equip piece : pieces) {
            if (piece.getUpgradeSlots() <= 0) {
                continue;
            }
            for (int scroll : basket) {
                if (scroll / 1000 == MODIFIER_SCROLLS || scroll / 10_000 != 204 || !fits.fits(scroll, piece.getItemId())) {
                    continue;
                }
                long offer = price.applyAsLong(scroll);
                if (offer > 0L && offer <= purse) {
                    return new Proposal(NeedKind.SCROLL, scroll, 1, offer, String.valueOf(piece.getItemId()));
                }
            }
        }
        return null;
    }

    /** The scroll rule ({@code ItemInformationProvider.canApplyScroll}), apart so tests need no WZ data. */
    interface ScrollFit {
        boolean fits(int scrollId, int equipId);
    }

    /** What the citizen wears, as equips. */
    static List<Equip> worn(Character agent) {
        List<Equip> out = new ArrayList<>();
        for (Item item : agent.getInventory(InventoryType.EQUIPPED).list()) {
            if (item instanceof Equip equip) {
                out.add(equip);
            }
        }
        return out;
    }

    /**
     * Decision D4: whether this free upgrade becomes a want. Stable per citizen and pick, so every load of it decides
     * the same way, and about {@code share} of them do.
     */
    static boolean wanted(int citizenId, int pick, double share) {
        return new SplittableRandom(citizenId * 31L + pick).nextDouble() < share;
    }

    /** The role a forming party lacks: a boss party without a cleric asks for one; anything else for "more". */
    static String role(List<Integer> memberJobIds, boolean boss) {
        if (!boss) {
            return "";
        }
        for (int job : memberJobIds) {
            if (job / 10 == CLERIC_LINE) {
                return "";
            }
        }
        return "cleric";
    }
}
