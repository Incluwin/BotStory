package server.agents.capabilities.shop;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.equipment.AgentEquipmentOwnerTags;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * Who sells its junk at every shop visit (decision 2026-10-10: a companion sells its junk gear at every shop visit,
 * its owner's store visits included, and the money stays in its purse). The Agent layer stays owner-free: the host
 * installs the {@link Rule} that names a companion (out with its owner, or on a live TRAIN bench slice). With nothing
 * installed nobody sells routinely, so a citizen and every other Agent keep today's shop visits.
 *
 * <p>Never inside an event instance, a party quest engagement or a boss run (KPQ, HPQ, LPQ, EPQ and boss runs are
 * unchanged), and never while the Agent wears a !bossgear kit piece: what it wore before is in its bag
 * (CompanionGearTool) and goes back on when the kit comes off, and a plain displaced piece reads as junk.</p>
 */
public final class AgentJunkSale {
    private static final Logger log = LoggerFactory.getLogger(AgentJunkSale.class);

    /** The host's answer to who sells at every visit. */
    public interface Rule {
        /** True for a companion the host lets sell its junk at every shop visit. Memory only, any thread. */
        boolean sellsAtEveryVisit(int agentCharacterId);
    }

    private static volatile Rule rule;

    private AgentJunkSale() {
    }

    /** Installs the host's rule (null: nobody sells routinely). */
    public static void install(Rule installed) {
        rule = installed;
    }

    /**
     * True when the Agent sells its junk gear quietly at this shop visit: the installed rule names it, and it is in no
     * event instance, party quest or boss run and wears no !bossgear piece. A failing rule answers no. Memory only.
     */
    public static boolean routineSale(Character agent) {
        Rule current = rule;
        if (current == null || agent == null) {
            return false;
        }
        try {
            return agent.getEventInstance() == null
                    && AgentPartyQuestEngagementRegistry.forMember(agent.getId()) == null
                    && AgentBossRunRegistry.forMember(agent.getId()) == null
                    && current.sellsAtEveryVisit(agent.getId())
                    && !wearsBossGear(agent);
        } catch (RuntimeException failure) {
            log.debug("Agent '{}' junk sale rule failed; no routine sale", agent.getName(), failure);
            return false;
        }
    }

    /** True when the Agent carries junk gear it would sell (AgentInventorySellTrashService). On its own thread only. */
    public static boolean hasJunk(AgentRuntimeEntry entry, Character agent) {
        return junkCount(entry, agent) > 0;
    }

    /**
     * How many pieces of junk gear the Agent would sell (AgentInventorySellTrashService.collectSellTrashEquips): kept
     * gear (its upgrades, scrolled pieces, good rolls) is not counted. On the Agent's own thread only: the look
     * refreshes its reservations.
     */
    public static int junkCount(AgentRuntimeEntry entry, Character agent) {
        if (entry == null || agent == null) {
            return 0;
        }
        return AgentInventorySellTrashService.collectSellTrashEquips(
                entry, agent, AgentInventoryGatewayRuntime.inventory()).size();
    }

    /**
     * True when a following Agent's owner walked on: its anchor (its follow target, else its interaction target, as
     * AgentSupplyReadinessRuntime.followAnchor) is gone, on another map, or changing maps. False when it does not
     * follow.
     */
    static boolean ownerWalkedOn(AgentRuntimeEntry entry, Character agent) {
        if (entry == null || agent == null || !AgentModeStateRuntime.following(entry)) {
            return false;
        }
        Character anchor = followAnchor(entry);
        return anchor == null || anchor.getMapId() != agent.getMapId() || anchor.isChangingMaps();
    }

    /** Who a following Agent follows: its follow target, or with none its interaction target (its owner). */
    static Character followAnchor(AgentRuntimeEntry entry) {
        Character target = AgentRelationshipRuntime.followTarget(entry);
        return target != null ? target : AgentRelationshipRuntime.interactionTarget(entry);
    }

    private static boolean wearsBossGear(Character agent) {
        Inventory equipped = agent.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return false;
        }
        for (Item item : equipped.list()) {
            if (item != null && AgentEquipmentOwnerTags.BOSS_GEAR.equals(item.getOwner())) {
                return true;
            }
        }
        return false;
    }
}
