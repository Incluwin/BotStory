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
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.CitizenSession.Activity;
import server.living.resident.ResidentSpots;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * population.MAP_VISIBLE_MAX through the presence scheduler (docs/SCALE_PLATFORM_PLAN.md section 1): a move to a full
 * map goes elsewhere of the same kind, a party-quest move waits while one citizen there moves on, a login to a full map
 * lands elsewhere or virtual, and a map over the cap sends its excess on. Nothing here ever changes map onto a full map
 * or vanishes; maps, timers and movement primitives are stand-ins and the timer is played by hand.
 */
class PresenceSchedulerVisibleCapTest {
    private static final int HENESYS = 100_000_000;
    private static final int ELLINIA = 101_000_000;
    private static final int PERION = 102_000_000;
    private static final int KERNING = 103_000_000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<Integer, Character> agents = new HashMap<>();
    private final TimerManager timers = mock(TimerManager.class);
    private final List<Runnable> queued = new ArrayList<>();
    private MockedStatic<TimerManager> timerManager;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<ResidentSpots> spots;
    private PresenceScheduler presence;

    @BeforeEach
    void wire() {
        config.MAP_VISIBLE_MAX = 2;
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.fields()).thenReturn(fields);
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{30, 0});
        when(population.store()).thenReturn(store); // a TRAIN re-pick finds no field (fields stand-in): a town then
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        when(population.map(anyInt(), anyInt())).thenAnswer(inv -> maps.get((int) inv.getArgument(1)));
        when(fields.regionOf(any())).thenReturn(new PopulationContent.Region("victoria", 1, 200,
                List.of(HENESYS, ELLINIA, PERION, KERNING), List.of(), List.of(), 0, 1));
        timerManager = mockStatic(TimerManager.class);
        timerManager.when(TimerManager::getInstance).thenReturn(timers);
        doAnswer(inv -> {
            queued.add(inv.getArgument(1));
            return null;
        }).when(timers).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), any(Runnable.class), anyLong());
        movement = mockStatic(AgentMovementCommandRuntime.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
        spots = mockStatic(ResidentSpots.class);
        for (int id : List.of(HENESYS, ELLINIA, PERION, KERNING)) {
            map(id);
        }
        presence = new PresenceScheduler(population, new Random(7L));
    }

    @AfterEach
    void unwire() {
        spots.close();
        townLife.close();
        movement.close();
        timerManager.close();
    }

    @Test
    void anEverydayMoveToAFullTownGoesToAnotherTownInstead() {
        loadedOn(1, HENESYS, Activity.TOWN);
        loadedOn(2, HENESYS, Activity.TOWN);
        CitizenSession mover = loadedOn(3, ELLINIA, Activity.TOWN);

        presence.travel(mover, Activity.TOWN, HENESYS, now());
        runTimers();

        assertNotEquals(HENESYS, agents.get(3).getMapId()); // another town of its region (its own one counts too)
        verify(agents.get(3), never()).changeMap(eq(maps.get(HENESYS)), nullable(Portal.class));
        assertEquals(1L, presence.visibleCap().stats().redirects());
    }

    @Test
    void aPartyQuestMoveToAFullLobbyWaitsWhileOneCitizenThereMovesOn() {
        CitizenSession older = loadedOn(1, KERNING, Activity.TOWN);
        loadedOn(2, KERNING, Activity.TOWN);
        older.lastPlanAtMs = now() - 600_000L; // the one longest on its plan moves on
        CitizenSession caller = loadedOn(3, HENESYS, Activity.TRAIN);

        presence.travel(caller, Activity.PQ, KERNING, now());

        assertEquals(HENESYS, agents.get(3).getMapId()); // it has not moved
        assertNotNull(caller.capRetry);
        runTimers(); // the older Kerning citizen moves on, then the caller asks again and is admitted
        assertNotEquals(KERNING, agents.get(1).getMapId());
        assertEquals(KERNING, agents.get(2).getMapId());
        runTimers(); // the caller's move lands
        assertEquals(KERNING, agents.get(3).getMapId());
        assertEquals(Activity.PQ, caller.activity);
        assertEquals(1L, presence.visibleCap().stats().nudges());
    }

    @Test
    void aMoveWithNowhereFreeStaysWhereItIsAndIsDroppedAfterItsTries() {
        config.MAP_VISIBLE_RETRY_LIMIT = 2;
        for (int id = 1, town = 0; id <= 8; id++, town = (town + 1) % 4) { // two a town, all in party quests
            loadedOn(id, List.of(HENESYS, ELLINIA, PERION, KERNING).get(town), Activity.PQ);
        }
        CitizenSession mover = loadedOn(9, 999_999_000, Activity.TRAIN);
        map(999_999_000);
        agentStandsOn(9, 999_999_000);

        presence.travel(mover, Activity.TOWN, HENESYS, now());
        runTimers();
        runTimers();
        runTimers();

        assertEquals(999_999_000, agents.get(9).getMapId());
        assertTrue(queued.isEmpty());
        assertNull(mover.capRetry);
        assertEquals(2L, presence.visibleCap().stats().deferrals());
    }

    @Test
    void aNewerMoveReplacesOneWaitingForRoom() {
        loadedOn(1, KERNING, Activity.TRAIN);
        loadedOn(2, KERNING, Activity.TRAIN);
        CitizenSession caller = loadedOn(3, HENESYS, Activity.TOWN);

        presence.travel(caller, Activity.PQ, KERNING, now());
        presence.travel(caller, Activity.TOWN, PERION, now());
        runTimers();
        runTimers();

        assertEquals(PERION, agents.get(3).getMapId());
        assertEquals(Activity.TOWN, caller.activity);
    }

    @Test
    void aLoginToAFullMapLandsElsewhereOrVirtualWhenEveryMapIsFull() {
        loadedOn(1, HENESYS, Activity.TOWN);
        loadedOn(2, HENESYS, Activity.TOWN);
        CitizenSession login = session(10);

        PresenceScheduler.Placement placed = presence.loginPlacement(login, Activity.TOWN, maps.get(HENESYS), now());

        assertNotNull(placed);
        assertNotEquals(HENESYS, placed.map().getId());
        assertTrue(presence.visibleCap().holds(login, placed.map(), now()));
        for (int id = 3, town = 1; town < 4; town++) {
            loadedOn(id++, List.of(HENESYS, ELLINIA, PERION, KERNING).get(town), Activity.TOWN);
            loadedOn(id++, List.of(HENESYS, ELLINIA, PERION, KERNING).get(town), Activity.TOWN);
        }
        presence.visibleCap().release(login);
        assertNull(presence.loginPlacement(session(11), Activity.TOWN, maps.get(HENESYS), now()));
        assertEquals(1L, presence.visibleCap().stats().virtualLogins());
    }

    @Test
    void aMapOverTheCapSendsItsExcessOnAFewASweep() {
        for (int id = 1; id <= 5; id++) {
            loadedOn(id, HENESYS, Activity.TOWN).lastPlanAtMs = now() - id * 1_000L;
        }
        config.MAP_VISIBLE_TRIM_PER_SWEEP = 2;

        presence.trimCrowdedMaps(now());
        runTimers();

        long stillThere = agents.values().stream().filter(agent -> agent.getMapId() == HENESYS).count();
        assertEquals(3L, stillThere);
        assertNotEquals(HENESYS, agents.get(5).getMapId()); // longest-planned first
        assertNotEquals(HENESYS, agents.get(4).getMapId());
        presence.trimCrowdedMaps(now());
        runTimers();
        assertEquals(2L, agents.values().stream().filter(agent -> agent.getMapId() == HENESYS).count());
    }

    @Test
    void theKillSwitchLetsAMoveIntoAFullMap() {
        config.MAP_VISIBLE_CAP_ENABLED = false;
        loadedOn(1, HENESYS, Activity.TOWN);
        loadedOn(2, HENESYS, Activity.TOWN);
        CitizenSession mover = loadedOn(3, ELLINIA, Activity.TOWN);

        presence.travel(mover, Activity.TOWN, HENESYS, now());
        runTimers();

        assertEquals(HENESYS, agents.get(3).getMapId());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private CitizenSession loadedOn(int id, int mapId, Activity activity) {
        Citizen citizen = citizen(id);
        registry.put(id, citizen);
        CitizenSession session = presence.login(citizen, now(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(citizen.name());
        when(agent.getClient()).thenReturn(client);
        when(agent.isAlive()).thenReturn(true);
        agents.put(id, agent);
        doAnswer(inv -> {
            MapleMap to = inv.getArgument(0);
            agentStandsOn(id, to.getId());
            return null;
        }).when(agent).changeMap(any(MapleMap.class), nullable(Portal.class));
        agentStandsOn(id, mapId);
        session.entry = new AgentRuntimeEntry(agent, null, null);
        session.virtual = false;
        session.mapId = mapId;
        session.activity = activity;
        return session;
    }

    private void agentStandsOn(int id, int mapId) {
        Character agent = agents.get(id);
        when(agent.getMap()).thenReturn(maps.get(mapId));
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getPosition()).thenReturn(new Point());
    }

    private CitizenSession session(int id) {
        CitizenSession session = new CitizenSession(citizen(id), now(), now() + 3_600_000L, false);
        session.channel = 1;
        return session;
    }

    private static Citizen citizen(int id) {
        return new Citizen(id, "Citizen" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private void map(int mapId) {
        MapleMap map = mock(MapleMap.class);
        Portal spawn = mock(Portal.class);
        when(spawn.getPosition()).thenReturn(new Point());
        when(map.getId()).thenReturn(mapId);
        when(map.getPortal(0)).thenReturn(spawn);
        maps.put(mapId, map);
    }

    private void runTimers() {
        List<Runnable> due = new ArrayList<>(queued);
        queued.clear();
        due.forEach(Runnable::run);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
