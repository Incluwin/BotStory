package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/**
 * Looted Use stacks a shop visit sells for the Use slots its potions need, once no ammo for another weapon is left to
 * sell (AgentShopService.makeUseRoom): equipment scrolls (204xxxx) and skill and mastery books (228xxxx, 229xxxx) that
 * teach no job of its line. The stacks worth least go first, one whole stack per slot missing.
 *
 * <p>Found live 2026-11-11: 116 of 219 active Lv30+ warrior citizens carried 95 or 96 of their 96 Use slots, about 43
 * of them scrolls and 9 books. A potion stack drunk to nothing leaves its slot, and the next looted scroll takes it, so
 * a warrior that had drunk its HP potions found no slot for new ones. The 2026-10-13 room sale sells only ammo for a
 * weapon it does not wield; warriors with none left (TinyCat: 49 scroll stacks, 35 books, no ammo) were refused their
 * HP potions on every visit ("HP_POTION 0 -> 0 ... a full Use tab", 454 refusals for 22 citizens in two days) and fought
 * on with none.
 *
 * <p>The Agent layer stays market-free: the host installs the {@link Rule}, which says which Agents sell loot for room
 * (the population: citizens no player hired) and what one piece is worth (its price index). With nothing installed no
 * Agent sells loot, as before.
 */
public final class AgentUseRoomLoot {
    /** The host's answer to who sells loot for potion room and what it is worth. */
    public interface Rule {
        /** Whether {@code agent} sells looted scrolls and books when its potions need Use room. */
        boolean sellsLoot(Character agent);

        /** What one {@code itemId} is worth in mesos (the host's market); the cheapest stacks are sold first. */
        long worth(int itemId);
    }

    private static volatile Rule rule;

    private AgentUseRoomLoot() {
    }

    /** Installs the host's rule (null: no Agent sells loot for room). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /** Whether its host lets {@code agent} sell loot for potion room; false without a rule or when it fails. */
    public static boolean sellsLoot(Character agent) {
        Rule current = rule;
        if (current == null || agent == null) {
            return false;
        }
        try {
            return current.sellsLoot(agent);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** What one {@code itemId} is worth to the host's market; Long.MAX_VALUE (sold last) when it cannot say. */
    static long worth(int itemId) {
        Rule current = rule;
        if (current == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.max(0L, current.worth(itemId));
        } catch (RuntimeException failure) {
            return Long.MAX_VALUE;
        }
    }

    /** True for an equipment scroll (204xxxx), the loot a Use tab holds the most of. */
    public static boolean isScroll(int itemId) {
        return itemId / 10_000 == 204;
    }

    /** True for a skill book (228xxxx) or mastery book (229xxxx). */
    public static boolean isBook(int itemId) {
        int kind = itemId / 10_000;
        return kind == 228 || kind == 229;
    }

    /** True for loot this sale may take: a scroll or a book. */
    public static boolean isLoot(int itemId) {
        return isScroll(itemId) || isBook(itemId);
    }

    /**
     * {@code candidates} in the order the sale takes them: the whole stack worth least first ({@code worth} of one
     * piece times its quantity, saturating), ties by slot.
     */
    static List<Item> worthLeastFirst(List<Item> candidates, IntToLongFunction worth) {
        Map<Item, Long> stackWorth = new HashMap<>();
        for (Item item : candidates) {
            long one = worth.applyAsLong(item.getItemId());
            int quantity = Math.max(1, item.getQuantity());
            stackWorth.put(item, one > Long.MAX_VALUE / quantity ? Long.MAX_VALUE : one * quantity);
        }
        List<Item> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong((Item item) -> stackWorth.get(item))
                .thenComparingInt(Item::getPosition));
        return ordered;
    }
}
