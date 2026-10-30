package server.agents.capabilities.expedition.boss;

import client.BuffStat;
import client.Character;
import client.Disease;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Hermit;
import constants.skills.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatReach;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-29 (CWKPQ C9 run 2, 2026-10-23): on the real Council Hall (610030600), with real Skill.wz, Mob.wz and
 * Map.wz data, a Hero and a Night Lord on the hall floor (y=276) cannot reach Margana on the throne platform (y=1):
 * her body ends 279 px above the floor. For eight minutes she stayed at 98% to 87% while the crew killed Rellik and
 * Red Nirg beside them. The fight now sends each to the nearest stand point from which its attacks reach her, one the
 * navigation graph has a path up to, and keeps it there once it stands on it.
 */
class AgentBossCouncilReachStandTest {
    private static final int HALL = 610_030_600;
    private static final int MARGANA = 9_400_590;
    private static final int RELLIK = 9_400_592;
    /** Item.wz 1302081, Hadrik's one-handed sword; 1472052, NonThePriest's claw (his !bossgear kit). */
    private static final int SWORD = 1_302_081;
    private static final int CLAW = 1_472_052;
    private static final int FLOOR_Y = 276;
    private static final List<Integer> HERO = List.of(Hero.BRANDISH, Hero.RUSH);

    private static MapleMap hall;
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final List<Monster> monsters = new ArrayList<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, List<Integer>> skills = new HashMap<>();
    private final Map<Integer, Character> characters = new HashMap<>();
    private final Map<Integer, Point> positions = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private MockedStatic<AgentInventoryGatewayRuntime> inventory;
    private AgentBossPriorityFight fight;

    @BeforeAll
    static void load() {
        SkillFactory.loadAllSkills();
        hall = AgentNavigationMapLoader.loadMapGeometry(HALL);
    }

    @BeforeEach
    void setUp() {
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(SWORD)).thenReturn(WeaponType.SWORD1H);
        when(items.getWeaponType(CLAW)).thenReturn(WeaponType.CLAW);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
        when(gateway.groundPoint(any(), any())).thenAnswer(call ->
                CosmicPrimitiveCapabilityGateway.INSTANCE.groundPoint(hall, call.getArgument(1)));
        when(gateway.canReach(any(), any(), any())).thenReturn(true);
        fight = new AgentBossPriorityFight("cwkpq", new AgentBossPriorityFight.Ports() {
            @Override
            public PrimitiveCapabilityGateway gateway() {
                return gateway;
            }

            @Override
            public AgentRuntimeEntry entry(int characterId) {
                return entries.get(characterId);
            }

            @Override
            public List<Monster> monsters(MapleMap map) {
                return monsters;
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public boolean melee(Character member) {
                return member.getJob() == Job.HERO;
            }

            @Override
            public Rectangle body(Monster part) {
                return AgentMobHitboxProvider.getInstance().getMobBounds(part);
            }

            @Override
            public boolean reaches(Character member, Monster part, Point stand) {
                return AgentCombatReach.reachesFrom(member, skills.get(member.getId()), part, stand);
            }
        });
        mob(MARGANA, new Point(-327, 1));    // where the crew finally hit her (CWKPQ why, 14:49)
        mob(RELLIK, new Point(-496, FLOOR_Y));
    }

    @AfterEach
    void tearDown() {
        inventory.close();
    }

    @Test
    void aHeroAndANightLordOnTheHallFloorClimbToWhereTheyReachMargana() {
        AgentRuntimeEntry hadrik = member(1, Job.HERO, SWORD, HERO, new Point(-673, FLOOR_Y));
        AgentRuntimeEntry nonThePriest = member(2, Job.NIGHTLORD, CLAW, List.of(Hermit.AVENGER),
                new Point(-391, FLOOR_Y));
        Monster margana = monsters.getFirst();
        assertFalse(AgentCombatReach.reachesFrom(characters.get(1), skills.get(1), margana, positions.get(1)));
        assertFalse(AgentCombatReach.reachesFrom(characters.get(2), skills.get(2), margana, positions.get(2)));

        tick(1, 2);

        for (int id : new int[] {1, 2}) {
            AgentRuntimeEntry entry = entries.get(id);
            Point stand = anchorOf(entry);
            assertTrue(stand.y < FLOOR_Y, "member " + id + " stands above the floor: " + stand);
            assertEquals(stand, CosmicPrimitiveCapabilityGateway.INSTANCE.groundPoint(hall, stand), "on a foothold");
            assertTrue(AgentCombatReach.reachesFrom(characters.get(id), skills.get(id), margana, stand),
                    "member " + id + " reaches Margana from " + stand);
            assertTrue(pathUp(positions.get(id), stand), "a path up from " + positions.get(id) + " to " + stand);
            verify(gateway, never()).grind(eq(entry), anySet(), anySet());
        }
        verify(gateway).grindFromAnchor(eq(hadrik), any(), eq(Set.of(MARGANA)), eq(Set.of(RELLIK)));
        verify(gateway).grindFromAnchor(eq(nonThePriest), any(), eq(Set.of(MARGANA)), eq(Set.of(RELLIK)));
    }

    @Test
    void onceThereItKeepsToItsStandInsteadOfGoingBackToTheNearestMaster() {
        AgentRuntimeEntry hadrik = member(1, Job.HERO, SWORD, HERO, new Point(-673, FLOOR_Y));
        tick(1);
        Point stand = anchorOf(hadrik);

        positions.put(1, new Point(stand));   // it walked there: from here it reaches her
        tick(1);
        tick(1);

        verify(gateway, atLeastOnce()).grindFromAnchor(hadrik, stand, Set.of(MARGANA), Set.of(RELLIK));
        verify(gateway, never()).grind(eq(hadrik), anySet(), anySet());
    }

    @Test
    void aMemberOnHerOwnPlatformWalksInAsBefore() {
        AgentRuntimeEntry hadrik = member(1, Job.HERO, SWORD, HERO, new Point(330, 1));
        assertFalse(AgentCombatReach.reachesFrom(characters.get(1), skills.get(1), monsters.getFirst(),
                positions.get(1)), "too far along the throne to reach her");

        tick(1);

        verify(gateway).grind(hadrik, Set.of(MARGANA), Set.of(RELLIK));
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void withMarganaDownTheCrewFightsTheFloorMastersAsBefore() {
        monsters.removeFirst();
        AgentRuntimeEntry hadrik = member(1, Job.HERO, SWORD, HERO, new Point(-673, FLOOR_Y));

        tick(1);

        verify(gateway).grind(hadrik, Set.of(RELLIK), Set.of());
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    private Point anchorOf(AgentRuntimeEntry entry) {
        ArgumentCaptor<Point> stand = ArgumentCaptor.forClass(Point.class);
        verify(gateway, atLeastOnce()).grindFromAnchor(eq(entry), stand.capture(), anySet(), anySet());
        assertNotNull(stand.getValue());
        return stand.getValue();
    }

    private static boolean pathUp(Point from, Point to) {
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(hall, AgentMovementProfile.base());
        int fromRegion = graph.findRegionId(hall, new Point(from.x, from.y - 1));
        int toRegion = graph.findRegionId(hall, new Point(to.x, to.y - 1));
        List<AgentNavigationGraph.Edge> path = fromRegion < 0 || toRegion < 0 ? null
                : AgentNavigationPathService.findPath(graph, hall, from, fromRegion, toRegion, to);
        return path != null && (fromRegion == toRegion || !path.isEmpty());
    }

    private void tick(int... ids) {
        List<Character> fighters = new ArrayList<>();
        for (int id : ids) {
            fighters.add(characters.get(id));
        }
        fight.tick(fighters, event, 0L);
    }

    private void mob(int id, Point at) {
        Monster monster = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        Point ground = CosmicPrimitiveCapabilityGateway.INSTANCE.groundPoint(hall, at);
        when(monster.getId()).thenReturn(id);
        when(monster.getObjectId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isBoss()).thenReturn(true);
        when(monster.getPosition()).thenReturn(ground);
        when(monster.getStats()).thenReturn(stats);
        when(monster.isFacingLeft()).thenReturn(true);
        monsters.add(monster);
    }

    /** A Lv200 member at {@code at} with {@code known} at level 30, and its runtime entry. */
    private AgentRuntimeEntry member(int id, Job job, int weapon, List<Integer> known, Point at) {
        Character member = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(weapon, (short) -11, (short) 1));
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(member.getId()).thenReturn(id);
        when(member.getMap()).thenReturn(hall);
        when(member.getMapId()).thenReturn(HALL);
        positions.put(id, at);
        when(member.getPosition()).thenAnswer(call -> new Point(positions.get(id)));
        when(member.getJob()).thenReturn(job);
        when(member.getLevel()).thenReturn(200);
        when(member.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(member.hasDisease(any(Disease.class))).thenReturn(false);
        when(member.getSkillLevel(any(Skill.class))).thenAnswer(invocation ->
                (byte) (known.contains(((Skill) invocation.getArgument(0)).getId()) ? 30 : 0));
        characters.put(id, member);
        skills.put(id, known);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        entries.put(id, entry);
        return entry;
    }
}
