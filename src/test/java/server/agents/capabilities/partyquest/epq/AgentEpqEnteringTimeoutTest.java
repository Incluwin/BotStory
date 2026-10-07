package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.3: a companion run counts its members in from the leader's entry. A member never seen in the bound event
 * COMPANION_ENTRY_WAIT_MS after the leader went in is taken out of the run (out of both indexes, the engagement, and
 * handed back to its owner) while at least four are inside, whatever phase the leader has reached by then: a leader
 * who walks through portal 4 at once skips ENTERING. A member seen once is never ejected by this rule.
 */
class AgentEpqEnteringTimeoutTest {
    private static final long ENTERED = 10_000L;
    private static final long WAIT = 30_000L;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character pia = run.companion(5, "Pia", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character cleo = run.companion(6, "Cleo", AgentEpqDefinition.RECRUIT_MAP);
    private final AgentPartyQuestEngagement engagement = mock(AgentPartyQuestEngagement.class);
    private final MockedStatic<AgentPartyQuestEngagementRegistry> engagements =
            mockStatic(AgentPartyQuestEngagementRegistry.class, CALLS_REAL_METHODS);

    AgentEpqEnteringTimeoutTest() {
        when(cleo.getEventInstance()).thenReturn(null); // never registered: still in the lobby
        engagements.when(() -> AgentPartyQuestEngagementRegistry.forOperator(ben.getId())).thenReturn(engagement);
        engagements.when(() -> AgentPartyQuestEngagementRegistry.removeAndUnindexMember(
                eq(engagement), anyInt(), anyLong())).then(call -> null);
    }

    @AfterEach
    void tearDown() {
        engagements.close();
        run.close();
    }

    private AgentEpqSession companionRun(AgentEpqSession.Phase phase) {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase,
                ben, mira, tobi, una, pia, cleo);
        session.markEnteredEvent(ENTERED);
        return session;
    }

    private void tick(AgentEpqSession session, long nowMs) {
        session.markProgress(nowMs);
        AgentEpqCoordinator.tickSession(session, nowMs);
    }

    @Test
    void aMemberNeverSeenInsideIsTakenOutOfTheRunAfterTheWait() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.ENTERING);
        tick(session, ENTERED + 1_000L);
        tick(session, ENTERED + WAIT - 1L);
        assertNotNull(session.member(cleo.getId()));

        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            tick(session, ENTERED + WAIT);

            assertEquals(1, lines.startingWith("EPQ member left the run and was ejected: session="
                    + session.sessionId() + " member=6 type=AGENT").size());
        }
        assertNull(session.member(cleo.getId()));
        assertEquals(5, session.memberCount());
        assertNull(AgentEpqSessionRegistry.forMember(cleo.getId()));
        engagements.verify(() -> AgentPartyQuestEngagementRegistry.removeAndUnindexMember(
                engagement, cleo.getId(), ENTERED + WAIT));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(
                cleo.getId(), "epq", ENTERED + WAIT));
        assertFalse(session.terminal());
    }

    @Test
    void theClockIsTheLeadersEntryEvenWhenHeSkippedTheEntrance() {
        for (Character member : new Character[] {ben, mira, tobi, una, pia}) {
            run.onMap(member, AgentEpqDefinition.STAGE_ONE_MAP);
        }
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.STAGE_ONE);
        tick(session, ENTERED + 1_000L);
        tick(session, ENTERED + WAIT);

        assertNull(session.member(cleo.getId()));
        assertFalse(session.terminal());
    }

    @Test
    void aMemberSeenOnceAndDepartedSinceIsNeverEjectedByThisRule() {
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.ENTERING);
        when(cleo.getEventInstance()).thenReturn(run.event);
        run.onMap(cleo, AgentEpqDefinition.ENTRANCE_MAP);
        tick(session, ENTERED + 1_000L);
        when(cleo.getEventInstance()).thenReturn(null); // its respawn walked it out
        run.onMap(cleo, AgentEpqDefinition.REWARD_MAP);
        tick(session, ENTERED + 2_000L);
        tick(session, ENTERED + WAIT + 5_000L);

        assertNotNull(session.member(cleo.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(
                anyInt(), anyString(), anyLong()), never());
    }

    @Test
    void withFewerThanFourInsideTheRunEndsWithoutAWord() {
        for (Character absent : new Character[] {una, pia}) {
            when(absent.getEventInstance()).thenReturn(null);
            run.onMap(absent, AgentEpqDefinition.RECRUIT_MAP);
        }
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.ENTERING);
        tick(session, ENTERED + 1_000L);
        tick(session, ENTERED + WAIT);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals("Fewer than four of the companion run entered EPQ within the entry wait", session.failure());
        assertEquals(6, session.memberCount());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void pinAStockRunEjectsNobody() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.ENTERING, ben, mira, tobi, una, pia, cleo);
        session.markEnteredEvent(ENTERED);
        tick(session, ENTERED + 1_000L);
        tick(session, ENTERED + WAIT + 5_000L);

        assertNotNull(session.member(cleo.getId()));
        assertEquals(6, session.memberCount());
    }
}
