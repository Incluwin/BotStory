package server.agents.capabilities.expedition.zakum;

import client.Character;
import client.Job;
import client.status.MonsterStatus;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Reactor;
import testutil.LogLines;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Zakum fight (P8 step 10) on the altar 280030000. The test drives the scenario through its ports with a mocked
 * gateway; the arms' body boxes are the real WZ stand frames (Ports.LIVE.body), so the floor tier is 8800007, 8800009
 * and 8800010 at the shipped FLOOR_REACH_PX. Shipped tuning: PRE_SUMMON_OFFSET_PX 150, PRE_SUMMON_SPACING_PX 30,
 * ADD_CLEAR_THRESHOLD 6, the four platform anchors and a 30 s vitals line.
 */
class AgentZakumScenarioTest {
    private static final String ANCHORS_KEY =
            "server.agents.capabilities.expedition.zakum.AgentZakumScenario.PLATFORM_ANCHORS";
    private static final int ALTAR_REACTOR = 2_111_001;
    private static final Point ALTAR = new Point(-10, -204);
    private static final int MIN_X = -570;
    private static final int MAX_X = 660;
    private static final int FAKE_BODY = 8_800_000;
    private static final int SECOND_BODY = 8_800_001;
    private static final int LAST_BODY = 8_800_002;
    private static final Set<Integer> FLOOR_ARMS = Set.of(8_800_007, 8_800_009, 8_800_010);
    private static final Set<Integer> UPPER_ARMS = Set.of(8_800_003, 8_800_004, 8_800_005, 8_800_006, 8_800_008);
    private static final int ADD = 6_400_003;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<Monster> monsters = new ArrayList<>();
    private final Set<Monster> idle = new HashSet<>();
    private AgentZakumScenario fight;
    private String shippedAnchors;

    @BeforeEach
    void setUp() {
        when(map.getFootholds()).thenReturn(floor(MIN_X, MAX_X));
        when(gateway.groundPoint(any(), any())).thenAnswer(invocation -> new Point(invocation.<Point>getArgument(1)));
        Reactor altar = mock(Reactor.class);
        when(altar.getId()).thenReturn(ALTAR_REACTOR);
        when(altar.getPosition()).thenReturn(new Point(ALTAR));
        when(gateway.reactors(any())).thenReturn(List.of(altar));
        fight = new AgentZakumScenario(new AgentZakumScenario.Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap onMap) {
                return onMap == map ? List.copyOf(monsters) : List.of();
            }

            @Override
            public boolean engaged(Monster part) {
                return !idle.contains(part);
            }

            @Override
            public Rectangle body(Monster part) {
                return AgentZakumScenario.Ports.LIVE.body(part);
            }
        });
        shippedAnchors = AgentYamlConfig.config.tuning.get(ANCHORS_KEY);
    }

    @AfterEach
    void restoreTuning() {
        AgentYamlConfig.config.tuning.put(ANCHORS_KEY, shippedAnchors);
    }

    @Test
    void beforeTheSummonMembersLineUpLeftOfTheAltarInsideTheFootholdWalls() {
        List<Character> members = List.of(member(1, 0, Job.HERO, new Point(500, -213)),
                member(2, 1, Job.BISHOP, new Point(500, -213)), member(3, 2, Job.MARKSMAN, new Point(500, -213)));

        fight.tick(members, event, 0L);

        for (int ordinal = 0; ordinal < 3; ordinal++) {
            AgentRuntimeEntry entry = entries.get(ordinal + 1);
            verify(gateway).navigate(entry, new Point(-160 - 30 * ordinal, -204), true);
            verify(gateway).setHorizontalBoundary(entry, 280_030_000, MIN_X, MAX_X);
        }
        verify(gateway, never()).grind(any(), anySet(), anySet());
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void aMemberOnItsSpotWaitsThere() {
        Character member = member(1, 1, Job.HERO, new Point(-178, -204));

        fight.tick(List.of(member), event, 0L);

        verify(gateway).stop(entries.get(1));
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void preSummonSpotsAreSpacedAndStayInsideTheWalls() {
        Set<Integer> xs = new HashSet<>();
        for (int ordinal = 0; ordinal < 14; ordinal++) {
            Point spot = AgentZakumScenario.preSummonSpot(ALTAR, ordinal, 150, 30, MIN_X, MAX_X);
            assertEquals(-160 - 30 * ordinal, spot.x, "ordinal " + ordinal);
            assertEquals(ALTAR.y, spot.y);
            assertTrue(spot.x >= MIN_X && spot.x <= MAX_X);
            xs.add(spot.x);
        }
        assertEquals(14, xs.size(), "fourteen spots fit between x=-160 and the west wall");
        assertEquals(new Point(-160, -204), AgentZakumScenario.preSummonSpot(ALTAR, 14, 150, 30, MIN_X, MAX_X),
                "a line longer than the floor starts over at its first spot");
        assertEquals(new Point(-160, -204), AgentZakumScenario.preSummonSpot(ALTAR, 3, 150, 0, MIN_X, MAX_X));
        assertEquals(new Point(MIN_X, -204), AgentZakumScenario.preSummonSpot(ALTAR, 2, 900, 30, MIN_X, MAX_X),
                "an offset past the wall stops at the wall");
    }

    @Test
    void theBoundaryComesFromTheFootholds() {
        when(map.getFootholds()).thenReturn(floor(-100, 100));
        Character member = member(1, 0, Job.HERO, new Point(0, -204));

        fight.tick(List.of(member), event, 0L);

        verify(gateway).setHorizontalBoundary(entries.get(1), 280_030_000, -100, 100);
        verify(gateway).navigate(entries.get(1), new Point(-100, -204), true);
    }

    @Test
    void meleeTakesTheFloorArmsFirstAndRangedAndMagicTheUpperArms() {
        summon();
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));
        Character marksman = member(2, 1, Job.MARKSMAN, new Point(0, -213));
        Character bishop = member(3, 2, Job.BISHOP, new Point(0, -213));
        when(gateway.rangedWeapon(marksman)).thenReturn(true);

        fight.tick(List.of(hero, marksman, bishop), event, 0L);

        verify(gateway).grind(entries.get(1), FLOOR_ARMS, UPPER_ARMS);
        verify(gateway).grind(entries.get(2), UPPER_ARMS, FLOOR_ARMS);
        verify(gateway).grind(entries.get(3), UPPER_ARMS, FLOOR_ARMS);
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void aCygnusMageCountsAsMagic() {
        summon();
        Character blazeWizard = member(1, 0, Job.BLAZEWIZARD3, new Point(0, -213));

        fight.tick(List.of(blazeWizard), event, 0L);

        verify(gateway).grind(entries.get(1), UPPER_ARMS, FLOOR_ARMS);
    }

    @Test
    void theFakeBodyIsNeverATarget() {
        summon();
        List<Character> members = List.of(member(1, 0, Job.HERO, new Point(0, -213)),
                member(2, 1, Job.BISHOP, new Point(0, -213)));

        fight.tick(members, event, 0L);
        monsters.removeIf(monster -> monster.getId() != FAKE_BODY);
        fight.tick(members, event, 500L);

        assertFalse(grindIds().contains(FAKE_BODY));
        verify(gateway).stop(entries.get(1));
        verify(gateway).stop(entries.get(2));
    }

    @Test
    void aPartNoHumanDrivesIsDropped() {
        summon();
        idle.add(monster(8_800_007));
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);

        verify(gateway).grind(entries.get(1), Set.of(8_800_009, 8_800_010), UPPER_ARMS);
    }

    @Test
    void weaponImmunityStopsOnlyPhysicalMembers() {
        Monster body = part(SECOND_BODY);
        when(body.isBuffed(MonsterStatus.WEAPON_IMMUNITY)).thenReturn(true);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));
        Character marksman = member(2, 1, Job.MARKSMAN, new Point(0, -213));
        Character bishop = member(3, 2, Job.BISHOP, new Point(0, -213));
        when(gateway.rangedWeapon(marksman)).thenReturn(true);

        fight.tick(List.of(hero, marksman, bishop), event, 0L);

        verify(gateway).stop(entries.get(1));
        verify(gateway).stop(entries.get(2));
        verify(gateway).grind(entries.get(3), Set.of(SECOND_BODY), Set.of());
    }

    @Test
    void magicImmunityStopsOnlyMagicMembers() {
        Monster body = part(SECOND_BODY);
        when(body.isBuffed(MonsterStatus.MAGIC_IMMUNITY)).thenReturn(true);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));
        Character bishop = member(2, 1, Job.BISHOP, new Point(0, -213));

        fight.tick(List.of(hero, bishop), event, 0L);

        verify(gateway).grind(entries.get(1), Set.of(SECOND_BODY), Set.of());
        verify(gateway).stop(entries.get(2));
    }

    @Test
    void nothingLeftToHitMeansHold() {
        idle.add(part(LAST_BODY));
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);

        verify(gateway).stop(entries.get(1));
        verify(gateway, never()).grind(any(), anySet(), anySet());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void sixLiveAddsComeFirstAndFiveComeLast() {
        part(LAST_BODY);
        for (int index = 0; index < 5; index++) {
            part(ADD);
        }
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);
        verify(gateway).grind(entries.get(1), Set.of(LAST_BODY), Set.of(ADD));

        part(ADD);
        fight.tick(List.of(hero), event, 500L);
        verify(gateway).grind(entries.get(1), Set.of(ADD), Set.of(LAST_BODY));
    }

    @Test
    void meleeGrindsFromThePlatformNearestTheHighArmsOnceTheLowArmsAreDown() {
        part(FAKE_BODY, true);
        part(8_800_003);
        part(8_800_005);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));
        Character marksman = member(2, 1, Job.MARKSMAN, new Point(0, -213));
        when(gateway.rangedWeapon(marksman)).thenReturn(true);

        fight.tick(List.of(hero, marksman), event, 0L);

        verify(gateway).grindFromAnchor(entries.get(1), new Point(-345, -488), Set.of(8_800_003, 8_800_005),
                Set.of());
        verify(gateway).grind(entries.get(2), Set.of(8_800_003, 8_800_005), Set.of());
    }

    @Test
    void theRightHandArmsTakeTheRightHandPlatform() {
        part(FAKE_BODY, true);
        part(8_800_004);
        part(8_800_006);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);

        verify(gateway).grindFromAnchor(entries.get(1), new Point(446, -530), Set.of(8_800_004, 8_800_006),
                Set.of());
    }

    @Test
    void withNoAnchorsMeleeGrindsTheHighArmsFromWhereItCan() {
        AgentYamlConfig.config.tuning.put(ANCHORS_KEY, "none");
        part(8_800_004);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);

        verify(gateway).grind(entries.get(1), Set.of(8_800_004), Set.of());
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void theNearestAnchorIsTheOneClosestToAnyBox() {
        List<Point> anchors = AgentZakumScenario.parseAnchors("-345,-488|-373,-556|446,-530|473,-391");
        Rectangle leftHigh = new Rectangle(-154, -604, 96, 134);
        Rectangle rightHigh = new Rectangle(85, -613, 91, 153);

        assertEquals(new Point(-345, -488), AgentZakumScenario.nearestAnchor(anchors, List.of(leftHigh)));
        assertEquals(new Point(446, -530), AgentZakumScenario.nearestAnchor(anchors, List.of(rightHigh)));
        assertEquals(new Point(-345, -488), AgentZakumScenario.nearestAnchor(anchors, List.of(rightHigh, leftHigh)),
                "191 px to the left arm beats 270 px to the right one");
        assertNull(AgentZakumScenario.nearestAnchor(List.of(), List.of(leftHigh)));
        assertNull(AgentZakumScenario.nearestAnchor(anchors, List.of()));
        assertEquals(List.of(), AgentZakumScenario.parseAnchors("none"));
    }

    @Test
    void atTheClearEveryMemberStops() {
        summon();
        when(event.isEventCleared()).thenReturn(true);
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));

        fight.tick(List.of(hero), event, 0L);

        verify(gateway).stop(entries.get(1));
        verify(gateway, never()).grind(any(), anySet(), anySet());
    }

    @Test
    void aMemberWithNoRuntimeIsLeftAlone() {
        summon();
        Character ghost = member(1, 0, Job.HERO, new Point(0, -213));
        entries.clear();

        fight.tick(List.of(ghost), event, 0L);

        verify(gateway, never()).grind(any(), anySet(), anySet());
        verify(gateway, never()).stop(any());
        verify(gateway, never()).setHorizontalBoundary(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void thePhaseIsLoggedWhenItChangesAndTheVitalsOncePerInterval() {
        Character hero = member(1, 0, Job.HERO, new Point(0, -213));
        try (LogLines lines = new LogLines(AgentZakumScenario.class)) {
            fight.tick(List.of(hero), event, 0L);
            summon();
            fight.tick(List.of(hero), event, 1_000L);
            fight.tick(List.of(hero), event, 2_000L);
            fight.tick(List.of(hero), event, 30_000L);

            assertEquals(2, lines.startingWith("Zakum combat phase=").size());
            assertEquals(1, lines.startingWith("Zakum combat phase=WAITING_FOR_SUMMON members=1").size());
            assertEquals(1, lines.startingWith("Zakum combat phase=ARMS members=1").size());
            List<String> vitals = lines.startingWith("Zakum party vitals phase=");
            assertEquals(2, vitals.size(), vitals.toString());
            assertTrue(vitals.getLast().startsWith("Zakum party vitals phase=ARMS [Hero1=50/100hp,40/80mp@(0,-213) "
                    + "FLOOR_ARMS]"), vitals.getLast());
            assertTrue(vitals.getLast().contains("8800000(fake)="), vitals.getLast());
        }
    }

    /** Right after the Eye: the fake body and the eight arms, each driven by a human, all at the altar. */
    private void summon() {
        part(FAKE_BODY, true);
        for (int arm = 8_800_003; arm <= 8_800_010; arm++) {
            part(arm);
        }
    }

    /**
     * A real foothold tree with one flat floor at the altar's height; insert() sets the drop walls to its ends. Real
     * objects, not mocks: a mocked Foothold/FootholdTree slows every later graph build (FootholdMockingGuardTest).
     */
    private static FootholdTree floor(int minX, int maxX) {
        FootholdTree footholds = new FootholdTree(new Point(-2_000, -2_000), new Point(2_000, 2_000));
        footholds.insert(new Foothold(new Point(minX, ALTAR.y), new Point(maxX, ALTAR.y), 1));
        assertEquals(minX, footholds.getMinDropX());
        assertEquals(maxX, footholds.getMaxDropX());
        return footholds;
    }

    private Monster part(int mobId) {
        return part(mobId, false);
    }

    private Monster part(int mobId, boolean fake) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isFake()).thenReturn(fake);
        when(monster.getPosition()).thenReturn(new Point(ALTAR));
        when(monster.getHp()).thenReturn(1_000L);
        when(monster.getMaxHp()).thenReturn(2_000L);
        monsters.add(monster);
        return monster;
    }

    private Monster monster(int mobId) {
        return monsters.stream().filter(monster -> monster.getId() == mobId).findFirst().orElseThrow();
    }

    private Character member(int id, int ordinal, Job job, Point position) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(job.name().charAt(0) + job.name().substring(1).toLowerCase() + id);
        when(member.getMap()).thenReturn(map);
        when(member.getJob()).thenReturn(job);
        when(member.getPosition()).thenReturn(position);
        when(member.getHp()).thenReturn(50);
        when(member.getCurrentMaxHp()).thenReturn(100);
        when(member.getMp()).thenReturn(40);
        when(member.getCurrentMaxMp()).thenReturn(80);
        entries.put(id, mock(AgentRuntimeEntry.class));
        fight.adopt(id, ordinal);
        return member;
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> grindIds() {
        ArgumentCaptor<Set<Integer>> preferred = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<Integer>> fallback = ArgumentCaptor.forClass(Set.class);
        verify(gateway, atLeast(0)).grind(any(), preferred.capture(), fallback.capture());
        Set<Integer> ids = new HashSet<>();
        preferred.getAllValues().forEach(ids::addAll);
        fallback.getAllValues().forEach(ids::addAll);
        return ids;
    }
}
