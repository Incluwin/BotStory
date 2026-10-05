package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Floor;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fix 6: a living stage-9 member out of potions backs off out of Alishar's reach and stands still, with the
 * generic out-of-potions stop's own thresholds (fewer than POT_STOP HP potions and HP under 40 %), until it is back to
 * 70 % HP or holds POT_STOP potions again. A re-registered member (50 HP, no potions) rests by the same rule. Its rest
 * point is kept while Alishar stays out of his reach (REACH_PX), and it crosses him only once he is within it.
 */
class AgentLpqStageNineRestTest {
    private static final Floor FLOOR = AgentLpqCoordinator.stageNineFloor();
    private static final double REST = AgentLpqStageNineBossPolicy.restHpFraction();
    private static final double RESUME = AgentLpqStageNineBossPolicy.resumeHpFraction();
    private static final int POT_STOP = 10;
    private static final int MAX_HP = 2_000;
    private static final int REACH = AgentLpqStageNineBossPolicy.reachPx();

    private static boolean starts(int potions, int hp) {
        return AgentLpqStageNineBossPolicy.resting(false, potions, POT_STOP, hp, MAX_HP, REST, RESUME);
    }

    private static boolean keeps(int potions, int hp) {
        return AgentLpqStageNineBossPolicy.resting(true, potions, POT_STOP, hp, MAX_HP, REST, RESUME);
    }

    @Test
    void theThresholdsAreTheGenericStopsAndTheResumeIsHigher() {
        assertEquals(0.4, REST, "the generic out-of-potions stop's 0.4");
        assertTrue(RESUME > REST);
        assertEquals(POT_STOP, server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP);
    }

    @Test
    void whoStartsResting() {
        assertTrue(starts(9, 780), "9 potions, 39 %");
        assertFalse(starts(9, 820), "9 potions, 41 %");
        assertFalse(starts(10, 200), "10 potions, 10 %: still potting");
        assertFalse(starts(-1, 200), "an unknown count never rests");
        assertTrue(starts(0, 50), "a re-registered member: 50 HP, no potions");
    }

    @Test
    void aRestingMemberRestsUntilSeventyPercentOrPotions() {
        assertTrue(keeps(9, 820), "41 %: still resting");
        assertTrue(keeps(0, 1_399), "69.95 %");
        assertFalse(keeps(0, 1_400), "70 %: back to the fight");
        assertFalse(keeps(10, 200), "potions again");
        assertFalse(keeps(-1, 200), "an unknown count never rests");
    }

    @Test
    void theRestPointIsAFloorEndOutOfReach() {
        assertEquals(new Point(-250, 184),
                AgentLpqStageNineBossPolicy.restPoint(new Point(600, 184), new Point(300, 184), null, FLOOR, REACH));
        assertEquals(new Point(-250, 184),
                AgentLpqStageNineBossPolicy.restPoint(new Point(900, 184), new Point(1_000, 184), null, FLOOR, REACH),
                "the near right end is 250 px from a boss at 900: the far end");
        assertEquals(new Point(1_150, 184),
                AgentLpqStageNineBossPolicy.restPoint(new Point(0, 184), new Point(-100, 184), null, FLOOR, REACH),
                "the left end is within his 320-px reach of a boss at 0");
        Point stored = new Point(-250, 184);
        assertEquals(stored, AgentLpqStageNineBossPolicy.restPoint(new Point(95, 184), new Point(-200, 184), stored,
                FLOOR, REACH), "345 px away: kept");
        assertEquals(stored, AgentLpqStageNineBossPolicy.restPoint(new Point(90, 184), new Point(-200, 184), stored,
                FLOOR, REACH), "340 px away, still out of his reach: kept");
        assertEquals(new Point(1_150, 184), AgentLpqStageNineBossPolicy.restPoint(new Point(70, 184),
                new Point(-200, 184), stored, FLOOR, REACH), "320 px away: inside his reach, moved");
    }

    /** One pass of a resting member out of potions (50 HP of 2000) standing at {@code x}, Alishar at {@code bossX}. */
    private static AgentLpqStageNineBossPolicy.Order restingPass(AgentLpqMemberState member, int x, int bossX) {
        AgentLpqStageNineBossPolicy.Order order = AgentLpqStageNineBossPolicy.order(member.stageNineState(),
                new AgentLpqStageNineBossPolicy.Pass(true, 0, true, new Point(x, 184), new Point(bossX, 184),
                        java.util.List.of(), 0, POT_STOP, 50, MAX_HP), FLOOR);
        member.applyStageNine(order.state());
        return order;
    }

    @Test
    void aRestingMemberKeepsItsRestPointWhileAlisharStaysOutOfHisReach() {
        AgentLpqMemberState member = new AgentLpqMemberState(762_401, AgentLpqMemberState.MemberType.AGENT);
        Point leftEnd = new Point(-250, 184);
        assertEquals(leftEnd, restingPass(member, -250, 200).point(), "450 px: it rests at its own end");
        // Alishar steps around the band's 345-px edge next to her: she stays put (340-350 px is outside his 320 px).
        for (int bossX : new int[]{96, 94, 96, 94, 95, 93, 97, 94, 90, 71}) {
            AgentLpqStageNineBossPolicy.Order order = restingPass(member, -250, bossX);
            assertEquals(leftEnd, order.point(), "boss " + bossX);
            assertEquals(AgentLpqStageNineBossPolicy.Move.REST, order.move(), "boss " + bossX);
        }
        AgentLpqStageNineBossPolicy.Order cornered = restingPass(member, -250, 70);
        assertEquals(new Point(1_150, 184), cornered.point(), "320 px: inside his reach, she leaves the corner");
        assertEquals(AgentLpqStageNineBossPolicy.Move.WALK_TO_REST, cornered.move());

        AgentLpqMemberState fresh = new AgentLpqMemberState(762_402, AgentLpqMemberState.MemberType.AGENT);
        assertEquals(leftEnd, restingPass(fresh, -200, 80).point(),
                "its own end 330 px from him is out of his reach: it does not cross him");
    }

    @Test
    void theRestCheckComesFirstForEveryLivingMember() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        String boss = AgentLpqStageNineStationTest.method(source, "    private static void boss(");
        String branch = AgentLpqStageNineStationTest.block(boss, boss.indexOf("if (alisharAlive) {"));
        int hp = branch.indexOf("if (agent.getHp() <= 0) continue;");
        assertTrue(hp > 0 && branch.indexOf("fightAlishar(") > hp, "only a living member rests or fights");

        // The order checks rest first, for melee members and shooters alike, with the yaml thresholds in their order.
        String policy = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqStageNineBossPolicy.java"))
                .replace("\r\n", "\n");
        String order = AgentLpqStageNineStationTest.method(policy, "    static Order order(");
        String flat = order.replaceAll("\\s+", "");
        assertTrue(flat.contains("booleanrest=resting(current.resting(),pass.hpPotions(),pass.potStop(),pass.hp(),"
                + "pass.maxHp(),REST_HP_FRACTION,RESUME_HP_FRACTION);"), flat);
        int rest = order.indexOf("if (rest) {");
        assertTrue(rest > 0 && order.indexOf("if (!pass.shooter())") > rest, "the rest check comes first");
        String restBlock = AgentLpqStageNineStationTest.block(order, rest);
        assertFalse(restBlock.contains("targets("), "a resting member does not fight");
    }
}
