package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6d LPQ step 1, C1 pin (green on the base and after): how a run that is not a companion run is released. The
 * companion seam changes the release; these runs must not notice.
 */
class AgentLpqStockReleasePinTest {
    private final LpqRunFixture run = new LpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character cas = run.companion(6, "Cas");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStockProductionFailureTakesTheAgentsOutOfThePartyExitsEveryoneAndDisposes() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.STAGE_3,
                ben, mira, kes, pel, hal, cas);

        AgentLpqTerminationService.fail(session, "The LPQ event timer expired", 5_000L);

        for (Character member : new Character[]{ben, mira, kes, pel, hal, cas}) {
            verify(run.event).exitPlayer(member);
        }
        verify(run.event).dispose();
        for (Character agent : new Character[]{mira, kes, pel, hal, cas}) {
            verify(run.party).leaveCurrentParty(agent);
            verify(run.actions).stop(run.entries.get(agent.getId()));
        }
        verify(run.party, never()).leaveCurrentParty(ben);
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("The LPQ event timer expired", session.failure());
        assertNull(session.eventInstance());
        assertNull(AgentLpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aStockFailureAfterTheClearStillDisposesForEveryone() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.BONUS,
                ben, mira, kes, pel, hal, cas);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.fail(session, "The LPQ event leader left the bound instance map", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
        verify(run.party).leaveCurrentParty(mira);
    }

    @Test
    void aCompletedObservationRunKeepsItsParty() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.TEST_OBSERVATION,
                AgentLpqSession.Phase.EXITING, mira, kes, pel, hal, cas, ben);

        AgentLpqTerminationService.complete(session, 5_000L);

        verify(run.party, never()).leaveCurrentParty(any());
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentLpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aStockProductionCompletionLeavesThePartyAndDisposesNothing() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.EXITING,
                ben, mira, kes, pel, hal, cas);

        AgentLpqTerminationService.complete(session, 5_000L);

        for (Character agent : new Character[]{mira, kes, pel, hal, cas}) {
            verify(run.party).leaveCurrentParty(agent);
        }
        verify(run.party, never()).leaveCurrentParty(ben);
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void anLpqOwnedPartyIsLeftByEveryMemberAndAFailedObservationRunLeavesToo() {
        AgentLpqSession owned = run.session(AgentLpqSession.Mode.TEST_OBSERVATION, AgentLpqSession.Phase.STAGE_2,
                mira, kes, pel, hal, cas, ben);

        AgentLpqTerminationService.fail(owned, "stopped by operator", 5_000L);

        verify(run.party).leaveCurrentParty(ben);
        verify(run.party).leaveCurrentParty(mira);
        verify(run.event).dispose();
    }

    @Test
    void aSecondReleaseDoesNothing() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.STAGE_3,
                ben, mira, kes, pel, hal, cas);
        AgentLpqTerminationService.fail(session, "first", 5_000L);
        AgentLpqTerminationService.fail(session, "second", 6_000L);
        AgentLpqTerminationService.complete(session, 7_000L);

        verify(run.event).dispose();
        assertEquals("first", session.failure());
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }
}
