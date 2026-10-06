package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.shop.AgentPotionPurchasePolicy;
import server.agents.runtime.AgentRuntimeConfig;

/**
 * One kind of potion (HP or MP) as an ordinary restock measures it (AgentPotionPurchasePolicy): how many the Agent
 * carries, the recovery they hold, and the reserve a shop visit buys to (TARGET_RESERVE_BARS of its maximum).
 *
 * <p>An ordinary potion trip (any but a readiness duty's, which buys and is judged by count) is judged by the same
 * standard the shop buys to: it sets off only while the reserve is short, and it comes back restored once the reserve
 * is met, the carry limit is reached or the visit bought its whole allowance. Found live 2026-10-05: trips were judged
 * by a count of 200 potions while the shop bought 10-75 strong potions to the reserve, so 16 of 18 visits in an hour
 * were given up, suppressed for five minutes and asked the party, and the bots shopped again and again.
 */
public record AgentPotionReserve(int count, long capacity, long target) {

    public static AgentPotionReserve of(Character agent, AgentResourceCategory category) {
        return of(agent, category, AgentSupplyInventorySnapshot.quantity(agent, category));
    }

    private static AgentPotionReserve of(Character agent, AgentResourceCategory category, int count) {
        boolean forHp = category == AgentResourceCategory.HP_POTION;
        int maxHp = agent.getCurrentMaxHp();
        int maxMp = agent.getCurrentMaxMp();
        Inventory use = agent.getInventory(InventoryType.USE);
        long capacity = use == null ? 0L : AgentPotionInventoryPolicy.recoveryCapacity(
                use.list(), AgentUseItemClassificationPolicy::itemEffect, maxHp, maxMp, forHp);
        return new AgentPotionReserve(count, capacity,
                AgentPotionPurchasePolicy.targetReserve(forHp ? maxHp : maxMp, forHp));
    }

    /**
     * Whether its {@code category} potions call for an ordinary trip: fewer than POT_STOP, and the reserve short. A
     * standing duty holder's visit also buys toward its readiness standard by count, so for it the count alone decides.
     */
    public static boolean tripNeeded(Character agent, AgentResourceCategory category, boolean dutyHolder) {
        int count = AgentSupplyInventorySnapshot.quantity(agent, category);
        if (count >= AgentRuntimeConfig.cfg.POT_STOP) {
            return false;
        }
        return dutyHolder || !of(agent, category, count).met();
    }

    /**
     * No potion of this kind is left to buy at a shop: the reserve is met or the carry limit reached. Never met by
     * recovery while the maximum reads 0 (no reserve to measure).
     */
    public boolean met() {
        return (target > 0L && capacity >= target) || AgentPotionPurchasePolicy.carryLimitReached(count);
    }

    /** A shop visit that began with {@code countBefore} of them restored this kind: met, or its allowance bought. */
    public boolean restoredSince(int countBefore) {
        return met() || AgentPotionPurchasePolicy.visitLimitReached(count - Math.max(0, countBefore));
    }
}
