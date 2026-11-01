package server.agents.progression;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every town potion supplier is a real NPC shop, standing on its map, that the portal router walks to. */
class AgentPotionSupplierCatalogTest {
    /** Red, Orange and White; and Unagi, which Ant Tunnel Park's 24 Hr Mobile Store sells instead (fix L7). */
    private static final Set<Integer> BASIC_HP_POTIONS = Set.of(2000000, 2000001, 2000002, 2022003);
    /** Blue and Mana Elixir; and Pure Water, which Ant Tunnel Park's 24 Hr Mobile Store sells instead (fix L7). */
    private static final Set<Integer> BASIC_MP_POTIONS = Set.of(2000003, 2000006, 2022000);
    private static final Pattern LIFE_BLOCK = Pattern.compile("(?s)<imgdir name=\"life\">(.*?)\\n  </imgdir>");
    private static final Pattern LIFE_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern FIELD = Pattern.compile("name=\"(\\w+)\" value=\"([^\"]*)\"");

    @Test
    void everySupplierSellsHpAndMpPotions() throws IOException {
        Map<Integer, Integer> shopByNpc = new HashMap<>();
        Matcher shops = Pattern.compile("\\((\\d+),\\s*(\\d+)\\)").matcher(resource("/db/data/101-shops-data.sql"));
        while (shops.find()) {
            shopByNpc.put(Integer.parseInt(shops.group(2)), Integer.parseInt(shops.group(1)));
        }
        Map<Integer, Set<Integer>> stock = new HashMap<>();
        Matcher items = Pattern.compile("\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)")
                .matcher(resource("/db/data/102-shopitems-data.sql"));
        while (items.find()) {
            stock.computeIfAbsent(Integer.parseInt(items.group(1)), ignored -> new HashSet<>())
                    .add(Integer.parseInt(items.group(2)));
        }
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            Integer shopId = shopByNpc.get(supplier.npcId());
            assertTrue(shopId != null, () -> supplier + " has no NPC shop");
            Set<Integer> sold = stock.getOrDefault(shopId, Set.of());
            assertTrue(sold.stream().anyMatch(BASIC_HP_POTIONS::contains), () -> supplier + " sells no HP potion");
            assertTrue(sold.stream().anyMatch(BASIC_MP_POTIONS::contains), () -> supplier + " sells no MP potion");
        }
    }

    @Test
    void everySupplierStandsOnItsMapInWz() throws IOException {
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            int mapId = supplier.mapId();
            Path file = Path.of("wz", "Map.wz", "Map", "Map" + mapId / 100_000_000,
                    String.format("%09d.img.xml", mapId));
            Matcher life = LIFE_BLOCK.matcher(Files.readString(file));
            boolean placed = false;
            if (life.find()) {
                Matcher entry = LIFE_ENTRY.matcher(life.group(1));
                while (entry.find() && !placed) {
                    Map<String, String> fields = new HashMap<>();
                    Matcher field = FIELD.matcher(entry.group(1));
                    while (field.find()) {
                        fields.put(field.group(1), field.group(2));
                    }
                    placed = "n".equals(fields.get("type"))
                            && Integer.parseInt(fields.getOrDefault("id", "0")) == supplier.npcId();
                }
            }
            assertTrue(placed, () -> supplier + " is not placed on its map in WZ");
        }
    }

    @Test
    void everySupplierIsOnThePortalGraphOnce() {
        Set<Integer> nodes = new HashSet<>();
        for (AgentVictoriaPortalRouteGraphRepository repository : List.of(
                AgentVictoriaPortalRouteGraphRepository.defaultRepository(),
                AgentVictoriaPortalRouteGraphRepository.worldRepository())) {
            for (AgentVictoriaPortalRouteGraph.Edge edge : repository.graph().edges()) {
                nodes.add(edge.fromMapId());
                nodes.add(edge.toMapId());
            }
        }
        Set<Integer> entered = new HashSet<>();
        for (AgentVictoriaPortalRouteGraphRepository repository : List.of(
                AgentVictoriaPortalRouteGraphRepository.defaultRepository(),
                AgentVictoriaPortalRouteGraphRepository.worldRepository())) {
            for (AgentVictoriaPortalRouteGraph.Edge edge : repository.graph().edges()) {
                entered.add(edge.toMapId());
            }
        }
        Set<String> seen = new HashSet<>();
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            assertTrue(nodes.contains(supplier.mapId()), () -> supplier + " is off the portal graph");
            if (supplier.rideLeg() == null) {
                assertTrue(entered.contains(supplier.mapId()), () -> "no portal leads into " + supplier);
            } else {
                // its ride is the only way in, and the walk out leads back to where the ride boards
                AgentTravelLegCatalog.Leg leg = AgentTravelLegCatalog.leg(supplier.rideLeg());
                assertEquals(supplier.mapId(), leg.toMapId(), () -> supplier + " is not where its ride lands");
                assertTrue(nodes.contains(leg.fromMapId()), () -> supplier + "'s ride boards off the portal graph");
                assertFalse(entered.contains(supplier.mapId()), () -> "a portal leads into " + supplier + ": no ride");
                assertTrue(AgentVictoriaTrainingRouteCatalog.distance(supplier.mapId(), leg.fromMapId()) >= 0,
                        () -> "nothing walks back from " + supplier);
            }
            assertTrue(seen.add(supplier.mapId() + ":" + supplier.npcId()), () -> "duplicate " + supplier);
            assertFalse(supplier.town().isBlank(), () -> supplier + " names no town");
        }
    }

    @Test
    void shopsBotsCannotReachAreLeftOut() {
        // live 2026-10-04: "visit timeout (never reached the shop NPC)" at the Eos Tower Gumball Machine
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            assertFalse(supplier.npcId() == 2040049, "the Gumball Machine on Eos Tower's 26th-40th floors");
        }
    }

    @Test
    void sleepywoodsSaunaIsTheHotelReceptionistsRideAndTheOnlySupplierARideReaches() {
        // 2026-11-01: an Agent respawned in Sleepywood at 50 HP with nothing to drink has a monster map on every walk
        // to a shop; the Hotel Receptionist (1061100, 105040400) takes it into the Regular Sauna for 499 mesos, where
        // Mr. Sweatbottom sells Red, Orange, White, Blue, Mana Elixir, Unagi and Pure Water
        assertTrue(AgentPotionSupplierCatalog.suppliers().contains(new AgentPotionSupplierCatalog.Supplier(
                105040401, 1061002, "Sleepywood", "sleepywood-hotel-to-regular-sauna")));
        AgentTravelLegCatalog.Leg ride = AgentTravelLegCatalog.leg("sleepywood-hotel-to-regular-sauna");
        assertEquals(105040400, ride.fromMapId());
        assertEquals(1061100, ride.npcId());
        assertEquals(499, ride.cost());
        assertEquals(ride, AgentPotionSupplierCatalog.rideLegTo(105040401));
        assertEquals(1, AgentPotionSupplierCatalog.suppliers().stream()
                .filter(supplier -> supplier.rideLeg() != null).count(), "every other supplier is walked to");
        assertNull(AgentPotionSupplierCatalog.rideLegTo(105070001));
    }

    @Test
    void theSaunaIsNeverTheWalkingMeasuresPickFromOutside() {
        // From Sleepywood, its hotel and Ant Tunnel Park the nearest walking supplier is as before; standing in the
        // sauna it is the shop there
        AgentCareerBuildBundle bundle = AgentCareerBuildBundleRepository.defaultRepository()
                .find("pirate-knuckle-standard-v1").orElseThrow();
        for (int mapId : new int[]{105040300, 105040400, 105070001}) {
            assertNotEquals(105040401, AgentCareerShopCatalog.forSupply(
                    bundle, AgentResourceCategory.HP_POTION, mapId).mapId(), () -> "from map " + mapId);
        }
        assertEquals(new AgentCareerShopCatalog.ShopStop(105040401, 1061002),
                AgentCareerShopCatalog.forSupply(bundle, AgentResourceCategory.HP_POTION, 105040401));
    }

    @Test
    void moInThePhantomForestSellsTheStarsACrimsonwoodRunRestocksFrom() {
        // CWKPQ C9 (2026-10-23): NonThePriest ran out of stars in the Council Hall; New Leaf City's store sells arrows
        // but no stars, and Mo sells Icicles (2070010) besides Unagi and Pure Water (db/data/102-shopitems-data.sql)
        assertTrue(AgentPotionSupplierCatalog.suppliers().contains(
                new AgentPotionSupplierCatalog.Supplier(610010004, 9201099, "Phantom Forest", null)));
    }

    @Test
    void antTunnelParksMobileStoreIsTheLastEntryTheStopOnTheWayDownToEasyBalrog() {
        // fix L7 (2026-10-08): no shop is in reach of Mu Young's room; this store on the walk down sells Unagi
        // (2022003) and Pure Water (2022000) (db/data/102-shopitems-data.sql), appended so earlier ties break as before
        List<AgentPotionSupplierCatalog.Supplier> suppliers = AgentPotionSupplierCatalog.suppliers();
        assertEquals(new AgentPotionSupplierCatalog.Supplier(105070001, 1061001, "Ant Tunnel Park", null),
                suppliers.get(suppliers.size() - 1));
    }

    @Test
    void firstJobShopsComeFirstSoVictoriaTiesBreakAsBefore() {
        List<AgentPotionSupplierCatalog.Supplier> suppliers = AgentPotionSupplierCatalog.suppliers();
        List<AgentVictoriaLevel15Catalog.Career> careers =
                AgentVictoriaLevel15CatalogRepository.defaultRepository().catalog().careers();
        for (int i = 0; i < careers.size(); i++) {
            assertEquals(careers.get(i).shopMapId(), suppliers.get(i).mapId());
            assertEquals(careers.get(i).shopNpcId(), suppliers.get(i).npcId());
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = AgentPotionSupplierCatalogTest.class.getResourceAsStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
