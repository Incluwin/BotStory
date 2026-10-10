package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.Trade;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.shop.AgentShopWorkflowPhase;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.ShopGateway;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.plans.AgentTask;
import server.agents.progression.AgentCareerBuildBundleRepository;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.activity.AgentActivityHostState;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.life.SpawnPoint;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The standing readiness duty on the Agent tick (AgentSupplyReadinessRuntime inside AgentSupplyProcurementRuntime),
 * from the live 2026-10-02 KPQ evening: companions with 14 HP / 21 MP potions and plenty of mesos waited at Lakelis
 * because their own resupply only goes under POT_STOP, and Fenna (1 HP potion, 155 mesos) could not buy anything.
 * Now a duty holder short of the standard in a town near a shop walks there by itself, buys both kinds in one visit
 * and rejoins; a broke one says so once (the rule's refusal) and neither walks nor asks the party, until its purse
 * grows by what it lacked. The mocked Agents stand on map mocks; the shop, its prices, the potions and the route are
 * stubs, as in AgentSupplyProcurementReachabilityTest.
 */
class AgentSupplyReadinessRuntimeTest {
    private static final int KERNING = 103_000_000;
    private static final int KERNING_SHOP = 103_000_002;
    /** The Ant Tunnel park's neighbour: a hunting field, not a town. */
    private static final int FIELD = 104_040_000;
    private static final int LPQ_DESK = 221_024_500;
    private static final int LUDIBRIUM_PHARMACY = 220_000_002;
    private static final int PPQ_DESK = 251_010_404;
    private static final int HERB_TOWN = 251_000_000;
    private static final int ZAKUM_DOOR = 211_042_300;
    private static final int EOS_TOWER_100TH = 221_024_400;
    private static final int SLEEPYWOOD = 105_040_300;
    private static final int ORBIS_TRAIN_GATE = 200_000_121;
    private static final int PAPULATUS_GATE = 220_080_000;
    /** A hunting field (town=0, about 60 monsters) with a store on it: the 24 Hr Mobile Store. */
    private static final int ANT_TUNNEL_PARK = 105_070_001;
    private static final int MOBILE_STORE = 1_061_001;
    private static final int OWNER = 77;
    private static final int RED = 50;
    private static final int BLUE = 200;
    private static final AgentSupplyReadiness.Activity PQ = AgentSupplyReadiness.Activity.PARTY_QUEST;
    private static final AgentSupplyReadiness.Activity EPQ = AgentSupplyReadiness.Activity.partyQuest("epq");
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");

    /** Character id -> {HP potions, MP potions}. */
    private final Map<Integer, int[]> potions = new HashMap<>();
    private final Map<Integer, Integer> purses = new HashMap<>();
    private final Map<Integer, Integer> freeSlots = new HashMap<>();
    /** Character ids that carry a Red (HP) stack with room, onto which bought potions stack without a new slot. */
    private final java.util.Set<Integer> redStack = new java.util.HashSet<>();
    /** Character ids that carry a Blue (MP) stack with room. */
    private final java.util.Set<Integer> blueStack = new java.util.HashSet<>();
    /** Character id -> the activity its duty keeps it ready for (PQ when not listed). */
    private final Map<Integer, AgentSupplyReadiness.Activity> activities = new HashMap<>();
    /** What AgentShopService.potionStackSize answers; 0 (the stacks are not known) keeps the one-slot rule. */
    private int stackSize;
    /** Character id -> what AgentShopService.carriedStackRoom answers for HP (0 when not listed). */
    private final Map<Integer, Integer> hpStackRoom = new HashMap<>();
    /**
     * Character id -> the room of its carried stacks of the coverage picks, HP and MP: what
     * AgentShopService.readinessPickRoom answers while the pick's whole count fits its budget (0 when not listed).
     */
    private final Map<Integer, int[]> pickRoom = new HashMap<>();
    /** What AgentShopService.coveragePickPrice answers for HP: a White Potion. */
    private int hpPickPrice = 320;
    /** What AgentShopService.coveragePickPrice answers for MP: a Blue Potion, the cheapest. */
    private int mpPickPrice = BLUE;
    /** What AgentShopService.coveragePickRecovery answers for HP: a White Potion's 300 HP. */
    private int hpPickRecovery = 300;
    /** What AgentShopService.coveragePickRecovery answers for MP: a Blue Potion's 100 MP. */
    private int mpPickRecovery = 100;
    /** The rule's callbacks and the party asks, in order. */
    private final List<String> calls = new ArrayList<>();
    private final java.util.Set<Integer> duty = new java.util.HashSet<>();
    /** What the rule answers for a refusal: true, its host said its line; false, it kept quiet (said too recently). */
    private boolean refusalsSaid = true;
    /** Maps the rule keeps companions on (a boss's gate): no shop trip from there. */
    private final java.util.Set<Integer> noShopMaps = new java.util.HashSet<>();
    /** Hunting fields the rule opens to a duty holder of Easy Balrog's standard (a store on the way down there). */
    private final java.util.Set<Integer> fieldStores = new java.util.HashSet<>();
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final ShopGateway shops = mock(ShopGateway.class);
    private AgentRouteStatus route = AgentRouteStatus.MOVING;
    private int ammoSpend;
    private MockedStatic<AgentPotionService> potionService;
    /** Character id -> the projectiles in its bag (AgentAmmoService.carriedProjectiles); not listed: no ammo weapon. */
    private final Map<Integer, Integer> ammo = new HashMap<>();
    /** What AgentShopService.readinessAmmoPlan answers: by default it reaches any standard for nothing. */
    private AgentShopService.ReadinessAmmoPlan ammoPlan = new AgentShopService.ReadinessAmmoPlan(Integer.MAX_VALUE, 0L, 0);
    private MockedStatic<AgentAmmoService> ammoService;
    private MockedStatic<AgentShopService> shopService;
    private MockedStatic<AgentShopGatewayRuntime> shopGateway;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives;

    @BeforeEach
    void stubTheWorld() {
        potionService = mockStatic(AgentPotionService.class);
        ammoService = mockStatic(AgentAmmoService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ammoService.when(() -> AgentAmmoService.carriedProjectiles(any()))
                .thenAnswer(inv -> ammo.getOrDefault(((Character) inv.getArgument(0)).getId(), Integer.MAX_VALUE));
        potionService.when(() -> AgentPotionService.countPotions(any(Character.class)))
                .thenAnswer(inv -> potions.get(((Character) inv.getArgument(0)).getId()).clone());
        potionService.when(() -> AgentPotionService.requestPotShare(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> calls.add("party ask " + ((Character) inv.getArgument(1)).getId()));
        shopService = mockStatic(AgentShopService.class);
        shopService.when(() -> AgentShopService.requestVisitAtNpc(any(), any(), anyInt(), anyInt())).thenReturn(true);
        shopService.when(() -> AgentShopService.cheapestRecoveryPrice(any(), any(), eq(true))).thenReturn(RED);
        shopService.when(() -> AgentShopService.cheapestRecoveryPrice(any(), any(), eq(false))).thenReturn(BLUE);
        shopService.when(() -> AgentShopService.plannedAmmoSpend(any(), any())).thenAnswer(inv -> ammoSpend);
        shopService.when(() -> AgentShopService.readinessAmmoPlan(any(), any(), anyInt(), any()))
                .thenAnswer(inv -> ammoPlan);
        shopService.when(() -> AgentShopService.carriesStackWithRoom(any(), any(), eq(true)))
                .thenAnswer(inv -> redStack.contains(((Character) inv.getArgument(0)).getId()));
        shopService.when(() -> AgentShopService.carriesStackWithRoom(any(), any(), eq(false)))
                .thenAnswer(inv -> blueStack.contains(((Character) inv.getArgument(0)).getId()));
        shopService.when(() -> AgentShopService.potionStackSize(any(), any(), anyBoolean()))
                .thenAnswer(inv -> stackSize);
        shopService.when(() -> AgentShopService.carriedStackRoom(any(), any(), eq(true)))
                .thenAnswer(inv -> hpStackRoom.getOrDefault(((Character) inv.getArgument(0)).getId(), 0));
        shopService.when(() -> AgentShopService.coveragePickPrice(any(), any(), anyBoolean()))
                .thenAnswer(inv -> (boolean) inv.getArgument(2) ? hpPickPrice : mpPickPrice);
        shopService.when(() -> AgentShopService.coveragePickRecovery(any(), any(), anyBoolean()))
                .thenAnswer(inv -> (boolean) inv.getArgument(2) ? hpPickRecovery : mpPickRecovery);
        shopService.when(() -> AgentShopService.readinessPickRoom(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    boolean forHp = inv.getArgument(2);
                    int want = inv.getArgument(3);
                    long budget = inv.getArgument(4);
                    int[] room = pickRoom.get(((Character) inv.getArgument(0)).getId());
                    long cost = (long) (forHp ? hpPickPrice : mpPickPrice) * want;
                    return room == null || cost > budget ? 0 : room[forHp ? 0 : 1]; // only while its whole count fits
                });
        when(shops.findForNpc(anyInt())).thenReturn(mock(Shop.class));
        shopGateway = mockStatic(AgentShopGatewayRuntime.class);
        shopGateway.when(AgentShopGatewayRuntime::shop).thenReturn(shops);
        primitives = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong())).thenAnswer(inv -> new AgentRouteOutcome(
                route, KERNING, KERNING_SHOP, inv.getArgument(2), false));
        AgentSupplyReadiness.install(new AgentSupplyReadiness.Rule() {
            @Override
            public AgentSupplyReadiness.Activity standing(int id) {
                return duty.contains(id) ? activities.getOrDefault(id, PQ) : null;
            }

            @Override
            public boolean restocksFrom(int id, int mapId) {
                return !noShopMaps.contains(mapId);
            }

            @Override
            public boolean restocksOnField(int id, int mapId, AgentSupplyReadiness.Activity activity) {
                return fieldStores.contains(mapId) && EASY_BALROG.equals(activity);
            }

            @Override
            public void started(int id, AgentSupplyReadiness.Activity activity, long etaMs) {
                calls.add("started " + id + " " + etaMs);
            }

            @Override
            public boolean refused(int id, AgentSupplyReadiness.Activity activity, AgentSupplyReadiness.Refusal why,
                                   AgentSupplyReadiness.Shortfall shortfall, int missingMesos) {
                calls.add("refused " + id + " " + why + " " + missingMesos);
                return refusalsSaid;
            }

            @Override
            public void stillShort(int id, AgentSupplyReadiness.Activity activity,
                                   AgentSupplyReadiness.Shortfall shortfall, int missingMesos) {
                calls.add("still short " + id + " " + missingMesos);
            }

            @Override
            public void finished(int id, AgentSupplyReadiness.Activity activity, AgentSupplyReadiness.Outcome outcome) {
                calls.add("finished " + id + " " + outcome);
            }
        });
        AgentWalletReserve.install(id -> true); // a companion out with its owner keeps no journey reserve
    }

    @AfterEach
    void unstub() {
        AgentSupplyReadiness.install(null);
        AgentWalletReserve.install(null);
        primitives.close();
        shopGateway.close();
        shopService.close();
        potionService.close();
        ammoService.close();
    }

    // (a)
    @Test
    void aShortCompanionWithMesosInKerningSetsOffForTheShopOnItsNextTick() {
        Character silent = companion(501, "SilentSin", Job.ASSASSIN, 14, 21, 103_000);
        AgentRuntimeEntry entry = following(silent);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));

        AgentSupplyProcurementState execution = execution(entry);
        assertTrue(execution.isActive());
        assertTrue(execution.requestId().startsWith("readiness:HP_POTION:"), execution.requestId());
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution.phase());
        assertEquals(KERNING_SHOP, execution.supplierMapId());
        assertTrue(execution.resumeFollow(), "it follows its owner back");
        assertEquals(List.of("started 501 50000"), calls, "one hop: 25 s + 25 s");
        assertEquals(AgentSupplyReadiness.StatusKind.RESTOCKING, AgentSupplyReadiness.status(entry, 1_000L).kind());

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_100L));
        assertEquals(List.of("started 501 50000"), calls, "started once, and no party ask");
    }

    // (b)
    @Test
    void outsideATownNothingStartsEvenWithTheCriticalPathSuppressedAndKerningStartsTheTrip() {
        Character casse = companion(502, "Casse", Job.BANDIT, 5, 30, 5_000);
        stand(casse, FIELD, false);
        AgentRuntimeEntry entry = following(casse);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);
        execution(entry).suppress(AgentResourceCategory.HP_POTION, 1_000L + 300_000L); // its field trip failed

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls, "no trip, no callback, no party ask");

        stand(casse, KERNING, true);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 2_000L), "the next check is not due yet");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 6_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:"), "readiness ignores the suppression");
        assertEquals(List.of("started 502 50000"), calls);
    }

    @Test
    void atTheLpqDeskItWalksTheFiveHopsToLudibriumsPharmacyAndHasTimeForTheWayBack() {
        Character silent = companion(531, "SilentSin", Job.ASSASSIN, 14, 21, 103_000);
        standWithoutSpawns(silent, LPQ_DESK, 0); // Eos Tower 101st floor: no town, no monsters
        AgentRuntimeEntry entry = following(silent);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));

        AgentSupplyProcurementState execution = execution(entry);
        assertTrue(execution.isActive());
        assertEquals(LUDIBRIUM_PHARMACY, execution.supplierMapId());
        assertEquals(List.of("started 531 150000"), calls, "five hops: 25 s + 5 x 25 s");
        assertEquals(1_000L + Math.max(ttlMs(), 5 * ttlPerHopMs()), readinessRequest(entry).expiresAtMs());
    }

    @Test
    void atThePpqDeskItWalksTheSevenHopsBackToHerbTown() {
        Character garrick = companion(532, "Garrick", Job.FIGHTER, 14, 21, 103_000);
        standWithoutSpawns(garrick, PPQ_DESK, 0); // Over the Pirate Ship
        AgentRuntimeEntry entry = following(garrick);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, garrick, 1_000L));

        assertEquals(HERB_TOWN, execution(entry).supplierMapId());
        assertEquals(List.of("started 532 200000"), calls, "seven hops: 25 s + 7 x 25 s");
        assertEquals(1_000L + Math.max(ttlMs(), 7 * ttlPerHopMs()), readinessRequest(entry).expiresAtMs());
    }

    @Test
    void aMonsterFreeMapFartherFromAnyShopThanItsHopsStartsNothing() {
        Character faro = companion(533, "Faro723", Job.CLERIC, 14, 21, 103_000);
        standWithoutSpawns(faro, ZAKUM_DOOR, 0); // 21 hops from Glibber's shop in Ice Valley II
        AgentRuntimeEntry entry = following(faro);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, faro, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 533 NO_SUPPLIER 0"), calls, "the host hears it; no trip, no party ask");
    }

    @Test
    void aMonsterFreeMapThatIsNoPartyQuestDeskKeepsTheTwoHopRadius() {
        Character faro = companion(535, "Faro723", Job.CLERIC, 14, 21, 103_000);
        standWithoutSpawns(faro, EOS_TOWER_100TH, 0); // four hops above Ludibrium's pharmacy, but no desk
        AgentRuntimeEntry entry = following(faro);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, faro, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 535 NO_SUPPLIER 0"), calls);
    }

    @Test
    void aTownFartherThanTwoHopsFromItsShopStartsNothing() {
        Character fenna = companion(536, "Fenna", Job.HUNTER, 14, 21, 103_000);
        stand(fenna, SLEEPYWOOD, true); // Henesys' store is six maps away
        AgentRuntimeEntry entry = following(fenna);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 536 NO_SUPPLIER 0"), calls);
    }

    @Test
    void aStationPlatformIsNoPlaceToSetOffFromItsOwnerMayBoardAnyMoment() {
        Character garrick = companion(537, "Garrick", Job.FIGHTER, 14, 21, 103_000);
        standWithoutSpawns(garrick, ORBIS_TRAIN_GATE, 0);
        AgentRuntimeEntry entry = following(garrick);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, garrick, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls);
    }

    @Test
    void atABossGateItNeverSetsOffAndItsCriticalRequestOnlyAsksTheParty() {
        Character silent = companion(538, "SilentSin", Job.ASSASSIN, 5, 30, 103_000);
        standWithoutSpawns(silent, PAPULATUS_GATE, 0); // Toly's shop is six hops back down the Path of Time
        noShopMaps.add(PAPULATUS_GATE);
        AgentRuntimeEntry entry = following(silent);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);

        for (long now = 1_000L; now <= 1_000L + 3 * partyWaitMs(); now += 5_000L) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, now));
        }

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("party ask 538"), calls, "it asks once and never walks off the gate");
    }

    @Test
    void anArcherAtAnOssyriaDeskIsNotHeldBackByArrowsItWouldNotBuyThere() {
        Character fenna = companion(539, "Fenna", Job.HUNTER, 14, 21, 20 * RED + 10 * BLUE);
        standWithoutSpawns(fenna, LPQ_DESK, 0);
        ammoSpend = 1_000_000; // what its arrows would cost at a Victoria store
        ammo.put(539, 0);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_600, 1_000_000L, 1); // and its readiness arrows here
        AgentRuntimeEntry entry = following(fenna);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        assertEquals(LUDIBRIUM_PHARMACY, execution(entry).supplierMapId());
    }

    @Test
    void aMapWithMonstersOnItIsNoPlaceToSetOffFromEvenWithoutSpawns() {
        Character casse = companion(534, "Casse", Job.BANDIT, 14, 21, 103_000);
        standWithoutSpawns(casse, LPQ_DESK, 2); // something spawned there by a script
        AgentRuntimeEntry entry = following(casse);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls);
    }

    /**
     * Fix L7 (live 2026-10-07: no shop is in reach of Mu Young's room): on the walk down to Easy Balrog, Ant Tunnel
     * Park is a hunting field, so a companion never set off from it. Where the rule opens the field for the boss's
     * standard, a short one shops at the store on that same map (no travel, 0 hops) and follows its owner again after.
     */
    @Test
    void onAFieldTheRuleOpensForABossItShopsAtTheStoreOnThatMapAndFollowsBack() {
        Character isolde = companion(544, "Isolde", Job.CLERIC, 60, 40, 300_000);
        stand(isolde, ANT_TUNNEL_PARK, false);
        activities.put(544, EASY_BALROG);
        AgentRuntimeEntry entry = following(isolde);

        assertFalse(AgentSupplyReadiness.restockPending(entry, 500L), "a field the rule keeps closed");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, isolde, 1_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls, "no trip and no callback while the rule keeps it closed (the switch off)");

        fieldStores.add(ANT_TUNNEL_PARK);
        assertTrue(AgentSupplyReadiness.restockPending(entry, 5_500L), "short, free, and the field is open now");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, isolde, 6_000L));

        AgentSupplyProcurementState execution = execution(entry);
        assertTrue(execution.isActive());
        assertTrue(execution.requestId().startsWith("readiness:HP_POTION:"), execution.requestId());
        assertEquals(ANT_TUNNEL_PARK, execution.supplierMapId(), "the store on the same map");
        assertEquals(MOBILE_STORE, execution.supplierNpcId(), "the 24 Hr Mobile Store");
        assertEquals(AgentSupplyProcurementState.Phase.SHOPPING, execution.phase(), "no travel");
        assertTrue(execution.resumeFollow(), "it follows its owner again after");
        assertEquals(List.of("started 544 25000"), calls, "no hop: 25 s");
    }

    /**
     * User decision 2 (2026-10-08): Easy Balrog's standard is HP and MP points (130,000 / 32,000), so the trip's checks
     * price what a companion's potions lack in the store's picks (Unagi, 1,000 HP for 1,100 mesos; Pure Water, 800 MP
     * for 1,650): 130 and 40 of them, 209,000 mesos, and its request counts Unagi, not HP points.
     */
    @Test
    void aBossTripPricesThePointsItLacksInTheStoresPicks() {
        hpPickPrice = 1_100;
        hpPickRecovery = 1_000;
        mpPickPrice = 1_650;
        mpPickRecovery = 800;
        fieldStores.add(ANT_TUNNEL_PARK);
        Character isolde = companion(548, "Isolde", Job.CLERIC, 60, 40, 209_000 - 1); // no recovery in her bag
        stand(isolde, ANT_TUNNEL_PARK, false);
        activities.put(548, EASY_BALROG);
        AgentRuntimeEntry entry = following(isolde);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, isolde, 1_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 548 CANNOT_AFFORD 1"), calls, "130 Unagi and 40 Pure Water cost 209,000");

        purses.put(548, 209_000); // its purse grew by what it lacked
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, isolde, 6_000L));
        assertEquals(130, readinessRequest(entry).quantity(), "Unagi, not HP points");
        assertEquals(MOBILE_STORE, execution(entry).supplierNpcId());
    }

    @Test
    void anOpenFieldIsOpenOnlyForTheActivityTheRuleNamesAndOnlyWhileItsOwnerStaysThere() {
        fieldStores.add(ANT_TUNNEL_PARK);
        Character casse = companion(545, "Casse", Job.BANDIT, 5, 5, 300_000);
        stand(casse, ANT_TUNNEL_PARK, false); // a party quest's standard: the field stays closed to it
        AgentRuntimeEntry entry = following(casse);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls);

        Character isolde = companion(546, "Isolde", Job.CLERIC, 60, 40, 300_000);
        stand(isolde, ANT_TUNNEL_PARK, false);
        activities.put(546, EASY_BALROG);
        AgentRuntimeEntry walkingOn = following(isolde);
        Character owner = AgentRelationshipRuntime.followTarget(walkingOn);
        when(owner.getMapId()).thenReturn(105_070_100); // its owner walked on toward Sleepywood's depths

        assertFalse(AgentSupplyProcurementRuntime.tick(walkingOn, isolde, 1_000L));
        assertFalse(execution(walkingOn).isActive(), "it follows its owner instead");
        assertEquals(List.of(), calls);
    }

    @Test
    void withNoRuleInstalledAFieldStaysClosed() {
        AgentSupplyReadiness.install(null);
        assertFalse(AgentSupplyReadiness.restocksOnField(547, ANT_TUNNEL_PARK, EASY_BALROG));
        AgentSupplyReadiness.install(new AgentSupplyReadiness.Rule() {
            @Override
            public AgentSupplyReadiness.Activity standing(int id) {
                return EASY_BALROG;
            }
        });
        assertFalse(AgentSupplyReadiness.restocksOnField(547, ANT_TUNNEL_PARK, EASY_BALROG), "closed by default");
    }

    // (c)
    @Test
    void aBrokeCompanionNeitherWalksNorAsksAndSaysSoOnceUntilItsPurseGrowsByWhatItLacked() {
        Character fenna = companion(503, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        planCritical(entry, AgentResourceCategory.HP_POTION, 1);
        int missing = 19 * RED - 155;

        for (long now : new long[]{1_000L, 1_100L, 6_000L, 6_100L, 11_000L}) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, now));
        }

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 503 CANNOT_AFFORD " + missing), calls,
                "once, no party ask: in town she cannot even pay for the 9 potions back to POT_STOP");
        AgentSupplyReadiness.Status status = AgentSupplyReadiness.status(entry, 11_000L);
        assertEquals(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, status.kind());
        assertEquals(missing, status.missingMesos());

        // one look for her readiness trip and one for her critical one; both refusals hold meanwhile
        verify(shops, times(2)).findForNpc(anyInt());
        purses.put(503, 155 + 9 * RED - 156); // her owner's coin, one meso short of the critical potions
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertFalse(execution(entry).isActive());
        verify(shops, times(2)).findForNpc(anyInt()); // still short of what she lacked: not even looked at again

        purses.put(503, 155 + missing - 1); // enough for the critical potions, one meso short of the standard
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 21_000L));
        assertFalse(execution(entry).isActive(), "no readiness trip yet");
        assertEquals("party ask 503", calls.getLast(), "her critical trip goes as before: the party first");

        purses.put(503, 155 + missing);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, 26_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:HP_POTION:"));
        assertEquals("started 503 50000", calls.getLast());
    }

    // (c, the field)
    @Test
    void aDutyHolderOutHuntingThatCanPayForTheCriticalPotionsAsksItsPartyAndThenShopsAsBefore() {
        Character archer = companion(522, "Archer", Job.HUNTER, 5, 30, 600);
        stand(archer, FIELD, false);
        AgentRuntimeEntry entry = following(archer);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);
        ammoSpend = 2_000; // its arrow top-up and the 15 potions to the standard are past its purse

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, archer, 1_000L));
        assertEquals(List.of("party ask 522"), calls, "5 potions back to POT_STOP cost 250: it asks, then shops");
        assertFalse(execution(entry).isActive());

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, archer, 1_000L + partyWaitMs()));
        assertTrue(execution(entry).isActive(), "nobody shared: it walks to the shop as before");
        assertTrue(execution(entry).requestId().startsWith("supply:HP_POTION:"), execution(entry).requestId());
    }

    // (c, the field)
    @Test
    void aBrokeDutyHolderOutHuntingAsksItsPartyOnceButNeverWalksToAShop() {
        Character fenna = companion(523, "Fenna", Job.HUNTER, 1, 30, 155);
        stand(fenna, FIELD, false);
        AgentRuntimeEntry entry = following(fenna);
        planCritical(entry, AgentResourceCategory.HP_POTION, 1);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L + partyWaitMs()));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 2_000L + partyWaitMs()));

        assertFalse(execution(entry).isActive(), "155 mesos buy nothing worth the walk");
        assertEquals(List.of("party ask 523"), calls, "a share is all that helps out here; no broke line");
    }

    // (d)
    @Test
    void aBowmanWhosePurseCoversThePotionsButNotTheArrowsCannotAfford() {
        Character archer = companion(504, "Archer", Job.HUNTER, 15, 30, 1_000);
        AgentRuntimeEntry entry = following(archer);
        ammoSpend = 4_000; // arrows up to the ammo target come first at the shop

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, archer, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 504 CANNOT_AFFORD " + (4_000 + 5 * RED - 1_000)), calls);
    }

    // (e)
    @Test
    void aReadinessTripThatBoughtBothSpendsTheCriticalManaRequestWithoutAPartyAsk() {
        Character rurik = companion(505, "Rurik", Job.FIGHTER, 15, 5, 20_000);
        AgentRuntimeEntry entry = following(rurik);
        AgentResourcePlanningState planning = planCritical(entry, AgentResourceCategory.MP_POTION, 5);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, rurik, 1_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:HP_POTION:"), "the readiness trip goes first");
        shopCompletes(entry, rurik, 40, 50);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, rurik, 2_000L));
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution(entry).lastOutcome().status());
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, rurik, 2_100L));

        assertFalse(execution(entry).isActive());
        assertNull(planning.procurement(AgentResourceCategory.MP_POTION), "spent by the visit that bought it");
        assertNull(planning.procurement(AgentResourceCategory.HP_POTION));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, rurik, 7_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("started 505 50000", "finished 505 READY"), calls, "no party ask");
    }

    // (f)
    @Test
    void aTripPastItsTimeStallsAsInterruptedWithoutAPartyAsk() {
        Character pip = companion(506, "Pip", Job.FP_WIZARD, 15, 30, 20_000);
        AgentRuntimeEntry entry = following(pip);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, 1_000L));
        long expiresAt = 1_000L + ttlMs();

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, expiresAt), "still within its time");
        assertTrue(execution(entry).isActive());
        AgentSupplyProcurementRuntime.tick(entry, pip, expiresAt + 1);

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("started 506 50000", "finished 506 INTERRUPTED"), calls);
    }

    // (g)
    @Test
    void endingAtTheStandardIsRestoredSucceedsAndIsReadyOnce() {
        Character tobi = companion(507, "Tobi", Job.PAGE, 14, 21, 20_000);
        AgentRuntimeEntry entry = following(tobi);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, tobi, 1_000L));
        shopCompletes(entry, tobi, 40, 50);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, tobi, 2_000L));
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution(entry).lastOutcome().status());
        assertEquals(AgentSupplyProcurementState.Phase.RETURNING, execution(entry).phase());
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, tobi, 2_100L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, tobi, 2_200L));

        assertFalse(execution(entry).isActive());
        assertTrue(AgentModeStateRuntime.following(entry), "back to following its owner");
        assertEquals(List.of("started 507 50000", "finished 507 READY"), calls, "never a party ask");
        assertEquals(AgentSupplyReadiness.Status.NONE, AgentSupplyReadiness.status(entry, 2_200L));
    }

    // (g)
    @Test
    void aCriticalTripOfADutyHolderThatEndsAboveTheStandardIsRestored() {
        Character mira = companion(508, "Mira", Job.HUNTER, 5, 30, 20_000);
        AgentRuntimeEntry entry = following(mira);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);
        startCriticalTrip(entry, "supply:HP_POTION:508", AgentResourceCategory.HP_POTION, 5);
        shopCompletes(entry, mira, 45, 50);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, mira, 2_000L));

        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED, execution(entry).lastOutcome().status(),
                "45 is short of its planned 200, but the standard is met");
        assertFalse(execution(entry).abandoned());
    }

    // (h)
    @Test
    void aTripThatRanOutOfMesosWaitsUntilItsPurseGrowsByWhatItStillLacks() {
        Character kettle = companion(509, "Kettle", Job.CLERIC, 15, 30, 1_000);
        AgentRuntimeEntry entry = following(kettle);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(509, 100); // something else took its purse meanwhile
        shopCompletes(entry, kettle, 15, 30);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_100L);
        assertFalse(execution(entry).isActive());
        assertEquals("finished 509 SHORT_MESOS", calls.getLast());

        purses.put(509, 101); // one looted meso
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 400_000L));
        assertFalse(execution(entry).isActive());
        assertEquals("finished 509 SHORT_MESOS", calls.getLast(), "not assessed again: no new refusal");

        purses.put(509, 100 + 5 * RED);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 405_000L));
        assertTrue(execution(entry).isActive());
    }

    // (h)
    @Test
    void aFullUseTabStaysPutUntilASlotForThePotionsAndOneForTheRunAreFree() {
        Character halvard = companion(510, "Halvard", Job.SPEARMAN, 15, 30, 20_000);
        freeSlots.put(510, 0);
        AgentRuntimeEntry entry = following(halvard);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, halvard, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, halvard, 6_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 510 NO_SPACE 0"), calls);
        assertEquals(AgentSupplyReadiness.StatusKind.NO_SPACE, AgentSupplyReadiness.status(entry, 6_000L).kind());

        freeSlots.put(510, 1);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, halvard, 11_000L));
        assertFalse(execution(entry).isActive(), "its last slot would go to the potions: the run needs one free");

        freeSlots.put(510, 2);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, halvard, 16_000L));
        assertTrue(execution(entry).isActive());
    }

    // (h)
    @Test
    void aFullUseTabWithAStackThePotionsGoOntoStillSetsOff() {
        Character halvard = companion(524, "Halvard", Job.SPEARMAN, 15, 30, 20_000);
        freeSlots.put(524, 0);
        redStack.add(524);
        AgentRuntimeEntry entry = following(halvard);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, halvard, 1_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:HP_POTION:"));
        assertEquals(List.of("started 524 50000"), calls);
    }

    @Test
    void slotsForCountsEveryStackOnceTheStandardIsMoreThanOne() {
        assertEquals(3, AgentSupplyReadinessRuntime.slotsFor(250, false, 100, 250, 0));
        assertEquals(3, AgentSupplyReadinessRuntime.slotsFor(250, true, 100, 250, 0),
                "the room of a carried stack is not counted on");
        assertEquals(4, AgentSupplyReadinessRuntime.slotsFor(350, false, 100, 350, 0));
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(100, true, 100, 250, 0),
                "one stack's worth onto a stack with one free place still needs a slot");
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(30, true, 100, 250, 0));
        assertEquals(2, AgentSupplyReadinessRuntime.slotsFor(101, false, 100, 250, 0));
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(25, true, 100, 25, 0), "one stack: today's rule");
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(25, false, 100, 25, 0));
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(40, true, 100, 40, 0));
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(250, false, 0, 250, 0), "stacks not known: today's rule");
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(0, false, 100, 250, 0));
    }

    @Test
    void slotsForCountsNoneWhenTheLastFreeSlotsStackTakesThemAll() {
        // review 2026-10-06: at its last free slot the counter buys onto a carried stack (AgentShopService.floorItem)
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(30, false, 100, 250, 50));
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(50, false, 100, 250, 50), "exactly its room");
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(51, false, 100, 250, 50), "one more takes a slot");
        assertEquals(1, AgentSupplyReadinessRuntime.slotsFor(25, false, 100, 25, 50),
                "one stack: today's rule, which reads its own stack");
    }

    @Test
    void slotsForSubtractsTheRoomOfTheStackTheCounterBuysOnto() {
        // review 2026-10-06: back from an EPQ run with 20/30 on a stack each, its restock asked two slots too many
        assertEquals(2, AgentSupplyReadinessRuntime.slotsFor(230, false, 100, 250, 80));
        assertEquals(3, AgentSupplyReadinessRuntime.slotsFor(320, false, 100, 350, 70));
        assertEquals(3, AgentSupplyReadinessRuntime.slotsFor(250, false, 100, 250, 0));
        assertEquals(0, AgentSupplyReadinessRuntime.slotsFor(80, false, 100, 250, 80), "all onto the carried stack");
    }

    @Test
    void anEpqCompanionBackFromARunRestocksIntoTheSlotsItsRunFreed() {
        // review 2026-10-06: from 0/0 with exactly 9 free the visit leaves White 100/100/100/50 and Blue 100/100/100/50;
        // a run uses 330/320, autopot empties 3 + 3 stacks: 7 free, 20 White and 30 Blue on a stack each. 3 + 3 + the
        // run's
        stackSize = 100;
        Character casse = companion(545, "Casse", Job.MAGICIAN, 20, 30, 200_000);
        activities.put(545, EPQ);
        pickRoom.put(545, new int[]{80, 70});
        freeSlots.put(545, 6);
        AgentRuntimeEntry entry = following(casse);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 6_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 545 NO_SPACE 0"), calls, "one slot short: it says so once");
        freeSlots.put(545, 7);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 11_000L), "the slots its run freed are enough");
        assertTrue(execution(entry).isActive());

        // 330 White do not fit the purse less the mana potions at their cheapest (100,000 - 320 x 200): the counter
        // may buy another HP potion, so that stack's room is not counted on (4), the Blue's is (3), and the run's
        assertFalse(epqMagicianSetsOff(546, 100_000, 80, 70, 7));
        assertTrue(epqMagicianSetsOff(547, 100_000, 80, 70, 8));
        // two carried White stacks with 130 room: still not counted on while the White do not fit
        assertFalse(epqMagicianSetsOff(548, 100_000, 130, 70, 7));
        assertTrue(epqMagicianSetsOff(549, 200_000, 130, 70, 6), "they fit: 2 + 3 + the run's");

        // the MP pick is dearer than the cheapest: after an HP pass that may spend its whole budget, 320 of it at 310
        // do not fit what is left (64,000), so its room is not counted on (4 + 4 + the run's)
        mpPickPrice = 310;
        assertFalse(epqMagicianSetsOff(550, 100_000, 80, 70, 8));
        assertTrue(epqMagicianSetsOff(551, 100_000, 80, 70, 9));
        // the White fit (200,000 - 64,000): that pass spends 105,600, and 320 at 250 fit the 94,400 left (3 + 3 + 1)
        mpPickPrice = 250;
        assertTrue(epqMagicianSetsOff(552, 200_000, 80, 70, 7));
    }

    @Test
    void anEpqTripHeldBackAtTheRunsSlotEndsShortOfSpaceNotCutShort() {
        // review 2026-10-06: the counter keeps the run's slot free for a standard of more than one stack
        // (AgentShopService.roomBesideTheRunsSlot); a trip that ends there ran out of room, nobody cut it short
        stackSize = 100;
        Character casse = companion(553, "Casse", Job.MAGICIAN, 0, 0, 200_000);
        activities.put(553, EPQ);
        freeSlots.put(553, 9);
        AgentRuntimeEntry entry = following(casse);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        freeSlots.put(553, 1); // loot on the way took a slot: the counter stopped at the run's
        shopCompletes(entry, casse, 250, 300);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, casse, 2_100L);
        assertFalse(execution(entry).isActive());
        assertEquals("finished 553 SHORT_SPACE", calls.getLast());
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        assertEquals(AgentSupplyReadinessState.Cooldown.SHORT_SPACE, state.cooldown());
        assertEquals(1, state.interrupted(KERNING), "no trip from Kerning was counted as cut short");

        // a KPQ trip that ends short with one slot free is cut short as before: its standard is one stack
        Character silent = companion(554, "SilentSin", Job.ASSASSIN, 15, 30, 20_000);
        entry = following(silent);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        freeSlots.put(554, 1);
        shopCompletes(entry, silent, 18, 30);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, silent, 2_100L);
        assertFalse(execution(entry).isActive());
        assertEquals("finished 554 INTERRUPTED", calls.getLast());
        assertEquals(AgentSupplyReadinessState.Cooldown.INTERRUPTED,
                entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).cooldown());
    }

    @Test
    void anEpqCompanionNeedsAFreeUseSlotForEveryStackAndOneForTheRun() {
        stackSize = 100;
        // from 0/0: 4 stacks of HP potions, 4 of MP potions, and the slot the run keeps free
        Character casse = companion(540, "Casse", Job.MAGICIAN, 0, 0, 200_000);
        activities.put(540, EPQ);
        freeSlots.put(540, 8);
        AgentRuntimeEntry entry = following(casse);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 6_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 540 NO_SPACE 0"), calls, "it says so once");
        freeSlots.put(540, 9);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 11_000L));
        assertTrue(execution(entry).isActive());

        // back from a run with 150/200 on stacks with room, short 200/150: 2 + 2 + the run's
        calls.clear();
        Character halvard = companion(541, "Halvard", Job.SPEARMAN, 150, 200, 200_000);
        activities.put(541, EPQ);
        redStack.add(541);
        blueStack.add(541);
        hpStackRoom.put(541, 100); // not counted on with more than the run's slot free: the counter may open a new kind
        freeSlots.put(541, 4);
        entry = following(halvard);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, halvard, 1_000L));
        assertEquals(List.of("refused 541 NO_SPACE 0"), calls);
        freeSlots.put(541, 5);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, halvard, 6_000L));
        assertTrue(execution(entry).isActive());
        shopService.verify(() -> AgentShopService.carriesStackWithRoom(eq(halvard), any(), anyBoolean()), never());

        // a few short of 350 with only the run's slot free: the counter puts them on the carried stack
        calls.clear();
        Character fenna = companion(543, "Fenna", Job.CLERIC, 320, 350, 200_000);
        activities.put(543, EPQ);
        freeSlots.put(543, 1);
        hpStackRoom.put(543, 20);
        entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertEquals(List.of("refused 543 NO_SPACE 0"), calls, "20 room for 30: a new stack would take the slot");
        Character garrick = companion(544, "Garrick", Job.CLERIC, 320, 350, 200_000);
        activities.put(544, EPQ);
        freeSlots.put(544, 1);
        hpStackRoom.put(544, 50);
        entry = following(garrick);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, garrick, 1_000L), "50 room for 30: no new slot");
        assertTrue(execution(entry).isActive());

        // a KPQ companion short 5 HP potions keeps today's rule: its standard is under one stack
        calls.clear();
        Character silent = companion(542, "SilentSin", Job.ASSASSIN, 15, 30, 20_000);
        freeSlots.put(542, 1);
        entry = following(silent);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        assertEquals(List.of("refused 542 NO_SPACE 0"), calls);
        freeSlots.put(542, 2);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 6_000L));
        assertTrue(execution(entry).isActive());
    }

    // (h)
    @Test
    void aFullUseTabNeverHoldsBackACriticalTripOrItsPartyAsk() {
        Character halvard = companion(525, "Halvard", Job.SPEARMAN, 5, 30, 20_000);
        freeSlots.put(525, 0);
        stand(halvard, FIELD, false);
        AgentRuntimeEntry entry = following(halvard);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, halvard, 1_000L));
        assertEquals(List.of("party ask 525"), calls, "as before this duty: the shop finds out whether it fits");

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, halvard, 1_000L + partyWaitMs()));
        assertTrue(execution(entry).requestId().startsWith("supply:HP_POTION:"), "nobody shared: it goes and tries");
    }

    // (i)
    @Test
    void aShopVisitCancelledMidwayIsInterruptedAndWaitsFiveMinutesWhateverItsPurse() {
        Character wren = companion(511, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        route = AgentRouteStatus.ARRIVED;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 1_000L));
        assertEquals(AgentSupplyProcurementState.Phase.SHOPPING, execution(entry).phase());

        cancelledMidShop(entry);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 2_000L)); // retried once
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 7_000L));
        cancelledMidShop(entry);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 7_100L));
        assertTrue(execution(entry).abandoned());
        long finishedAt = 7_200L;
        AgentSupplyProcurementRuntime.tick(entry, wren, finishedAt);

        assertFalse(execution(entry).isActive());
        assertEquals("finished 511 INTERRUPTED", calls.getLast(), "not short of mesos: no broke line");
        purses.put(511, 50_000);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 12_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, finishedAt + shortTripCooldownMs() - 1));
        assertFalse(execution(entry).isActive(), "no new trip within five minutes");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, finishedAt + shortTripCooldownMs() + 5_000L));
        assertTrue(execution(entry).isActive());
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("party ask")));
    }

    // (j)
    @Test
    void aBeginnersReadinessTripIsNotCancelled() {
        Character nob = companion(512, "Nob", Job.BEGINNER, 5, 0, 5_000);
        AgentRuntimeEntry entry = following(nob);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, nob, 1_000L));
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, nob, 1_100L));

        assertTrue(execution(entry).isActive(), "a beginner's reserve rule does not cancel a readiness trip");
        assertNotNull(AgentObjectiveKernel.active(entry));
    }

    // (k)
    @Test
    void aPendingShopVisitAnOpenTradeAnActiveObjectiveOrAForeignControllerEachKeepItHome() {
        Character visit = companion(513, "Visit", Job.HUNTER, 15, 30, 5_000);
        AgentRuntimeEntry visiting = following(visit);
        AgentShopStateRuntime.startShopVisit(visiting, new Point(0, 0), new Point(0, 0), 0, 100L);
        assertFalse(AgentSupplyProcurementRuntime.tick(visiting, visit, 1_000L));
        assertFalse(execution(visiting).isActive());

        Character trader = companion(514, "Trader", Job.HUNTER, 15, 30, 5_000);
        when(trader.getTrade()).thenReturn(mock(Trade.class));
        AgentRuntimeEntry trading = following(trader);
        assertFalse(AgentSupplyProcurementRuntime.tick(trading, trader, 1_000L));
        assertFalse(execution(trading).isActive());

        Character questing = companion(515, "Questing", Job.HUNTER, 15, 30, 5_000);
        AgentRuntimeEntry onObjective = following(questing);
        AgentObjectiveKernel.start(onObjective, new AgentObjectiveDefinition("quest:515", "quest.deliver", 50,
                Long.MAX_VALUE, 1, AgentObjectiveSource.QUEST_PLAN, "test", "quest:515"), 500L);
        AgentSupplyProcurementRuntime.tick(onObjective, questing, 1_000L);
        assertFalse(execution(onObjective).isActive());

        Character scripted = companion(516, "Scripted", Job.HUNTER, 15, 30, 5_000);
        AgentRuntimeEntry withTask = following(scripted);
        withTask.scriptTaskQueueState().addTask(AgentTask.followOwner());
        assertFalse(AgentSupplyProcurementRuntime.tick(withTask, scripted, 1_000L));
        assertFalse(execution(withTask).isActive());

        Character townsfolk = companion(517, "Townsfolk", Job.HUNTER, 15, 30, 5_000);
        AgentRuntimeEntry hosted = following(townsfolk);
        hosted.capabilityStates().require(AgentActivityHostState.STATE_KEY)
                .select(AgentActivityBootstrap.TOWN_LIFE_CONTROLLER_ID, AgentActivityKind.TOWN_LIFE, 500L);
        assertFalse(AgentSupplyProcurementRuntime.tick(hosted, townsfolk, 1_000L));
        assertFalse(execution(hosted).isActive());

        assertEquals(List.of(), calls, "nothing set off and nothing was refused");
        Character idle = companion(518, "Idle", Job.HUNTER, 15, 30, 5_000);
        AgentRuntimeEntry following = following(idle);
        assertNull(following.capabilityStates().require(AgentActivityHostState.STATE_KEY).controllerId(),
                "a following companion has no Activity Host controller");
        assertTrue(AgentSupplyProcurementRuntime.tick(following, idle, 1_000L));
    }

    // (a, second half)
    @Test
    void aDutyHoldersCriticalRequestThatItsPotionsNoLongerBearOutIsDropped() {
        Character yara = companion(521, "Yara", Job.CLERIC, 15, 30, 5_000);
        stand(yara, FIELD, false); // a field: no readiness trip either
        AgentRuntimeEntry entry = following(yara);
        AgentResourcePlanningState planning = planCritical(entry, AgentResourceCategory.HP_POTION, 5); // shared since

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, yara, 1_000L));

        assertNull(planning.procurement(AgentResourceCategory.HP_POTION));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls, "no party ask for potions it has");
    }

    // (l)
    @Test
    void withNoRuleInstalledNothingChanges() {
        AgentSupplyReadiness.install(null);
        Character silent = companion(519, "SilentSin", Job.ASSASSIN, 14, 21, 103_000);
        AgentRuntimeEntry entry = following(silent);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 6_000L));
        assertFalse(execution(entry).isActive(), "above POT_STOP: no trip without a duty");

        Character fenna = companion(520, "Fenna", Job.HUNTER, 1, 30, 0);
        AgentRuntimeEntry broke = following(fenna);
        planCritical(broke, AgentResourceCategory.HP_POTION, 1);
        assertFalse(AgentSupplyProcurementRuntime.tick(broke, fenna, 1_000L));
        assertEquals(List.of("party ask 520"), calls, "without a duty, a broke companion asks its party as before");
        assertEquals(0, AgentSupplyReadiness.buyTarget(fenna, true));
    }

    // ---- review round 1 (2026-10-02) ---------------------------------------------------------------

    @Test
    void aBrokeCompanionBackFromARunStillShowsOutOfMesosWhileItsCooldownHolds() {
        Character fenna = companion(530, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertEquals(List.of("refused 530 CANNOT_AFFORD " + (19 * RED - 155)), calls);

        duty.remove(530); // in the run: no duty, and its verdict is let go
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        assertEquals(AgentSupplyReadiness.Status.NONE, AgentSupplyReadiness.status(entry, 6_000L), "no duty, no status");
        duty.add(530); // back at Lakelis, as broke as before
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));

        AgentSupplyReadiness.Status status = AgentSupplyReadiness.status(entry, 11_000L);
        assertEquals(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, status.kind(), "its cooldown still holds it back");
        assertEquals(19 * RED - 155, status.missingMesos());
        assertEquals(1, calls.size(), "and it does not say so again");
    }

    @Test
    void aTripThatRanOutOfMesosShowsOutOfMesosAtTheDesk() {
        Character kettle = companion(531, "Kettle", Job.CLERIC, 15, 30, 1_000);
        AgentRuntimeEntry entry = following(kettle);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(531, 100);
        shopCompletes(entry, kettle, 15, 30);
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_000L);
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_100L);
        assertEquals("finished 531 SHORT_MESOS", calls.getLast());

        AgentSupplyReadiness.Status status = AgentSupplyReadiness.status(entry, 2_100L);
        assertEquals(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, status.kind());
        assertEquals(5 * RED - 100, status.missingMesos());
    }

    @Test
    void aCompanionThatGotItsPotionsAndRanShortAgainIsAssessedAfresh() {
        Character kettle = companion(544, "Kettle", Job.CLERIC, 15, 30, 100);
        AgentRuntimeEntry entry = following(kettle);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        assertEquals(List.of("refused 544 CANNOT_AFFORD " + (5 * RED - 100)), calls);

        potions.put(544, new int[]{20, 30}); // its owner handed it some
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 6_000L));
        potions.put(544, new int[]{1, 30}); // a run later, far shorter, the purse as thin
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 11_000L));

        AgentSupplyReadiness.Status status = AgentSupplyReadiness.status(entry, 11_000L);
        assertEquals(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, status.kind());
        assertEquals(19 * RED - 100, status.missingMesos(), "what it lacks now, not the old 150");
    }

    @Test
    void aBrokeCompanionGivenPotionsInsteadOfCoinSetsOffForTheFewItStillLacks() {
        Character fenna = companion(532, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertEquals(List.of("refused 532 CANNOT_AFFORD " + (19 * RED - 155)), calls);

        potions.put(532, new int[]{18, 30}); // her owner handed her 17 potions: 2 short, 100 mesos
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:HP_POTION:"));
        assertEquals("started 532 50000", calls.getLast());
    }

    // ---- taking its owner's help (user decision 2026-10-09) ------------------------------------------
    // Live 2026-10-08 06:34 at the Ellin desk: Kestrel (165 mesos), Halvard (49) and Lioren (17) could not pay for
    // EPQ's 350/350 standard. Its words name no amount, so an owner's first gift may fall short: the companion says
    // so, and sets off the moment what it was given pays for the trip.

    @Test
    void aBrokeCompanionGivenPartOfWhatItLacksSaysItIsStillShortWithoutLookingAtTheShopAgain() {
        Character fenna = companion(560, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        int missing = 19 * RED - 155;
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertEquals(List.of("refused 560 CANNOT_AFFORD " + missing), calls);

        purses.put(560, 155 + 300); // her owner's coin: part of it
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        assertEquals(List.of("refused 560 CANNOT_AFFORD " + missing, "still short 560 " + (missing - 300)), calls);
        assertFalse(execution(entry).isActive());
        verify(shops, times(1)).findForNpc(anyInt()); // what it still lacks is known from its purse

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        assertEquals(2, calls.size(), "nothing new, nothing said: " + calls);

        purses.put(560, 155 + missing); // the rest
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertEquals("started 560 50000", calls.getLast(), "its go line, no still-short line: " + calls);
        assertEquals(3, calls.size(), calls.toString());
    }

    @Test
    void potionsGivenThatStillLeaveItShortAreToldWithWhatTheRestCosts() {
        Character fenna = companion(561, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        potions.put(561, new int[]{9, 30}); // eight handed over: eleven short, still more than her purse buys
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        assertEquals(List.of("refused 561 CANNOT_AFFORD " + (19 * RED - 155), "still short 561 " + (11 * RED - 155)),
                calls, "one refusal: the verdict did not change, the gift did");
    }

    @Test
    void aGiftThatCoversEverythingIsNeitherStillShortNorATrip() {
        Character fenna = companion(562, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        potions.put(562, new int[]{20, 30});
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        potions.put(562, new int[]{1, 30}); // a run later it is short again, as poor as before
        purses.put(562, 155);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));

        assertEquals(List.of("refused 562 CANNOT_AFFORD " + (19 * RED - 155),
                "refused 562 CANNOT_AFFORD " + (19 * RED - 155)), calls, "asked afresh, nothing still short");
    }

    @Test
    void aPurseThatGrowsOnAnotherMapOrOutOnAFieldIsNoGift() {
        Character fenna = companion(563, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        stand(fenna, FIELD, false); // out hunting with its owner: loot
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        purses.put(563, 255);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        stand(fenna, KERNING, true); // back in town, richer by its own hunt
        purses.put(563, 355);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));

        assertEquals(List.of("refused 563 CANNOT_AFFORD " + (19 * RED - 155)), calls);
    }

    @Test
    void aGiftThatPaidForATripIsSpentByItAndNeverToldAfterwards() {
        Character kettle = companion(565, "Kettle", Job.CLERIC, 15, 30, 100);
        AgentRuntimeEntry entry = following(kettle);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(565, 150);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 6_000L));
        assertEquals("still short 565 " + (5 * RED - 150), calls.getLast());

        purses.put(565, 5 * RED); // the rest, within STILL_SHORT_EVERY_MS of the first gift
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 11_000L));
        assertEquals("started 565 50000", calls.getLast());
        purses.put(565, 100); // something else took its purse on the way
        shopCompletes(entry, kettle, 15, 30);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 12_000L));
        AgentSupplyProcurementRuntime.tick(entry, kettle, 12_100L);
        assertEquals("finished 565 SHORT_MESOS", calls.getLast());

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 100_000L));
        assertEquals("finished 565 SHORT_MESOS", calls.getLast(), "no gift since: nothing still short");
    }

    @Test
    void giftsWithinStillShortEveryMsAreToldOnceTogether() {
        Character fenna = companion(564, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        int missing = 19 * RED - 155;
        long every = config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.STILL_SHORT_EVERY_MS");
        assertTrue(every > 10_000L, "the shipped value spans more than two checks: " + every);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        purses.put(564, 255);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        purses.put(564, 355);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        assertEquals(List.of("refused 564 CANNOT_AFFORD " + missing, "still short 564 " + (missing - 100)), calls);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L + every));
        assertEquals("still short 564 " + (missing - 200), calls.getLast(), "the second gift, told once it may be");
        assertEquals(3, calls.size(), calls.toString());
    }

    // ---- what it was never given (review of the 2026-10-09 change) ------------------------------------
    // Each of these said "that helps, but i'm still short" (or nothing at all for a real gift) before the fix.

    @Test
    void potionsItBoughtOnItsOwnTripThatRanOutOfMesosAreNoGift() {
        Character kettle = companion(580, "Kettle", Job.CLERIC, 15, 30, 5 * RED);
        AgentRuntimeEntry entry = following(kettle);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(580, 0); // something else took its purse on the way: two potions bought, three still short
        shopCompletes(entry, kettle, 17, 30);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_100L);
        assertEquals(List.of("started 580 50000", "finished 580 SHORT_MESOS"), calls, "its broke line");

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 7_000L)); // back on the map it set off from
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 12_000L));

        assertEquals(List.of("started 580 50000", "finished 580 SHORT_MESOS"), calls,
                "what it bought itself is no gift: nothing still short after its broke line");
    }

    @Test
    void potionsItBoughtOnItsOwnCriticalTripAreNoGift() {
        Character fenna = companion(581, "Fenna", Job.HUNTER, 1, 30, 155 + 9 * RED);
        AgentRuntimeEntry entry = following(fenna);
        planCritical(entry, AgentResourceCategory.HP_POTION, 1);
        int missing = 19 * RED - (155 + 9 * RED);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertEquals(List.of("refused 581 CANNOT_AFFORD " + missing, "party ask 581"), calls,
                "the standard is past her purse, the nine potions back to POT_STOP are not");
        long now = 1_000L + partyWaitMs();
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, now));
        assertTrue(execution(entry).requestId().startsWith("supply:HP_POTION:"), execution(entry).requestId());

        purses.put(581, 155);
        shopCompletes(entry, fenna, 10, 30);
        for (int i = 0; i < 5 && execution(entry).isActive(); i++) {
            AgentSupplyProcurementRuntime.tick(entry, fenna, now + 100L * (i + 1));
        }
        assertFalse(execution(entry).isActive(), "the critical trip is over");
        for (long later = now + 5_000L; later <= now + 20_000L; later += 5_000L) {
            assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, later));
        }

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("still short")),
                "the potions she bought herself are no gift: " + calls);
    }

    @Test
    void mesosItPicksUpOnItsOwnCriticalTripThatNeverReachedAShopAreNoGift() {
        Character fenna = companion(586, "Fenna", Job.HUNTER, 1, 30, 155 + 9 * RED);
        AgentRuntimeEntry entry = following(fenna);
        planCritical(entry, AgentResourceCategory.HP_POTION, 1);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        route = AgentRouteStatus.NO_ROUTE; // the way to the store is shut: the trip turns back without shopping
        long now = 1_000L + partyWaitMs();
        AgentSupplyProcurementRuntime.tick(entry, fenna, now);
        assertTrue(execution(entry).requestId().startsWith("supply:HP_POTION:"), execution(entry).requestId());
        purses.put(586, 155 + 9 * RED + 100); // a coin it picked up on the way

        for (int i = 0; i < 5 && execution(entry).isActive(); i++) {
            AgentSupplyProcurementRuntime.tick(entry, fenna, now + 100L * (i + 1));
        }
        assertFalse(execution(entry).isActive(), "the critical trip is over");
        for (long later = now + 5_000L; later <= now + 20_000L; later += 5_000L) {
            AgentSupplyProcurementRuntime.tick(entry, fenna, later);
        }

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("still short")),
                "what it picked up on its own trip is no gift: " + calls);
    }

    @Test
    void mesosFromSellingItsOwnLootAtAShopAreNoGift() {
        Character fenna = companion(587, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        // its owner told it to sell its trash (AgentUtilityRuntime.sellTrash): a whole visit between two checks
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 2_000L);
        purses.put(587, 255);
        AgentShopStateRuntime.clearShopState(entry);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        // and again, a check while the visit is under way
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 7_000L);
        purses.put(587, 355);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        AgentShopStateRuntime.clearShopState(entry);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 21_000L));

        assertEquals(List.of("refused 587 CANNOT_AFFORD " + (19 * RED - 155)), calls,
                "what it sold itself is no gift: nothing still short");
    }

    @Test
    void anOwnersOrderRightAfterAGiftDoesNotWipeIt() {
        // second review: every movement command cancels the shop visit (AgentCommandModeService), even with none open
        Character fenna = companion(588, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        potions.put(588, new int[]{9, 30}); // eight handed over
        AgentShopStateRuntime.clearShopState(entry); // and "follow me" a moment later: no visit was open
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));

        assertEquals(List.of("refused 588 CANNOT_AFFORD " + (19 * RED - 155), "still short 588 " + (11 * RED - 155)),
                calls);
    }

    @Test
    void aGiftHeldBackByTheSpacingIsNotToldAfterANewRefusal() {
        // second review: the refusal on the new map is the news now; a gift from before it is not told after it
        Character fenna = companion(589, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        long every = config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.STILL_SHORT_EVERY_MS");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        purses.put(589, 255);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        stand(fenna, KERNING_SHOP, true);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        potions.put(589, new int[]{9, 30}); // within the spacing of its last still-short news
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertEquals("refused 589 CANNOT_AFFORD " + (11 * RED - 255), calls.getLast(), calls.toString());

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L + every));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L + every));

        assertEquals(List.of("refused 589 CANNOT_AFFORD " + (19 * RED - 155), "still short 589 " + (19 * RED - 255),
                "refused 589 CANNOT_AFFORD " + (11 * RED - 255)), calls, "nothing still short after the refusal");
    }

    @Test
    void aGiftHeldBackByTheSpacingIsStillToldAfterARefusalItsHostKeptQuiet() {
        // third review: the host mutes a broke line said within COMPANION_RESTOCK_ASK_EVERY_MS, and that is exactly
        // when it answers a still-short report: the gift must stay to be told
        Character fenna = companion(592, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        long every = config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.STILL_SHORT_EVERY_MS");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        purses.put(592, 255);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        stand(fenna, KERNING_SHOP, true);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        refusalsSaid = false; // its broke line was said a minute ago: the new map's refusal is not said again
        potions.put(592, new int[]{9, 30});
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L + every));

        assertEquals(List.of("refused 592 CANNOT_AFFORD " + (19 * RED - 155), "still short 592 " + (19 * RED - 255),
                "refused 592 CANNOT_AFFORD " + (11 * RED - 255), "still short 592 " + (11 * RED - 255)), calls,
                "the potions are answered once the spacing allows");
    }

    @Test
    void aBrokeCompanionIsHeldBackForMesosUntilItsPurseGrowsByWhatItLacked() {
        Character fenna = companion(590, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyReadiness.heldForMesos(entry, 500L), "not looked yet");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        assertTrue(AgentSupplyReadiness.heldForMesos(entry, 1_000L));

        stand(fenna, KERNING_SHOP, true); // it follows its owner into the store
        assertTrue(AgentSupplyReadiness.heldForMesos(entry, 2_000L), "its hold goes along");
        purses.put(590, 155 + 19 * RED - 155);
        assertFalse(AgentSupplyReadiness.heldForMesos(entry, 3_000L), "paid: its own trip goes next");

        Character idle = companion(591, "Idle", Job.HUNTER, 1, 30, 155);
        duty.remove(591);
        assertFalse(AgentSupplyReadiness.heldForMesos(following(idle), 1_000L), "no duty");
        assertFalse(AgentSupplyReadiness.heldForMesos(null, 1_000L));
    }

    @Test
    void mesosItLootedWhileItsDutyWasOffAreNoGift() {
        Character fenna = companion(582, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));

        duty.remove(582); // its owner told it to hunt on its own: no duty
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));
        stand(fenna, FIELD, false);
        purses.put(582, 455); // its own loot
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        stand(fenna, KERNING, true); // back with its owner in the town it was refused in
        duty.add(582);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 21_000L));

        assertEquals(List.of("refused 582 CANNOT_AFFORD " + (19 * RED - 155)), calls,
                "richer by its own hunt: no gift, nothing still short");
    }

    @Test
    void potionsGivenAfterATripThatRanOutOfMesosAreToldStillShort() {
        Character kettle = companion(583, "Kettle", Job.CLERIC, 15, 30, 5 * RED);
        AgentRuntimeEntry entry = following(kettle);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(583, 0);
        shopCompletes(entry, kettle, 17, 30);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_100L);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 7_000L));

        potions.put(583, new int[]{18, 30}); // its owner hands over one potion: two still short, an empty purse
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, kettle, 12_000L));

        assertEquals(List.of("started 583 50000", "finished 583 SHORT_MESOS", "still short 583 " + 2 * RED,
                "refused 583 CANNOT_AFFORD " + 2 * RED), calls,
                "the gift first: its host says still short, and its broke line (said at the trip's end) holds");
    }

    @Test
    void potionsGivenOnAnotherMapThanItsRefusalAreToldStillShort() {
        Character fenna = companion(584, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        stand(fenna, KERNING_SHOP, true); // its owner walks into the store with it
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));

        potions.put(584, new int[]{9, 30}); // and hands over eight potions there
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));

        assertEquals(List.of("refused 584 CANNOT_AFFORD " + (19 * RED - 155), "still short 584 " + (11 * RED - 155),
                "refused 584 CANNOT_AFFORD " + (11 * RED - 155)), calls,
                "the gift is told before the new map's refusal, whose broke line its host already said");
    }

    @Test
    void atABossGateWhereItCannotShopAGiftIsNeverToldAndTheRestPaysForATripBackInTown() {
        Character fenna = companion(585, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry entry = following(fenna);
        int missing = 19 * RED - 155;
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 1_000L));
        standWithoutSpawns(fenna, PAPULATUS_GATE, 0); // no town, no monsters: but the rule keeps it here
        noShopMaps.add(PAPULATUS_GATE);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 6_000L));

        purses.put(585, 455); // part of it at the gate
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 11_000L));
        purses.put(585, 155 + missing); // the rest
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, fenna, 16_000L));
        assertEquals(List.of("refused 585 CANNOT_AFFORD " + missing), calls, "no shop from here: nothing still short");
        assertFalse(execution(entry).isActive());

        stand(fenna, KERNING, true);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, fenna, 21_000L));
        assertEquals("started 585 50000", calls.getLast(), "back in town, what it was given pays for its trip");
    }

    @Test
    void anOwnersFollowMeMidTripBringsItAlongAndNoNewTripForFiveMinutes() {
        Character wren = companion(533, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 1_000L));
        assertEquals(AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER, execution(entry).phase());

        assertTrue(AgentSupplyProcurementRuntime.ownerCalledBack(entry, true));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 1_500L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("started 533 50000", "finished 533 INTERRUPTED"), calls, "no party ask, no broke line");
        assertTrue(AgentModeStateRuntime.following(entry), "with its owner again");
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 1_500L + shortTripCooldownMs() - 1));
        assertFalse(execution(entry).isActive(), "no new trip within five minutes");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 1_500L + shortTripCooldownMs() + 5_000L));
    }

    @Test
    void anOwnersWaitHereMidTripStopsItWhereItStandsWithoutAWalkBack() {
        Character wren = companion(534, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 1_000L));
        stand(wren, KERNING_SHOP, false);
        org.mockito.Mockito.clearInvocations(gateway);

        assertTrue(AgentSupplyProcurementRuntime.ownerCalledBack(entry, false));
        AgentModeStateRuntime.setFollowing(entry, false); // the order's own effect: it stops
        purses.put(534, 0); // spent meanwhile: still no broke line, it was called back
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 1_500L));

        assertFalse(execution(entry).isActive());
        verify(gateway, org.mockito.Mockito.never()).travelTo(any(), any(), anyInt(), anyLong());
        assertFalse(AgentModeStateRuntime.following(entry), "it waits where it was told");
        assertEquals("finished 534 INTERRUPTED", calls.getLast());
        assertFalse(AgentSupplyProcurementRuntime.ownerCalledBack(entry, true), "no trip under way any more");
    }

    @Test
    void aCriticalTripIsNotCalledBack() {
        Character mira = companion(535, "Mira", Job.HUNTER, 5, 30, 20_000);
        AgentRuntimeEntry entry = following(mira);
        startCriticalTrip(entry, "supply:HP_POTION:535", AgentResourceCategory.HP_POTION, 5);

        assertFalse(AgentSupplyProcurementRuntime.ownerCalledBack(entry, true));
        assertFalse(execution(entry).abandoned());
    }

    @Test
    void aCompanionWhoseOwnerIsLeavingTownFollowsInsteadOfTurningBackToTheShop() {
        Character wren = companion(536, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        Character owner = AgentRelationshipRuntime.followTarget(entry);
        when(owner.getMapId()).thenReturn(FIELD); // its owner just went out to hunt

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 1_000L));
        assertFalse(execution(entry).isActive());

        when(owner.getMapId()).thenReturn(KERNING);
        when(owner.isChangingMaps()).thenReturn(true); // on its way through a portal
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, wren, 6_000L));
        assertFalse(execution(entry).isActive());

        when(owner.isChangingMaps()).thenReturn(false);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, wren, 11_000L));
        assertEquals(List.of("started 536 50000"), calls);
    }

    @Test
    void twoTripsFromOneTownCutShortInARowEndItsTriesThereUntilItHasBeenElsewhere() {
        Character pip = companion(537, "Pip", Job.FP_WIZARD, 15, 30, 20_000);
        AgentRuntimeEntry entry = following(pip);
        long now = 1_000L;
        for (int trip = 0; trip < 2; trip++) {
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now), "trip " + trip);
            assertTrue(AgentSupplyProcurementRuntime.ownerCalledBack(entry, true));
            AgentSupplyProcurementRuntime.tick(entry, pip, now + 100L);
            assertFalse(execution(entry).isActive());
            now += shortTripCooldownMs() + 5_000L;
        }
        assertEquals(4, calls.size(), calls.toString());

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, pip, now));
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, pip, now + shortTripCooldownMs() * 3));
        assertFalse(execution(entry).isActive(), "no third trip, and no third go line, from Kerning");
        assertEquals(4, calls.size(), calls.toString());

        stand(pip, FIELD, false); // out with its owner, then back in town (in a run out there: no duty)
        duty.remove(537);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, pip, now + shortTripCooldownMs() * 4));
        duty.add(537);
        stand(pip, KERNING, true);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now + shortTripCooldownMs() * 5));
        assertEquals("started 537 50000", calls.getLast());
    }

    @Test
    void standingAtTheShopItGaveUpOnIsNotHavingBeenElsewhere() {
        // live 2026-10-04: a 7-hop trip ended at the store; one check there, before the follow warp landed, lifted the
        // give-up and the companion set off again 5 s later
        Character pip = companion(541, "Pip", Job.FP_WIZARD, 15, 30, 20_000);
        AgentRuntimeEntry entry = following(pip);
        long now = 1_000L;
        for (int trip = 0; trip < 2; trip++) {
            assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now), "trip " + trip);
            assertTrue(AgentSupplyProcurementRuntime.ownerCalledBack(entry, true));
            AgentSupplyProcurementRuntime.tick(entry, pip, now + 100L);
            assertFalse(execution(entry).isActive());
            now += shortTripCooldownMs() + 5_000L;
        }
        assertEquals(4, calls.size(), calls.toString());

        stand(pip, KERNING_SHOP, true); // still at the store it gave up on
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, pip, now));
        stand(pip, KERNING, true);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, pip, now + shortTripCooldownMs() * 3));

        assertFalse(execution(entry).isActive(), "no third trip from Kerning");
        assertEquals(4, calls.size(), calls.toString());
    }

    @Test
    void aTripThatCameBackReadyClearsTheCountOfTripsCutShort() {
        Character pip = companion(538, "Pip", Job.FP_WIZARD, 15, 30, 20_000);
        AgentRuntimeEntry entry = following(pip);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, 1_000L));
        AgentSupplyProcurementRuntime.ownerCalledBack(entry, true);
        AgentSupplyProcurementRuntime.tick(entry, pip, 1_100L);
        long now = 1_100L + shortTripCooldownMs() + 5_000L;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now));
        shopCompletes(entry, pip, 40, 50);
        AgentSupplyProcurementRuntime.tick(entry, pip, now + 100L);
        AgentSupplyProcurementRuntime.tick(entry, pip, now + 200L);
        AgentSupplyProcurementRuntime.tick(entry, pip, now + 300L);
        assertEquals("finished 538 READY", calls.getLast());

        potions.put(538, new int[]{15, 30}); // a run later, short again
        now += 10_000L;
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now));
        AgentSupplyProcurementRuntime.ownerCalledBack(entry, true);
        AgentSupplyProcurementRuntime.tick(entry, pip, now + 100L);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, pip, now + 100L + shortTripCooldownMs() + 5_000L),
                "the first trip cut short since it came back ready: it tries once more");
    }

    @Test
    void anAgentIsCarriedByItsLeaderWhileItFollowsThemOrIsOnATripThatFollowsThemBack() {
        Character wren = companion(539, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        assertTrue(AgentSupplyReadiness.carriedBy(entry, OWNER));
        assertFalse(AgentSupplyReadiness.carriedBy(entry, OWNER + 1), "following someone else");

        AgentModeStateRuntime.setFollowing(entry, false); // told to wait here
        assertFalse(AgentSupplyReadiness.carriedBy(entry, OWNER));

        execution(entry).start("readiness:HP_POTION:1", "maintenance:resupply:readiness:HP_POTION:1",
                AgentResourceCategory.HP_POTION, KERNING_SHOP, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        execution(entry).rememberForegroundMode(true, false);
        assertTrue(AgentSupplyReadiness.carriedBy(entry, OWNER), "on a trip that ends by following its owner");
        execution(entry).rememberForegroundMode(false, true);
        assertFalse(AgentSupplyReadiness.carriedBy(entry, OWNER), "on a trip back to its hunt");
        assertFalse(AgentSupplyReadiness.carriedBy(null, OWNER));
    }

    @Test
    void aHostsFollowAfterTheTripIsTakenOnlyWhileTheTripIsUnderWay() {
        Character wren = companion(540, "Wren", Job.ASSASSIN, 15, 30, 5_000);
        AgentRuntimeEntry entry = following(wren);
        AgentSupplyProcurementState execution = execution(entry);
        assertFalse(AgentSupplyReadiness.resumeFollowAfterTrip(entry), "no trip");
        assertFalse(execution.resumeFollow());

        execution.start("readiness:HP_POTION:1", "maintenance:resupply:readiness:HP_POTION:1",
                AgentResourceCategory.HP_POTION, KERNING_SHOP, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING);
        execution.rememberForegroundMode(false, true);
        assertTrue(AgentSupplyReadiness.resumeFollowAfterTrip(entry));
        AgentSupplyProcurementState.ForegroundMode mode = execution.clearTakingForegroundMode();

        assertEquals(new AgentSupplyProcurementState.ForegroundMode(true, false), mode, "the trip ends following");
        assertFalse(execution.isActive());
        assertFalse(AgentSupplyReadiness.resumeFollowAfterTrip(entry), "taken with the clear: too late now");
        assertFalse(execution.resumeFollow());
    }

    @Test
    void aShortCompanionWhoseRestockWillLookIsPendingAndOneHeldBackIsNot() {
        Character silent = companion(541, "SilentSin", Job.ASSASSIN, 14, 21, 103_000);
        AgentRuntimeEntry entry = following(silent);
        assertTrue(AgentSupplyReadiness.restockPending(entry, 500L), "short, free, nothing in the way");

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        assertFalse(AgentSupplyReadiness.restockPending(entry, 1_000L), "gone: RESTOCKING instead");

        Character fenna = companion(542, "Fenna", Job.HUNTER, 1, 30, 155);
        AgentRuntimeEntry broke = following(fenna);
        assertFalse(AgentSupplyProcurementRuntime.tick(broke, fenna, 1_000L));
        assertFalse(AgentSupplyReadiness.restockPending(broke, 1_000L), "it cannot afford them: the desk asks");

        Character ready = companion(543, "Ready", Job.ASSASSIN, 20, 25, 1_000);
        assertFalse(AgentSupplyReadiness.restockPending(following(ready), 1_000L), "nothing lacking");
        duty.remove(541);
        assertFalse(AgentSupplyReadiness.restockPending(following(silent), 1_000L), "no duty");
    }


    // ---- projectiles (2026-10-07: Kestrel reached LPQ stage 7 with no stars) ------------------------------

    @Test
    void aCompanionWithItsPotionsButNoStarsSetsOffForTheStore() {
        Character silent = companion(560, "SilentSin", Job.ASSASSIN, 20, 25, 103_000);
        ammo.put(560, 120);
        AgentRuntimeEntry entry = following(silent);

        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));

        AgentSupplyProcurementState execution = execution(entry);
        assertTrue(execution.isActive());
        assertTrue(execution.requestId().startsWith("readiness:HP_POTION:"), execution.requestId());
        assertEquals(380, readinessRequest(entry).quantity(), "what it lacks of the 500 stars");
        assertEquals(KERNING_SHOP, execution.supplierMapId(), "the potion supplier: one visit fills both");
        assertEquals(List.of("started 560 50000"), calls);
    }

    @Test
    void aCompanionShortOnlyOfAmmoThatCannotPayForItCannotAfford() {
        Character silent = companion(561, "SilentSin", Job.ASSASSIN, 20, 25, 300);
        ammo.put(561, 0);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_000, 1_000L, 2);
        AgentRuntimeEntry entry = following(silent);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 561 CANNOT_AFFORD 700"), calls);
        assertEquals(AgentSupplyReadiness.StatusKind.CANNOT_AFFORD, AgentSupplyReadiness.status(entry, 1_000L).kind());
    }

    @Test
    void aCompanionShortOfPotionsWhosePurseCoversOnlyThePotionsStillGoes() {
        Character silent = companion(562, "SilentSin", Job.ASSASSIN, 14, 21, 6 * RED + 4 * BLUE);
        ammo.put(562, 0);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_000, 1_000_000L, 2); // stars it buys with what is left

        assertTrue(AgentSupplyProcurementRuntime.tick(following(silent), silent, 1_000L),
                "the potions are never held back by the price of its stars");
        assertEquals(List.of("started 562 50000"), calls);
    }

    @Test
    void anAmmoOnlyShortfallThatTheSupplierCannotBringToTheStandardIsNoSupplier() {
        Character silent = companion(563, "SilentSin", Job.ASSASSIN, 20, 25, 103_000);
        ammo.put(563, 300);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(300, 0L, 0); // its own stack is full and no stars on sale
        AgentRuntimeEntry entry = following(silent);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));

        assertFalse(execution(entry).isActive());
        assertEquals(List.of("refused 563 NO_SUPPLIER 0"), calls);
    }

    @Test
    void anAmmoOnlyClawWithOneFreeSlotAndNoRefillableStackHasNoSpace() {
        Character silent = companion(564, "SilentSin", Job.ASSASSIN, 20, 25, 103_000);
        ammo.put(564, 0);
        freeSlots.put(564, 1);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_000, 1_000L, 2);
        AgentRuntimeEntry entry = following(silent);

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        assertEquals(List.of("refused 564 NO_SPACE 0"), calls, "the run's slot stays free");

        freeSlots.put(564, 2); // the boundary: as many free slots as new stacks, none left for the run
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, silent, 6_000L), "two sets need three free slots");
        assertEquals(List.of("refused 564 NO_SPACE 0"), calls, "no trip started (the same refusal is not repeated)");

        freeSlots.put(564, 3);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 11_000L), "two sets and the run's slot");
    }

    @Test
    void aTripEndsReadyOnlyWithTheAmmoStandardMet() {
        Character silent = companion(565, "SilentSin", Job.ASSASSIN, 20, 25, 103_000);
        ammo.put(565, 0);
        AgentRuntimeEntry entry = following(silent);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        AgentSupplyProcurementState execution = execution(entry);
        AgentSupplyProcurementOutcome bought = new AgentSupplyProcurementOutcome(
                AgentSupplyProcurementOutcome.Status.RESTORED, AgentResourceCategory.HP_POTION, 20, 30, 103_000,
                100_000, 1_500L, "bought");

        assertEquals(AgentSupplyProcurementOutcome.Status.PARTIALLY_RESTORED,
                AgentSupplyReadinessRuntime.judged(entry, silent, execution, bought).status(),
                "potions bought, stars still short");
        ammo.put(565, 1_000);
        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentSupplyReadinessRuntime.judged(entry, silent, execution, bought).status());

        shopCompletes(entry, silent, 20, 25);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, silent, 2_100L);
        AgentSupplyProcurementRuntime.tick(entry, silent, 2_200L);
        assertEquals("finished 565 READY", calls.getLast());
    }

    @Test
    void aCriticalPotionTripThatMetThePotionStandardIsRestoredWhileAmmoIsShort() {
        Character mira = companion(566, "Mira", Job.ASSASSIN, 5, 30, 20_000);
        ammo.put(566, 0);
        AgentRuntimeEntry entry = following(mira);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);
        startCriticalTrip(entry, "supply:HP_POTION:566", AgentResourceCategory.HP_POTION, 5);
        potions.put(566, new int[]{45, 50});
        AgentSupplyProcurementOutcome bought = new AgentSupplyProcurementOutcome(
                AgentSupplyProcurementOutcome.Status.NO_PROGRESS, AgentResourceCategory.HP_POTION, 5, 45, 20_000,
                18_000, 1_500L, "short of its planned 200");

        assertEquals(AgentSupplyProcurementOutcome.Status.RESTORED,
                AgentSupplyReadinessRuntime.judged(entry, mira, execution(entry), bought).status(),
                "its critical trip is about potions: stars are its readiness trip's business");
    }

    @Test
    void anAmmoTripThatCannotPayForTheStarsEndsShortOfMesos() {
        Character silent = companion(567, "SilentSin", Job.ASSASSIN, 20, 25, 2_000);
        ammo.put(567, 0);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_000, 1_000L, 2);
        AgentRuntimeEntry entry = following(silent);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        purses.put(567, 400); // something else took its purse meanwhile

        assertEquals(AgentSupplyReadiness.Outcome.SHORT_MESOS,
                AgentSupplyReadinessRuntime.abandonedOutcome(entry, silent, execution(entry), 2_000L));
        assertEquals(AgentSupplyReadinessState.Cooldown.SHORT_MESOS,
                entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).cooldown());
    }

    @Test
    void anAmmoTripEndingWithOneFreeSlotEndsShortOfSpace() {
        Character silent = companion(568, "SilentSin", Job.ASSASSIN, 20, 25, 103_000);
        ammo.put(568, 0);
        ammoPlan = new AgentShopService.ReadinessAmmoPlan(1_000, 1_000L, 2);
        AgentRuntimeEntry entry = following(silent);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, silent, 1_000L));
        freeSlots.put(568, 1); // loot on the way: the counter stopped at the run's slot

        assertEquals(AgentSupplyReadiness.Outcome.SHORT_SPACE,
                AgentSupplyReadinessRuntime.abandonedOutcome(entry, silent, execution(entry), 2_000L));
    }

    @Test
    void aFailedReadinessTripDoesNotSuppressTheCriticalHpPath() {
        Character kettle = companion(569, "Kettle", Job.CLERIC, 15, 30, 1_000);
        AgentRuntimeEntry entry = following(kettle);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 1_000L));
        purses.put(569, 100);
        shopCompletes(entry, kettle, 15, 30);
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, kettle, 2_000L));
        AgentSupplyProcurementRuntime.tick(entry, kettle, 2_100L);
        assertEquals("finished 569 SHORT_MESOS", calls.getLast());

        assertFalse(execution(entry).suppressed(AgentResourceCategory.HP_POTION, 2_200L),
                "an ammo-only trip carries HP_POTION too: readiness keeps its own cooldowns");
    }

    // ---- fixture ---------------------------------------------------------------------------

    /**
     * An EPQ magician back from a run with 20/30 potions, its carried stacks of the coverage picks with this room,
     * {@code free} Use slots free: whether it sets off on its first tick.
     */
    private boolean epqMagicianSetsOff(int id, int mesos, int hpRoom, int mpRoom, int free) {
        stackSize = 100;
        Character agent = companion(id, "Casse", Job.MAGICIAN, 20, 30, mesos);
        activities.put(id, EPQ);
        pickRoom.put(id, new int[]{hpRoom, mpRoom});
        freeSlots.put(id, free);
        return AgentSupplyProcurementRuntime.tick(following(agent), agent, 1_000L);
    }


    /** A Lv25 companion of {@code job} in Kerning with these potions and mesos, under a standing duty. */
    private Character companion(int id, String name, Job job, int hp, int mp, int mesos) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getJob()).thenReturn(job);
        when(agent.getLevel()).thenReturn(25);
        when(agent.getHp()).thenReturn(500);
        when(agent.getChair()).thenReturn(-1);
        when(agent.getMeso()).thenAnswer(inv -> purses.get(id));
        Inventory use = mock(Inventory.class);
        when(use.getNumFreeSlot()).thenAnswer(inv -> (short) (int) freeSlots.getOrDefault(id, 10));
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        stand(agent, KERNING, true);
        potions.put(id, new int[]{hp, mp});
        purses.put(id, mesos);
        duty.add(id);
        return agent;
    }

    /** On a town map, or out on a field where monsters spawn. */
    private static void stand(Character agent, int mapId, boolean town) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.isTown()).thenReturn(town);
        when(map.getMonsterSpawn()).thenReturn(town ? List.of() : List.of(mock(SpawnPoint.class)));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
    }

    /** On a map that is no town but has no monsters: a party quest's entry, with {@code monsters} alive on it. */
    private static void standWithoutSpawns(Character agent, int mapId, int monsters) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.isTown()).thenReturn(false);
        when(map.getMonsterSpawn()).thenReturn(List.of());
        when(map.countMonsters()).thenReturn(monsters);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
    }

    /** Out with its owner, following; its career's bundle picks the shops. */
    private static AgentRuntimeEntry following(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY).assign(
                AgentCareerBuildBundleRepository.defaultRepository().find("thief-claw-standard-v1").orElseThrow());
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getMapId()).thenAnswer(inv -> agent.getMapId()); // its owner stands with it
        AgentRelationshipRuntime.setInteractionTarget(entry, owner);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        AgentModeStateRuntime.setFollowing(entry, true);
        return entry;
    }

    /** A critical request of {@code category} as the potion check plans it at {@code count}. */
    private static AgentResourcePlanningState planCritical(AgentRuntimeEntry entry, AgentResourceCategory category,
                                                           int count) {
        AgentResourcePlanningState planning = entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY);
        int target = Math.max(AgentRuntimeConfig.cfg.POT_LOW_WARN * 2, count);
        planning.update(new AgentSupplyNeed(category, count, target, AgentSupplyUrgency.CRITICAL, "", 1L),
                new AgentProcurementRequest("supply:" + category + ":" + entry.bot().getId(), category,
                        target - count, Long.MAX_VALUE, List.of(AgentProcurementMethod.NPC_SHOP),
                        AgentSupplyUrgency.CRITICAL, "", Long.MAX_VALUE));
        return planning;
    }

    private static void startCriticalTrip(AgentRuntimeEntry entry, String requestId, AgentResourceCategory category,
                                          int before) {
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                "maintenance:resupply:" + requestId, "maintenance.resupply", 1_000, Long.MAX_VALUE, 2,
                AgentObjectiveSource.RECOVERY_POLICY, "supply-procurement-v2", "maintenance:resupply:" + requestId);
        AgentObjectiveKernel.start(entry, maintenance, 5L);
        AgentSupplyProcurementState execution = execution(entry);
        execution.start(requestId, maintenance.objectiveId(), category, KERNING_SHOP, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING, before, 20_000);
        execution.rememberForegroundMode(true, false);
        execution.markShopRequested();
    }

    /** The shop visit ran and left these potions. */
    private void shopCompletes(AgentRuntimeEntry entry, Character agent, int hp, int mp) {
        AgentSupplyProcurementState execution = execution(entry);
        if (!execution.shopRequested()) {
            execution.markShopRequested();
        }
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.workflow(entry).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
        AgentShopStateRuntime.completeWorkflow(entry, "bought", 150L);
        AgentShopStateRuntime.clearShopState(entry);
        potions.put(agent.getId(), new int[]{hp, mp});
    }

    /** The owner's order cancels the shop visit mid-transaction (AgentShopService.cancelShopVisit). */
    private static void cancelledMidShop(AgentRuntimeEntry entry) {
        AgentShopStateRuntime.startShopVisit(entry, new Point(-297, -79), new Point(-297, -78), 0, 100L);
        AgentShopStateRuntime.workflow(entry).transition(AgentShopWorkflowPhase.TRANSACTING, "buying", 120L);
        AgentShopStateRuntime.clearShopState(entry);
    }

    private static AgentSupplyProcurementState execution(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY);
    }

    private static long ttlMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.REQUEST_TTL_MS");
    }

    private static long ttlPerHopMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.REQUEST_TTL_PER_HOP_MS");
    }

    private static AgentProcurementRequest readinessRequest(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).request();
    }

    private static long partyWaitMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy.PARTY_SUPPLY_WAIT_MS");
    }

    private static long shortTripCooldownMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.SHORT_TRIP_COOLDOWN_MS");
    }
}
