package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.8 (decision D-f): after the clear a companion run's companions claim and leave and never wait on the
 * human (live R3: the run waited for his claim and timed out as 'failed' although cleared). They farm the bonus beside
 * him while he stays on it (C1), else leave through the Pink Balloon; on the reward map they wait a short grace (C2)
 * and claim at Arturo; one Arturo keeps turning away gives its reward up and leaves; a leader who logs off is let go.
 */
class AgentLpqCompanionAfterClearTest {
    private static final Point ARTURO = new Point(190, -1_136);
    private static final Point PINK = new Point(-34, 474);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Arturo takes a companion's claim and warps it out to the Red Sign; one in {@code refusing} finds no slot. */
    private void arturo(AgentLpqSession session, Character... refusing) {
        fx.npcAt(AgentLpqDefinition.REWARD_NPC, ARTURO);
        when(fx.lpq.runNpc(any(), eq(AgentLpqDefinition.REWARD_NPC), anyInt())).thenAnswer(call -> {
            Character agent = call.getArgument(0);
            session.beginRewardClaim(agent.getId());
            if (List.of(refusing).contains(agent)) {
                session.cancelRewardClaim(agent.getId());
            } else {
                session.completeRewardClaim(agent.getId());
                fx.outside(agent, AgentLpqDefinition.RECRUIT_MAP);
            }
            return true;
        });
    }

    private AgentLpqSession onTheRewardMap() {
        crew = fx.crew(AgentLpqDefinition.BONUS_MAP, ARTURO);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.CLAIMING_REWARD,
                crew.toArray(Character[]::new));
        session.freezeRewardEligibility();
        return session;
    }

    @Test
    void theCompanionsClaimAfterTheGraceAndTheRunCompletesWithoutHim() {
        AgentLpqSession session = onTheRewardMap();
        arturo(session);

        fx.tick(session, 3_000L);
        fx.said(session, "arturo", crew.get(1), "arturo's up at the top. grab your reward, we'll wait for you outside.");
        verify(fx.lpq, never()).runNpc(any(), eq(AgentLpqDefinition.REWARD_NPC), anyInt());
        fx.tick(session, 8_000L);
        for (Character companion : crew.subList(1, 6)) {
            verify(fx.lpq).runNpc(companion, AgentLpqDefinition.REWARD_NPC, 1);
        }
        fx.tickEvery(session, 9_000L, 13_000L, 1_000L);

        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
        assertFalse(session.member(1).rewardResolved(), "the human was never waited for");
        fx.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(true),
                eq("completed"), anyLong(), any()));
    }

    @Test
    void theyFarmTheBonusBesideHimAndLeaveWhenHeDoes() {
        fx.npcAt(AgentLpqDefinition.CLEAR_NPC, PINK);
        crew = fx.crew(AgentLpqDefinition.CLEAR_MAP, PINK);
        fx.drop(AgentLpqDefinition.CLEAR_MAP, 4_000_018, 1, new Point(PINK.x + 300, PINK.y), false, 0, 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.BONUS, crew.toArray(Character[]::new));
        session.freezeRewardEligibility();

        fx.tick(session, 3_000L);
        fx.said(session, "cleared", crew.get(1),
                "we did it! break the boxes for loot. the pink balloon lets you out early, arturo has the rewards.");
        verify(fx.actions, atLeastOnce()).navigate(eq(fx.entry(crew.get(1))), eq(new Point(PINK.x + 300, PINK.y)),
                eq(true));
        verify(fx.lpq, never()).runNpc(any(), eq(AgentLpqDefinition.CLEAR_NPC), anyInt());

        fx.onMap(crew.getFirst(), AgentLpqDefinition.BONUS_MAP, ARTURO);
        fx.tick(session, 4_000L);

        verify(fx.lpq, atLeastOnce()).runNpc(any(), eq(AgentLpqDefinition.CLEAR_NPC), eq(1));
    }

    @Test
    void aLeaderWhoLogsOffIsLetGoAndTheClaimsGoOn() {
        AgentLpqSession session = onTheRewardMap();
        arturo(session);
        fx.offline(crew.getFirst());
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 3_000L);
            fx.tick(session, 8_000L);

            assertEquals(1, log.startingWith("LPQ leader let go after the clear: session=" + session.sessionId()
                    + " leader=1 why=offline").size());
        }
        verify(fx.lpq).runNpc(crew.get(1), AgentLpqDefinition.REWARD_NPC, 1);
        assertTrue(session.leaderReleased());
    }

    @Test
    void aCompanionArturoKeepsTurningAwayGivesUpAndLeaves() {
        AgentLpqSession session = onTheRewardMap();
        Character kes = crew.get(2);
        arturo(session, kes);
        doAnswer(call -> {
            fx.outside(kes, AgentLpqDefinition.EXIT_MAP);
            return null;
        }).when(fx.event).exitPlayer(kes);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tickEvery(session, 3_000L, 20_000L, 1_500L);

            assertEquals(1, log.startingWith("LPQ claim failed: session=" + session.sessionId()
                    + " member=3 reason=inventory failures=3").size());
        }
        verify(fx.event).exitPlayer(kes);
        assertEquals(AgentLpqMemberState.RewardState.FORFEITED, session.member(kes.getId()).rewardState());
        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
    }

    /**
     * The companions still stand on the boss map when the clear is first seen (clearPQ's warp has not reached them):
     * they hold the bonus, stopped, and the bonus line waits for the bonus map (amended A1, B1).
     */
    @Test
    void theFirstTickPastTheClearBeginsTheBonusAndTheLimitCountsFromThere() {
        crew = fx.crew(AgentLpqDefinition.stage(9).mapId(), new Point(0, 184));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        when(fx.event.isEventCleared()).thenReturn(true);

        fx.tick(session, 700_000L);

        assertEquals(700_000L, session.clearedAtMs());
        assertEquals(AgentLpqSession.Phase.BONUS, session.phase(), "not wrapped up past the stock phase timeout");
        fx.tick(session, 1_000_000L);
        assertEquals(AgentLpqSession.Phase.BONUS, session.phase());
        fx.tick(session, 1_000_001L);
        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
        verify(fx.event, never()).dispose();
        fx.neverSaid("cleared");
    }
}
