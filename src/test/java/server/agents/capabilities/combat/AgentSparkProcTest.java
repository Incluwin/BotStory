package server.agents.capabilities.combat;

import client.BuffStat;
import client.Job;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.ThunderBreaker;
import net.server.channel.handlers.AbstractDealDamageHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-30: Agents never sent the Spark a real v83 client sends after a Thunder Breaker's swing. In monitored packets
 * of a Lv255 Thunder Breaker with Spark 20 on, every basic swing that listed a monster was followed 3-30 ms later by a
 * RANGED_ATTACK with Spark (display 0x80, action 0x2C, the swing's facing, one line on each of up to 3 monsters), and
 * the swings themselves carried display 0x80 while the buff was on. The spark's first jump stays near the struck
 * monster, its later ones go anywhere, and each target's delay grows 2 ms a pixel along its path.
 */
class AgentSparkProcTest {
    private static final int KNUCKLE = 1_482_000;
    private static final int SPARK_LEVEL = 20;

    @BeforeAll
    static void loadSkills() {
        SkillFactory.loadAllSkills();
    }

    @Test
    void aThunderBreakersBasicSwingIsFollowedByASparkOnTheMonstersAroundTheStruckOne() {
        AgentProcFixture fight = thunderBreaker().buff(BuffStat.SPARK, 1);
        Monster struck = fight.monster(5_000, 140);
        fight.monster(5_001, 240); // 100 px past it: the only monster within the spark's first jump
        fight.monster(5_002, 900);
        fight.monster(5_003, -500);
        fight.monster(5_004, 1_300);

        assertTrue(fight.swing(basicSwing(struck)).committed());

        assertEquals(2, fight.sent.size(), "the swing, then its spark");
        AbstractDealDamageHandler.AttackInfo swing = fight.sent.get(0).attack();
        assertEquals(AgentAttackRoute.CLOSE, fight.sent.get(0).route());
        assertEquals(0x80, swing.display, "a swing carries the Spark bit while the buff is on");
        AgentProcFixture.Sent spark = fight.sent.get(1);
        assertEquals(AgentAttackRoute.RANGED, spark.route());
        assertEquals(ThunderBreaker.SPARK, spark.attack().skill);
        assertEquals(SPARK_LEVEL, spark.attack().skilllevel);
        assertTrue(spark.attack().ranged);
        assertEquals(0x80, spark.attack().display);
        assertEquals(0x2C, spark.attack().direction, "alert5, Spark's own action");
        assertEquals(0x00, spark.attack().stance, "the swing's facing (right)");
        assertEquals(3, spark.attack().speed, "the swing's speed");
        assertEquals(0x31, spark.attack().numAttackedAndDamage, "3 monsters (mobCount at 20), one line each");
        List<Integer> chain = List.copyOf(spark.attack().targets.keySet());
        assertEquals(5_001, chain.get(0), "first the one beside the struck monster");
        assertTrue(Set.of(5_002, 5_003, 5_004).containsAll(chain.subList(1, 3)), "then any others, however far");
        assertFalse(chain.contains(5_000), "never back to the struck monster");
        assertEquals(174 + 2 * 100, spark.attack().targets.get(5_001).delay());
    }

    @Test
    void withoutTheBuffASwingGoesOutAloneAndPlain() {
        AgentProcFixture fight = thunderBreaker();
        Monster struck = fight.monster(5_000, 140);
        fight.monster(5_001, 240);

        assertTrue(fight.swing(basicSwing(struck)).committed());

        assertEquals(1, fight.sent.size());
        assertEquals(0x00, fight.sent.get(0).attack().display);
    }

    @Test
    void withNothingNearTheStruckMonsterTheSparkGoesOutEmpty() {
        AgentProcFixture fight = thunderBreaker().buff(BuffStat.SPARK, 1);
        Monster struck = fight.monster(5_000, 140);
        fight.monster(5_002, 900);

        assertTrue(fight.swing(basicSwing(struck)).committed());

        assertEquals(2, fight.sent.size(), "13:19:17.518 in the capture: a spark with no monster");
        assertEquals(0, fight.sent.get(1).attack().numAttacked);
        assertEquals(0x01, fight.sent.get(1).attack().numAttackedAndDamage);
        assertTrue(fight.sent.get(1).attack().targets.isEmpty());
    }

    @Test
    void onlyABasicCloseRangeSwingCarriesASpark() {
        Monster target = mock(Monster.class);
        assertTrue(AgentSparkProc.ridesOn(basicSwing(target)));
        assertFalse(AgentSparkProc.ridesOn(new AgentAttackPlan(ThunderBreaker.CORKSCREW_BLOW, 20, 1, null,
                List.of(target), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 3, 300, 600, null)), "not captured after skills");
        assertFalse(AgentSparkProc.ridesOn(new AgentAttackPlan(0, 0, 1, null, List.of(target),
                AgentAttackRoute.RANGED, 0, 0, 0, 0, 3, 300, 600, null)));
    }

    @Test
    void theChainStartsBesideTheStruckMonsterAndTakesTheRestFromAnywhere() {
        Monster struck = monsterAt(1, 1_508);
        Monster beside = monsterAt(2, 1_437);
        Monster farLeft = monsterAt(3, 300);
        Monster farRight = monsterAt(4, 2_000);
        Monster dead = monsterAt(5, 1_450);

        List<Monster> chain = AgentSparkProc.chain(struck, List.of(farLeft, struck, beside, dead, farRight), 3,
                candidate -> Math.abs(candidate.getPosition().x - 1_508) <= 150,
                candidate -> candidate != dead, bound -> 0);

        assertEquals(List.of(beside, farLeft, farRight), chain);
        assertEquals(List.of(), AgentSparkProc.chain(struck, List.of(farLeft, farRight), 3,
                candidate -> Math.abs(candidate.getPosition().x - 1_508) <= 150, candidate -> true, bound -> 0),
                "nothing within the first jump: no target at all");
        assertEquals(List.of(beside), AgentSparkProc.chain(struck, List.of(struck, beside), 3,
                candidate -> true, candidate -> true, bound -> 0), "fewer monsters than mobCount");
    }

    @Test
    void theDelaysFollowTheSparksPathAsCaptured() {
        // 13:19:01.598: from (1628,215) to 1567, 771 and 665; the client sent 308, 1890 and 2104
        assertWithin(List.of(308, 1_890, 2_104), AgentSparkProc.delays(new Point(1_628, 215),
                List.of(new Point(1_567, 215), new Point(771, 215), new Point(665, 215))));
        // 13:20:36.312: its second jump went back past the Thunder Breaker; the client sent 444, 970 and 2096
        assertWithin(List.of(444, 970, 2_096), AgentSparkProc.delays(new Point(1_287, 215),
                List.of(new Point(1_160, 215), new Point(1_410, 215), new Point(847, 215))));
    }

    @Test
    void theFirstJumpReachesRangeAroundTheStruckBody() {
        assertEquals(new Rectangle(-170, -200, 340, 350),
                AgentSparkProc.around(new Rectangle(-20, -50, 40, 50), new Point(0, 0), 150));
        assertEquals(new Rectangle(-150, -150, 301, 301), AgentSparkProc.around(null, new Point(0, 0), 150));
    }

    private static void assertWithin(List<Integer> captured, List<Integer> made) {
        assertEquals(captured.size(), made.size());
        for (int i = 0; i < captured.size(); i++) {
            int index = i;
            assertTrue(Math.abs(captured.get(i) - made.get(i)) <= 55,
                    () -> "target " + index + ": made " + made.get(index) + ", captured " + captured.get(index));
        }
    }

    private static AgentProcFixture thunderBreaker() {
        return new AgentProcFixture(Job.THUNDERBREAKER3, KNUCKLE, WeaponType.KNUCKLE)
                .skill(ThunderBreaker.SPARK, SPARK_LEVEL);
    }

    /** A knuckle stab (stabO1) facing right at speed 3, at a snail 40 px ahead. */
    private static AgentAttackPlan basicSwing(Monster target) {
        return new AgentAttackPlan(0, 0, 1, new Rectangle(100, 150, 80, 70), List.of(target),
                AgentAttackRoute.CLOSE, 0, 0x10, 0x10, AgentAttackExecutionProvider.attackPacketStance(false), 3,
                289, 661, null);
    }

    private static Monster monsterAt(int objectId, int x) {
        Monster monster = mock(Monster.class);
        when(monster.getObjectId()).thenReturn(objectId);
        when(monster.getPosition()).thenReturn(new Point(x, 215));
        return monster;
    }
}
