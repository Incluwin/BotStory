package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * After Growlie's clear (plan 2.5): the companions wait for the leader on the clear map, follow him into Pig Town and
 * out through Tommy, claim only once he is off the clear map and Pig Town (or out of the event, or the clear wait is
 * over), never fail the run because he logged off or went down, never wait for a human or send him system text, and end
 * the run without a word once they are out.
 */
class AgentHpqCompanionAfterClearTest {
    private static final Point TORY = new Point(-50, 200);
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 200));
    private final Character mira = run.companion(2, "Mira", new Point(-40, 200));
    private final Character tobi = run.companion(3, "Tobi", new Point(-45, 200));

    @BeforeEach
    void onTheClearMap() {
        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.CLEAR_MAP);
        when(run.actions.npcPosition(any(), eq(AgentHpqDefinition.ENTRY_NPC))).thenReturn(TORY);
        when(run.actions.configuredMonsterSpawnIds(any())).thenReturn(Set.of(9_300_073));
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

    @Test
    void theyWaitForHimAndFollowHimIntoPigTown() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.BONUS_DECISION, session.phase());
        run.said(session, "cleared", mira,
                "nice one! tory has your reward, and tommy can take us to pig town if you want more.");
        verify(run.hpq, never()).runNpc(any(), anyInt(), any(int[].class));

        for (Character member : new Character[] {ben, mira, tobi}) run.onMap(member, AgentHpqDefinition.BONUS_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.BONUS_FARMING, session.phase());

        AgentHpqCoordinator.tick(session, 4_000L);
        run.said(session, "pig-town", mira, "pig town! watch out for the iron hogs.");
        verify(run.actions).grind(run.entry(mira), Set.of(9_300_073));
        verify(run.actions).grind(run.entry(tobi), Set.of(9_300_073));
    }

    @Test
    void whenHeLeavesPigTownTheyLeaveThroughTommyAndClaimWhereHeWent() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_FARMING);
        run.onMap(ben, AgentHpqDefinition.REWARD_EXIT_MAP);
        run.onMap(mira, AgentHpqDefinition.BONUS_MAP);
        run.onMap(tobi, AgentHpqDefinition.BONUS_MAP);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.hpq).runNpc(mira, AgentHpqDefinition.TOMMY_NPC);
        verify(run.hpq).runNpc(tobi, AgentHpqDefinition.TOMMY_NPC);

        run.onMap(mira, AgentHpqDefinition.REWARD_EXIT_MAP);
        run.onMap(tobi, AgentHpqDefinition.REWARD_EXIT_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());

        AgentHpqCoordinator.tick(session, 4_000L);
        verify(run.hpq).runNpc(mira, AgentHpqDefinition.ENTRY_NPC);
        verify(run.hpq).runNpc(tobi, AgentHpqDefinition.ENTRY_NPC);
    }

    @Test
    void aLeaderWhoLogsOffAfterTheClearEndsNothingTheyClaimAndGo() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);
        when(run.characters.findOnlineCharacterById(ben.getId())).thenReturn(null);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());

        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());
        verify(run.hpq).runNpc(mira, AgentHpqDefinition.ENTRY_NPC);
    }

    @Test
    void aLeaderDownOnTheClearMapEndsNothingEither() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());
    }

    @Test
    void theyDoNotClaimWhileHeIsStillOnTheClearMapUntilTheWaitIsOver() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.BONUS_DECISION);

        AgentHpqCoordinator.tick(session, 300_999L);
        assertEquals(AgentHpqSession.Phase.BONUS_DECISION, session.phase());
        run.neverSaid("wait-outside");

        AgentHpqCoordinator.tick(session, 301_000L);
        run.said(session, "wait-outside", mira, "we'll grab our rewards and wait for you outside.");
        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());

        AgentHpqCoordinator.tick(session, 302_000L);
        verify(run.hpq).runNpc(mira, AgentHpqDefinition.ENTRY_NPC);
    }

    @Test
    void inTheClaimPhaseTheyStandBesideHimWhileHeIsOnTheClearMap() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.CLAIMING_REWARD);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.hpq, never()).runNpc(any(), eq(AgentHpqDefinition.ENTRY_NPC), any(int[].class));
        verify(run.actions, never()).navigate(any(), any(), anyBoolean());
        verify(ben, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void humansAreNeverWaitedForAndTheRunEndsQuietlyOnceTheCompanionsAreOut() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.CLAIMING_REWARD);
        run.outside(mira, AgentHpqDefinition.RECRUIT_MAP);
        run.outside(tobi, AgentHpqDefinition.RECRUIT_MAP);
        run.onMap(ben, AgentHpqDefinition.REWARD_EXIT_MAP);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.EXITING, session.phase(), "Ben's reward is his own business");

        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
        run.saidNothing();
        verify(run.packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        verify(ben, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aCompanionThatCannotTakeItsRewardGivesItUpAndTheOthersGoOn() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.CLAIMING_REWARD);
        run.onMap(ben, AgentHpqDefinition.REWARD_EXIT_MAP);
        // Tory pays Mira and sends her out; Tobi's bag is full, so she cancels his claim each time (1012112.js).
        when(run.hpq.runNpc(mira, AgentHpqDefinition.ENTRY_NPC)).thenAnswer(call -> {
            session.beginRewardClaim(mira.getId());
            session.completeRewardClaim(mira.getId());
            run.outside(mira, AgentHpqDefinition.RECRUIT_MAP);
            return true;
        });
        when(run.hpq.runNpc(tobi, AgentHpqDefinition.ENTRY_NPC)).thenAnswer(call -> {
            session.beginRewardClaim(tobi.getId());
            session.cancelRewardClaim(tobi.getId());
            return true;
        });

        for (long now = 2_000L; now <= 4_400L; now += 1_200L) AgentHpqCoordinator.tick(session, now);
        verify(run.hpq, org.mockito.Mockito.times(3)).runNpc(tobi, AgentHpqDefinition.ENTRY_NPC);
        verify(run.event, never()).exitPlayer(tobi);

        AgentHpqCoordinator.tick(session, 5_600L);

        verify(run.hpq, org.mockito.Mockito.times(3)).runNpc(tobi, AgentHpqDefinition.ENTRY_NPC);
        verify(run.event).exitPlayer(tobi);
        assertEquals(AgentHpqMemberState.RewardState.FORFEITED, session.member(tobi.getId()).rewardState());
        assertEquals(AgentHpqSession.Phase.EXITING, session.phase());
        run.saidNothing();
    }

    @Test
    void theRunWaitsForACompanionStillInTheEvent() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.EXITING);
        run.outside(mira, AgentHpqDefinition.RECRUIT_MAP);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.EXITING, session.phase(), "Tobi is still on the clear map");

        run.outside(tobi, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void theRunWaitsForADownedCompanionToGetUpBeforeItEnds() {
        AgentHpqSession session = cleared(AgentHpqSession.Phase.EXITING);
        run.outside(mira, AgentHpqDefinition.RECRUIT_MAP);
        run.outside(tobi, AgentHpqDefinition.EXIT_MAP);
        when(tobi.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.EXITING, session.phase());

        when(tobi.getHp()).thenReturn(500);
        AgentHpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
    }
}
