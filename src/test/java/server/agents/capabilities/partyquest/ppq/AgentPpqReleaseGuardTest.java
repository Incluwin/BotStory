package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * PPQ Step 1b.6 (every mode; the RJPQ review's release race): a release can begin off the central tick thread (a
 * '!ppqtest stop', a router forceStop). From then on the companions' own ticks do nothing more and the run's pass
 * stops, so nothing acts for a run its release is taking apart.
 */
class AgentPpqReleaseGuardTest {
    @Test
    void aCompanionWhoseRunIsBeingReleasedDoesNothingMore() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.session.beginTermination();

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verifyNoInteractions(run.actions);
        }
    }

    @Test
    void anEndedRunsCompanionDoesNothingMore() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.session.fail("test", 2_000L);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verifyNoInteractions(run.actions);
        }
    }

    @Test
    void theRunsOwnPassStopsOnceItsReleaseBegan() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            run.session.beginTermination();

            run.tick(5_000L);

            assertEquals(AgentPpqSession.Phase.PREPARING, run.session.phase());
        }
    }
}
