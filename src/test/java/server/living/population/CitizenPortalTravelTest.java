package server.living.population;

import client.BotClient;
import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;
import server.maps.Portal;
import testutil.LogLines;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen someone can see travels on foot (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md): it walks to the portal and through
 * it, boards at a dock's NPC, or walks out by the nearest portal; once the map it stands on is empty it goes the cheap
 * way, and the cheap way re-checks before it moves, so it never vanishes in front of a player. The real presence
 * scheduler, CitizenTravel, engine walk (AgentMapWalk) and route graphs run here; maps, the timer and the movement
 * primitives are stand-ins, and each test plays the Agent's ticks and the timer by hand.
 */
class CitizenPortalTravelTest {
    private static final int CITIZEN = 964;
    private static final int HENESYS = 100_000_000;
    private static final int FIELD = 100_010_000;
    private static final int ELLINIA = 101_000_000;
    private static final int HENESYS_MARKET = 100_000_100;
    private static final int STATION = 101_000_300;
    private static final int ORBIS_STATION = 200_000_100;
    private static final int ORBIS = 200_000_000;
    private static final int CHERRY = 1_032_008;
    private static final int FM_ROOM_3 = FieldCatalog.FM_ENTRANCE + 3;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final Market market = mock(Market.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<Integer, Portal> spawns = new HashMap<>();
    private final TimerManager timers = mock(TimerManager.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final List<AgentRuntimeEntry> registered = new ArrayList<>();
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<ResidentSpots> spots;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways;
    private Character agent;
    private AgentRuntimeEntry entry;
    private PresenceScheduler presence;
    private CitizenSession session;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(market);
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.fields()).thenReturn(mock(FieldCatalog.class));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            return null;
        }).when(population).save(any());
        when(population.map(anyInt(), anyInt())).thenAnswer(inv -> maps.get((int) inv.getArgument(1)));
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timers);
        doAnswer(inv -> {
            queued.add(inv.getArgument(1));
            delays.add(inv.getArgument(2));
            return null;
        }).when(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), any(Runnable.class), anyLong());
        movement = mockStatic(AgentMovementCommandRuntime.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
        spots = mockStatic(ResidentSpots.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        gateways = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gateways.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong()))
                .thenReturn(new AgentRouteOutcome(AgentRouteStatus.MOVING, HENESYS, FIELD, FIELD, false));

        map(HENESYS, portal(20, "west00", Portal.MAP_PORTAL, 104_040_000, null, -922, 272),
                portal(21, "east00", Portal.MAP_PORTAL, FIELD, null, 5907, 455));
        map(FIELD, portal(1, "west00", Portal.MAP_PORTAL, HENESYS, null, -1000, 200),
                portal(2, "east00", Portal.MAP_PORTAL, 100_020_000, null, 1500, 200));
        map(ELLINIA);
        map(HENESYS_MARKET, portal(5, "market00", 7, 999_999_999, "market01", 200, 100));
        map(FieldCatalog.FM_ENTRANCE, portal(5, "in03", Portal.TELEPORT_PORTAL, FM_ROOM_3, null, 827, 2),
                portal(34, "out00", 7, 999_999_999, "market00", -538, 30));
        map(FM_ROOM_3, portal(11, "out00", Portal.MAP_PORTAL, FieldCatalog.FM_ENTRANCE, null, 790, 35));
        map(STATION);
        map(ORBIS_STATION);
        map(ORBIS);
    }

    @AfterEach
    void close() {
        registered.forEach(AgentRuntimeRegistry::unregisterEntry);
        AgentMapWalk.stopAll(CITIZEN);
        gateways.close();
        cleanup.close();
        spots.close();
        townLife.close();
        movement.close();
        timerManager.close();
    }

    @Test
    void aCitizenSomeoneCanSeeWalksThroughThePortalInsteadOfVanishing() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);

        try (LogLines lines = new LogLines(CitizenTravel.class)) {
            presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
            runTimers();
            assertEquals(1, lines.startingWith("citizen QuietWalker sets off on foot from 100000000 to 100010000").size());
        }
        assertTrue(AgentMapWalk.walking(CITIZEN), "it walks through the route graphs");
        assertTrue(AgentMapWalk.tick(entry, agent, now()));
        verify(gateway).travelTo(eq(entry), eq(agent), eq(FIELD), anyLong());
        runTimers();
        assertEquals(CitizenSession.Activity.TOWN, session.activity, "its activity changes when it gets there");

        standOn(FIELD, new Point(-1000, 200)); // the portal took it over (enterPortal changes the map)
        runTimers();

        assertEquals(CitizenSession.Activity.TRAIN, session.activity);
        assertEquals(FIELD, session.mapId);
        assertNull(session.trip);
        movement.verify(() -> AgentMovementCommandRuntime.grind(entry));
        verify(agent, never()).changeMap(any(MapleMap.class), nullable(Portal.class));
    }

    @Test
    void aCitizenNobodyCanSeeKeepsTheDelayedMoveToPortalZero() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());

        assertEquals(List.of(1_000L), delays);
        assertNull(session.trip);
        runTimers();
        verify(agent).changeMap(maps.get(FIELD), spawns.get(FIELD));
        assertEquals(CitizenSession.Activity.TRAIN, session.activity);
    }

    @Test
    void onAMapNobodyCanSeeTheWalkHandsOverToTheCheapWay() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, ELLINIA, now());
        runTimers();
        assertTrue(AgentMapWalk.walking(CITIZEN));

        standOn(FIELD, new Point(-1000, 200)); // through Henesys' east portal, onto an empty field
        runTimers();

        assertNull(session.trip);
        assertFalse(AgentMapWalk.walking(CITIZEN));
        assertEquals(FIELD, session.mapId, "it stands there while the cheap move waits");
        verify(agent, never()).changeMap(any(MapleMap.class), nullable(Portal.class));
        runTimers();
        verify(agent).changeMap(maps.get(ELLINIA), spawns.get(ELLINIA));
        assertEquals(CitizenSession.Activity.TRAIN, session.activity);
    }

    @Test
    void aPlayerWhoComesInDuringTheCheapPauseSeesItWalkFromWhereItStands() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        assertNull(session.trip);

        watch(HENESYS); // a player warps in within the second
        runTimers();

        verify(agent, never()).changeMap(any(MapleMap.class), nullable(Portal.class));
        assertNotNull(session.trip);
        runTimers();
        assertTrue(AgentMapWalk.walking(CITIZEN));
    }

    @Test
    void aCheapArrivalOnAMapSomeoneCanSeeComesInAtThePortalItsRouteEntersBy() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(FIELD);

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        assertNull(session.trip, "nobody sees it leave Henesys");
        runTimers();

        verify(agent).changeMap(maps.get(FIELD), portalOf(FIELD, "west00")); // the portal back to Henesys
    }

    /**
     * Ellinia's magic library (101000003) to Ellinia: Ellinia has no portal back to the library, but the library's own
     * exit names the portal a player comes out of, as GenericPortal.enterPortal places them.
     */
    @Test
    void aCheapArrivalWithNoReturnPortalComesInAtThePortalItsOwnMapsPortalNames() {
        int library = 101_000_003;
        map(library, named(portal(3, "out00", Portal.MAP_PORTAL, ELLINIA, null, 100, 50), "lib00"));
        map(ELLINIA, portal(9, "lib00", 7, 999_999_999, "libraryIn", 2_000, -300));
        loadedOn(library, new Point(100, 50), CitizenSession.Activity.TOWN);
        watch(ELLINIA);

        presence.travel(session, CitizenSession.Activity.TOWN, ELLINIA, now());
        runTimers();

        verify(agent).changeMap(maps.get(ELLINIA), portalOf(ELLINIA, "lib00"));
    }

    @Test
    void fromATownWithoutAMarketPortalItWalksToTheTownsMarketMapFirst() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        watch(HENESYS_MARKET);
        when(gateway.portalPosition(agent, 5)).thenReturn(new Point(200, 100));

        presence.travel(session, CitizenSession.Activity.MARKET, FieldCatalog.FM_ENTRANCE, now());
        runTimers();
        assertTrue(AgentMapWalk.tick(entry, agent, now()));
        verify(gateway).travelTo(eq(entry), eq(agent), eq(HENESYS_MARKET), anyLong()); // Henesys Market, one portal away

        standOn(HENESYS_MARKET, new Point(190, 100));
        assertFalse(AgentMapWalk.tick(entry, agent, now()), "it stands on the market map");
        runTimers();
        runTimers();
        assertFalse(AgentMapWalk.tick(entry, agent, now()));
        verify(gateway).enterPortal(agent, 5); // and goes in by its market portal
    }

    @Test
    void onAWatchedDockItWalksUpToTheBoardingNpcAndIsGoneLikeABoardingPlayer() {
        config.PORTAL_TRAVEL_BOARD_MS = 0;
        loadedOn(STATION, new Point(0, 0), CitizenSession.Activity.TOWN);
        watch(STATION);
        when(gateway.npcPosition(agent, CHERRY)).thenReturn(new Point(60, 0));

        presence.travel(session, CitizenSession.Activity.TOWN, ORBIS, now());
        runTimers();
        assertFalse(AgentMapWalk.tick(entry, agent, now()), "it stands beside Cherry");
        verify(gateway).facePosition(agent, new Point(60, 0));
        runTimers(); // it stands there a moment, as if talking
        try (LogLines lines = new LogLines(CitizenTravel.class)) {
            runTimers();
            assertEquals(1, lines.startingWith("citizen QuietWalker boards at 1032008 on 101000300 for 200000100").size());
        }

        verify(agent).changeMap(maps.get(ORBIS_STATION), spawns.get(ORBIS_STATION));
        runTimers(); // nobody on Orbis station: the cheap way to Orbis
        runTimers();
        verify(agent).changeMap(maps.get(ORBIS), spawns.get(ORBIS));
        assertNull(session.trip);
    }

    @Test
    void withNoRouteItWalksOutByTheNearestPortalAndComesInAtTheMarketsOut00() {
        int amoria = 680_000_000; // in no route group, and no market portal: nothing on foot reaches the market from it
        map(amoria, portal(1, "west00", Portal.MAP_PORTAL, 680_010_000, null, -1000, 200),
                portal(2, "east00", Portal.MAP_PORTAL, 680_000_100, null, 1500, 200));
        loadedOn(amoria, new Point(1400, 200), CitizenSession.Activity.TOWN);
        watch(amoria);
        when(gateway.portalPosition(agent, 2)).thenReturn(new Point(1500, 200));

        presence.travel(session, CitizenSession.Activity.MARKET, FieldCatalog.FM_ENTRANCE, now());
        runTimers();
        assertTrue(AgentMapWalk.tick(entry, agent, now()), "to Amoria's east portal, the nearest one");
        verify(gateway).navigate(entry, new Point(1500, 200), true);
        when(agent.getPosition()).thenReturn(new Point(1490, 200));
        assertFalse(AgentMapWalk.tick(entry, agent, now()));
        runTimers();

        verify(agent).changeMap(maps.get(FieldCatalog.FM_ENTRANCE), portalOf(FieldCatalog.FM_ENTRANCE, "out00"));
        assertEquals(CitizenSession.Activity.MARKET, session.activity);
        verify(market).onArrive(session);
    }

    @Test
    void fromATownItGoesIntoTheFreeMarketByTheMarketPortal() {
        loadedOn(HENESYS_MARKET, new Point(190, 100), CitizenSession.Activity.TOWN);
        watch(HENESYS_MARKET);
        when(gateway.portalPosition(agent, 5)).thenReturn(new Point(200, 100));
        when(gateway.enterPortal(agent, 5)).thenAnswer(inv -> { // market01.js: save the town, warp to out00
            standOn(FieldCatalog.FM_ENTRANCE, new Point(-538, 30));
            return true;
        });

        presence.travel(session, CitizenSession.Activity.MARKET, FieldCatalog.FM_ENTRANCE, now());
        runTimers();
        assertFalse(AgentMapWalk.tick(entry, agent, now()));
        runTimers();

        verify(gateway).enterPortal(agent, 5);
        assertEquals(CitizenSession.Activity.MARKET, session.activity);
        verify(market).onArrive(session);
        verify(agent, never()).changeMap(any(MapleMap.class), nullable(Portal.class));
    }

    @Test
    void atTheFreeMarketEntranceItWalksThroughTheRoomsDoor() {
        loadedOn(FieldCatalog.FM_ENTRANCE, new Point(700, 2), CitizenSession.Activity.MARKET);
        watch(FieldCatalog.FM_ENTRANCE);
        when(gateway.portalPosition(agent, 5)).thenReturn(new Point(827, 2));

        presence.travel(session, CitizenSession.Activity.MARKET, FM_ROOM_3, now());
        runTimers();
        assertTrue(AgentMapWalk.tick(entry, agent, now()));

        verify(gateway).navigate(entry, new Point(827, 2), true);
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void aWalkWithNoWayOutWaitsForNobodyToWatchAndNeverVanishesInFrontOfThem() {
        config.PORTAL_TRAVEL_HOP_TIMEOUT_MS = 1;
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, ELLINIA, now());
        runTimers();
        assertFalse(AgentMapWalk.tick(entry, agent, now() + 1_000L), "it stood on Henesys too long");
        runTimers(); // it walks out by the nearest portal instead
        assertTrue(AgentMapWalk.walking(CITIZEN));
        assertFalse(AgentMapWalk.tick(entry, agent, now() + 2_000L), "that walk stalls too");

        try (LogLines lines = new LogLines(CitizenTravel.class)) {
            runTimers();
            assertEquals(1, lines.startingWith("citizen QuietWalker on map 100000000 waits for nobody to watch").size());
        }
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(eq(entry), nullable(Point.class), eq(true))); // a stroll
        runTimers();
        runTimers();
        verify(agent, never()).changeMap(any(MapleMap.class), nullable(Portal.class));

        unwatch(HENESYS);
        runTimers();
        runTimers();
        verify(agent).changeMap(maps.get(ELLINIA), spawns.get(ELLINIA));
        assertEquals(CitizenSession.Activity.TRAIN, session.activity);
    }

    @Test
    void aSupplyTripOfItsOwnTakesOverAndTheMoveWaitsForIt() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        runTimers();
        CitizenSession.Move move = session.latestMove;

        AgentRuntimeRegistry.registerEntry(entry);
        registered.add(entry);
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start("supply:HP_POTION:964:1",
                "maintenance:resupply:supply:HP_POTION:964:1", AgentResourceCategory.HP_POTION, 100000102, 1012003,
                HENESYS, AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
        runTimers();

        assertNull(session.trip);
        assertFalse(AgentMapWalk.walking(CITIZEN));
        assertSame(move, session.waitingMove.get());
    }

    @Test
    void aNewerMoveReplacesTheWalk() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, ELLINIA, now());
        runTimers();
        CitizenTravel.Trip first = session.trip;

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        assertFalse(AgentMapWalk.walking(CITIZEN), "the old walk stopped");
        runTimers();

        assertNotNull(session.trip);
        assertFalse(first == session.trip);
        assertEquals(FIELD, session.trip.move.mapId());
        assertTrue(AgentMapWalk.tick(entry, agent, now()));
        verify(gateway).travelTo(eq(entry), eq(agent), eq(FIELD), anyLong());
    }

    @Test
    void loggingOffEndsTheWalk() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        runTimers();
        assertTrue(AgentMapWalk.walking(CITIZEN));

        presence.logout(session, "session over");

        assertNull(session.trip);
        assertFalse(AgentMapWalk.walking(CITIZEN));
        assertFalse(AgentMapWalk.tick(entry, agent, now()));
    }

    @Test
    void aDueReplanWaitsWhileItWalks() {
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());
        CitizenSession.Move move = session.latestMove;
        session.lastPlanAtMs = 0L;
        session.endsAtMs = Long.MAX_VALUE;

        presence.sweep();

        assertSame(move, session.latestMove, "no re-plan, AFK or wander while it is on its way");
        assertEquals(0L, session.lastPlanAtMs, "a re-plan or an AFK roll would have counted as its plan");
        assertEquals(0L, session.afkUntilMs);
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(any(), any(), eq(false)), never());
    }

    @Test
    void theKillSwitchKeepsTheDelayedMoveEvenWhenSomeoneCanSeeIt() {
        config.PORTAL_TRAVEL_ENABLED = false;
        loadedOn(HENESYS, new Point(5000, 455), CitizenSession.Activity.TOWN);
        watch(HENESYS);
        watch(FIELD);

        presence.travel(session, CitizenSession.Activity.TRAIN, FIELD, now());

        assertNull(session.trip);
        runTimers();
        verify(agent).changeMap(maps.get(FIELD), spawns.get(FIELD));
        assertFalse(AgentMapWalk.walking(CITIZEN));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void loadedOn(int mapId, Point position, CitizenSession.Activity activity) {
        presence = new PresenceScheduler(population, new Random(5L));
        Citizen citizen = new Citizen(CITIZEN, "QuietWalker", "evening", 0, 0, "victoria", "hunter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(CITIZEN, citizen);
        session = presence.login(citizen, now(), true);
        agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("QuietWalker");
        when(agent.getClient()).thenReturn(client);
        when(agent.isAlive()).thenReturn(true);
        doAnswer(inv -> {
            MapleMap to = inv.getArgument(0);
            Portal at = inv.getArgument(1);
            standOn(to.getId(), at == null ? new Point() : at.getPosition());
            return null;
        }).when(agent).changeMap(any(MapleMap.class), nullable(Portal.class));
        standOn(mapId, position);
        entry = new AgentRuntimeEntry(agent, null, null);
        session.entry = entry;
        session.virtual = false;
        session.mapId = mapId;
        session.activity = activity;
    }

    private void standOn(int mapId, Point position) {
        MapleMap map = maps.get(mapId);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getPosition()).thenReturn(position);
    }

    private MapleMap map(int mapId, Portal... portals) {
        MapleMap map = mock(MapleMap.class);
        Portal spawn = portal(0, "sp", 0, 999_999_999, null, 0, 0);
        List<Portal> all = new ArrayList<>(List.of(spawn));
        all.addAll(List.of(portals));
        when(map.getId()).thenReturn(mapId);
        when(map.getPortals()).thenReturn(all);
        when(map.getPortal(0)).thenReturn(spawn);
        for (Portal portal : all) {
            String name = portal.getName();
            when(map.getPortal(name)).thenReturn(portal);
        }
        maps.put(mapId, map);
        spawns.put(mapId, spawn);
        return map;
    }

    private static Portal portal(int id, String name, int type, int targetMapId, String script, int x, int y) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn(name);
        when(portal.getType()).thenReturn(type);
        when(portal.getTargetMapId()).thenReturn(targetMapId);
        when(portal.getScriptName()).thenReturn(script);
        when(portal.getPosition()).thenReturn(new Point(x, y));
        when(portal.getPortalStatus()).thenReturn(true);
        return portal;
    }

    /** The portal, naming the portal on its target map it puts a player at. */
    private static Portal named(Portal portal, String target) {
        when(portal.getTarget()).thenReturn(target);
        return portal;
    }

    private Portal portalOf(int mapId, String name) {
        return maps.get(mapId).getPortal(name);
    }

    private void watch(int mapId) {
        when(maps.get(mapId).isObservedByPlayer()).thenReturn(true);
    }

    private void unwatch(int mapId) {
        when(maps.get(mapId).isObservedByPlayer()).thenReturn(false);
    }

    /** Runs what the timer holds now, once (a step that checks again queues its next check). */
    private void runTimers() {
        List<Runnable> due = new ArrayList<>(queued);
        queued.clear();
        due.forEach(Runnable::run);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
