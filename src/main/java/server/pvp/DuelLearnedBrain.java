package server.pvp;

import client.Character;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentSkillAttackPlanRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A think of a bot with a learner's seat (docs/PVP_PLAN.md V2.3): it lists what is legal now, asks the seat, and either
 * moves, buffs or drinks itself, or hands {@link DuelBrain} its attack skills with the chosen one first, so every swing
 * still goes through the prior brain's gates (plan, box, cooldown, ammo, MP).
 */
final class DuelLearnedBrain {
    /** A foe whose last attack packet came within this long is mid-swing. */
    static final long SWINGING_MS = 500L;
    static final int RETREAT_PX = 160;
    static final int LEVEL_PROBE_PX = 220;
    /** An action that is no swing and no move is thought over again this soon. */
    static final long FREE_ACTION_MS = 60L;

    private DuelLearnedBrain() {
    }

    /** The order to try attack skills in, or null when this think did something else instead. */
    static List<Integer> decide(AgentRuntimeEntry entry, DuelField.Fighter self, DuelField.Fighter foe, long now) {
        Character bot = self.chr;
        Character target = foe.chr;
        DuelBrain.Mind mind = self.mind;
        Point at = bot.getPosition();
        Point aim = target.getPosition();
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        var map = bot.getMap();
        Function<Point, Point> ground = p -> gateway.groundPoint(map, p);
        boolean mayAttack = DuelBrain.mayAttackFrom(bot.getStance());
        boolean free = gateway.grounded(bot) && mayAttack;

        Rectangle body = PvpHitResolver.hurtbox(aim, 0);
        int reach = 0;
        List<Integer> swings = new ArrayList<>();
        for (int skillId : mind.skills) {
            AgentAttackPlan plan = AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, aim, skillId);
            Rectangle box = plan == null ? null : PvpHitResolver.attackBox(bot, skillId, DuelBrain.effectOf(bot, skillId),
                    plan.route == AgentAttackRoute.RANGED, plan.route == AgentAttackRoute.MAGIC, (plan.stance & 0x80) != 0);
            if (box == null) {
                continue;
            }
            reach = Math.max(reach, DuelBrain.reachToward(box, at, aim));
            if (mayAttack && box.intersects(body)) {
                swings.add(skillId);
            }
        }
        DuelField field = DuelRegistry.of(bot.getId());
        boolean tonic = field != null && field.tonicReady(bot, now);
        boolean buffDue = mind.buffs.stream().anyMatch(id -> now >= mind.buffDueMs.getOrDefault(id, 0L));
        Point back = free ? retreatSpot(at, aim, ground) : null;
        Point other = free ? otherLevel(at, ground) : null;
        int hop = free ? DuelBrain.hopDirection(at, aim, Math.max(reach, DuelBrain.HOP_PX), ground) : 0;

        List<Integer> legal = new ArrayList<>(swings);
        legal.add(DuelLearner.APPROACH);
        addIf(legal, DuelLearner.RETREAT, back != null);
        addIf(legal, DuelLearner.JUMP, hop != 0);
        addIf(legal, DuelLearner.LEVEL, other != null);
        addIf(legal, DuelLearner.BUFF, buffDue);
        addIf(legal, DuelLearner.DRINK, tonic && bot.getHp() < bot.getCurrentMaxHp());
        addIf(legal, DuelLearner.WAIT, free);
        int prior = legal.contains(DuelLearner.DRINK) && DuelBrain.wantsTonic(bot.getHp(), bot.getCurrentMaxHp())
                ? DuelLearner.DRINK : buffDue ? DuelLearner.BUFF : swings.isEmpty() ? DuelLearner.APPROACH : swings.getFirst();
        DuelLearner.Situation situation = new DuelLearner.Situation(Math.abs(at.x - aim.x), reach, at.y - aim.y,
                share(bot.getHp(), bot.getCurrentMaxHp()), share(target.getHp(), target.getCurrentMaxHp()),
                share(bot.getMp(), bot.getCurrentMaxMp()), ranged(target.getJob() == null ? 0 : target.getJob().getId()),
                now - foe.lastAttackMs < SWINGING_MS, tonic);

        int action = mind.seat.choose(situation, legal, prior, score(self, foe), now);
        switch (action) {
            case DuelLearner.RETREAT -> gateway.navigate(entry, back, true);
            case DuelLearner.LEVEL -> gateway.navigate(entry, other, true);
            case DuelLearner.JUMP -> {
                gateway.jump(entry, hop);
                mind.nextThinkMs = now + DuelBrain.HOP_MS;
            }
            case DuelLearner.WAIT -> gateway.stop(entry);
            case DuelLearner.DRINK -> {
                self.stats.drinkTries.incrementAndGet();
                field.drinkTonic(bot, now);
                mind.nextThinkMs = now + FREE_ACTION_MS;
            }
            case DuelLearner.BUFF -> {
                if (!DuelBrain.buffUp(entry, bot, mind, self.stats, now)) {
                    mind.nextThinkMs = now + FREE_ACTION_MS; // each buff due was tried and is put off: think again
                }
            }
            case DuelLearner.APPROACH -> {
                self.stats.walks.incrementAndGet();
                mind.inReach = false;
                gateway.navigate(entry, DuelBrain.standPoint(at, aim, reach, ground), true);
            }
            default -> {
                return chosenFirst(action, mind.skills);
            }
        }
        return null;
    }

    /** The chosen skill, then the rest strongest first: what DuelBrain falls back on when the chosen one will not go. */
    static List<Integer> chosenFirst(int chosen, List<Integer> skills) {
        List<Integer> order = new ArrayList<>(List.of(chosen));
        skills.stream().filter(id -> id != chosen).forEach(order::add);
        return order;
    }

    private static void addIf(List<Integer> legal, int action, boolean can) {
        if (can) {
            legal.add(action);
        }
    }

    /** Damage dealt less taken so far, each as a share of its victim's max HP. */
    static double score(DuelField.Fighter self, DuelField.Fighter foe) {
        return share(self.dealt, foe.chr.getCurrentMaxHp()) - share(self.taken, self.chr.getCurrentMaxHp());
    }

    private static double share(long part, int whole) {
        return whole <= 0 ? 0d : part / (double) whole;
    }

    /** Magicians, bowmen, the claw line (41x, 14xx) and the gun line (52x): they fight from away. */
    static boolean ranged(int jobId) {
        int line = jobId / 100 % 10;
        return line == 2 && jobId < 2000 || line == 3 || jobId / 10 == 41 || jobId / 10 == 52 || jobId / 100 == 14;
    }

    /** A spot {@link #RETREAT_PX} further from the foe on the fighter's own level, or the nearest short of it; or null. */
    static Point retreatSpot(Point at, Point aim, Function<Point, Point> ground) {
        int away = at.x >= aim.x ? 1 : -1;
        for (int step = RETREAT_PX; step >= 60; step -= 50) {
            Point floor = ground.apply(new Point(at.x + away * step, at.y));
            if (floor != null && Math.abs(floor.y - at.y) <= DuelBrain.LEVEL_TOLERANCE_PX) {
                return floor;
            }
        }
        return null;
    }

    /** The ground over or under the fighter's own spot on another level, the upper one first; null when there is none. */
    static Point otherLevel(Point at, Function<Point, Point> ground) {
        for (int from : new int[] {-LEVEL_PROBE_PX, DuelBrain.LEVEL_TOLERANCE_PX + 5}) {
            Point floor = ground.apply(new Point(at.x, at.y + from));
            if (floor != null && Math.abs(floor.y - at.y) > DuelBrain.LEVEL_TOLERANCE_PX
                    && Math.abs(floor.y - at.y) <= LEVEL_PROBE_PX) {
                return floor;
            }
        }
        return null;
    }
}
