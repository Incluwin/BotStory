package server.living.roster;

import client.Job;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The lost-run backstop (P6): a companion run whose KPQ session went away without a runEnded is let go by the
 * companion audit once it has been gone a minute, and not before. Only the session counts (addendum 8): a companion
 * that tapped out has left the session, and the run it left goes on.
 */
class LostRunSweepTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final AtomicLong now = new AtomicLong(100_000L);
    private CompanionAuditor auditor;
    private CompanionRun run;

    @BeforeEach
    void aRunIsOn() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        fx.ready();
        run = fx.yes();
        when(Server.getInstance().getWorlds()).thenReturn(List.of());
        auditor = new CompanionAuditor(game.world, now::get);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aRunWhoseSessionVanishedIsReleasedAfterAMinuteAndNotBefore() {
        AgentKpqSessionRegistry.remove(fx.doors.session); // released, and no runEnded came

        auditor.run();
        now.addAndGet(CompanionPartyQuestService.LOST_RUN_AFTER_MS - 1L);
        auditor.run();

        assertTrue(game.commitments.isCommitted(900), "not yet");
        assertEquals(run, fx.service.runForSession(run.sessionId));

        now.addAndGet(1L);
        auditor.run();

        assertFalse(game.commitments.isCommitted(900));
        assertNull(fx.service.runForSession(run.sessionId));
    }

    @Test
    void aCompanionThatTappedOutLeavesTheSessionAndTheRunGoesOn() {
        AgentKpqSessionRegistry.unindexMember(fx.doors.session, 900); // Mira tapped out: ejected from the session

        auditor.run();
        now.addAndGet(2 * CompanionPartyQuestService.LOST_RUN_AFTER_MS);
        auditor.run();

        assertTrue(game.commitments.isCommitted(900), "the commitment stays until the run ends");
        assertEquals(run, fx.service.runForSession(run.sessionId));
    }
}
