package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.ThunderBreaker;
import server.StatEffect;
import server.agents.capabilities.combat.data.AgentMobHitboxProvider;
import server.agents.capabilities.combat.learning.AgentCombatLearner;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * 2026-10-30: Thunder Breaker Spark (15111006), the follow-up a v83 client sends on its own. A real client (Lv255
 * Thunder Breaker, Spark 20 on, knuckle basic attacks; 24 sparks in two runs of monitored packets) followed every
 * basic swing that listed a monster, 3-30 ms later, with a RANGED_ATTACK carrying Spark: display 0x80, action 0x2C
 * (alert5), the swing's facing and speed, no ammo, one line on each of up to mobCount monsters. A swing that hit
 * nothing got none, and while the buff is on the swing itself carries display 0x80 (0x00 once it is off).
 *
 * <p>The spark jumps on from the struck monster and never back to it. Its first jump stayed near it (37-172 px between
 * origins, so 150 px of range around the two bodies), and when nothing was that near the spark went out empty (3 of
 * 24). Its later jumps went anywhere on the map and to either side (up to 1198 px a jump, 1479 px from the Thunder
 * Breaker), and every spark with a first target had all mobCount. Which monster a jump takes the samples do not
 * settle (not the nearest: from 1508 it took 1394 before 1437), so an Agent takes any eligible one at random. A
 * target's delay is 174 ms plus 2 ms per pixel the spark has travelled to it (63 targets, within 55 ms).</p>
 */
public final class AgentSparkProc {
    /** The display bit a v83 client sets on a swing, and on the spark, while Spark is on. */
    static final int SPARK_DISPLAY = 0x80;
    private static final String SPARK_ACTION = "alert5";
    private static final int DELAY_BASE_MS = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentSparkProc.DELAY_BASE_MS");
    private static final int DELAY_MS_PER_PX = config.AgentTuning.intValue("server.agents.capabilities.combat.AgentSparkProc.DELAY_MS_PER_PX");

    private AgentSparkProc() {
    }

    static boolean active(Character bot) {
        return bot != null && bot.getBuffedValue(BuffStat.SPARK) != null
                && bot.getSkillLevel(ThunderBreaker.SPARK) > 0;
    }

    /** A basic close-range swing; the only attack the captures show a spark after. */
    static boolean ridesOn(AgentAttackPlan swing) {
        return swing != null && swing.skillId == 0 && swing.isCloseRangeRoute();
    }

    /** The display byte {@code swing} goes out with: the Spark bit set on a basic swing while the buff is on. */
    static int swingDisplay(Character bot, AgentAttackPlan swing) {
        return ridesOn(swing) && active(bot) ? swing.display | SPARK_DISPLAY : swing.display;
    }

    /** After a committed basic swing that listed {@code struck}: the spark it sets off, sent now. */
    static void afterSwing(AgentRuntimeEntry entry, Character bot, AgentAttackPlan swing, Monster struck) {
        if (!ridesOn(swing) || struck == null || struck.getPosition() == null || !active(bot)) {
            return;
        }
        Skill spark = SkillFactory.getSkill(ThunderBreaker.SPARK);
        int level = bot.getSkillLevel(ThunderBreaker.SPARK);
        StatEffect effect = spark == null ? null : spark.getEffect(level);
        if (effect == null) {
            return;
        }
        Rectangle firstJump = around(AgentMobHitboxProvider.getInstance().getMobBounds(struck), struck.getPosition(),
                effect.getRange());
        List<Monster> targets = chain(struck, AgentMapPerception.monsters(bot.getMap()), effect.getMobCount(),
                candidate -> AgentCombatHitboxIntersection.intersectsMonster(firstJump, candidate),
                candidate -> AgentCombatAttackRuntime.mayFollowUpOn(entry, bot, candidate),
                bound -> ThreadLocalRandom.current().nextInt(bound));
        String action = spark.resolveAnimationAction(level, false);
        int actionId = AgentAttackExecutionProvider.bodyActionId(action, SPARK_ACTION);
        AgentAttackPlan plan = new AgentAttackPlan(ThunderBreaker.SPARK, level, 1, firstJump, targets,
                AgentAttackRoute.RANGED, SPARK_DISPLAY, actionId, actionId, swing.stance & 0x80, swing.speed,
                DELAY_BASE_MS, 0, null);
        AgentCombatAttackRuntime.commitFollowUp(entry, bot, plan,
                delays(struck.getPosition(), targets.stream().map(Monster::getPosition).toList()),
                AgentCombatLearner.decisions(entry));
    }

    /**
     * Who the spark from {@code struck} hits, in the order it jumps: first one of the eligible monsters
     * {@code withinFirstJump} takes, then any other eligible ones, {@code pick} choosing each among the rest, up to
     * {@code mobCount}. None when nothing is within the first jump. Never {@code struck}.
     */
    static List<Monster> chain(Monster struck, Iterable<Monster> candidates, int mobCount,
                               Predicate<Monster> withinFirstJump, Predicate<Monster> eligible,
                               IntUnaryOperator pick) {
        List<Monster> open = new ArrayList<>();
        for (Monster candidate : candidates) {
            if (candidate != null && candidate != struck && candidate.getObjectId() != struck.getObjectId()
                    && candidate.getPosition() != null && eligible.test(candidate)) {
                open.add(candidate);
            }
        }
        List<Monster> near = open.stream().filter(withinFirstJump).toList();
        if (near.isEmpty() || mobCount <= 0) {
            return List.of();
        }
        List<Monster> chain = new ArrayList<>(mobCount);
        Monster first = near.get(pick.applyAsInt(near.size()));
        chain.add(first);
        open.remove(first);
        while (chain.size() < mobCount && !open.isEmpty()) {
            chain.add(open.remove(pick.applyAsInt(open.size())));
        }
        return List.copyOf(chain);
    }

    /** Each target's delay: the spark leaves {@code origin} and travels the chain's path at half a pixel a ms. */
    static List<Integer> delays(Point origin, List<Point> path) {
        List<Integer> delays = new ArrayList<>(path.size());
        double travelled = 0;
        Point from = origin;
        for (Point to : path) {
            travelled += from.distance(to);
            delays.add((int) Math.min(Short.MAX_VALUE, Math.round(DELAY_BASE_MS + DELAY_MS_PER_PX * travelled)));
            from = to;
        }
        return delays;
    }

    /** The struck monster's body (its origin when it has none) with {@code range} px all round. */
    static Rectangle around(Rectangle body, Point origin, int range) {
        Rectangle box = body != null ? new Rectangle(body) : new Rectangle(origin.x, origin.y, 1, 1);
        box.grow(Math.max(0, range), Math.max(0, range));
        return box;
    }
}
