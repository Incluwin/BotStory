package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * With every Agent of a companion run down there is no Agent tick left, so only the watchdog ticks the run; it waits
 * for the respawn instead of failing first (P6 WP2, addendum fix 4).
 */
class WatchdogRespawnGraceTest {
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");

    private KpqRunFixture run;
    private Character ben;
    private Character ann;
    private Character tobi;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        ann = run.human(2, "Ann");
        tobi = run.companion(4, "Tobi");
        when(tobi.getHp()).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void anAdoptedRunWhoseOnlyCompanionIsDownWaitsForItsRespawn() {
        AgentKpqSession session = run.session(
                true, AgentKpqSession.Phase.STAGE_2, ben, ann, run.human(3, "Cid"), tobi);

        AgentKpqWatchdogRuntime.tick(session, 5_000L);

        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        assertTrue(session.member(4).down());
        assertEquals(5_000L, session.member(4).downSinceMs());
    }

    @Test
    void pastTheTimeoutItStillFailsAsBefore() {
        AgentKpqSession session = run.session(
                true, AgentKpqSession.Phase.STAGE_2, ben, ann, run.human(3, "Cid"), tobi);
        AgentKpqWatchdogRuntime.tick(session, 5_000L);

        AgentKpqWatchdogRuntime.tick(session, 5_000L + TIMEOUT_MS);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("No live Agent execution participant remains", session.failure());
    }

    @Test
    void aRunThatIsNotAdoptedFailsAtOnce() {
        AgentKpqSession session = run.session(
                false, AgentKpqSession.Phase.STAGE_2, ben, ann, run.human(3, "Cid"), tobi);

        AgentKpqWatchdogRuntime.tick(session, 5_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("No live Agent execution participant remains", session.failure());
    }

    @Test
    void aThreeMemberRunEndsAtOnceWithTheThreeOfUsLine() {
        // Only two would stand; the respawn would end the event for everyone anyway.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, tobi);

        AgentKpqWatchdogRuntime.tick(session, 5_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        assertEquals("Tobi went down, and KPQ needs three of us", session.failure());
    }
}
