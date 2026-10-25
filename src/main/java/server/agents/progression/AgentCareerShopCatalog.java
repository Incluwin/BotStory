package server.agents.progression;

import client.Job;
import server.agents.capabilities.contracts.AgentResourceCategory;

import java.util.function.IntBinaryOperator;

/** WZ- and shop-data-verified supply stops: each first job's career shop and the nearest town potion shop. */
public final class AgentCareerShopCatalog {
    public record ShopStop(int mapId, int npcId) {
    }

    private AgentCareerShopCatalog() {
    }

    /**
     * The career's own shop. A Cygnus Knight or an Aran has no Victoria career: it shops where its Explorer archetype
     * does (a Wind Archer's arrows in Henesys, a Night Walker's stars in Kerning City), as players of those lines did.
     */
    public static ShopStop forBundle(AgentCareerBuildBundle bundle) {
        AgentVictoriaLevel15CatalogRepository repository = AgentVictoriaLevel15CatalogRepository.defaultRepository();
        Job firstJob = bundle == null ? null : Job.getById(bundle.firstJobId());
        AgentVictoriaLevel15Catalog.Career career = repository.hasCareer(bundle) || firstJob == null
                ? repository.careerFor(bundle) : repository.careerForFirstJob(firstJob.getJobNiche() * 100);
        return new ShopStop(career.shopMapId(), career.shopNpcId());
    }

    public static ShopStop forSupply(AgentCareerBuildBundle bundle,
                                     AgentResourceCategory category,
                                     int currentMapId) {
        return forSupply(bundle, category, currentMapId, AgentVictoriaTrainingRouteCatalog::distance);
    }

    /**
     * Nearest supplier the portal router can reach from currentMapId, measured by routeHops
     * (portal hops, or a negative value when there is no route). Potions come from any town
     * potion shop (AgentPotionSupplierCatalog, every continent); everything else from the
     * career shop. Returns null when no supplier of this category is reachable, so callers
     * never travel toward a shop the router cannot get to.
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
        AgentPotionSupplierCatalog.Supplier nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (AgentPotionSupplierCatalog.Supplier candidate : AgentPotionSupplierCatalog.suppliers()) {
            int distance = routeHops.applyAsInt(currentMapId, candidate.mapId());
            // Outside Victoria a shop behind a one-way portal (Sharp Cliff into Ice Valley II, Leafre's forests)
            // would leave an Agent with no owner to warp back to standing by it for good. Victoria's one-way trips
            // (Mushroom Castle) stay as they were.
            if (distance >= 0 && AgentVictoriaTrainingRouteCatalog.outsideVictoria(candidate.mapId())
                    && routeHops.applyAsInt(candidate.mapId(), currentMapId) < 0) {
                continue;
            }
            if (distance >= 0 && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : new ShopStop(nearest.mapId(), nearest.npcId());
    }
}
