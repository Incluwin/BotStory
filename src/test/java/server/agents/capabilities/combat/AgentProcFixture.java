package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Disease;
import client.Job;
import client.Skill;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.game.CharacterStance;
import net.server.channel.handlers.AbstractDealDamageHandler;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;
import server.agents.integration.CombatAttackApplicationResult;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Agent at (100,200) on a map of plain monsters (Mob.wz 100100, the snail) whose attacks go through the real
 * AgentCombatAttackRuntime with only the packet route, the delayed-action scheduler, the alert stance and the Final
 * Attack roll stubbed. Every attack the route is handed is kept, in order, and every delayed action waits to be run.
 */
final class AgentProcFixture {
    static final int Y = 200;
    static final Point AGENT = new Point(100, Y);

    record Sent(AgentAttackRoute route, AbstractDealDamageHandler.AttackInfo attack) {
    }

    record Scheduled(long delayMs, Runnable action) {
    }

    final List<Sent> sent = new ArrayList<>();
    final List<Scheduled> scheduled = new ArrayList<>();
    final List<Monster> monsters = new ArrayList<>();
    final MapleMap map = mock(MapleMap.class);
    final Character bot = mock(Character.class);
    final AgentRuntimeEntry entry;
    private final Map<Integer, Integer> skills = new HashMap<>();
    private final WeaponType weaponType;
    private double chance = 0.0;

    AgentProcFixture(Job job, int weaponItemId, WeaponType weaponType) {
        this.weaponType = weaponType;
        when(map.getId()).thenReturn(100_000_000);
        when(map.isMobPhysicsObserverWarmupComplete()).thenReturn(true);
        when(map.getAllMonsters()).thenReturn(monsters);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(new Item(weaponItemId, (short) -11, (short) 1));
        when(equipped.iterator()).thenReturn(Collections.emptyIterator());
        when(bot.getId()).thenReturn(4_242);
        when(bot.getName()).thenReturn("Volt");
        when(bot.getMapId()).thenReturn(100_000_000);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenAnswer(invocation -> new Point(AGENT));
        when(bot.getHp()).thenReturn(20_000);
        when(bot.getCurrentMaxHp()).thenReturn(20_000);
        when(bot.getMp()).thenReturn(20_000);
        when(bot.getStance()).thenReturn(CharacterStance.STAND_RIGHT_STANCE);
        when(bot.getChair()).thenReturn(-1);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(200);
        when(bot.isAlive()).thenReturn(true);
        when(bot.hasDisease(any(Disease.class))).thenReturn(false);
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
        when(bot.getSkillLevel(anyInt())).thenAnswer(invocation -> skills.getOrDefault(invocation.<Integer>getArgument(0), 0));
        when(bot.getSkillLevel(any(Skill.class))).thenAnswer(invocation ->
                (byte) (int) skills.getOrDefault(invocation.<Skill>getArgument(0).getId(), 0));
        entry = new AgentRuntimeEntry(bot, null, null);
    }

    AgentProcFixture skill(int skillId, int level) {
        skills.put(skillId, level);
        return this;
    }

    AgentProcFixture buff(BuffStat stat, int value) {
        when(bot.getBuffedValue(stat)).thenReturn(value);
        return this;
    }

    /** The roll every Final Attack of this fixture makes against its prop. */
    AgentProcFixture chance(double chance) {
        this.chance = chance;
        return this;
    }

    /** A live snail standing at {@code x} on the Agent's floor. */
    Monster monster(int objectId, int x) {
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(x, Y));
        when(mob.getId()).thenReturn(100_100);
        when(mob.getObjectId()).thenReturn(objectId);
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
        monsters.add(mob);
        return mob;
    }

    AgentAttackTransactionResult swing(AgentAttackPlan plan) {
        return stubbed(() -> AgentCombatAttackRuntime.attackMonster(entry, bot, plan));
    }

    /**
     * Puts the Agent's combat learner in LEARN on a fresh table, with {@code plan} its pick at {@code target}; the
     * caller ends it ({@code AgentCombatLearner.end}).
     */
    AgentCombatPolicyTable learning(AgentAttackPlan plan, Monster target) {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        AgentCombatLearner.begin(entry, AgentCombatLearner.Mode.LEARN, table, 0.0d, false);
        stubbed(() -> AgentCombatLearner.choose(entry, bot, target, List.of(plan)));
        return table;
    }

    /** The Agent's next decision, a basic attack that lands {@code damage}, a millisecond on: it closes the open one. */
    void nextDecision(long damage) {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.onSpinWait();
        }
        AgentCombatLearner.onAttackCommitted(entry, 0, damage, 0.0d);
    }

    /** Every line {@code sent} landed, summed. */
    static long dealt(Sent sent) {
        return sent.attack().targets.values().stream().flatMap(target -> target.damageLines().stream())
                .mapToLong(Integer::longValue).sum();
    }

    /**
     * What the learner credits {@code sent} with as its own on a table that has seen no cast of it in
     * {@code context}: the damage the formula expected of it ({@code expected}), calibrated by what it landed.
     */
    static double ownReward(String context, Sent sent, double expected) {
        return new AgentCombatPolicyTable().calibratedReward(context, sent.attack().skill, dealt(sent), expected,
                config.AgentTuning.doubleValue(
                        "server.agents.capabilities.combat.learning.AgentCombatLearner.CALIBRATION_WEIGHT"));
    }

    /** Runs the delayed actions waiting now, as the Agent's mailbox would once each is due. */
    void runScheduled() {
        List<Scheduled> due = List.copyOf(scheduled);
        scheduled.clear();
        stubbed(() -> {
            due.forEach(action -> action.action().run());
            return null;
        });
    }

    /** Runs {@code body} with this fixture's stubs in place. */
    <T> T stubbed(Supplier<T> body) {
        ScheduledFuture<?> later = mock(ScheduledFuture.class);
        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     Mockito.mockStatic(AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentSchedulerRuntime> scheduler =
                     Mockito.mockStatic(AgentSchedulerRuntime.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentCombatAlertRuntime> alert = Mockito.mockStatic(AgentCombatAlertRuntime.class);
             MockedStatic<AgentFinalAttackProc> finalAttack =
                     Mockito.mockStatic(AgentFinalAttackProc.class, Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(weaponType);
            attacks.when(() -> AgentAttackExecutionProvider.applyAttackRoute(
                            any(AgentAttackRoute.class), any(AbstractDealDamageHandler.AttackInfo.class), eq(bot)))
                    .thenAnswer(invocation -> {
                        sent.add(new Sent(invocation.getArgument(0), invocation.getArgument(1)));
                        return CombatAttackApplicationResult.appliedResult();
                    });
            scheduler.when(() -> AgentSchedulerRuntime.afterDelay(anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> later);
            scheduler.when(() -> AgentSchedulerRuntime.afterDelay(
                            any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        scheduled.add(new Scheduled(invocation.getArgument(1), invocation.getArgument(2)));
                        return later;
                    });
            finalAttack.when(AgentFinalAttackProc::chance).thenReturn(chance);
            return body.get();
        }
    }
}
