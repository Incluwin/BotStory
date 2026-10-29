package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.population.CitizenSession.Activity;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Luxury spending (docs/ECONOMY_PLAN.md E4b): a citizen well off for its level goes to its region's hair salon with the
 * luxury lever's chance, once a day; it walks to the stylist, takes a moment, pays and has its hair done, then plans on.
 * A hired hand, a persona, a region with no salon or a short purse never goes, and logged out or moved on the way it
 * pays nothing.
 */
class CitizenSalonsTest {
    private static final int ID = 900;
    private static final int NATALIE = 1012103;
    private static final int KERNING = 1052100;
    private static final int HENESYS_SALON = 100000104;
    private static final Point CHAIR = new Point(200, 150);
    private static final long DAY = 86_400_000L;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final long[] now = {10 * DAY + 1_000L};
    private final int[] restyles = {0};
    private Citizen citizen;
    private CitizenSession session;
    private Character agent;
    private MapleMap map;
    private CitizenSalons salons;
    private MockedStatic<AgentMovementCommandRuntime> movement;

    @BeforeEach
    void wire() {
        when(population.store()).thenReturn(store);
        when(store.personaCharacterIds()).thenReturn(Set.of());
        citizen = citizen("victoria", Set.of());
        when(population.citizen(ID)).thenAnswer(inv -> citizen);
        session = new CitizenSession(citizen, now[0], now[0] + 3_600_000L, false);
        agent = mock(Character.class);
        when(agent.getName()).thenReturn("Lumi");
        when(agent.getLevel()).thenReturn(50);
        when(agent.getMeso()).thenReturn(3_000_000); // 1m + 3 x expectedMeso(50) = 3.12m
        when(agent.getPosition()).thenReturn(new Point(700, 150));
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.activity = Activity.TOWN;
        when(presence.session(ID)).thenAnswer(inv -> session);
        NPC natalie = mock(NPC.class);
        when(natalie.getPosition()).thenReturn(CHAIR);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(HENESYS_SALON);
        when(map.getNPCById(NATALIE)).thenReturn(natalie);
        salons = new CitizenSalons(population, presence);
        salons.luxuryRate = () -> 1.0d;
        salons.luxuryOver = () -> 3.0d;
        salons.clock = () -> now[0];
        salons.later = (task, delayMs) -> queue.add(task);
        salons.restyle = (entry, npcId) -> {
            restyles[0]++;
            return CompletableFuture.completedFuture(30140);
        };
        movement = mockStatic(AgentMovementCommandRuntime.class);
    }

    @AfterEach
    void close() {
        movement.close();
    }

    @Test
    void aWellOffCitizenGoesOnceADayWithTheLeversChance() {
        when(agent.getMeso()).thenReturn(3_200_000);
        int npc = salons.planned(session, now[0]);
        assertTrue(npc == NATALIE || npc == KERNING, "a Victoria citizen's salon is Henesys or Kerning City");
        assertEquals(npc, salons.salonFor(session).npcId(), "always its usual one");
        assertEquals(0, salons.planned(session, now[0] + 60_000L), "once a day");
        assertEquals(npc, salons.planned(session, now[0] + DAY), "the next day it may go again");

        salons.luxuryRate = () -> 0.0d;
        assertEquals(0, salons.planned(session, now[0] + 2 * DAY), "the lever at nothing: no visits");
    }

    @Test
    void onlyAWellOffLoadedCitizenFromARegionWithASalonMayGo() {
        assertNull(salons.salonFor(session), "3m is under 1m + 3 x what a level 50 should carry");
        when(agent.getMeso()).thenReturn(3_200_000);
        assertNotNull(salons.salonFor(session));

        citizen = citizen("singapore", Set.of());
        assertNull(salons.salonFor(session), "no salon near Singapore");
        citizen = citizen("victoria", Set.of(Citizen.FLAG_HIRED));
        assertNull(salons.salonFor(session), "a player's hired hand");
        citizen = citizen("victoria", Set.of());
        session.virtual = true;
        assertNull(salons.salonFor(session), "not loaded");
        session.virtual = false;

        CitizenSalons persona = new CitizenSalons(population, presence);
        persona.luxuryOver = () -> 3.0d;
        when(store.personaCharacterIds()).thenReturn(Set.of(ID));
        assertNull(persona.salonFor(session), "a persona's look is drawn elsewhere");
    }

    @Test
    void itWalksToTheStylistTakesAMomentPaysAndPlansOn() {
        session.salonNpc = NATALIE;
        assertTrue(salons.begin(session, map));
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(session.entry, CHAIR, false));

        queue.poll().run(); // still walking
        assertEquals(0, restyles[0]);
        when(agent.getPosition()).thenReturn(new Point(220, 150));
        queue.poll().run(); // there: it chooses a moment
        assertEquals(0, restyles[0]);
        now[0] += CitizenSalons.CHOOSE_MIN_MS + 6_000L;
        queue.poll().run(); // chosen: pays and has it done
        assertEquals(1, restyles[0]);
        queue.poll().run(); // done
        assertEquals(0, session.salonNpc);
        verify(presence).replan(session, now[0]);
    }

    @Test
    void movedOnOrLoggedOutOnTheWayItPaysNothing() {
        session.salonNpc = NATALIE;
        assertTrue(salons.begin(session, map));
        session.activity = Activity.TRAIN; // something else took it
        queue.poll().run();
        assertTrue(queue.isEmpty());
        assertEquals(0, restyles[0]);

        session.activity = Activity.TOWN;
        assertTrue(salons.begin(session, map));
        when(presence.session(ID)).thenReturn(null); // logged out
        queue.poll().run();
        assertEquals(0, restyles[0]);
        verify(presence, never()).replan(any(), anyLong());
    }

    @Test
    void onAnotherMapItHasNoSalonVisit() {
        session.salonNpc = NATALIE;
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(100000000);
        assertFalse(salons.begin(session, town), "redirected to Henesys itself: a stroll");
        assertEquals(0, session.salonNpc);
    }

    @Test
    void aNewLookIsAVipStyleInItsOwnColourOrItsOwnStyleInAnotherColour() {
        salons.styles = npcId -> new int[][]{{30060, 30140, 30200}, {31150}};
        salons.hairExists = hair -> hair != 30207 && hair != 30066; // a colour the game lacks
        for (int seed = 0; seed < 50; seed++) {
            int hair = salons.newHair(NATALIE, 30066, 0, new Random(seed)); // style 30060, colour 6 (30066 itself missing)
            assertTrue(hair == 30146 || hair == 30206 || hair / 10 == 3006 && hair != 30066, "got " + hair);
        }
        salons.styles = npcId -> new int[][]{{}, {}};
        salons.hairExists = hair -> hair == 31150;
        assertEquals(0, salons.newHair(NATALIE, 31150, 1, new Random(1)), "nothing new to offer: no visit is paid");
    }

    private static Citizen citizen(String region, Set<String> flags) {
        return new Citizen(ID, "Lumi", "evening", 0, 0, region, "bowman-hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
