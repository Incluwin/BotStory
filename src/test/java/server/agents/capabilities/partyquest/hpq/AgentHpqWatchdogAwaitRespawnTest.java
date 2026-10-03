package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The watchdog with every companion down (plan step 3). No companion can run the session, but in a companion run the
 * stock respawn will carry them out where their host taps them out, so it waits while one has been down less than
 * MEMBER_RECOVERY_TIMEOUT_MS and three still stand (here three humans); short-handed it ends the run WENT_DOWN at
 * once, said by the downed companion. A stock run fails at once, as it always did.
 */
class AgentHpqWatchdogAwaitRespawnTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(429, 83));
    private final Character ann = run.human(5, "Ann", new Point(429, 83));
    private final Character cal = run.human(6, "Cal", new Point(429, 83));
    private final Character mira = run.companion(2, "Mira", new Point(-183, -188));
    private final Character tobi = run.companion(3, "Tobi", new Point(-293, -188));

    @AfterEach
    void tearDown() {
        run.close();
    }

    private void bothDown() {
        when(mira.getHp()).thenReturn(0);
        when(tobi.getHp()).thenReturn(0);
    }

    @Test
    void itWaitsOutTheRecoveryTimeoutWhileThreeHumansStand() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, ann, cal, mira, tobi);
        bothDown();

        AgentHpqWatchdogRuntime.tick(session, 2_000L);
        AgentHpqWatchdogRuntime.tick(session, 46_999L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());

        AgentHpqWatchdogRuntime.tick(session, 47_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "we can't finish this one. let's head out.");
    }

    @Test
    void shortHandedItEndsTheRunAtOnceWentDown() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, ann, mira, tobi);
        bothDown();

        AgentHpqWatchdogRuntime.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "sorry, i went down. we need three for this, so let's head out.");
        verify(run.event).dispose();
    }

    @Test
    void aStockRunWithNoStandingAgentFailsAtOnce() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, ann, cal, mira, tobi);
        bothDown();

        AgentHpqWatchdogRuntime.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        assertEquals("No live Agent execution participant remains", session.failure());
        run.saidNothing();
    }
}
