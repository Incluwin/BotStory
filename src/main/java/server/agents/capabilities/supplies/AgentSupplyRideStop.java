package server.agents.capabilities.supplies;

import client.Character;
import server.Shop;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.progression.AgentPotionSupplierCatalog;
import server.agents.progression.AgentTravelLegCatalog;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

/**
 * A potion supplier no portal leads into, reached by its ride leg (AgentPotionSupplierCatalog's rideLeg): Sleepywood's
 * Regular Sauna, where Mr. Sweatbottom (1061002) sells potions, behind the Hotel Receptionist's 499-meso ride. A supply
 * trip goes there only while AgentSupplyProcurementRuntime's hostile-map gate holds its walk, and only by a way that
 * boards no other leg (a supply trip still never crosses the sea) and crosses no map where monsters spawn.
 */
record AgentSupplyRideStop(int mapId, int npcId, int fare) {

    /** The nearest such supplier the Agent reaches from where it stands by that way, or null. */
    static AgentSupplyRideStop nearest(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentSupplyRideStop nearest = null;
        int nearestSteps = Integer.MAX_VALUE;
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            AgentTravelLegCatalog.Leg leg = supplier.rideLeg() == null ? null
                    : AgentTravelLegCatalog.leg(supplier.rideLeg());
            if (leg == null || supplier.mapId() == agent.getMapId()) {
                continue;
            }
            List<AgentVictoriaRouteRuntime.PlannedStep> way =
                    AgentVictoriaRouteRuntime.plannedRoute(entry, agent.getMapId(), supplier.mapId(), nowMs);
            if (way.isEmpty() || way.size() >= nearestSteps || !boardsOnly(way, leg)
                    || AgentSupplyProcurementRuntime.firstMapWithSpawns(agent, way) > 0) {
                continue;
            }
            nearest = new AgentSupplyRideStop(supplier.mapId(), supplier.npcId(), leg.cost());
            nearestSteps = way.size();
        }
        return nearest;
    }

    /** The fare and one potion of the kind as the visit buys them there; the fare alone when no price is known. */
    int price(Character agent, AgentResourceCategory category) {
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npcId);
        return fare + Math.max(0, AgentShopService.ordinaryPotionPrice(
                agent, shop, category == AgentResourceCategory.HP_POTION));
    }

    private static boolean boardsOnly(List<AgentVictoriaRouteRuntime.PlannedStep> way, AgentTravelLegCatalog.Leg leg) {
        for (AgentVictoriaRouteRuntime.PlannedStep step : way) {
            if (step.ride() && (step.fromMapId() != leg.fromMapId() || step.toMapId() != leg.toMapId()
                    || step.boardingNpcId() != leg.npcId())) {
                return false;
            }
        }
        return true;
    }
}
