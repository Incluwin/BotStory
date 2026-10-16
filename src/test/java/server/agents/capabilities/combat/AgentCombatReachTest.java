package server.agents.capabilities.combat;

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
import constants.skills.DragonKnight;
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-10-16 (P20): {@link AgentCombatReach} over real Skill.wz, Mob.wz and Character.wz data at Pink Bean's birds.
 * Hugin (8820005) and Munin (8820006) stand at the statue's origin (5,-44), 2 px above the y=-42 floor, and their
 * bodies hang 97 px above that origin (Hugin x -397..-240, Munin x 248..405), so from the floor a box must reach 99 px
 * up. Loadouts as the database held them on 2026-10-16: the Heroes (Garrick, Brannock, Thane) and the Paladin (Orrin)
 * wield the one-handed sword 1302081, the Dark Knights (Ingrid, Corvin) the spear 1432038. Orrin swung 0-3 times a
 * minute against Hugin on the floor and 25-55 on the y=-99 ledge beside him; the Dark Knights about 30 on either.
 */
class AgentCombatReachTest {
    private static final int SWORD = 1_302_081;
    private static final int SPEAR = 1_432_038;
    private static final int HUGIN = 8_820_005;
    private static final int MUNIN = 8_820_006;
    private static final List<Integer> HERO = List.of(Hero.BRANDISH, Hero.RUSH, 1_111_008, 1_111_003, 1_111_005);
    private static final List<Integer> PALADIN = List.of(Paladin.BLAST, WhiteKnight.CHARGE_BLOW);
    private static final List<Integer> DARK_KNIGHT = List.of(DragonKnight.SPEAR_CRUSHER, 1_311_003, 1_311_006,
            DragonKnight.SACRIFICE);
    /** Under the middle of each bird's body, on the floor, and on the ledges boss-rules.json names for them. */
    private static final Point UNDER_HUGIN = new Point(-318, -42);
    private static final Point UNDER_MUNIN = new Point(326, -42);
    private static final Point HUGIN_LEDGE = new Point(-455, -99);
    private static final Point MUNIN_LEDGE = new Point(460, -102);

    private MockedStatic<AgentInventoryGatewayRuntime> inventory;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @BeforeEach
    void stubWeapons() {
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(SWORD)).thenReturn(WeaponType.SWORD1H);
        when(items.getWeaponType(SPEAR)).thenReturn(WeaponType.SPEAR_STAB);
        when(items.isTwoHandedWeapon(SPEAR)).thenReturn(true);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
    }

    @AfterEach
    void closeWeapons() {
        inventory.close();
    }

    @Test
    void theBirdsBodiesHangNinetyNinePixelsAboveTheFloor() {
        Rectangle hugin = AgentMobHitboxProvider.getInstance().getMobBounds(bird(HUGIN));
        Rectangle munin = AgentMobHitboxProvider.getInstance().getMobBounds(bird(MUNIN));

        assertEquals(-44 - 97, hugin.y + hugin.height, "Hugin's body ends 97 px above his origin");
        assertEquals(-44 - 97, munin.y + munin.height, "and Munin's above hers");
        assertEquals(UNDER_HUGIN.x, (int) hugin.getCenterX());
        assertEquals(UNDER_MUNIN.x, (int) munin.getCenterX());
    }

    @Test
    void fromTheFloorThePaladinReachesNeitherBirdWhileTheHeroesAndDarkKnightsDo() {
        for (Object[] bird : List.of(new Object[] {HUGIN, UNDER_HUGIN}, new Object[] {MUNIN, UNDER_MUNIN})) {
            Monster part = bird((int) bird[0]);
            Point floor = (Point) bird[1];

            assertFalse(reaches(Job.PALADIN, SWORD, PALADIN, part, floor), "no Paladin box is 99 px tall");
            assertTrue(reaches(Job.HERO, SWORD, List.of(Hero.BRANDISH), part, floor), "Brandish is 110 px tall");
            assertTrue(reaches(Job.DARKKNIGHT, SPEAR, DARK_KNIGHT, part, floor), "Dragon Roar (132 px) and the spear");
        }
    }

    @Test
    void fromItsLedgeThePaladinReachesEachBirdAndFartherOutItDoesNot() {
        assertTrue(reaches(Job.PALADIN, SWORD, PALADIN, bird(HUGIN), HUGIN_LEDGE));
        assertTrue(reaches(Job.PALADIN, SWORD, PALADIN, bird(MUNIN), MUNIN_LEDGE));
        assertTrue(reaches(Job.PALADIN, SWORD, List.of(Paladin.BLAST), bird(HUGIN), HUGIN_LEDGE), "Blast alone");

        assertFalse(reaches(Job.PALADIN, SWORD, PALADIN, bird(HUGIN), new Point(-560, -99)), "163 px from him");
        assertFalse(reaches(Job.PALADIN, SWORD, PALADIN, bird(MUNIN), new Point(570, -102)), "165 px from her");
        assertTrue(reaches(Job.HERO, SWORD, HERO, bird(HUGIN), HUGIN_LEDGE));
        assertTrue(reaches(Job.DARKKNIGHT, SPEAR, DARK_KNIGHT, bird(MUNIN), MUNIN_LEDGE));
    }

    @Test
    void aSkillTheAgentHasNotLearnedDoesNotCount() {
        Character orrin = agent(Job.PALADIN, SWORD, PALADIN, new Point(5, -42), mock(MapleMap.class));

        assertFalse(AgentCombatReach.reachesFrom(orrin, List.of(Hero.BRANDISH), bird(HUGIN), UNDER_HUGIN));
    }

    @Test
    void aBoxMovedToAStandPointIsTheBoxThePlannerPlansThere() {
        Monster hugin = bird(HUGIN);
        for (Point stand : List.of(UNDER_HUGIN, new Point(-200, -42), new Point(-420, -42), HUGIN_LEDGE,
                new Point(-530, -99), new Point(-560, -99), new Point(-380, -99))) {
            Character there = agent(Job.HERO, SWORD, List.of(Hero.BRANDISH), stand, hugin.getMap());
            AgentAttackPlan plan = AgentSkillAttackPlanRuntime.planSkillAttack(there, hugin, Hero.BRANDISH,
                    AgentCombatConfig.cfg);
            boolean planned = plan != null && AgentCombatRangePolicy.isTargetInAttackRange(plan, there, hugin);

            assertEquals(planned, reaches(Job.HERO, SWORD, List.of(Hero.BRANDISH), hugin, stand), "from " + stand);
        }
    }

    /** Whether an Agent standing at the statue would reach {@code part} from {@code stand}. */
    private static boolean reaches(Job job, int weapon, List<Integer> skills, Monster part, Point stand) {
        Character agent = agent(job, weapon, skills, new Point(5, -42), mock(MapleMap.class));
        return AgentCombatReach.reachesFrom(agent, skills, part, stand);
    }

    private static Monster bird(int id) {
        Monster monster = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        MapleMap map = mock(MapleMap.class);
        when(monster.getId()).thenReturn(id);
        when(monster.getObjectId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isBoss()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(5, -44));
        when(monster.getStats()).thenReturn(stats);
        when(stats.getFixedStance()).thenReturn(4);   // LifeFactory: a mob with speed 0 keeps its stance
        when(monster.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(monster));
        return monster;
    }

    private static Character agent(Job job, int weapon, List<Integer> skills, Point at, MapleMap map) {
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(weapon, (short) -11, (short) 1));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(agent.getPosition()).thenReturn(at);
        when(agent.getMap()).thenReturn(map);
        when(agent.getJob()).thenReturn(job);
        when(agent.getLevel()).thenReturn(200);
        when(agent.getHp()).thenReturn(19_000);
        when(agent.getMp()).thenReturn(3_000);
        when(agent.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(agent.hasDisease(any(Disease.class))).thenReturn(false);
        Map<Integer, Integer> levels = new HashMap<>();
        skills.forEach(id -> levels.put(id, id == DragonKnight.SACRIFICE ? 3 : id == Hero.RUSH ? 28 : 30));
        when(agent.getSkillLevel(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (int) levels.getOrDefault(skill.getId(), 0);
        });
        return agent;
    }
}
