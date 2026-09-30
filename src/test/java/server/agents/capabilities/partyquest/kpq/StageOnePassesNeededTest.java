package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloto still counts a human dropped from a companion run, so stage 1 asks for the passes the party size needs and
 * the missing-pass grace clears it (P6 WP3; Cloto 9020001.js counts party members - 1).
 */
class StageOnePassesNeededTest {
    private static final long MISSING_PASS_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MISSING_PASS_GRACE_MS");

    @Test
    void anAdoptedRunCountsTheLivePartyToo() {
        assertEquals(3, AgentKpqCoordinator.stageOnePassesNeeded(true, 3, 4));
        assertEquals(2, AgentKpqCoordinator.stageOnePassesNeeded(false, 3, 4));
        assertEquals(3, AgentKpqCoordinator.stageOnePassesNeeded(true, 4, 4));
    }

    @Test
    void humanLeaderWithEveryRemainingPassClearsStageOneAfterTheGrace() {
        try (KpqRunFixture run = new KpqRunFixture();
             MockedStatic<AgentKpqStageOneThiefLandingRuntime> landing =
                     mockStatic(AgentKpqStageOneThiefLandingRuntime.class);
             MockedStatic<AgentGrindLootStateRuntime> loot = mockStatic(AgentGrindLootStateRuntime.class)) {
            Character ben = run.human(1, "Ben");
            Character ann = run.human(2, "Ann");
            Character mira = run.companion(3, "Mira");
            Character pip = run.companion(4, "Pip");
            // Ann logged out and was dropped; the party (and Cloto) still lists her.
            AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
            run.offline(ann);
            landing.when(() -> AgentKpqStageOneThiefLandingRuntime.tick(any(), any(), any(), anyLong()))
                    .thenReturn(true);
            when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(2);
            for (int companion : new int[]{3, 4}) {
                AgentKpqMemberState member = session.member(companion);
                member.beginStageMovement(1, 0L);
                member.markQuestionRequested();
                member.markPassCreated();
                member.markPassDelivered();
            }

            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 2_000L + MISSING_PASS_GRACE_MS);

            verify(run.event).setProperty("1stageclear", "true");
            run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                    mira, "Ben, you have 2/2 passes. Please talk to Cloto."), never());
        }
    }
}
