package server.agents.progression;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        assertStop(105070001, 1061001, warrior, 105070001); // Ant Tunnel Park -> its 24 Hr Mobile Store (fix L7)
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
    void everyVictoriaFieldWalksBackFromThePotionShopItIsSentTo() throws IOException {
        AgentCareerBuildBundle warrior = AgentCareerBuildBundleRepository.defaultRepository()
                .find("warrior-standard-v1").orElseThrow();
        // The Victoria fields citizens train in and the bench chooser picks camps from (the FieldCatalog), the Kerning
        // subway among them. Live 2026-10-09: a companion camped on Line 2 <Area 1> (103000200) walked to the Kerning
        // City Pharmacy and logged "no return route from map 103000002".
        Pattern victoriaField = Pattern.compile("\\{ mapId: (\\d+), .*\\bregion: victoria, town: false,");
        List<Integer> fields = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("scripts", "config", "population", "fields.yaml"))) {
            Matcher field = victoriaField.matcher(line);
            if (field.find()) {
                fields.add(Integer.parseInt(field.group(1)));
            }
        }
        assertTrue(fields.contains(103000200), "fields.yaml lists the subway camp");
        // Mushroom Castle is quest-gated one way: back in from Deep Inside Mushroom Forest needs quest 30000 or a Poison
        // Spore (scripts/portal/obstacle.js), past the castle gate quest 2324 (gotocastle.js), and forSupply keeps
        // these one-way trips on purpose (onVictoriaTheOneWayTripsStayAsTheyWere).
        Set<Integer> questGatedOneWay = Set.of(106020400, 106020401, 106020402, 106020700, 106020800,
                106021000, 106021100, 106021200, 106021300);
        assertTrue(fields.containsAll(questGatedOneWay), "every quest-gated exception is a Victoria field");

        List<String> stranded = new ArrayList<>();
        for (int fieldMapId : fields) {
            AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(
                    warrior, AgentResourceCategory.HP_POTION, fieldMapId);
            if (stop == null || questGatedOneWay.contains(fieldMapId)) {
                continue;
            }
            String noWalk = noWalkBack(stop.mapId(), fieldMapId);
            if (noWalk != null) {
                stranded.add(fieldMapId + " (shop on " + stop.mapId() + ": " + noWalk + ")");
            }
        }
        assertEquals(List.of(), stranded, "fields with no walk back from the potion shop they are sent to");
    }

    /**
     * Why the router cannot walk from {@code shopMapId} to {@code fieldMapId}, or null when it can: a route, and on each
     * hop a portal to press, a standard one of the generated graph or an authored scripted one.
     */
    private static String noWalkBack(int shopMapId, int fieldMapId) {
        int hops = AgentVictoriaTrainingRouteCatalog.distance(shopMapId, fieldMapId);
        if (hops < 0) {
            return "no route";
        }
        Set<AgentVictoriaPortalRouteGraph.Edge> standard =
                Set.copyOf(AgentVictoriaPortalRouteGraphRepository.defaultRepository().graph().edges());
        int at = shopMapId;
        for (int hop = 0; hop < hops; hop++) {
            int next = AgentVictoriaTrainingRouteCatalog.nextHop(at, fieldMapId);
            if (!standard.contains(new AgentVictoriaPortalRouteGraph.Edge(at, next))
                    && AgentVictoriaTrainingRouteCatalog.scriptedPortalId(at, next) == null) {
                return "no portal to press from " + at + " to " + next;
            }
            at = next;
        }
        return null;
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
