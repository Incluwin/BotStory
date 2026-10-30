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
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-10-16 (P20 run 15): Orrin, a Lv200 Paladin, swung about 5 times a minute on the floor beside Pink Bean's
 * Solomon and Rex while the Heroes beside him swung 50 to 90. Their bodies start 56 px above their origin, and they
 * stand 2 px above the floor (the boss mirror's calibration lines report every player hit by them at dy=2), so from
 * the floor a box must reach 58 px up. Blast, his boss skill, has no level lt/rb and was planned with the sword's own
 * swing (58 px up, a row short): no Paladin skill reached. Skill.wz gives Blast an afterimage box of its own (150 px
 * ahead, 60 px up), and the Agent now strikes with it, as the client draws it. Brandish has its own lt/rb (110 px up).
 */
class AgentSkillAfterimageReachTest {
    /** Item.wz 1302081, the one-handed sword Orrin and the Heroes wield (afterimage swordOL). */
    private static final int SWORD = 1_302_081;
    private static final int FLOOR_Y = -42;
    /** Where the statues stand: origin x=5, 2 px above the floor. */
    private static final Point STATUE = new Point(5, FLOOR_Y - 2);

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @Test
    void skillWzGivesBlastItsOwnAfterimageBoxAndChargedBlowNone() {
        Rectangle blast = SkillFactory.getSkill(Paladin.BLAST).getAfterimageBox("swordOL");

        assertEquals(new Rectangle(-150, -60, 150, 60), blast);
        assertNull(SkillFactory.getSkill(Paladin.BLAST).getAfterimageBox("bow"), "no box for a weapon it has none for");
        assertNull(SkillFactory.getSkill(WhiteKnight.CHARGE_BLOW).getAfterimageBox("swordOL"));
    }

    @Test
    void theAfterimageBoxLiesAheadOfTheAgentOnTheSideItFaces() {
        Skill skill = new Skill(Paladin.BLAST);
        skill.setAfterimageBox("swordOL", new Rectangle(-150, -60, 150, 60));
        Point at = new Point(100, 200);

        assertEquals(new Rectangle(-50, 140, 150, 60),
                AgentCombatSkillHitboxPolicy.skillAfterimageHitBox(skill, "swordOL", at, true));
        assertEquals(new Rectangle(100, 140, 150, 60),
                AgentCombatSkillHitboxPolicy.skillAfterimageHitBox(skill, "swordOL", at, false));
        assertNull(AgentCombatSkillHitboxPolicy.skillAfterimageHitBox(skill, "spear", at, false));
        assertNull(AgentCombatSkillHitboxPolicy.skillAfterimageHitBox(null, "swordOL", at, false));
    }

    @Test
    void aPaladinOnTheFloorBesideSolomonOrRexReachesItsBodyWithBlast() {
        Monster solomon = statue(8_820_003);
        Monster rex = statue(8_820_004);
        Map<Integer, Integer> orrin = Map.of(Paladin.BLAST, 10, WhiteKnight.CHARGE_BLOW, 30);
        Map<Integer, Integer> brannock = Map.of(Hero.BRANDISH, 30);

        for (int x : List.of(-820, -760, -700)) {
            assertReaches(orrin, Paladin.BLAST, solomon, x);
            assertReaches(brannock, Hero.BRANDISH, solomon, x);
        }
        for (int x : List.of(607, 680, 770)) {
            assertReaches(orrin, Paladin.BLAST, rex, x);
            assertReaches(brannock, Hero.BRANDISH, rex, x);
        }
    }

    private static void assertReaches(Map<Integer, Integer> levels, int skillId, Monster part, int x) {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of(part));
        when(part.getMap()).thenReturn(map);
        Character agent = agent(new Point(x, FLOOR_Y), map, levels);
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(SWORD)).thenReturn(WeaponType.SWORD1H);
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);

            AgentAttackPlan plan = AgentSkillAttackPlanRuntime.planSkillAttack(agent, part, skillId,
                    AgentCombatConfig.cfg);

            String where = skillId + " from x=" + x + " at " + part.getId();
            assertNotNull(plan, where + ": " + AgentSkillAttackPlanRuntime.lastRejection());
            assertTrue(AgentCombatRangePolicy.isTargetInAttackRange(plan, agent, part), where);
        }
    }

    private static Monster statue(int id) {
        Monster monster = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        when(monster.getId()).thenReturn(id);
        when(monster.getObjectId()).thenReturn(id);
        when(monster.isAlive()).thenReturn(true);
        when(monster.isBoss()).thenReturn(true);
        when(monster.getPosition()).thenReturn(new Point(STATUE));
        when(monster.getStats()).thenReturn(stats);
        when(stats.getFixedStance()).thenReturn(4);   // LifeFactory: a mob with speed 0 keeps its stance
        return monster;
    }

    private static Character agent(Point at, MapleMap map, Map<Integer, Integer> levels) {
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(SWORD, (short) -11, (short) 1));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(agent.getPosition()).thenReturn(at);
        when(agent.getMap()).thenReturn(map);
        when(agent.getJob()).thenReturn(Job.PALADIN);
        when(agent.getLevel()).thenReturn(200);
        when(agent.getHp()).thenReturn(19_000);
        when(agent.getMp()).thenReturn(3_000);
        when(agent.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(agent.getBuffedValue(BuffStat.WK_CHARGE)).thenReturn(1);
        when(agent.hasDisease(any(Disease.class))).thenReturn(false);
        when(agent.getSkillLevel(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (int) levels.getOrDefault(skill.getId(), 0);
        });
        return agent;
    }
}
