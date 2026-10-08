package server.agents.progression;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * NPC shops that sell HP and MP potions, in every town the portal graphs reach (shop data, WZ placement and graph
 * membership verified by AgentPotionSupplierCatalogTest). The five first-job shops come first, in career order.
 */
public final class AgentPotionSupplierCatalog {
    private static final String RESOURCE = "/agents/catalogs/potion-supplier-catalog.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final List<Supplier> SUPPLIERS = load();

    public record Supplier(int mapId, int npcId, String town) {
        public Supplier {
            if (mapId <= 0 || npcId <= 0) {
                throw new IllegalArgumentException("a potion supplier needs a map and an NPC");
            }
            town = town == null ? "" : town;
        }
    }

    record Catalog(int schemaVersion, List<Supplier> suppliers) {
    }

    private AgentPotionSupplierCatalog() {
    }

    public static List<Supplier> suppliers() {
        return SUPPLIERS;
    }

    /**
     * Whether a shop visit on this map runs the old ammo top-up (a seed set of stars or bullets, the refills of the best
     * stacks, arrows to the ammo target) before the potions: only shops on Victoria, never the town shops of the other
     * continents, as before potion shops outside Victoria Island were known. A readiness trip's own ammo step
     * (AgentShopService.buyReadinessAmmo) runs after the potions at any supplier.
     */
    public static boolean ammoTopUpAllowedAt(int mapId) {
        return !AgentVictoriaTrainingRouteCatalog.outsideVictoria(mapId);
    }

    private static List<Supplier> load() {
        try (InputStream input = AgentPotionSupplierCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing potion supplier catalog: " + RESOURCE);
            }
            Catalog catalog = MAPPER.readValue(input, Catalog.class);
            if (catalog.suppliers() == null || catalog.suppliers().isEmpty()) {
                throw new IllegalStateException("potion supplier catalog lists no supplier: " + RESOURCE);
            }
            return List.copyOf(catalog.suppliers());
        } catch (IOException failure) {
            throw new IllegalStateException("could not load potion supplier catalog: " + RESOURCE, failure);
        }
    }
}
