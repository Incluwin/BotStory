package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Item;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.looting.AgentPassiveLootRuntimeService;
import server.agents.capabilities.trade.AgentInventoryTransferService;
import server.agents.capabilities.trade.AgentManualTradeRuntimeService;
import server.agents.capabilities.trade.AgentTradeLifecycleRuntimeService;
import server.agents.capabilities.trade.AgentTradeTickRuntimeService;
import server.agents.capabilities.trade.AgentTradeTransferAvailabilityRuntimeService;
import server.agents.capabilities.trade.AgentTradeWindow;
import server.agents.integration.AgentInventoryRuntimeAdapters;
import server.agents.integration.AgentEconomyRuntime;
import server.agents.integration.AgentTradeGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * Agent-owned entry points for inventory, loot, and trade tick behavior.
 */
public final class AgentInventoryTickRuntime {
    private AgentInventoryTickRuntime() {
    }

    public static void tickPassiveLoot(AgentRuntimeEntry entry, Character agent) {
        AgentPassiveLootRuntimeService.tickPassiveLoot(
                entry,
                agent,
                AgentInventoryRuntimeAdapters.passiveLootRuntimeCallbacks());
    }

    public static void tickManualTrade(AgentRuntimeEntry entry, Character agent) {
        if (AgentEconomyRuntime.handleManualTrade(agent)) return;
        AgentManualTradeRuntimeService.tickManualTrade(
                entry,
                agent,
                tradeOwner(entry, agent),
                AgentInventoryRuntimeAdapters.manualTradeRuntimeCallbacks(entry),
                AgentTradeLifecycleRuntimeService.lifecycleCallbacks(
                        AgentInventoryRuntimeAdapters.tradeLifecycleRuntimeCallbacks()));
    }

    /**
     * Whose trade the Agent takes as its owner's: its interaction target, and 2026-10-15 (P20 run 3) for a bench ally,
     * which leads itself (its interaction target is itself), the human it stays with in its boss run
     * (AgentBossRunRegistry.selfLedAnchor) while that human is its trade partner: its owner may hand it potions by
     * "Let's trade" on its card when it says on arrival that it is short.
     */
    static Character tradeOwner(AgentRuntimeEntry entry, Character agent) {
        Character target = AgentRelationshipRuntime.interactionTarget(entry);
        int anchorId = agent == null ? 0 : AgentBossRunRegistry.selfLedAnchor(agent.getId());
        if (anchorId == 0 || target != null && target.getId() == anchorId) {
            return target;
        }
        AgentTradeWindow trade = AgentTradeGatewayRuntime.trade().currentWindow(agent);
        AgentTradeWindow partner = trade == null ? null : trade.partner();
        Character other = partner == null ? null : partner.character();
        return other != null && other.getId() == anchorId ? other : target;
    }

    public static void executeChoice(String category, boolean tradeToLeader, AgentRuntimeEntry entry, Character agent) {
        AgentInventoryTransferService.executeChoice(category, tradeToLeader, entry, agent);
    }

    public static void startTradeTransfer(String category, AgentRuntimeEntry entry, Character agent) {
        AgentInventoryTransferService.startTradeTransfer(category, entry, agent);
    }

    public static void startTradeTransfer(Item item, Character recipient, AgentRuntimeEntry entry, Character agent) {
        AgentInventoryTransferService.startTradeTransfer(item, recipient, entry, agent);
    }

    public static boolean hasTransferableItems(String category, AgentRuntimeEntry entry, Character agent) {
        return AgentTradeTransferAvailabilityRuntimeService.hasTransferableItems(
                category,
                entry,
                agent,
                AgentInventoryRuntimeAdapters.transferAvailabilityRuntimeCallbacks(),
                AgentInventoryRuntimeAdapters.tradeRuntimeCallbacks(entry, agent));
    }

    public static int countTransferableItems(String category, AgentRuntimeEntry entry, Character agent) {
        return AgentTradeTransferAvailabilityRuntimeService.countTransferableItems(
                category,
                entry,
                agent,
                AgentInventoryRuntimeAdapters.transferAvailabilityRuntimeCallbacks(),
                AgentInventoryRuntimeAdapters.tradeRuntimeCallbacks(entry, agent));
    }

    public static void tickTrade(AgentRuntimeEntry entry, Character agent) {
        AgentTradeTickRuntimeService.tickTrade(
                entry,
                agent,
                AgentInventoryRuntimeAdapters.tradeTickRuntimeCallbacks(),
                AgentInventoryRuntimeAdapters.tradeRuntimeCallbacks(entry, agent),
                AgentInventoryRuntimeAdapters.tradeLifecycleRuntimeCallbacks());
    }
}
