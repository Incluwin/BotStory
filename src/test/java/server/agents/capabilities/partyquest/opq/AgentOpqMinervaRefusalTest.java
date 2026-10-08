package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB10: Minerva on 920011300 refuses a reward to a full bag ("Please make room on your inventory
 * first."), yet her script runs to its end, so the Agent asked again every pass and marked progress forever. Each run
 * that leaves the Agent on the reward map is now counted as a refusal; at REWARD_REFUSALS_MAX (agent-engine.yaml, 3)
 * the Agent leaves through the event's own exit, unrewarded, with 'OPQ reward refused: session= member= attempts='.
 */
class AgentOpqMinervaRefusalTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
            AgentOpqSession.Phase.CLAIMING_REWARD, ben, mira, run.companion(3, "Kes"), run.companion(4, "Pel"),
            run.companion(5, "Hal"));
    private final AtomicInteger miraMap = new AtomicInteger(AgentOpqDefinition.CLEAR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** Mira stands by Minerva on the reward map; her script runs, and {@code rewarded} says whether it warps her out. */
    private void besideMinerva(boolean rewarded) {
        when(mira.getMapId()).thenAnswer(call -> miraMap.get());
        when(mira.getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(20, 0));
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenAnswer(call -> {
            if (rewarded) miraMap.set(AgentOpqDefinition.RECRUIT_MAP);
            return true;
        });
    }

    private void tick(long nowMs) {
        AgentOpqCoordinator.tickMember(session, run.entries.get(mira.getId()), mira, nowMs);
    }

    @Test
    void aFullBagIsRefusedThreeTimesAndThenLeavesUnrewarded() {
        besideMinerva(false);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            tick(10_000L);
            tick(11_000L);
            verify(run.event, never()).exitPlayer(any());

            tick(12_000L);

            verify(run.event, times(1)).exitPlayer(mira);
            assertEquals(List.of("OPQ reward refused: session=" + session.sessionId() + " member=Mira attempts=3"),
                    lines.startingWith("OPQ reward refused:"));
        }
    }

    @Test
    void aRewardedClaimIsNotARefusal() {
        besideMinerva(true);

        tick(10_000L);
        miraMap.set(AgentOpqDefinition.CLEAR_MAP); // say she came back in: the earlier claim did not count
        besideMinerva(false);
        tick(11_000L);
        tick(12_000L);

        verify(run.event, never()).exitPlayer(any());
    }

    @Test
    void aMinervaRunThatDidNotRunIsNotARefusal() {
        besideMinerva(false);
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(false);

        for (long now = 10_000L; now <= 15_000L; now += 1_000L) tick(now);

        verify(run.event, never()).exitPlayer(any());
    }
}
