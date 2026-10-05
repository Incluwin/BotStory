package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6d LPQ step 1 (C6), the release policy of a companion run: its party is its owner's and is always kept; a failure
 * after the clear (the reward map, or the event cleared) takes only the companions out and leaves the event and the
 * human to their reward; any other failure, and every stock run, is released as today. A run is released once.
 */
class AgentLpqTerminationPolicyTest {
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
    void aCompanionRunKeepsItsPartyWhateverTheOutcomeAndOtherRunsOnlyAfterACompletedObservation() {
        for (AgentLpqSession.Mode mode : AgentLpqSession.Mode.values()) {
            for (boolean completed : new boolean[]{false, true}) {
                assertTrue(AgentLpqTerminationService.keepPartyAfterRelease(mode, completed, true),
                        mode + " completed=" + completed + " companion run");
                assertEquals(completed && mode == AgentLpqSession.Mode.TEST_OBSERVATION,
                        AgentLpqTerminationService.keepPartyAfterRelease(mode, completed, false),
                        mode + " completed=" + completed + " stock");
            }
        }
    }

    @Test
    void theClearIsTheRewardFreezeOrTheClearedEvent() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.STAGE_9, ben, mira, kes, pel, hal, cas);
        assertFalse(AgentLpqTerminationService.pastTheClear(session));
        when(run.event.isEventCleared()).thenReturn(true);
        assertTrue(AgentLpqTerminationService.pastTheClear(session));
        when(run.event.isEventCleared()).thenReturn(false);
        session.freezeRewardEligibility();
        assertTrue(AgentLpqTerminationService.pastTheClear(session));
        session.clearEventInstance();
        assertTrue(AgentLpqTerminationService.pastTheClear(session));
    }

    @Test
    void aCompanionRunFailingAfterTheClearLetsOnlyItsCompanionsGo() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.BONUS, ben, mira, kes, pel, hal, cas);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.fail(session, "The LPQ event leader left the bound instance map", 5_000L);

        for (Character companion : new Character[]{mira, kes, pel, hal, cas}) {
            verify(run.event).exitPlayer(companion);
        }
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertNull(AgentLpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aCompanionRunFailingWhenTheEventIsClearedIsAfterTheClearToo() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.STAGE_9, ben, mira, kes, pel, hal, cas);
        when(run.event.isEventCleared()).thenReturn(true);

        AgentLpqTerminationService.fail(session, "No LPQ progress before phase timeout", 5_000L);

        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void aCompanionRunFailingBeforeTheClearIsReleasedAsTodayButKeepsItsParty() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.STAGE_6, ben, mira, kes, pel, hal, cas);

        AgentLpqTerminationService.fail(session, "The LPQ event timer expired", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).exitPlayer(mira);
        verify(run.event).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aStockRunFailingAfterTheClearIsReleasedAsToday() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, false,
                AgentLpqSession.Phase.BONUS, ben, mira, kes, pel, hal, cas);
        session.freezeRewardEligibility();
        when(run.event.isEventCleared()).thenReturn(true);

        AgentLpqTerminationService.fail(session, "The LPQ event leader left the bound instance map", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
        verify(run.party).leaveCurrentParty(mira);
    }

    @Test
    void aRunIsReleasedOnceAfterTheClear() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.CLAIMING_REWARD, ben, mira, kes, pel, hal, cas);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentLpqTerminationService.failAfterClear(session, "stalled again", 6_000L);
        AgentLpqTerminationService.fail(session, "and again", 7_000L);

        verify(run.event, times(1)).exitPlayer(mira);
        verify(run.event, never()).dispose();
        assertEquals("stalled after the clear", session.failure());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any()), times(1));
    }

    @Test
    void failAfterClearDoesNothingOnceAReleaseHasBegun() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.BONUS, ben, mira, kes, pel, hal, cas);
        assertTrue(session.beginTermination()); // another release is under way

        AgentLpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentLpqTerminationService.failAfterClear(null, "no session", 5_000L);

        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentLpqSession.Phase.BONUS, session.phase());
    }
}
