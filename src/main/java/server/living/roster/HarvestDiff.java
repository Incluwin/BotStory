package server.living.roster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/**
 * What a live bench slice picked up (docs/BOT_MANAGEMENT_PLAN.md P5, "Harvest counts kill loot and meso only"): the
 * pickup hook (LivingWorldHooks.looted) counts every item stack and meso drop the companion picks up, and the harvest
 * takes only that into the haul. Coin its owner gives it, potions it buys, gear it puts on (and the gear that comes
 * off) and stall stock moved into its bag are never picked up, so they are never taken. What it picked up and no
 * longer holds (drank, sold, wore) is forgotten at the next take. The totals are the slice's yield. Thread-safe: the
 * pickups come from the Agent's tick, the takes from companion-ops.
 */
final class HarvestDiff {
    private final Map<Integer, Integer> items = new ConcurrentHashMap<>();
    private final AtomicLong meso = new AtomicLong();
    private final AtomicLong itemsTotal = new AtomicLong();
    private final AtomicLong mesoTotal = new AtomicLong();

    void item(int itemId, int quantity) {
        if (itemId > 0 && quantity > 0) {
            items.merge(itemId, quantity, Integer::sum);
            itemsTotal.addAndGet(quantity);
        }
    }

    void meso(int amount) {
        if (amount > 0) {
            meso.addAndGet(amount);
            mesoTotal.addAndGet(amount);
        }
    }

    /** The meso to take now: what it picked up since the last take, never more than {@code purse} holds. */
    long takeMeso(long purse) {
        long picked = meso.getAndSet(0L);
        return Math.max(0L, Math.min(picked, purse));
    }

    /**
     * Item id to the quantity to take now: each item it picked up since the last take, never more than it holds now
     * ({@code held} by item id).
     */
    Map<Integer, Integer> takeItems(IntUnaryOperator held) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Integer itemId : items.keySet()) {
            Integer picked = items.remove(itemId);
            int take = picked == null ? 0 : Math.min(picked, held.applyAsInt(itemId));
            if (take > 0) {
                out.put(itemId, take);
            }
        }
        return out;
    }

    /** Items picked up over the whole slice (a count), taken or not. */
    long itemsTotal() {
        return itemsTotal.get();
    }

    /** Meso picked up over the whole slice, taken or not. */
    long mesoTotal() {
        return mesoTotal.get();
    }
}
