package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Base defect from the 2026-10-03 smoke (all modes): once the leader held ten rice cakes the run went to
 * DELIVERING_CAKES and stopped defending; the companions wandered to the upper floors, bats killed the Moon Bunny while
 * the leader stood at Growlie with all ten, and the whole party was exiled. The guards keep guarding until the clear.
 */
class AgentHpqDeliveringDefenseTest {
    private final HpqRunFixture run = new HpqRunFixture();

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aHumanLeaderWalkingTheCakesToGrowlieKeepsHisGuards() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(2, "Mira", new Point(-150, -188));
        Character tobi = run.companion(3, "Tobi", new Point(600, 83));
        Character pip = run.companion(4, "Pip", new Point(650, 83));
        run.holds(ben, AgentHpqDefinition.RICE_CAKE, 10);
        run.monster(HpqRunFixture.STAGE_MOBS, new Point(-140, -188));
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DELIVERING_CAKES, ben, mira, tobi, pip);

        AgentHpqCoordinator.tick(session, 2_000L);

        // Mira has a bat on her: she fights it. Tobi has none near: he goes back to his post.
        verify(run.actions).grind(run.entry(mira), Set.of(HpqRunFixture.STAGE_MOBS));
        verify(run.actions).navigate(eq(run.entry(tobi)), any(Point.class), eq(true));
        assertEquals(AgentHpqSession.Phase.DELIVERING_CAKES, session.phase());
    }

    @Test
    void anAgentLeaderTakesTheCakesToGrowlieWhileTheOthersKeepGuarding() {
        Character mira = run.companion(2, "Mira", new Point(0, -210));
        Character tobi = run.companion(3, "Tobi", new Point(-150, -188));
        Character pip = run.companion(4, "Pip", new Point(650, 83));
        run.holds(mira, AgentHpqDefinition.RICE_CAKE, 10);
        run.monster(HpqRunFixture.STAGE_MOBS, new Point(-140, -188));
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DELIVERING_CAKES, mira, tobi, pip);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(429, 83), true);
        verify(run.actions, never()).grind(eq(run.entry(mira)), any());
        verify(run.actions).grind(run.entry(tobi), Set.of(HpqRunFixture.STAGE_MOBS));
        verify(run.actions).navigate(eq(run.entry(pip)), any(Point.class), eq(true));
    }
}
