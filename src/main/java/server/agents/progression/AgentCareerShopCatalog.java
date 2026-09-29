package server.agents.progression;

import server.agents.capabilities.contracts.AgentResourceCategory;

import java.util.function.IntBinaryOperator;

/** WZ- and shop-data-verified first supply stop for each first job. */
public final class AgentCareerShopCatalog {
    public record ShopStop(int mapId, int npcId) {
    }

    private AgentCareerShopCatalog() {
    }

    public static ShopStop forBundle(AgentCareerBuildBundle bundle) {
        AgentVictoriaLevel15Catalog.Career career =
                AgentVictoriaLevel15CatalogRepository.defaultRepository().careerFor(bundle);
        return new ShopStop(career.shopMapId(), career.shopNpcId());
    }

    public static ShopStop forSupply(AgentCareerBuildBundle bundle,
                                     AgentResourceCategory category,
                                     int currentMapId) {
        return forSupply(bundle, category, currentMapId, AgentVictoriaTrainingRouteCatalog::distance);
    }

    /**
     * Nearest supplier the portal router can reach from currentMapId, measured by routeHops
     * (portal hops, or a negative value when there is no route). Returns null when no
     * supplier of this category is reachable, so callers never travel toward a shop the
     * router cannot get to.
     */
    public static ShopStop forSupply(AgentCareerBuildBundle bundle,
                                     AgentResourceCategory category,
                                     int currentMapId,
                                     IntBinaryOperator routeHops) {
        if (category != AgentResourceCategory.HP_POTION
                && category != AgentResourceCategory.MP_POTION) {
            ShopStop careerShop = forBundle(bundle);
            return routeHops.applyAsInt(currentMapId, careerShop.mapId()) >= 0 ? careerShop : null;
        }
        AgentVictoriaLevel15Catalog.Career nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (AgentVictoriaLevel15Catalog.Career candidate :
                AgentVictoriaLevel15CatalogRepository.defaultRepository().catalog().careers()) {
            int distance = routeHops.applyAsInt(currentMapId, candidate.shopMapId());
            if (distance >= 0 && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : new ShopStop(nearest.shopMapId(), nearest.shopNpcId());
    }
}
