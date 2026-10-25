package server.agents.capabilities.partyquest.apq;

import client.Character;
import client.inventory.InventoryType;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * A companion's drop of an Etc item where it stands, as a player's drop (the inventory gateway, as CWKPQ's ports drop),
 * for the hand-overs and the Wing Hammer. A stack another plan holds stays put.
 */
final class AgentApqDrops {
    private AgentApqDrops() {
    }

    /** Drops {@code quantity} of {@code itemId} (all of it for 0 or less); false when it holds none it may drop. */
    static boolean dropEtc(AgentRuntimeEntry entry, int itemId, short quantity) {
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        var inventory = agent == null ? null : agent.getInventory(InventoryType.ETC);
        var item = inventory == null ? null : inventory.findById(itemId);
        if (item == null || item.getQuantity() <= 0
                || !AgentInventoryReservationRuntime.mayConsume(entry, item, System.currentTimeMillis())) {
            return false;
        }
        short dropped = quantity <= 0 ? item.getQuantity() : (short) Math.min(quantity, item.getQuantity());
        AgentInventoryGatewayRuntime.inventory().dropItem(agent, InventoryType.ETC, item.getPosition(), dropped);
        return true;
    }
}
