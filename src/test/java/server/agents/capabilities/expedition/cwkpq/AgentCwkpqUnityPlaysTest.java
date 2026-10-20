package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B6: in the hall each archetype goes to its room while its weapon is still wanted and a holder carries it
 * to its relic's drop spot; in a room the members clear it (or break its eyes or chests), the first by slot asks the
 * master, and everyone leaves once the weapon is held.
 */
class AgentCwkpqUnityPlaysTest {
    private static final int SWORD = 4_001_259;
    private static final int GUN = 4_001_260;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, Point> positions = new HashMap<>();
    private final List<Character> eventPlayers = new ArrayList<>();
    private final List<Reactor> reactors = new ArrayList<>();
    private final List<String> party = new ArrayList<>();
    private final List<String> dropped = new ArrayList<>();
    private final List<String> picked = new ArrayList<>();
    private final AgentCwkpqRun run = new AgentCwkpqRun(Map.of());
    private AgentCwkpqPorts ports;

    @BeforeEach
    void theHall() {
        ports = new AgentCwkpqPorts() {
            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public PartyQuestGateway partyQuest() {
                return partyQuest;
            }

            @Override
            public AgentCwkpqSigilCast.Casting casting() {
                return mock(AgentCwkpqSigilCast.Casting.class);
            }

            @Override
            public void sayInParty(Character member, String line) {
                party.add(member.getName() + ": " + line);
            }

            @Override
            public boolean dropOne(Character member, int itemId) {
                dropped.add(member.getName() + ":" + itemId);
                return true;
            }

            @Override
            public boolean pickUp(Character member, int itemId) {
                picked.add(member.getName() + ":" + itemId);
                return true;
            }

            @Override
            public server.maps.MapItem nearestLoot(Character member, int seekPx) {
                return null;
            }
        };
        when(gateway.grounded(any())).thenReturn(true);
        when(gateway.groundPoint(any(), any())).thenAnswer(inv -> new Point((Point) inv.getArgument(1)));
        when(gateway.reactors(any())).thenAnswer(inv -> List.copyOf(reactors));
        when(gateway.hitReactor(any(), anyInt())).thenReturn(true);
        when(event.getPlayers()).thenReturn(eventPlayers);
        when(map.getDroppedItems()).thenReturn(List.of());
    }

    // ---- the hall ----

    @Test
    void aMemberWhoseWeaponIsWantedTakesItsRoomAndTheRestWaitByTheExit() {
        portal("in01", 1, new Point(-25, 153));
        portal("next00", 6, new Point(1187, 93));
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-25, 153));
        Character magna = member(11, "Magna", Job.FP_ARCHMAGE, new Point(500, 342));
        Character holder = mock(Character.class);
        when(holder.getItemQuantity(4_001_257, false)).thenReturn(1); // a human holds the staff
        eventPlayers.add(holder);

        AgentCwkpqUnityPlays.hall(ports, List.of(orrin, magna), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 1);
        verify(partyQuest, never()).enterPortal(eq(magna), anyInt());
        verify(gateway).navigate(entries.get(11), new Point(1187, 93), false);
    }

    @Test
    void aHolderDropsItsWeaponOnTheSpotWhoseDropLandsInTheRelicsArea() {
        relic(6_109_005, new Rectangle(1093, -665, 200, 211));
        Character orrin = member(10, "Orrin", Job.HERO, new Point(1093, -586));
        when(gateway.itemCount(orrin, SWORD)).thenReturn(1);
        when(gateway.predictDropLanding(eq(orrin), any())).thenReturn(new Point(1093, -586));

        AgentCwkpqUnityPlays.hall(ports, List.of(orrin), event, run, 1_000L);

        verify(gateway).stop(entries.get(10));
        assertEquals(List.of("Orrin:" + SWORD), dropped);
        assertTrue(!run.ready(10, 6_000L), "it waits for the relic to take it");
    }

    @Test
    void aDropThatWouldLandOutsideTheAreaIsNotMade() {
        relic(6_109_005, new Rectangle(1093, -665, 200, 211));
        Character orrin = member(10, "Orrin", Job.HERO, new Point(1093, -586));
        when(gateway.itemCount(orrin, SWORD)).thenReturn(1);
        when(gateway.predictDropLanding(eq(orrin), any())).thenReturn(new Point(1093, -300));

        AgentCwkpqUnityPlays.hall(ports, List.of(orrin), event, run, 1_000L);

        assertEquals(List.of(), dropped);
        verify(gateway).navigate(entries.get(10), new Point(1093, -586), true);
    }

    @Test
    void withAllFiveRelicsFilledEveryoneTakesTheExit() {
        portal("next00", 6, new Point(1187, 93));
        when(event.getIntProperty("glpq5")).thenReturn(5);
        Character orrin = member(10, "Orrin", Job.HERO, new Point(1187, 93));

        AgentCwkpqUnityPlays.hall(ports, List.of(orrin), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 6);
    }

    // ---- the rooms ----

    @Test
    void theWarriorsClearTheirRoomThenTheFirstAsksTheMaster() {
        Character orrin = member(10, "Orrin", Job.HERO, new Point(117, -2259));
        Character dorran = member(11, "Dorran", Job.PALADIN, new Point(0, 156));
        when(map.countMonsters()).thenReturn(3);

        AgentCwkpqUnityPlays.room(ports, Archetype.WARRIOR, List.of(orrin, dorran), event, run, 1_000L);

        verify(gateway).grind(entries.get(10), Set.of());
        verify(gateway).grind(entries.get(11), Set.of());

        when(map.countMonsters()).thenReturn(0);
        when(gateway.npcPosition(orrin, 9_201_107)).thenReturn(new Point(117, -2259));
        int[] swords = {0};
        when(gateway.itemCount(orrin, SWORD)).thenAnswer(inv -> swords[0]);
        when(partyQuest.runNpc(orrin, 9_201_107)).thenAnswer(inv -> {
            swords[0] = 1; // the master's gainItem
            return true;
        });
        AgentCwkpqUnityPlays.room(ports, Archetype.WARRIOR, List.of(orrin, dorran), event, run, 2_000L);

        verify(partyQuest).runNpc(orrin, 9_201_107);
        verify(partyQuest, never()).runNpc(eq(dorran), anyInt());
        assertEquals(List.of("Orrin: got the master sword, taking it to its relic"), party);
    }

    @Test
    void onceTheWeaponIsHeldEveryoneLeavesTheRoomAndTheLedgeTakesTheShortcut() {
        portal("out00", 1, new Point(103, 153));
        portal("down00", 2, new Point(-224, -2246));
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-224, -2246));
        Character dorran = member(11, "Dorran", Job.PALADIN, new Point(103, 153));
        when(gateway.itemCount(orrin, SWORD)).thenReturn(1);
        eventPlayers.add(orrin);
        when(orrin.getItemQuantity(SWORD, false)).thenReturn(1);

        AgentCwkpqUnityPlays.room(ports, Archetype.WARRIOR, List.of(orrin, dorran), event, run, 1_000L);

        verify(partyQuest).enterPortal(orrin, 2);
        verify(partyQuest).enterPortal(dorran, 1);
    }

    @Test
    void aMasterWhoGivesNothingIsAskedThreeTimesThenTheMemberSaysSoAndLeaves() {
        portal("out00", 1, new Point(40, 271));
        Character fenna = member(10, "Fenna", Job.BOWMASTER, new Point(42, -745));
        when(gateway.npcPosition(fenna, 9_201_108)).thenReturn(new Point(42, -745));

        for (long now = 1_000L; now <= 4_000L; now += 1_000L) {
            AgentCwkpqUnityPlays.room(ports, Archetype.BOWMAN, List.of(fenna), event, run, now);
        }

        verify(partyQuest, org.mockito.Mockito.times(3)).runNpc(fenna, 9_201_108);
        assertEquals(List.of("Fenna: the master won't give me the ancestral bow"), party);
        verify(gateway).navigate(entries.get(10), new Point(40, 271), true);
    }

    @Test
    void thievesEachBreakADifferentEyeThenAskTheMaster() {
        reactor(31, 6_108_004, new Point(-359, 120));
        reactor(32, 6_108_004, new Point(450, -110));
        Character fenna = member(10, "Fenna", Job.HERMIT, new Point(-314, 120));
        Character vex = member(11, "Vex", Job.CHIEFBANDIT, new Point(495, -110));

        AgentCwkpqUnityPlays.room(ports, Archetype.THIEF, List.of(fenna, vex), event, run, 1_000L);

        verify(gateway).hitReactor(fenna, 31);
        verify(gateway).hitReactor(vex, 32);

        reactors.clear();
        when(gateway.npcPosition(any(), eq(9_201_110))).thenReturn(new Point(-314, 120));
        AgentCwkpqUnityPlays.room(ports, Archetype.THIEF, List.of(fenna, vex), event, run, 2_000L);
        verify(partyQuest).runNpc(fenna, 9_201_110);
        verify(partyQuest, never()).runNpc(eq(vex), anyInt());
    }

    @Test
    void piratesBreakTheChestsAndTheNearestTakesTheGunWhereItFell() {
        reactor(41, 6_102_001, new Point(-654, -548));
        reactor(42, 6_108_003, new Point(532, -547));
        Character kyra = member(10, "Kyra", Job.CORSAIR, new Point(-609, -548));
        Character brann = member(11, "Brann", Job.BUCCANEER, new Point(577, -547));

        AgentCwkpqUnityPlays.room(ports, Archetype.PIRATE, List.of(kyra, brann), event, run, 1_000L);
        verify(gateway).hitReactor(kyra, 41);
        verify(gateway).hitReactor(brann, 42);

        MapItem gun = mock(MapItem.class);
        when(gun.getItemId()).thenReturn(GUN);
        when(gun.getPosition()).thenReturn(new Point(-654, -548));
        when(map.getDroppedItems()).thenReturn(List.of(gun));
        AgentCwkpqUnityPlays.room(ports, Archetype.PIRATE, List.of(kyra, brann), event, run, 2_000L);

        assertEquals(List.of("Kyra:" + GUN), picked);
        assertEquals(List.of("Kyra: got the forbidden gun, taking it to its relic"), party);
    }

    @Test
    void theDropSpotsLieInTheirRelicsAreas() {
        Map<Integer, Rectangle> areas = Map.of(4_001_259, new Rectangle(1093, -665, 200, 211),
                4_001_258, new Rectangle(1280, -453, 164, 137), 4_001_257, new Rectangle(795, -959, 199, 128),
                4_001_256, new Rectangle(1343, -1027, 191, 167), 4_001_260, new Rectangle(1266, -607, 214, 101));
        AgentCwkpqUnityPlays.DROP_SPOTS.forEach((weapon, spot) -> assertTrue(areas.get(weapon).contains(spot),
                weapon + " at " + spot));
        assertEquals(5, AgentCwkpqUnityPlays.WEAPONS.size());
    }

    private Character member(int id, String name, Job job, Point at) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMap()).thenReturn(map);
        positions.put(id, at);
        when(member.getPosition()).thenAnswer(inv -> positions.get(id));
        entries.put(id, mock(AgentRuntimeEntry.class));
        run.adopt(id, entries.size() - 1);
        return member;
    }

    private Reactor reactor(int objectId, int reactorId, Point at) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getObjectId()).thenReturn(objectId);
        when(reactor.getId()).thenReturn(reactorId);
        when(reactor.getPosition()).thenReturn(at);
        when(reactor.isAlive()).thenReturn(true);
        when(reactor.isActive()).thenReturn(true);
        reactors.add(reactor);
        return reactor;
    }

    private void relic(int reactorId, Rectangle area) {
        Reactor relic = reactor(90 + reactors.size(), reactorId, new Point(area.x, area.y));
        when(relic.getArea()).thenReturn(area);
        when(relic.isActive()).thenReturn(false);
    }

    private void portal(String name, int id, Point at) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getPosition()).thenReturn(at);
        when(map.getPortal(name)).thenReturn(portal);
    }
}
