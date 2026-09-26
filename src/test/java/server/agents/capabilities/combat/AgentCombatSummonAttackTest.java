package server.agents.capabilities.combat;

import client.Skill;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatSummonAttackTest {
    /** Bahamut: attack1 box lt (-200,-150) rb (0,30), in front of the summon. */
    private static final Skill.SummonAttack BAHAMUT = new Skill.SummonAttack(1620, 990, 3,
            new Rectangle(-200, -150, 200, 180), 0, new Point(0, 0));
    /** Silver Hawk: flies to anything within 270 of its body. */
    private static final Skill.SummonAttack HAWK = new Skill.SummonAttack(700, 100, 1,
            new Rectangle(-200, -150, 400, 300), 270, new Point(-24, -37));

    @Test
    void aFrontBoxSummonOnlyHitsWhatIsInFrontOfIt() {
        Point summon = new Point(1000, 0);
        assertTrue(AgentCombatSummonRuntime.inRange(summon, true, BAHAMUT, new Point(850, 0)));
        assertFalse(AgentCombatSummonRuntime.inRange(summon, true, BAHAMUT, new Point(1150, 0)));
        // Facing right, the same box is mirrored.
        assertTrue(AgentCombatSummonRuntime.inRange(summon, false, BAHAMUT, new Point(1150, 0)));
        assertFalse(AgentCombatSummonRuntime.inRange(summon, false, BAHAMUT, new Point(850, 0)));
    }

    @Test
    void aFlyingSummonReachesAnythingWithinItsRadius() {
        Point summon = new Point(0, 0);
        assertTrue(AgentCombatSummonRuntime.inRange(summon, true, HAWK, new Point(200, 0)));
        assertTrue(AgentCombatSummonRuntime.inRange(summon, true, HAWK, new Point(-240, 0)));
        assertFalse(AgentCombatSummonRuntime.inRange(summon, true, HAWK, new Point(0, 400)));
    }

    @Test
    void masteryFollowsTheClientFormula() {
        assertEquals(0.65, AgentCombatSummonRuntime.masteryFloor(10), 1e-9);   // Bahamut: 5 x 10 + 15 = 65%
        assertEquals(0.5, AgentCombatSummonRuntime.masteryFloor(0), 1e-9);
        assertEquals(0.95, AgentCombatSummonRuntime.masteryFloor(30), 1e-9);
    }
}
