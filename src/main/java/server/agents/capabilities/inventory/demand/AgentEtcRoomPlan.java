package server.agents.capabilities.inventory.demand;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemRestrictionPolicy;
import server.agents.capabilities.inventory.AgentInventoryItemPolicy;
import server.agents.integration.AgentInventoryGatewayRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Room in the ETC tab before an activity that hands its members ETC items (CWKPQ: the stage-5 weapons and the Armory's
 * loot). C9 live 2026-10-22: four of crew C's five carried 96 stacks in 96 slots, so no master's weapon and no gun
 * could land. The plan sells whole stacks of monster drops (4000xxx) that no quest asks for, from the kind it holds
 * the most stacks of, until the wanted slots are free; ores, crafting materials, game pieces, a drop any quest asks
 * for and a quest item stay (user decision 2026-10-22).
 */
public final class AgentEtcRoomPlan {
    private AgentEtcRoomPlan() {
    }

    /** Free slots in its ETC tab. */
    public static int freeSlots(Character agent) {
        Inventory etc = agent.getInventory(InventoryType.ETC);
        return etc == null ? 0 : etc.getNumFreeSlot();
    }

    /** How many of {@code wanted} free slots it lacks that selling would free: 0 with room, or with nothing to sell. */
    public static int fixableShortfall(Character agent, int wanted) {
        int lacking = wanted - freeSlots(agent);
        return lacking <= 0 ? 0 : stacksToSell(sellable(agent), AgentEtcRoomPlan::questAsksFor, lacking).size();
    }

    /** The sales that free the slots it lacks of {@code wanted}, one whole stack each; empty with room. */
    public static List<AgentEtcSaleCandidate> plan(Character agent, int wanted) {
        int lacking = wanted - freeSlots(agent);
        if (lacking <= 0) {
            return List.of();
        }
        List<AgentEtcSaleCandidate> sales = new ArrayList<>();
        for (Item stack : stacksToSell(sellable(agent), AgentEtcRoomPlan::questAsksFor, lacking)) {
            sales.add(new AgentEtcSaleCandidate(stack, stack.getQuantity(), new AgentItemDispositionProposal(
                    stack.getItemId(), "", stack.getQuantity(), 0, stack.getQuantity(),
                    AgentItemDispositionProposal.Disposition.SELL_SAFE_SURPLUS, 9, "", "",
                    List.of("etc room: " + wanted + " free slots wanted"))));
        }
        return List.copyOf(sales);
    }

    /**
     * Up to {@code lacking} whole stacks of monster drops no quest asks for, from {@code safe}: the kind with the most
     * stacks first (ties by item id), its smallest stacks first.
     */
    static List<Item> stacksToSell(List<Item> safe, IntPredicate questAsksFor, int lacking) {
        Map<Integer, List<Item>> byKind = new LinkedHashMap<>();
        for (Item item : safe) {
            if (monsterDrop(item.getItemId()) && !questAsksFor.test(item.getItemId())) {
                byKind.computeIfAbsent(item.getItemId(), ignored -> new ArrayList<>()).add(item);
            }
        }
        List<List<Item>> kinds = new ArrayList<>(byKind.values());
        kinds.sort(Comparator.<List<Item>>comparingInt(List::size).reversed()
                .thenComparingInt(stacks -> stacks.getFirst().getItemId()));
        List<Item> sales = new ArrayList<>();
        for (List<Item> stacks : kinds) {
            stacks.sort(Comparator.comparingInt(Item::getQuantity));
            for (Item stack : stacks) {
                if (sales.size() >= lacking) {
                    return sales;
                }
                sales.add(stack);
            }
        }
        return sales;
    }

    /** A monster's drop: item ids 4000000-4000999 (an item-data range, not a tuning). */
    static boolean monsterDrop(int itemId) {
        return itemId >= 4_000_000 && itemId <= 4_000_999;
    }

    private static List<Item> sellable(Character agent) {
        return AgentInventoryItemPolicy.collectSafeItems(agent, InventoryType.ETC, item -> true,
                AgentInventoryGatewayRuntime.inventory()::isQuestItem,
                itemId -> ItemRestrictionPolicy.allowsUntradeable(agent, itemId));
    }

    private static boolean questAsksFor(int itemId) {
        return AgentQuestItemDemandIndexRepository.defaultRepository().findItem(itemId).isPresent();
    }
}
