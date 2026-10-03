package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * After Growlie's clear nothing about the leader ends a companion run (plan step 3): offline, at 0 HP or no longer the
 * party's leader, he is let go, and the companions claim at Tory and the run completes. Only a stall fails it, and
 * that takes the companions out of the event through its own exit with the wrap-up line, never closing the event on
 * the players still in it.
 */
class AgentHpqAfterClearTest {
    private static final Point TORY = new Point(-50, 200);
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 200));
    private final Character mira = run.companion(2, "Mira", new Point(-40, 200));
    private final Character tobi = run.companion(3, "Tobi", new Point(-45, 200));

    @BeforeEach
    void onTheClearMap() {
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.CLEAR_MAP);
        when(run.actions.npcPosition(any(), eq(AgentHpqDefinition.ENTRY_NPC))).thenReturn(TORY);
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi));
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentHpqSession cleared(AgentHpqSession.Phase phase) {
        AgentHpqSession session = run.session(true, phase, ben, mira, tobi);
        session.freezeRewardEligibility();
        session.markCleared(1_000L);
        return session;
    }

    /** They claim at Tory, which takes them out to Henesys, and the run completes with the event left to Ben. */
    private void theyClaimAndTheRunCompletes(AgentHpqSession session) {
        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());
        AgentHpqCoordinator.tick(session, 3_000L);
        verify(run.hpq).runNpc(mira, AgentHpqDefinition.ENTRY_NPC);
        verify(run.hpq).runNpc(tobi, AgentHpqDefinition.ENTRY_NPC);

        run.outside(mira, AgentHpqDefinition.RECRUIT_MAP);
        run.outside(tobi, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqCoordinator.tick(session, 4_000L);
        AgentHpqCoordinator.tick(session, 5_000L);

        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                session.sessionId(), true, "completed", 5_000L));
        verify(run.event, never()).dispose();
        run.neverSaid("failed");
    }

    @Test
    void aLeaderWhoLogsOffIsLetGo() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);
        run.offline(ben);

        AgentHpqCoordinator.tick(session, 2_000L);

        theyClaimAndTheRunCompletes(session);
    }

    @Test
    void aLeaderDownOnTheClearMapIsLetGo() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);

        theyClaimAndTheRunCompletes(session);
    }

    @Test
    void aLeaderWhoPassedTheLeadIsLetGo() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);
        run.passLead(mira);

        AgentHpqCoordinator.tick(session, 2_000L);

        theyClaimAndTheRunCompletes(session);
    }

    @Test
    void aLeaderWhoDropsAsGrowlieClearsIsLetGoToo() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DELIVERING_CAKES, ben, mira, tobi);
        run.offline(ben);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.BONUS_DECISION, session.phase());
        AgentHpqCoordinator.tick(session, 2_500L);

        theyClaimAndTheRunCompletes(session);
    }

    @Test
    void aStallTakesOnlyTheCompanionsOutWithTheWrapUpAndNeverClosesTheEvent() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.CLAIMING_REWARD);

        AgentHpqCoordinator.tick(session, 601_001L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "we've been at this a while. let's wrap it up and head back.");
        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), anyString(), anyLong()));
    }

    @Test
    void beforeTheClearAStallStillClosesTheEvent() {
        when(run.event.isEventCleared()).thenReturn(false);
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.STAGE_MAP);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 601_001L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "we've been stuck too long. let's call it and head out.");
        verify(run.event).dispose();
    }
}
