package server.agents.capabilities.expedition.boss;

import client.BuffStat;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import config.AgentYamlConfig;
import constants.game.CharacterStance;
import net.server.channel.handlers.AbstractDealDamageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatCooldownStateRuntime;
import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.integration.CombatAttackApplicationResult;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.expeditions.Expedition;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The client-load lever for boss runs (P8 step 14). ATTACK_INTERVAL_FLOOR_MS spaces the attacks of an INSIDE member on
 * its run's battle map, in every boss run; 0 (shipped) changes nothing. Outside a run's battle map, and for a KPQ
 * member, an attack keeps its own pace. Each run also counts the attacks its members commit on its battle map, for the
 * boss mirror's summary line (botAttacks=). The attacks go through the real AgentCombatAttackRuntime.attackMonster with
 * the packet route stubbed, so the floor is checked where the combat code applies it.
 */
class AgentBossClientLoadTest {
    private static final String FLOOR =
            "server.agents.capabilities.expedition.boss.AgentBossClientLoad.ATTACK_INTERVAL_FLOOR_MS";
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;
    private static final int INGRID = 12;
    private static final int SORREL = 13;
    private static final int FLINT = 14;
    private static final int GUNNAR = 15;
    private static final int STRANGER = 99;
    private static final long NOW = 1_000_000L;
    /** The attack plan's own cooldown: the pace an attack keeps without the floor. */
    private static final int PLAN_COOLDOWN_MS = 600;
    private static final int ALTAR = AgentZakumDefinition.BATTLE_MAP_ID;
    private static final int ADOBIS_ROOM = AgentZakumDefinition.RECRUIT_MAP_ID;
    private static final int EL_NATH = 211_000_000;
    /** Kerning PQ stage 1. */
    private static final int KPQ_STAGE = 103_000_800;
    /** Mano's field, Thicket Around the Beach III, and Mano: a field boss on a map with no event. */
    private static final int FIELD = 104_000_400;
    private static final int MANO = 2_220_000;

    private String shipped;

    @BeforeEach
    void setUp() {
        shipped = AgentYamlConfig.config.tuning.get(FLOOR);
    }

    @AfterEach
    void tearDown() {
        if (shipped == null) {
            AgentYamlConfig.config.tuning.remove(FLOOR);
        } else {
            AgentYamlConfig.config.tuning.put(FLOOR, shipped);
        }
        AgentBossRunRegistry.resetForTest();
        AgentBossClientLoad.resetForTest();
    }

    @Test
    void theShippedFloorIsZeroAndChangesNothing() {
        assertEquals("0", shipped, "agent-engine.yaml ships the lever off");
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);

        assertEquals(0, AgentBossClientLoad.attackFloorMs(thane));
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(thane), "the attack keeps its own cooldown");
    }

    @Test
    void aFloorSpacesTheAttacksOfAnInsideMemberOnItsBattleMapInEveryBossRun() {
        tune("900");
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        AgentBossRunSession easy = open(2L, AgentBossRunCatalog.EASY_BALROG);
        Character isolde = inside(easy, ISOLDE, AgentBalrogDefinition.BATTLE_MAP);

        assertEquals(900, AgentBossClientLoad.attackFloorMs(thane));
        assertEquals(900, AgentBossClientLoad.attackFloorMs(isolde), "a lever for every boss run");
        assertTrue(commitAttack(thane) >= 900, "the next attack waits at least the floor");
        assertEquals(900, commitAttack(isolde));

        tune("300");
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(thane), "a floor below the attack's own cooldown changes nothing");
    }

    @Test
    void theFloorStaysOffOutsideARunsBattleMap() {
        tune("900");
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        zakum.addMember(THANE, OWNER, NOW);
        Character waiting = agent(THANE, ADOBIS_ROOM);
        zakum.addMember(ISOLDE, OWNER, NOW).registered();
        Character registered = agent(ISOLDE, ADOBIS_ROOM);
        Character offTheAltar = inside(zakum, INGRID, ADOBIS_ROOM);
        AgentBossRunMember sorrel = adopt(zakum, SORREL);
        sorrel.leaving(AgentBossRunHost.Out.OWNER_LEFT, NOW);
        Character leaving = agent(SORREL, ALTAR);
        adopt(zakum, FLINT).leftRun(AgentBossRunHost.Out.DIED);
        Character out = agent(FLINT, EL_NATH);
        Character stranger = agent(STRANGER, ALTAR);
        Character gunnar = inside(zakum, GUNNAR, ALTAR);

        assertEquals(0, AgentBossClientLoad.attackFloorMs(waiting), "WAITING");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(registered), "REGISTERED");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(offTheAltar), "INSIDE, not on the battle map");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(leaving), "LEAVING");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(out), "OUT");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(stranger), "not a member");
        assertEquals(0, AgentBossClientLoad.attackFloorMs(null));
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(stranger));
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(leaving));

        AgentBossRunRegistry.remove(zakum);
        assertEquals(0, AgentBossClientLoad.attackFloorMs(gunnar), "the run ended");
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(gunnar));
    }

    @Test
    void aKpqMemberKeepsItsOwnPaceWhileABossRunUsesTheFloor() {
        tune("900");
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        Character kpq = agent(SORREL, KPQ_STAGE);

        assertEquals(900, AgentBossClientLoad.attackFloorMs(thane));
        assertEquals(0, AgentBossClientLoad.attackFloorMs(kpq));
        assertEquals(PLAN_COOLDOWN_MS, commitAttack(kpq));
    }

    @Test
    void eachRunCountsTheAttacksItsInsideMembersCommitOnItsBattleMap() {
        tune("0");
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        Character isolde = inside(zakum, ISOLDE, ALTAR);
        Character offTheAltar = inside(zakum, INGRID, ADOBIS_ROOM);
        AgentBossRunSession easy = open(2L, AgentBossRunCatalog.EASY_BALROG);
        Character flint = inside(easy, FLINT, AgentBalrogDefinition.BATTLE_MAP);
        AgentBossRunSession idle = open(3L, AgentBossRunCatalog.ZAKUM);

        commitAttack(thane);
        commitAttack(thane);
        commitAttack(isolde);
        commitAttack(offTheAltar);
        commitAttack(flint);
        commitAttack(agent(STRANGER, ALTAR));
        commitAttack(agent(SORREL, KPQ_STAGE));

        assertEquals(3L, AgentBossClientLoad.attacks(zakum), "the floor does not have to be on");
        assertEquals(1L, AgentBossClientLoad.attacks(easy));
        assertEquals(0L, AgentBossClientLoad.attacks(idle));
    }

    @Test
    void eachInsideMembersAttacksAndDamageAreCountedUntilItsStatusLineDrainsThem() {
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        Character offTheAltar = inside(zakum, INGRID, ADOBIS_ROOM);

        AgentBossClientLoad.memberAttack(thane, 30_000L);
        AgentBossClientLoad.memberAttack(thane, 12_000L);
        AgentBossClientLoad.memberAttack(offTheAltar, 50_000L);
        AgentBossClientLoad.memberAttack(agent(STRANGER, ALTAR), 50_000L);

        assertArrayEquals(new long[]{2L, 42_000L}, AgentBossClientLoad.drain(THANE));
        assertArrayEquals(new long[]{0L, 0L}, AgentBossClientLoad.drain(THANE), "a drain starts the next count");
        assertArrayEquals(new long[]{0L, 0L}, AgentBossClientLoad.drain(INGRID), "off its battle map it is not counted");
        assertArrayEquals(new long[]{0L, 0L}, AgentBossClientLoad.drain(STRANGER));
    }


    @Test
    void besideAFieldBossTheFloorActsOnlyWhileItsKeyIsOnAndNeverOnAnEventMap() {
        tune("900");
        AgentBossRules.replaceForTest(List.of(new AgentBossRules.Boss("field_test", "Test field", null, null, null,
                null, List.of(MANO), false, null)));
        boolean[] keyOn = {false};
        AgentBossRunHost.install(new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public boolean bossEnabled(String key) {
                return keyOn[0] && "field_test".equals(key);
            }
        });
        try {
            Character citizen = agent(STRANGER, FIELD);
            Monster mano = fieldBoss(citizen.getMap(), MANO);
            when(citizen.getMap().getAllMonsters()).thenReturn(List.of(mano));
            assertEquals(0, AgentBossClientLoad.attackFloorMs(citizen), "the field key is off");

            keyOn[0] = true;
            assertEquals(900, AgentBossClientLoad.attackFloorMs(citizen), "any Agent beside a field boss");
            assertTrue(commitAttack(citizen) >= 900);

            when(mano.isAlive()).thenReturn(false);
            assertEquals(0, AgentBossClientLoad.attackFloorMs(citizen), "the boss is dead");

            Character kpq = agent(SORREL, KPQ_STAGE);
            scripting.event.EventInstanceManager kpqEvent = mock(scripting.event.EventInstanceManager.class);
            Monster eventMano = fieldBoss(kpq.getMap(), MANO);
            when(kpq.getMap().getEventInstance()).thenReturn(kpqEvent);
            when(kpq.getMap().getAllMonsters()).thenReturn(List.of(eventMano));
            assertEquals(0, AgentBossClientLoad.attackFloorMs(kpq), "a KPQ member keeps its own pace");
        } finally {
            AgentBossRules.replaceForTest(null);
            AgentBossRunHost.install(null);
        }
    }

    private static Monster fieldBoss(MapleMap map, int mobId) {
        Monster boss = mock(Monster.class);
        when(boss.getId()).thenReturn(mobId);
        when(boss.isAlive()).thenReturn(true);
        when(boss.getMap()).thenReturn(map);
        return boss;
    }

    @Test
    void aRefusedAttackIsNotCounted() {
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        AgentCombatCooldownStateRuntime.maxAttackCooldown(entry, 100);

        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(entry, thane,
                plan(target(thane.getMap())));

        assertEquals(AgentAttackTransactionResult.Status.DEFERRED, result.status(), "still on its cooldown");
        assertEquals(0L, AgentBossClientLoad.attacks(zakum));
    }

    /**
     * P12 G4 at the attack's commit (it shares this class's attack harness): an inside member's attack on a target that
     * reflects its damage is refused, so no reflect damage comes back; outside the run the same attack goes through.
     */
    @Test
    void anInsideMembersAttackOnAReflectingTargetIsRefusedAtCommit() {
        AgentBossRunSession zakum = open(1L, AgentBossRunCatalog.ZAKUM);
        Character thane = inside(zakum, THANE, ALTAR);
        scripting.event.EventInstanceManager altarEvent = mock(scripting.event.EventInstanceManager.class);
        when(thane.getMap().getEventInstance()).thenReturn(altarEvent);
        when(thane.getEventInstance()).thenReturn(altarEvent);
        Monster reflecting = target(thane.getMap());
        when(reflecting.isBuffed(client.status.MonsterStatus.WEAPON_REFLECT)).thenReturn(true);

        AgentAttackTransactionResult refused = AgentCombatAttackRuntime.attackMonster(
                new AgentRuntimeEntry(thane, null, null), thane, plan(reflecting));

        assertEquals(AgentAttackTransactionResult.Status.REJECTED, refused.status(), () -> "attack " + refused);
        assertEquals(AgentAttackTransactionResult.Reason.TARGET_UNAVAILABLE, refused.reason());
        assertEquals(0L, AgentBossClientLoad.attacks(zakum));
    }

    // ---- fixture ---------------------------------------------------------------------------

    private static void tune(String floorMs) {
        AgentYamlConfig.config.tuning.put(FLOOR, floorMs);
    }

    private static AgentBossRunSession open(long sessionId, AgentBossRunDefinition definition) {
        AgentBossRunSession session = new AgentBossRunSession(sessionId, definition, mock(Expedition.class),
                new FakeBossRunPorts.Fight(), NOW);
        AgentBossRunRegistry.put(session);
        return session;
    }

    private static AgentBossRunMember adopt(AgentBossRunSession session, int agentId) {
        AgentBossRunMember member = session.addMember(agentId, OWNER, NOW);
        member.registered();
        member.adopted();
        return member;
    }

    /** An INSIDE member of {@code session} standing on {@code mapId}. */
    private static Character inside(AgentBossRunSession session, int agentId, int mapId) {
        adopt(session, agentId);
        return agent(agentId, mapId);
    }

    /**
     * Commits one basic attack of {@code bot} on a monster beside it through the real attack runtime, with only the
     * packet route and the after-attack timers stubbed, and returns the attack cooldown it leaves.
     */
    private static int commitAttack(Character bot) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentAttackPlan plan = plan(target(bot.getMap()));
        AgentAttackTransactionResult result;
        ScheduledFuture<?> later = mock(ScheduledFuture.class);
        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     Mockito.mockStatic(AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentSchedulerRuntime> scheduler =
                     Mockito.mockStatic(AgentSchedulerRuntime.class, Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.SWORD1H);
            attacks.when(() -> AgentAttackExecutionProvider.applyAttackRoute(
                            any(AgentAttackRoute.class), any(AbstractDealDamageHandler.AttackInfo.class), eq(bot)))
                    .thenReturn(CombatAttackApplicationResult.appliedResult());
            scheduler.when(() -> AgentSchedulerRuntime.afterDelay(anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> later);
            scheduler.when(() -> AgentSchedulerRuntime.afterDelay(
                            any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> later);
            result = AgentCombatAttackRuntime.attackMonster(entry, bot, plan);
        }
        assertEquals(AgentAttackTransactionResult.Status.COMMITTED, result.status(), () -> "attack " + result);
        return AgentCombatCooldownStateRuntime.attackCooldownMs(entry);
    }

    private static AgentAttackPlan plan(Monster target) {
        return new AgentAttackPlan(
                0, 0, 1, new Rectangle(100, 150, 80, 70),
                List.of(target), AgentAttackRoute.CLOSE,
                4, 1, 1, AgentAttackExecutionProvider.attackPacketStance(true),
                4, 300, PLAN_COOLDOWN_MS, WeaponType.SWORD1H);
    }

    /** A live, plain monster 40 px right of the Agent, inside the plan's hit box. */
    private static Monster target(MapleMap map) {
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(140, 200));
        when(mob.getId()).thenReturn(100_100);
        when(mob.getObjectId()).thenReturn(5_000);
        when(mob.getPADamage()).thenReturn(1_000);
        when(mob.getLevel()).thenReturn(1);
        when(mob.getAccuracy()).thenReturn(9_999);
        when(mob.getAvoidability()).thenReturn(0);
        when(mob.getWdef()).thenReturn(0);
        when(mob.getMdef()).thenReturn(0);
        when(mob.getHp()).thenReturn(10_000L);
        when(mob.getMaxHp()).thenReturn(10_000L);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getMap()).thenReturn(map);
        return mob;
    }

    /** An Agent on {@code mapId} at (100,200), stubbed enough for the attack runtime and the damage formula. */
    private static Character agent(int agentId, int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.isMobPhysicsObserverWarmupComplete()).thenReturn(true);
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(bot.getId()).thenReturn(agentId);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenAnswer(invocation -> new Point(100, 200));
        when(bot.getHp()).thenReturn(20_000);
        when(bot.getCurrentMaxHp()).thenReturn(20_000);
        when(bot.getStance()).thenReturn(CharacterStance.STAND_RIGHT_STANCE);
        when(bot.getChair()).thenReturn(-1);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getLevel()).thenReturn(200);
        when(bot.isAlive()).thenReturn(true);
        when(bot.isFacingLeft()).thenReturn(false);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(4);
        when(bot.getTotalDex()).thenReturn(4);
        when(bot.getTotalInt()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getAllBuffs()).thenReturn(Collections.emptyList());
        // Mockito answers 0 for an unstubbed Integer; the live API answers null for a buff that is absent
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(bot.calculateMaxBaseDamage(anyInt())).thenReturn(1_000);
        when(bot.calculateMinBaseDamage(anyInt())).thenReturn(500);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem((short) -11)).thenReturn(null);
        when(equipped.iterator()).thenReturn(Collections.emptyIterator());
        return bot;
    }
}
