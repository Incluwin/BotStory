package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan C8, after the clear: the companions walk on from the Council Hall, wait in the Secret Chamber until
 * someone has started the Armory's minute, and there break only their own share of the 30 boxes (the humans' shares
 * come first along the shelves), then take what the loot rules let them.
 */
class AgentCwkpqArmoryPlayTest {
    /** Map.wz 610030800: the 30 boxes, object id = their life index, on five shelves. */
    private static final int[][] BOXES = {
            {18, -832, -386}, {12, -644, -386}, {28, -479, -387}, {1, -297, -386},
            {27, 209, -386}, {2, 401, -386}, {14, 565, -387}, {16, 721, -386},
            {0, -831, -206}, {17, -644, -206}, {29, -480, -207}, {11, -297, -206},
            {13, 215, -206}, {15, 402, -206}, {3, 569, -207}, {26, 724, -206},
            {19, -500, -26}, {24, -311, -26}, {25, 205, -26}, {20, 407, -26},
            {22, -504, 153}, {7, -314, 153}, {8, 210, 154}, {6, 411, 153},
            {5, -726, 333}, {10, -503, 333}, {23, -313, 334}, {9, 207, 333}, {21, 411, 333}, {4, 651, 334}};

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, Point> positions = new HashMap<>();
    private final List<String> party = new ArrayList<>();
    private final List<Reactor> boxes = new ArrayList<>();
    private final List<Character> onTheMap = new ArrayList<>();
    private final AgentCwkpqRun run = new AgentCwkpqRun(Map.of());
    private MapItem loot;
    private AgentCwkpqPorts ports;

    @BeforeEach
    void theArmory() {
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
                return false;
            }

            @Override
            public boolean pickUp(Character member, int itemId) {
                return false;
            }

            @Override
            public MapItem nearestLoot(Character member, int seekPx) {
                return loot;
            }
        };
        for (int[] box : BOXES) {
            box(box[0], new Point(box[1], box[2]));
        }
        portal("next00", 3, new Point(552, 134));
        when(map.getAllPlayers()).thenAnswer(inv -> List.copyOf(onTheMap));
        when(gateway.grounded(any())).thenReturn(true);
        when(gateway.groundPoint(any(), any())).thenAnswer(inv -> new Point((Point) inv.getArgument(1)));
        when(gateway.reactors(any())).thenAnswer(inv -> List.copyOf(boxes));
        when(gateway.hitReactor(any(), anyInt())).thenReturn(true);
    }

    @Test
    void theRunPlaysOnOnlyFromTheCouncilHallToTheArmory() {
        AgentCwkpqRun forCompanions = AgentCwkpqRun.forCompanions();

        assertTrue(forCompanions.playsAfterClear(610_030_600));
        assertTrue(forCompanions.playsAfterClear(610_030_700));
        assertTrue(forCompanions.playsAfterClear(610_030_800));
        assertFalse(forCompanions.playsAfterClear(610_030_500));
        assertFalse(forCompanions.playsAfterClear(610_030_020), "the recruit map is the boss track's");
        assertFalse(run.playsAfterClear(610_030_800), "a run built without the walk on plays nothing after");
    }

    @Test
    void fromTheCouncilHallTheyTakeTheWayOnAndInTheChamberWaitForSomeoneToStartTheMinute() {
        portal("next00", 6, new Point(1809, 274));
        Character orrin = member(10, "Orrin", Job.HERO, new Point(1809, 274), 610_030_600);

        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 1_000L);
        verify(partyQuest).enterPortal(orrin, 6);

        portal("next00", 3, new Point(552, 134));
        Character magna = member(11, "Magna", Job.BISHOP, new Point(-1100, 110), 610_030_700);
        when(event.getIntProperty("current_instance")).thenReturn(5);
        AgentCwkpqArmoryPlay.tick(ports, List.of(magna), event, run, 2_000L);
        verify(gateway).navigate(entries.get(11), new Point(552, 134), false);
        verify(partyQuest, never()).enterPortal(eq(magna), anyInt());

        when(event.getIntProperty("current_instance")).thenReturn(6);
        positions.put(11, new Point(552, 134));
        AgentCwkpqArmoryPlay.tick(ports, List.of(magna), event, run, 3_000L);
        verify(partyQuest).enterPortal(magna, 3);
    }

    @Test
    void theHumansShareComesFirstAndACompanionBreaksOnlyItsOwn() {
        Character ganre = human(1, "Ganre");
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-359, 153), 610_030_800);
        Character magna = member(11, "Magna", Job.BISHOP, new Point(0, 336), 610_030_800);

        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin, magna), event, run, 1_000L);

        // three share 30: the human the top three left shelves, Orrin the low left and the low right, Magna the rest
        Set<Integer> humans = owned(ganre.getId());
        assertEquals(Set.of(18, 12, 28, 1, 0, 17, 29, 11, 19, 24), humans);
        assertEquals(Set.of(22, 7, 5, 10, 23, 9, 21, 4, 8, 6), owned(orrin.getId()));
        verify(gateway).hitReactor(orrin, 7);
        verify(gateway).facePosition(orrin, new Point(-314, 153));
        verify(gateway, never()).hitReactor(eq(magna), anyInt());
        verify(gateway).navigate(eq(entries.get(11)), any(), eq(true));
        for (int box : humans) {
            verify(gateway, never()).hitReactor(any(), eq(box));
        }
        assertEquals(List.of("Orrin: the top left boxes are yours, we'll break the rest"), party);
    }

    @Test
    void aBrokenBoxIsLeftForTheNextOfItsShareAndTheLineIsSaidOnce() {
        human(1, "Ganre");
        // two share 30: the human the left half, Orrin the right
        Character orrin = member(10, "Orrin", Job.HERO, new Point(162, 333), 610_030_800);
        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 1_000L);
        verify(gateway).hitReactor(orrin, 9);

        broken(9);
        positions.put(10, new Point(165, 154));
        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 2_000L);

        verify(gateway).hitReactor(orrin, 8);
        assertEquals(List.of("Orrin: the left boxes are yours, we'll break the rest"), party, "said once");
    }

    @Test
    void withItsShareBrokenACompanionWalksToADropItMayTakeAndPicksItUp() {
        human(1, "Ganre");
        Character orrin = member(10, "Orrin", Job.HERO, new Point(0, 336), 610_030_800);
        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 1_000L);
        for (int id : owned(orrin.getId())) {
            broken(id);
        }
        loot = mock(MapItem.class);
        when(loot.getObjectId()).thenReturn(900);
        when(loot.getPosition()).thenReturn(new Point(200, 336));

        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 2_000L);
        verify(gateway).navigate(entries.get(10), new Point(200, 336), true);
        verify(gateway, never()).lootItem(any(), anyInt(), anyInt());

        positions.put(10, new Point(205, 336));
        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin), event, run, 3_000L);
        verify(gateway).lootItem(orrin, 900, AgentCwkpqArmoryPlay.LOOT_REACH_PX);
    }

    @Test
    void withNoHumanTheCompanionsShareEveryBoxAndSayNothing() {
        Character orrin = member(10, "Orrin", Job.HERO, new Point(-359, 153), 610_030_800);
        Character magna = member(11, "Magna", Job.BISHOP, new Point(0, 336), 610_030_800);

        AgentCwkpqArmoryPlay.tick(ports, List.of(orrin, magna), event, run, 1_000L);

        assertEquals(30, owned(orrin.getId()).size() + owned(magna.getId()).size());
        assertEquals(List.of(), party);
    }

    private Set<Integer> owned(int memberId) {
        Set<Integer> owned = new HashSet<>();
        for (int[] box : BOXES) {
            Integer owner = run.boxOwner(box[0]);
            if (owner != null && owner == memberId) {
                owned.add(box[0]);
            }
        }
        return owned;
    }

    private Character human(int id, String name) {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(id);
        when(human.getName()).thenReturn(name);
        onTheMap.add(human);
        return human;
    }

    private Character member(int id, String name, Job job, Point at, int mapId) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(mapId);
        positions.put(id, at);
        when(member.getPosition()).thenAnswer(inv -> positions.get(id));
        entries.put(id, mock(AgentRuntimeEntry.class));
        run.adopt(id, entries.size() - 1);
        onTheMap.add(member);
        return member;
    }

    private void box(int objectId, Point at) {
        Reactor box = mock(Reactor.class);
        when(box.getObjectId()).thenReturn(objectId);
        when(box.getId()).thenReturn(6_102_002 + objectId % 4);
        when(box.getPosition()).thenReturn(at);
        when(box.isAlive()).thenReturn(true);
        when(box.isActive()).thenReturn(true);
        boxes.add(box);
    }

    private void broken(int objectId) {
        for (Reactor box : boxes) {
            if (box.getObjectId() == objectId) {
                when(box.isActive()).thenReturn(false);
            }
        }
    }

    private void portal(String name, int id, Point at) {
        Portal portal = mock(Portal.class);
        when(portal.getId()).thenReturn(id);
        when(portal.getPosition()).thenReturn(at);
        when(map.getPortal(name)).thenReturn(portal);
    }
}
