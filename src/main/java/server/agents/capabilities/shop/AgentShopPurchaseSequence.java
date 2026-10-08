package server.agents.capabilities.shop;

import client.Character;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeHandle;

import java.awt.Point;
import java.util.List;

/**
 * Agent-owned runtime context for the temporary AgentShopService purchase flow. {@code unspokenUnits}: what the visit
 * bought that its spoken "bought ..." line leaves out (a readiness trip's ammo, AgentShopService.buyReadinessAmmo),
 * so the visit still counts as one that bought something. {@code quietShortfall}: the visit fell short of something
 * it does not say at the counter (a readiness trip's ammo, or the extra potions past its standard; the trip's own back
 * and broke lines tell it), so it never closes with "turned out I didn't need anything here".
 */
public record AgentShopPurchaseSequence<E extends AgentRuntimeHandle>(E entry,
                                        Character bot,
                                        InventoryGateway inventory,
                                        Point npcPos,
                                        List<AgentShopPurchaseAction<E>> actions,
                                        List<String> bought,
                                        AgentShopBuyReport firstShortfall,
                                        int unspokenUnits,
                                        boolean quietShortfall) {
    public AgentShopPurchaseSequence(E entry, Character bot, InventoryGateway inventory, Point npcPos,
                                     List<AgentShopPurchaseAction<E>> actions, List<String> bought,
                                     AgentShopBuyReport firstShortfall) {
        this(entry, bot, inventory, npcPos, actions, bought, firstShortfall, 0, false);
    }

    public AgentShopPurchaseSequence<E> withFirstShortfall(AgentShopBuyReport report) {
        if (firstShortfall == null && report != null && report.hasShortfall()) {
            return new AgentShopPurchaseSequence<>(entry, bot, inventory, npcPos, actions, bought, report,
                    unspokenUnits, quietShortfall);
        }
        return this;
    }

    /** The visit fell short where {@code report} says, and does not say so at the counter. */
    public AgentShopPurchaseSequence<E> withQuietShortfall(AgentShopBuyReport report) {
        if (quietShortfall || report == null || !report.hasShortfall()) {
            return this;
        }
        return new AgentShopPurchaseSequence<>(entry, bot, inventory, npcPos, actions, bought, firstShortfall,
                unspokenUnits, true);
    }

    /** The visit bought {@code units} more that its spoken line leaves out. */
    public AgentShopPurchaseSequence<E> withUnspoken(int units) {
        if (units <= 0) {
            return this;
        }
        return new AgentShopPurchaseSequence<>(entry, bot, inventory, npcPos, actions, bought, firstShortfall,
                (int) Math.min(Integer.MAX_VALUE, (long) unspokenUnits + units), quietShortfall);
    }

    /** True when the visit bought anything, spoken or not. */
    public boolean boughtAnything() {
        return !bought.isEmpty() || unspokenUnits > 0;
    }
}
