package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fixes 1 and 5 (all modes): a claim at the reward Rolly is not progress (a full bag gets a refusal, and
 * the click used to keep the run alive until its timer), and an Agent still on the reward map after
 * REWARD_REFUSALS_MAX (3) claims leaves once through the event's own exit, unrewarded, with an 'LMPQ reward refused:'
 * line; the event is disposed when that Agent was the last player in it.
 */
class AgentLmpqRewardRefusalTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** A run past the clear with Mira on the reward map at Rolly, whose every claim is refused (she stays). */
    private AgentLmpqSession atRolly() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REWARD,
                ben, mira, kes, pel);
        when(run.event.isEventCleared()).thenReturn(true);
        Point rolly = new Point(13, 107);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        when(mira.getPosition()).thenReturn(rolly);
        when(run.actions.npcPosition(mira, AgentLmpqDefinition.REWARD_NPC)).thenReturn(rolly);
        when(run.partyQuests.runNpc(mira, AgentLmpqDefinition.REWARD_NPC, 1)).thenReturn(true);
        return session;
    }

    /** Mira's member ticks, one second apart from {@code fromMs}: the first settles, every later one may claim. */
    private void ticks(AgentLmpqSession session, long fromMs, int count) {
        for (int i = 0; i < count; i++) {
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, fromMs + i * 1_000L);
        }
    }

    @Test
    void aRefusedClaimIsNotProgress() {
        AgentLmpqSession session = atRolly();
        long before = session.lastProgressAtMs();

        ticks(session, 10_000L, 4);

        verify(run.partyQuests, times(3)).runNpc(mira, AgentLmpqDefinition.REWARD_NPC, 1);
        assertEquals(before, session.lastProgressAtMs());
        assertEquals(3, session.member(mira.getId()).rewardAttempts());
    }

    @Test
    void afterThreeRefusalsTheAgentLeavesOnceUnrewarded() {
        AgentLmpqSession session = atRolly();
        List<Character> held = run.livePlayers(ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            ticks(session, 10_000L, 4);
            verify(run.event, never()).exitPlayer(any());

            ticks(session, 14_000L, 3);

            verify(run.event, times(1)).exitPlayer(mira);
            verify(run.partyQuests, times(3)).runNpc(mira, AgentLmpqDefinition.REWARD_NPC, 1);
            assertEquals(List.of("LMPQ reward refused: session=" + session.sessionId() + " member=Mira(2) attempts=3"),
                    lines.startingWith("LMPQ reward refused:"));
        }
        assertEquals(List.of(ben, kes, pel), held);
        verify(run.event, never()).dispose();
        assertTrue(!session.terminal(), "the run goes on for those who can claim");
    }

    @Test
    void theLastPlayerLeavingDisposesTheEvent() {
        AgentLmpqSession session = atRolly();
        run.livePlayers(mira); // everyone else has claimed and left

        ticks(session, 10_000L, 5);

        verify(run.event).exitPlayer(mira);
        verify(run.event).dispose();
    }
}
