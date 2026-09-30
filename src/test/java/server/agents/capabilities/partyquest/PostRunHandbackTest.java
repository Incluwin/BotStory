package server.agents.capabilities.partyquest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A run whose Agents all go back to their host skips the post-run hold, so the leader can start the next run at once
 * (P6 WP1). The Agents here have no loaded runtime, so recovery closes the engagement without moving anyone.
 */
class PostRunHandbackTest {
    private static final int OPERATOR = 750_100;
    private static final int FIRST_AGENT = 750_101;
    private static final int SECOND_AGENT = 750_102;
    private final List<AgentPartyQuestEngagement> registered = new ArrayList<>();

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        registered.forEach(AgentPartyQuestEngagementRegistry::remove);
    }

    @Test
    void productionSuccessSkipsTheHoldWhenEveryAgentIsHandedBack() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
        AgentPartyQuestEngagement engagement = activeRun(AgentPartyQuestEngagement.Mode.PRODUCTION, "session-1");

        AgentPartyQuestLifecycleRuntime.childFinished("session-1", true, "completed", 5_000L);

        assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state());
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(OPERATOR));
        // Used to throw "operator already owns a party-quest engagement" for the whole 90 s hold.
        assertDoesNotThrow(() -> register(new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 8L, OPERATOR, 4, 5_100L)));
    }

    @Test
    void oneTownLifeAgentKeepsTheHold() {
        AgentPartyQuestHandback.install((id, questKey) ->
                id == FIRST_AGENT ? AgentPartyQuestHandback.Handback.FOLLOW_LEADER : null);
        AgentPartyQuestEngagement engagement = activeRun(AgentPartyQuestEngagement.Mode.PRODUCTION, "session-2");

        AgentPartyQuestLifecycleRuntime.childFinished("session-2", true, "completed", 5_000L);

        assertEquals(AgentPartyQuestEngagement.State.POST_RUN_HOLD, engagement.state());
        assertSame(engagement, AgentPartyQuestEngagementRegistry.forOperator(OPERATOR));
    }

    @Test
    void testObservationKeepsTheHold() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
        AgentPartyQuestEngagement engagement =
                activeRun(AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, "session-3");

        AgentPartyQuestLifecycleRuntime.childFinished("session-3", false, "KPQ stopped", 5_000L);

        assertEquals(AgentPartyQuestEngagement.State.POST_RUN_HOLD, engagement.state());
        assertSame(engagement, AgentPartyQuestEngagementRegistry.forOperator(OPERATOR));
    }

    @Test
    void runEndedFiresOnceAfterRecoverAndAlsoWithoutAnEngagement() {
        List<AgentPartyQuestHandback.RunEnded> ended = new ArrayList<>();
        List<AgentPartyQuestEngagement> engagementAtRunEnd = new ArrayList<>();
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.FOLLOW_LEADER;
            }

            @Override
            public void runEnded(AgentPartyQuestHandback.RunEnded run) {
                ended.add(run);
                engagementAtRunEnd.add(AgentPartyQuestEngagementRegistry.forOperator(OPERATOR));
            }
        });
        activeRun(AgentPartyQuestEngagement.Mode.PRODUCTION, "session-4");

        AgentPartyQuestLifecycleRuntime.childFinished("session-4", false, "KPQ stopped", 5_000L);
        AgentPartyQuestLifecycleRuntime.childFinished("session-gone", true, "completed", 6_000L);
        AgentPartyQuestLifecycleRuntime.childFinished("", true, "completed", 7_000L);

        assertEquals(List.of(
                new AgentPartyQuestHandback.RunEnded("kpq", "session-4", OPERATOR, false, "KPQ stopped", 5_000L),
                new AgentPartyQuestHandback.RunEnded("", "session-gone", 0, true, "completed", 6_000L)), ended);
        // The run's engagement was already recovered and removed when its host heard the run end.
        assertNull(engagementAtRunEnd.getFirst());
    }

    private AgentPartyQuestEngagement activeRun(AgentPartyQuestEngagement.Mode mode, String sessionId) {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement("kpq", mode, 7L, OPERATOR, 4, 1_000L);
        engagement.addMember(OPERATOR, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        engagement.addMember(FIRST_AGENT, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        engagement.addMember(SECOND_AGENT, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        register(engagement);
        engagement.beginLobby("lobby", 1_100L);
        engagement.reserveEntry(1_200L);
        engagement.activateSession(sessionId, 1_300L);
        return engagement;
    }

    private void register(AgentPartyQuestEngagement engagement) {
        AgentPartyQuestEngagementRegistry.register(engagement);
        registered.add(engagement);
    }
}
