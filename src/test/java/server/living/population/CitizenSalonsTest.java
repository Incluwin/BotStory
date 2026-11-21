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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Luxury spending (docs/ECONOMY_PLAN.md E4b, section 9.6): a citizen well off for its level goes to a hair salon, a
 * plastic surgeon or a skin-care shop with the luxury lever's chance, once a day for each, and a restart does not roll
 * the same day again; it walks to the stylist, takes a moment, pays and gets its new look, then plans on. A region with
 * no stylist for a look sends it to the next town along the way; a hired hand, a persona or a short purse never goes,
 * and logged out or moved on the way it pays nothing.
 */
class CitizenSalonsTest {
    private static final int ID = 900;
    private static final int NATALIE = 1012103;
    private static final int KERNING = 1052100;
    private static final int HENESYS_SALON = 100000104;
    private static final int HENESYS_FACE = 1052004;
    private static final int HENESYS_SKIN = 1012105;
    private static final int SINGAPORE_FACE = 9270024;
    private static final int NLC_HAIR = 9201064;
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
        salons.io = Runnable::run;
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
    void aWellOffCitizenGoesOnceADayForEachLookWithTheLeversChance() {
        when(agent.getMeso()).thenReturn(3_200_000);
        Set<Integer> went = new HashSet<>();
        for (int plan = 0; plan < 3; plan++) {
            int npc = salons.planned(session, now[0] + plan * 60_000L);
            assertTrue(npc != 0, "plan " + plan + ": a look not rolled yet today");
            went.add(npc);
        }
        int hair = salons.salonFor(session).npcId();
        assertTrue(hair == NATALIE || hair == KERNING, "a Victoria citizen's salon is Henesys or Kerning City");
        assertEquals(Set.of(hair, HENESYS_FACE, HENESYS_SKIN), went, "its usual hair salon, surgeon and skin-care shop");
        assertEquals(0, salons.planned(session, now[0] + 5 * 60_000L), "each look once a day");
        assertTrue(salons.planned(session, now[0] + DAY) != 0, "the next day it may go again");

        salons.luxuryRate = () -> 0.0d;
        assertEquals(0, salons.planned(session, now[0] + 2 * DAY), "the lever at nothing: no visits");
    }

    @Test
    void aRestartDoesNotRollTheSameDayAgain() {
        when(agent.getMeso()).thenReturn(3_200_000);
        long today = now[0] / DAY;
        when(store.luxuryRolls()).thenReturn(Map.of(ID, Map.of("HAIR", today, "FACE", today, "SKIN", today - 1)));
        int npc = salons.planned(session, now[0]);
        assertEquals(HENESYS_SKIN, npc, "hair and face were rolled today before the restart; skin yesterday");
        verify(store).saveLuxuryRoll(ID, "SKIN", today);
        verify(store, never()).saveLuxuryRoll(eq(ID), eq("HAIR"), anyLong());
        assertEquals(0, salons.planned(session, now[0] + 60_000L));
        verify(store, times(1)).luxuryRolls(); // read once, at the first roll after the start
        verify(store, times(1)).saveLuxuryRoll(anyInt(), anyString(), anyLong());
    }

    @Test
    void aRegionWithNoStylistForALookGoesToTheNextTownAlongTheWay() {
        when(agent.getMeso()).thenReturn(3_200_000);
        citizen = citizen("singapore", Set.of());
        assertEquals(SINGAPORE_FACE, salons.salonFor(session, CitizenSalons.Look.FACE).npcId(), "its own surgeon");
        int hair = salons.salonFor(session, CitizenSalons.Look.HAIR).npcId();
        assertTrue(hair == NATALIE || hair == KERNING, "no hair salon in Singapore: the plane to Kerning City");
        citizen = citizen("malaysia", Set.of());
        assertEquals(SINGAPORE_FACE, salons.salonFor(session, CitizenSalons.Look.FACE).npcId(), "Malaysia: Singapore's");
        citizen = citizen("crimsonwood", Set.of());
        assertEquals(NLC_HAIR, salons.salonFor(session).npcId(), "Crimsonwood: through the Phantom Forest");
        citizen = citizen("temple-of-time", Set.of());
        assertEquals("Orbis", salons.salonFor(session, CitizenSalons.Look.SKIN).town(), "the dragon to Leafre, the cabin on");
        citizen = citizen("atlantis", Set.of());
        assertNull(salons.salonFor(session), "a region with no way to a stylist");
        for (String region : CitizenSalons.NEXT.keySet()) {
            citizen = citizen(region, Set.of());
            for (CitizenSalons.Look look : CitizenSalons.Look.values()) {
                assertNotNull(salons.salonFor(session, look), region + " reaches a " + look);
            }
        }
    }

    @Test
    void onlyAWellOffLoadedCitizenMayGo() {
        assertNull(salons.salonFor(session), "3m is under 1m + 3 x what a level 50 should carry");
        when(agent.getMeso()).thenReturn(3_200_000);
        assertNotNull(salons.salonFor(session));

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
        salons.styles = (npcId, look) -> new int[][]{{30060, 30140, 30200}, {31150}};
        salons.lookExists = hair -> hair != 30207 && hair != 30066; // a colour the game lacks
        for (int seed = 0; seed < 50; seed++) {
            int hair = salons.newHair(NATALIE, 30066, 0, new Random(seed)); // style 30060, colour 6 (30066 itself missing)
            assertTrue(hair == 30146 || hair == 30206 || hair / 10 == 3006 && hair != 30066, "got " + hair);
        }
        salons.styles = (npcId, look) -> new int[][]{{}, {}};
        salons.lookExists = hair -> hair == 31150;
        assertEquals(0, salons.newHair(NATALIE, 31150, 1, new Random(1)), "nothing new to offer: no visit is paid");
    }

    @Test
    void aNewFaceIsAVipFaceInItsOwnEyeColour() {
        salons.styles = (npcId, look) -> new int[][]{{20000, 20001, 20003, 20005}, {21000}};
        salons.lookExists = face -> face != 20103; // style 3 has no colour 1: the script falls back to its own colour
        Set<Integer> seen = new HashSet<>();
        Random draw = new Random(7);
        for (int pick = 0; pick < 80; pick++) {
            seen.add(salons.newFace(HENESYS_FACE, 20105, 0, draw)); // style 5, eye colour 1
        }
        assertEquals(Set.of(20100, 20101, 20003), seen, "its own face is no new look");
        salons.styles = (npcId, look) -> new int[][]{{20000}, {}};
        assertEquals(0, salons.newFace(HENESYS_FACE, 20000, 0, new Random(1)), "nothing new to offer");
    }

    @Test
    void aNewSkinIsAnotherToneFromTheShopsList() {
        salons.styles = (npcId, look) -> new int[][]{{0, 1, 2, 3, 4, 77}, {0, 1, 2, 3, 4, 77}};
        Set<Integer> seen = new HashSet<>();
        Random draw = new Random(7);
        for (int pick = 0; pick < 80; pick++) {
            seen.add(salons.newSkin(HENESYS_SKIN, 2, draw));
        }
        assertEquals(Set.of(0, 1, 3, 4), seen, "a light skin is 0; 77 is no skin the game has");
        salons.styles = (npcId, look) -> new int[][]{{2}, {2}};
        assertEquals(-1, salons.newSkin(HENESYS_SKIN, 2, new Random(1)), "nothing new to offer");
    }

    @Test
    void everyStylistsScriptHasItsList() {
        for (CitizenSalons.Salon salon : CitizenSalons.SALONS) {
            int[][] lists = CitizenSalons.scriptStyles(salon.npcId(), salon.look());
            assertTrue(lists[0].length > 0 && lists[1].length > 0, salon.town() + " " + salon.look() + " " + salon.npcId());
        }
        assertEquals(24, CitizenSalons.SALONS.size(), "8 hair salons, 9 surgeons, 7 skin-care shops");
    }

    private static Citizen citizen(String region, Set<String> flags) {
        return new Citizen(ID, "Lumi", "evening", 0, 0, region, "bowman-hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
