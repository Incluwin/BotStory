package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.Band;
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
 * 70 % HP or holds POT_STOP potions again. A re-registered member (50 HP, no potions) rests by the same rule.
 */
class AgentLpqStageNineRestTest {
    private static final Band BAND = AgentLpqStageNineBossPolicy.band();
    private static final Floor FLOOR = AgentLpqCoordinator.stageNineFloor();
    private static final double REST = AgentLpqStageNineBossPolicy.restHpFraction();
    private static final double RESUME = AgentLpqStageNineBossPolicy.resumeHpFraction();
    private static final int POT_STOP = 10;
    private static final int MAX_HP = 2_000;

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
                AgentLpqStageNineBossPolicy.restPoint(new Point(600, 184), new Point(300, 184), null, BAND, FLOOR));
        assertEquals(new Point(-250, 184),
                AgentLpqStageNineBossPolicy.restPoint(new Point(900, 184), new Point(1_000, 184), null, BAND, FLOOR),
                "the near right end is 250 px from a boss at 900: the far end");
        assertEquals(new Point(1_150, 184),
                AgentLpqStageNineBossPolicy.restPoint(new Point(0, 184), new Point(-100, 184), null, BAND, FLOOR),
                "the left end is within 345 px of a boss at 0");
        Point stored = new Point(-250, 184);
        assertEquals(stored, AgentLpqStageNineBossPolicy.restPoint(new Point(95, 184), new Point(-200, 184), stored,
                BAND, FLOOR), "345 px away: kept");
        assertEquals(new Point(1_150, 184), AgentLpqStageNineBossPolicy.restPoint(new Point(90, 184),
                new Point(-200, 184), stored, BAND, FLOOR), "340 px away: moved");
    }

    @Test
    void theRestCheckComesFirstForEveryLivingMember() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        String boss = AgentLpqStageNineStationTest.method(source, "    private static void boss(");
        String branch = AgentLpqStageNineStationTest.block(boss, boss.indexOf("if (alisharAlive) {"));
        int hp = branch.indexOf("if (agent.getHp() <= 0) continue;");
        int rest = branch.indexOf("if (restAtStageNine(session, member, entry, agent, alishar.getPosition())) continue;");
        int fight = branch.indexOf("fightAlishar(");
        assertTrue(hp > 0 && rest > hp && fight > rest, "HP check, then the rest check, then the fight");

        String body = AgentLpqStageNineStationTest.method(source, "    private static boolean restAtStageNine(");
        String flat = body.replaceAll("\\s+", "");
        assertTrue(flat.contains("AgentLpqStageNineBossPolicy.resting(wasResting,potions,"
                + "server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP,agent.getHp(),agent.getMaxHp(),"));
        assertTrue(flat.contains("intpotions=ACTIONS.hpPotionCount(agent);"));
        assertFalse(body.contains("ACTIONS.grind"), "a resting member does not fight");
        for (String rescue : AgentLpqStageNineStationTest.RESCUE_CALLS) {
            assertFalse(body.contains(rescue), "no rescue: " + rescue);
        }
    }
}
