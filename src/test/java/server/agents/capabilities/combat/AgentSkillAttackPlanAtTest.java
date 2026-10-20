package server.agents.capabilities.combat;

import client.Character;
import constants.skills.ChiefBandit;
import org.junit.jupiter.api.Test;
import server.agents.integration.SkillGateway;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B3: a skill planned at a point with no monster in it (a sigil) refuses what a swing at nothing cannot
 * be: Meso Explosion (it needs mesos on the floor), no point, no skill, and a skill the Agent does not hold.
 */
class AgentSkillAttackPlanAtTest {
    @Test
    void mesoExplosionNoPointAndNoSkillAreNeverPlannedAtAPoint() {
        Character bot = mock(Character.class);
        SkillGateway skills = mock(SkillGateway.class);
        when(bot.getPosition()).thenReturn(new Point(0, 0));

        assertNull(AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, new Point(40, 0), ChiefBandit.MESO_EXPLOSION,
                skills));
        assertEquals("needs-mesos", AgentSkillAttackPlanRuntime.lastRejection());
        assertNull(AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, null, 4_111_005, skills));
        assertEquals("invalid", AgentSkillAttackPlanRuntime.lastRejection());
        assertNull(AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, new Point(40, 0), 0, skills));
        assertEquals("invalid", AgentSkillAttackPlanRuntime.lastRejection());
    }

    @Test
    void aSkillTheAgentDoesNotHoldIsRefusedAtReadiness() {
        Character bot = mock(Character.class);
        SkillGateway skills = mock(SkillGateway.class); // no such skill
        when(bot.getPosition()).thenReturn(new Point(0, 0));

        assertNull(AgentSkillAttackPlanRuntime.planSkillAttackAt(bot, new Point(40, 0), 4_111_005, skills));
        assertEquals(true, AgentSkillAttackPlanRuntime.lastRejection().startsWith("readiness:"),
                AgentSkillAttackPlanRuntime.lastRejection());
    }
}
