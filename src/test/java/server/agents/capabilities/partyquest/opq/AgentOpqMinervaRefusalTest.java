package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB10: Minerva on 920011300 refuses a reward to a full bag ("Please make room on your inventory
 * first."), yet her script runs to its end, so the Agent asked again every pass and marked progress forever. Now an
 * Agent that asked waits out ACTION_RETRY_MS (650 ms); if it is still on the reward map when that window has passed,
 * the ask counts as a refusal (a rewarded Agent is warped to Wonky by her script, at once or later). At
 * REWARD_REFUSALS_MAX (agent-engine.yaml, 3) it stops asking and leaves through the event's own exit, unrewarded, with
 * one 'OPQ reward refused: session= member= attempts=' line.
 */
class AgentOpqMinervaRefusalTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
            AgentOpqSession.Phase.CLAIMING_REWARD, ben, mira, run.companion(3, "Kes"), run.companion(4, "Pel"),
            run.companion(5, "Hal"));
    private final AtomicInteger miraMap = new AtomicInteger(AgentOpqDefinition.CLEAR_MAP);
    private final AtomicBoolean rewards = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        run.close();
    }

    /**
     * Mira stands by Minerva on the reward map; her script runs, and {@link #rewards} says whether it rewards her (and
     * warps her to Wonky at once) or refuses her full bag (she stays). Stubbed once: a second stubbing would run this
     * answer.
     */
    private void besideMinerva(boolean rewarded) {
        rewards.set(rewarded);
        when(mira.getMapId()).thenAnswer(call -> miraMap.get());
        when(mira.getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(20, 0));
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenAnswer(call -> {
            if (rewards.get()) miraMap.set(AgentOpqDefinition.RECRUIT_MAP);
            return true;
        });
    }

    private void tick(long nowMs) {
        AgentOpqCoordinator.tickMember(session, run.entries.get(mira.getId()), mira, nowMs);
    }

    private void verifyAsked(int times) {
        verify(run.partyQuest, times(times)).runNpc(mira, AgentOpqDefinition.MINERVA_NPC);
    }

    @Test
    void aFullBagIsRefusedThreeTimesAndThenLeavesUnrewardedOnce() {
        besideMinerva(false);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            tick(10_000L); // asks
            tick(11_000L); // refusal 1, asks again
            tick(12_000L); // refusal 2, asks again
            verify(run.event, never()).exitPlayer(any());
            tick(13_000L); // refusal 3: leaves

            verify(run.event, times(1)).exitPlayer(mira);
            verifyAsked(3);
            assertEquals(List.of("OPQ reward refused: session=" + session.sessionId() + " member=Mira attempts=3"),
                    lines.startingWith("OPQ reward refused:"));
        }
    }

    @Test
    void anAskIsJudgedOnlyAfterItsRetryWindow() {
        besideMinerva(false);

        tick(10_000L);
        tick(10_001L); // back-to-back passes inside the window: no new ask and nothing counted
        tick(10_002L);
        verifyAsked(1);
        assertEquals(0, session.member(mira.getId()).rewardRefusals());

        tick(10_700L); // the window has passed and she is still here: one refusal, one new ask
        verifyAsked(2);
        assertEquals(1, session.member(mira.getId()).rewardRefusals());
    }

    @Test
    void aWarpThatLandsAfterTheRunIsNotARefusal() {
        besideMinerva(false); // the run returns with Mira still on the reward map ...
        tick(10_000L);
        miraMap.set(AgentOpqDefinition.RECRUIT_MAP); // ... and the reward's warp lands after it
        tick(11_000L);
        miraMap.set(AgentOpqDefinition.CLEAR_MAP); // say she came back in
        tick(12_000L);

        assertEquals(0, session.member(mira.getId()).rewardRefusals());
        verify(run.event, never()).exitPlayer(any());
    }

    @Test
    void aRewardedClaimIsNotARefusal() {
        besideMinerva(true);
        tick(10_000L);
        assertEquals(AgentOpqDefinition.RECRUIT_MAP, miraMap.get(), "rewarded and warped to Wonky");

        // Say she came back in with a full bag: the rewarded claim did not count, so two refusals do not end it ...
        rewards.set(false);
        miraMap.set(AgentOpqDefinition.CLEAR_MAP);
        tick(11_000L);
        tick(12_000L);
        tick(13_000L);
        verify(run.event, never()).exitPlayer(any());

        // ... and the third does.
        tick(14_000L);
        verify(run.event).exitPlayer(mira);
    }

    @Test
    void aFailedExitIsWarnedOnceAndNotRetried() {
        besideMinerva(false);
        doThrow(new IllegalStateException("map busy")).when(run.event).exitPlayer(mira);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            for (long now = 10_000L; now <= 20_000L; now += 1_000L) tick(now);

            assertEquals(1, lines.startingWith("OPQ reward refused:").size());
        }
        verify(run.event, times(1)).exitPlayer(mira);
        verifyAsked(3);
    }

    @Test
    void aMinervaRunThatDidNotRunIsNotARefusal() {
        besideMinerva(false);
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(false);

        for (long now = 10_000L; now <= 15_000L; now += 1_000L) tick(now);

        verify(run.event, never()).exitPlayer(any());
        assertEquals(0, session.member(mira.getId()).rewardRefusals());
    }
}
