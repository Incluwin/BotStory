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
    private static final int KPQ_EXIT = 103_000_890;
    /** The Ant Tunnel park's neighbour: a hunting field, not a town. */
    private static final int FIELD = 104_040_000;
    private static final int OWNER = 77;
    private static final int RED = 50;
    private static final int BLUE = 200;
    private static final AgentSupplyReadiness.Activity PQ = AgentSupplyReadiness.Activity.PARTY_QUEST;

    /** Character id -> {HP potions, MP potions}. */
    private final Map<Integer, int[]> potions = new HashMap<>();
    private final Map<Integer, Integer> purses = new HashMap<>();
    private final Map<Integer, Integer> freeSlots = new HashMap<>();
    /** Character ids that carry a Red (HP) stack with room, onto which bought potions stack without a new slot. */
    private final java.util.Set<Integer> redStack = new java.util.HashSet<>();
    /** The rule's callbacks and the party asks, in order. */
    private final List<String> calls = new ArrayList<>();
    private final java.util.Set<Integer> duty = new java.util.HashSet<>();
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final ShopGateway shops = mock(ShopGateway.class);
    private AgentRouteStatus route = AgentRouteStatus.MOVING;
    private int ammoSpend;
    private MockedStatic<AgentPotionService> potionService;
    private MockedStatic<AgentShopService> shopService;
    private MockedStatic<AgentShopGatewayRuntime> shopGateway;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives;

    @BeforeEach
    void stubTheWorld() {
        potionService = mockStatic(AgentPotionService.class);
        potionService.when(() -> AgentPotionService.countPotions(any(Character.class)))
                .thenAnswer(inv -> potions.get(((Character) inv.getArgument(0)).getId()).clone());
        potionService.when(() -> AgentPotionService.requestPotShare(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> calls.add("party ask " + ((Character) inv.getArgument(1)).getId()));
        shopService = mockStatic(AgentShopService.class);
        shopService.when(() -> AgentShopService.requestVisitAtNpc(any(), any(), anyInt(), anyInt())).thenReturn(true);
        shopService.when(() -> AgentShopService.cheapestRecoveryPrice(any(), any(), eq(true))).thenReturn(RED);
        shopService.when(() -> AgentShopService.cheapestRecoveryPrice(any(), any(), eq(false))).thenReturn(BLUE);
        shopService.when(() -> AgentShopService.plannedAmmoSpend(any(), any())).thenAnswer(inv -> ammoSpend);
        shopService.when(() -> AgentShopService.carriesStackWithRoom(any(), any(), eq(true)))
                .thenAnswer(inv -> redStack.contains(((Character) inv.getArgument(0)).getId()));
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
                return duty.contains(id) ? PQ : null;
            }

            @Override
            public void started(int id, AgentSupplyReadiness.Activity activity, long etaMs) {
                calls.add("started " + id + " " + etaMs);
            }

            @Override
            public void refused(int id, AgentSupplyReadiness.Activity activity, AgentSupplyReadiness.Refusal why,
                                AgentSupplyReadiness.Shortfall shortfall, int missingMesos) {
                calls.add("refused " + id + " " + why + " " + missingMesos);
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
        stand(casse, KPQ_EXIT, false);
        AgentRuntimeEntry entry = following(casse);
        planCritical(entry, AgentResourceCategory.HP_POTION, 5);
        execution(entry).suppress(AgentResourceCategory.HP_POTION, 1_000L + 300_000L); // its exit-map trip failed

        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 1_000L));
        assertFalse(execution(entry).isActive());
        assertEquals(List.of(), calls, "no trip, no callback, no party ask");

        stand(casse, KERNING, true);
        assertFalse(AgentSupplyProcurementRuntime.tick(entry, casse, 2_000L), "the next check is not due yet");
        assertTrue(AgentSupplyProcurementRuntime.tick(entry, casse, 6_000L));
        assertTrue(execution(entry).requestId().startsWith("readiness:"), "readiness ignores the suppression");
        assertEquals(List.of("started 502 50000"), calls);
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
        stand(yara, KPQ_EXIT, false); // not a town: no readiness trip either
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

    // ---- fixture ---------------------------------------------------------------------------

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

    private static void stand(Character agent, int mapId, boolean town) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.isTown()).thenReturn(town);
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

    private static long partyWaitMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy.PARTY_SUPPLY_WAIT_MS");
    }

    private static long shortTripCooldownMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.SHORT_TRIP_COOLDOWN_MS");
    }
}
