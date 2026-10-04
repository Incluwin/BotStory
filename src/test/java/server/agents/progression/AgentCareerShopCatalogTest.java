package server.agents.progression;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCareerShopCatalogTest {
    @Test
    void basicPotionsUseNearestRoutableVictoriaShop() {
        AgentCareerBuildBundle pirate = AgentCareerBuildBundleRepository.defaultRepository()
                .find("pirate-gun-standard-v1").orElseThrow();

        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(
                pirate, AgentResourceCategory.MP_POTION, 101010000);

        assertEquals(101000002, stop.mapId());
        assertEquals(1031100, stop.npcId());
    }

    @Test
    void careerSpecificSuppliesKeepCareerShop() {
        AgentCareerBuildBundle pirate = AgentCareerBuildBundleRepository.defaultRepository()
                .find("pirate-gun-standard-v1").orElseThrow();

        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(
                pirate, AgentResourceCategory.BULLET, 101010000);

        assertEquals(120000200, stop.mapId());
        assertEquals(1091002, stop.npcId());
    }

    @Test
    void noPotionSupplierWhenThePortalRouterReachesNoShop() {
        AgentCareerBuildBundle warrior = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();

        // KPQ's first stage has no portal out, and Ereve's shop lies behind scripted portals.
        assertNull(AgentCareerShopCatalog.forSupply(warrior, AgentResourceCategory.HP_POTION, 103000800));
        assertNull(AgentCareerShopCatalog.forSupply(warrior, AgentResourceCategory.MP_POTION, 130000000));
    }

    @Test
    void potionsComeFromTheTownShopOfTheContinentTheAgentStandsOn() {
        AgentCareerBuildBundle warrior = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();

        assertStop(211000102, 2022001, warrior, 211000000); // El Nath -> Hana
        assertStop(220000002, 2041006, warrior, 220000000); // Ludibrium (LMPQ) -> Misky
        assertStop(220000002, 2041006, warrior, 221024500); // Eos Tower 101st floor (LPQ) -> Misky, 5 hops
        assertStop(200000002, 2012005, warrior, 200080101); // The Unknown Tower (OPQ) -> Edel
        assertStop(300000000, 2130000, warrior, 300030100); // Deep Fairy Forest (EPQ) -> Mayoren
        assertStop(251000000, 2093002, warrior, 251010404); // Over the Pirate Ship (PPQ) -> Lan Ming
        assertStop(261000000, 2110001, warrior, 261000000); // Magatia (RJPQ) -> Jerry on the same map
        assertStop(240000002, 2080001, warrior, 240000000); // Leafre -> Sly
        assertStop(250000002, 2090003, warrior, 250000000); // Mu Lung -> Dalsuk
        assertStop(260000000, 2100004, warrior, 260000000); // Ariant -> SagaT
        assertStop(230000002, 2060004, warrior, 230000000); // Aquarium -> Oannes
        assertStop(104000002, 1001100, warrior, 104000000); // Lith Harbor -> Mina, not a first-job town
    }

    @Test
    void offVictoriaAShopItCannotWalkBackFromIsNoSupplier() {
        AgentCareerBuildBundle warrior = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();

        // Sharp Cliff I drops into Ice Valley II (Glibber) through a one-way portal: an Agent with no owner to warp
        // it back would stand by the shop for good.
        assertNull(AgentCareerShopCatalog.forSupply(warrior, AgentResourceCategory.HP_POTION, 211040300));
        // Next door, Ice Valley I walks there and back.
        assertStop(211040200, 2030009, warrior, 211040100);
    }

    @Test
    void onVictoriaTheOneWayTripsStayAsTheyWere() {
        AgentCareerBuildBundle warrior = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();

        // Mushroom Castle is one-way too, and its bots shopped before town shops elsewhere were known.
        assertNotNull(AgentCareerShopCatalog.forSupply(warrior, AgentResourceCategory.HP_POTION, 106021000));
    }

    @Test
    void projectilesAreToppedUpOnlyAtVictoriaShops() {
        assertTrue(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(103000002), "Kerning City Pharmacy");
        assertTrue(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(104000002), "Lith Harbor");
        assertFalse(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(220000002), "Ludibrium Pharmacy");
        assertFalse(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(801000300), "Showa Street Market");
    }

    private static void assertStop(int mapId, int npcId, AgentCareerBuildBundle bundle, int fromMapId) {
        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(
                bundle, AgentResourceCategory.HP_POTION, fromMapId);
        assertEquals(new AgentCareerShopCatalog.ShopStop(mapId, npcId), stop, () -> "from map " + fromMapId);
    }

    @Test
    void careerSpecificSuppliesNeedARouteToTheCareerShop() {
        AgentCareerBuildBundle pirate = AgentCareerBuildBundleRepository.defaultRepository()
                .find("pirate-gun-standard-v1").orElseThrow();

        assertNull(AgentCareerShopCatalog.forSupply(pirate, AgentResourceCategory.BULLET, 211000000));
    }
}
