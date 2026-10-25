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
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatReach;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-16 (P20): from the shipped boss rules, a melee member whose attacks cannot reach Pink Bean's Hugin (8820005)
 * or Munin (8820006) from the floor grinds the bird from the ledge boss-rules.json names for it, and only such a
 * member. Real Skill.wz, Mob.wz and Character.wz data decide who reaches ({@link AgentCombatReach}), with the crew's
 * loadouts as the database held them on 2026-10-16 (sword 1302081 for the Heroes and the Paladin, spear 1432038 for
 * the Dark Knights). The birds' bodies hang 99 px above the floor. Orrin, the Paladin, swung 0-3 times a minute against
 * Hugin on the floor and 25-55 on the y=-99 ledge beside him, where no rule sent him; the Heroes reach with Brandish.
 */
class AgentBossLedgeTest {
    private static final int BATTLE = 270_050_100;
    private static final int PINK_BEAN = 8_820_001;
    private static final int ARIEL = 8_820_002;
    private static final int SOLOMON = 8_820_003;
    private static final int REX = 8_820_004;
    private static final int HUGIN = 8_820_005;
    private static final int MUNIN = 8_820_006;
    private static final int SWORD = 1_302_081;
    private static final int SPEAR = 1_432_038;
    private static final Point HUGIN_LEDGE = new Point(-455, -99);
    private static final Point MUNIN_LEDGE = new Point(460, -102);
    private static final List<Integer> PALADIN = List.of(Paladin.BLAST, WhiteKnight.CHARGE_BLOW);
    private static final List<Integer> HERO = List.of(Hero.BRANDISH, Hero.RUSH, 1_111_008);
    private static final List<Integer> DARK_KNIGHT = List.of(1_311_001, 1_311_003, 1_311_006);

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final MapleMap battle = mock(MapleMap.class);
    private final List<Monster> monsters = new ArrayList<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final Map<Integer, List<Integer>> skills = new HashMap<>();
    private final Set<Integer> melee = new HashSet<>();
    private final Map<Integer, Character> characters = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private MockedStatic<AgentInventoryGatewayRuntime> inventory;
    private AgentBossPriorityFight fight;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @BeforeEach
    void setUp() {
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(SWORD)).thenReturn(WeaponType.SWORD1H);
        when(items.getWeaponType(SPEAR)).thenReturn(WeaponType.SPEAR_STAB);
        when(items.isTwoHandedWeapon(SPEAR)).thenReturn(true);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
        when(battle.getId()).thenReturn(BATTLE);
        // the floor lies at y=-42 under every point the fight asks about below the ledges
        when(gateway.groundPoint(any(), any())).thenAnswer(call -> {
            Point at = call.getArgument(1);
            return at.y < -90 ? new Point(at) : new Point(at.x, -42);
        });
        fight = new AgentBossPriorityFight("pinkbean", new AgentBossPriorityFight.Ports() {
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
                return map == battle ? monsters : List.of();
            }

            @Override
            public boolean hittable(Monster monster) {
                return true;
            }

            @Override
            public boolean melee(Character member) {
                return melee.contains(member.getId());
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
    }

    @AfterEach
    void tearDown() {
        inventory.close();
        AgentBossRules.replaceForTest(null);
    }

    @Test
    void theShippedRulesNameALedgeBesideEachBird() {
        assertEquals(List.of(new AgentBossRules.Anchor(HUGIN_LEDGE.x, HUGIN_LEDGE.y, List.of(HUGIN)),
                        new AgentBossRules.Anchor(MUNIN_LEDGE.x, MUNIN_LEDGE.y, List.of(MUNIN))),
                AgentBossRules.of("pinkbean").ledges());
    }

    @Test
    void inWaveThreeThePaladinFightsHuginFromTheLeftLedgeAndTheHeroAndDarkKnightFromWhereTheyStand() {
        mobs(SOLOMON, REX, HUGIN);
        AgentRuntimeEntry orrin = member(10, Job.PALADIN, SWORD, PALADIN, true);
        AgentRuntimeEntry garrick = member(11, Job.HERO, SWORD, HERO, true);
        AgentRuntimeEntry corvin = member(12, Job.DARKKNIGHT, SPEAR, DARK_KNIGHT, true);

        tick(10, 11, 12);

        verify(gateway).grindFromAnchor(orrin, HUGIN_LEDGE, Set.of(HUGIN), Set.of(SOLOMON, REX));
        verify(gateway, never()).grind(eq(orrin), anySet(), anySet());
        verify(gateway).grindFromAnchor(any(), any(), anySet(), anySet());
        verify(gateway).grind(garrick, Set.of(HUGIN), Set.of(SOLOMON, REX));
        verify(gateway).grind(corvin, Set.of(HUGIN), Set.of(SOLOMON, REX));
    }

    @Test
    void withMuninUpThePaladinFightsHerFromTheRightLedge() {
        mobs(SOLOMON, REX, HUGIN, MUNIN);
        AgentRuntimeEntry orrin = member(10, Job.PALADIN, SWORD, PALADIN, true);

        tick(10);

        verify(gateway).grindFromAnchor(orrin, MUNIN_LEDGE, Set.of(MUNIN), Set.of(SOLOMON, REX, HUGIN));
    }

    @Test
    void aPartNoLedgeNamesIsFoughtAsBefore() {
        mobs(SOLOMON, REX, HUGIN, ARIEL);
        AgentRuntimeEntry orrin = member(10, Job.PALADIN, SWORD, PALADIN, true);

        tick(10);

        verify(gateway).grind(orrin, Set.of(ARIEL), Set.of(SOLOMON, REX, HUGIN));
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void aRangedMemberIsNeverSentToALedge() {
        mobs(HUGIN, PINK_BEAN);
        AgentRuntimeEntry notMelee = member(10, Job.PALADIN, SWORD, PALADIN, false);

        tick(10);

        verify(gateway).grind(notMelee, Set.of(HUGIN), Set.of(PINK_BEAN));
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void aLedgeFromWhichTheMemberStillCannotReachIsNotUsed() {
        rules(List.of(new AgentBossRules.Anchor(-560, -99, List.of(HUGIN))));
        mobs(HUGIN);
        AgentRuntimeEntry orrin = member(10, Job.PALADIN, SWORD, PALADIN, true);

        tick(10);

        verify(gateway).grind(orrin, Set.of(HUGIN), Set.of());
        verify(gateway, never()).grindFromAnchor(any(), any(), anySet(), anySet());
    }

    @Test
    void membersWhoNeedTheLedgesSpreadOverTheOnesFromWhichTheyReach() {
        rules(List.of(new AgentBossRules.Anchor(-455, -99, List.of(HUGIN)),
                new AgentBossRules.Anchor(-560, -99, List.of(HUGIN)),
                new AgentBossRules.Anchor(-480, -99, List.of(HUGIN))));
        mobs(HUGIN);
        AgentRuntimeEntry orrin = member(10, Job.PALADIN, SWORD, PALADIN, true);
        AgentRuntimeEntry second = member(11, Job.PALADIN, SWORD, PALADIN, true);

        tick(10, 11);

        verify(gateway).grindFromAnchor(orrin, new Point(-455, -99), Set.of(HUGIN), Set.of());
        verify(gateway).grindFromAnchor(second, new Point(-480, -99), Set.of(HUGIN), Set.of());
    }

    /** The shipped Pink Bean row with {@code ledges} instead of its own. */
    private static void rules(List<AgentBossRules.Anchor> ledges) {
        AgentBossRules.Boss row = AgentBossRules.of("pinkbean");
        AgentBossRules.replaceForTest(List.of(new AgentBossRules.Boss(row.key(), row.label(), row.roles(),
                row.targetPriority(), row.adds(), row.neverTarget(), row.fieldBosses(), row.neverTargetFakes(),
                row.markerItems(), row.minLevel(), row.deathWaitMap(), row.randomSpots(), row.stageMaps(),
                row.stagePortal(), row.meleePriority(), row.reentryWarpFrom(), row.entryItems(), row.keepOut(),
                row.focus(), row.attackKeepOut(), row.keepOutHpPercent(), row.companionDamagePercent(),
                row.attackKeepOutHpPercent(), row.lastStand(), ledges)));
    }

    private void tick(int... ids) {
        List<Character> fighters = new ArrayList<>();
        for (int id : ids) {
            fighters.add(characters.get(id));
        }
        fight.tick(fighters, event, 0L);
    }

    private void mobs(int... ids) {
        for (int id : ids) {
            Monster monster = mock(Monster.class);
            MonsterStats stats = mock(MonsterStats.class);
            when(monster.getId()).thenReturn(id);
            when(monster.getObjectId()).thenReturn(id);
            when(monster.isAlive()).thenReturn(true);
            when(monster.isBoss()).thenReturn(true);
            when(monster.getPosition()).thenReturn(new Point(5, -44));   // every part spawns at the statue
            when(monster.getStats()).thenReturn(stats);
            when(stats.getFixedStance()).thenReturn(4);
            monsters.add(monster);
        }
    }

    /** A Lv200 member on the floor at x=0 with {@code known} at level 30, and its runtime entry. */
    private AgentRuntimeEntry member(int id, Job job, int weapon, List<Integer> known, boolean hitsInMelee) {
        Character member = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(weapon, (short) -11, (short) 1));
        when(member.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(member.getId()).thenReturn(id);
        when(member.getMap()).thenReturn(battle);
        when(member.getMapId()).thenReturn(BATTLE);
        when(member.getPosition()).thenReturn(new Point(0, -42));
        when(member.getJob()).thenReturn(job);
        when(member.getLevel()).thenReturn(200);
        when(member.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(member.hasDisease(any(Disease.class))).thenReturn(false);
        when(member.getSkillLevel(any(Skill.class))).thenAnswer(invocation ->
                (byte) (known.contains(((Skill) invocation.getArgument(0)).getId()) ? 30 : 0));
        characters.put(id, member);
        skills.put(id, known);
        if (hitsInMelee) {
            melee.add(id);
        }
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        entries.put(id, entry);
        return entry;
    }
}
