package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * P6g LPQ C4.2: a companion run's release hands the host (childFinished) a pace whose spans are the pace profile's
 * leaf keys, in its order (Step 1 handed "stage1".."stage9"); a stock run hands none.
 */
class AgentLpqTerminationPaceTest {
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
    void aCompanionReleaseHandsItsPaceInTheProfilesKeys() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true, AgentLpqSession.Phase.PREPARING,
                ben, mira, kes, pel, hal, cas);
        session.transition(AgentLpqSession.Phase.STAGE_1, 3_000L);
        session.pace().markPassesAtFeet(1, 90_000L);
        session.transition(AgentLpqSession.Phase.STAGE_2, 120_000L);
        session.pace().markPassesAtFeet(2, 200_000L);
        session.transition(AgentLpqSession.Phase.STAGE_3, 220_000L);
        session.freezeRewardEligibility();
        session.pace().markCleared(300_000L);

        AgentLpqTerminationService.completeAfterClear(session, "done", 310_000L);

        ArgumentCaptor<AgentPqPace> pace = ArgumentCaptor.forClass(AgentPqPace.class);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(true),
                eq("completed"), eq(310_000L), pace.capture()));
        List<String> keys = pace.getValue().spans().stream().map(AgentPqPace.Span::key).toList();
        assertEquals(List.of("yes", "s1.collect", "s1.handin", "s2.collect", "s2.handin", "s3.collect"), keys);
        List<String> profileKeys = AgentLpqPaceProfile.load(125_000L).segmentKeys(6);
        assertEquals(profileKeys.subList(0, keys.size()), keys, "the profile's leaf keys, in its order");
        assertTrue(pace.getValue().cleared());
    }

    @Test
    void aStockReleaseHandsNone() {
        AgentLpqSession stock = run.session(AgentLpqSession.Mode.PRODUCTION, false, AgentLpqSession.Phase.STAGE_2,
                mira, kes, pel, hal, cas, ben);

        AgentLpqTerminationService.fail(stock, "No live Agent remains", 95_000L);

        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(stock.sessionId()), eq(false),
                anyString(), anyLong(), isNull()));
    }
}
