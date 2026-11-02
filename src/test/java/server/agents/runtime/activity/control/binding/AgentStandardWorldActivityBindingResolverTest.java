package server.agents.runtime.activity.control.binding;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.control.facade.AgentStandardLiveActivityFacades;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentWorldActivityRequestType;
import server.agents.runtime.activity.world.AgentWorldCompletionPolicy;
import server.agents.runtime.activity.world.AgentWorldDirective;
import server.agents.runtime.activity.world.AgentWorldDirectiveSource;
import server.agents.runtime.activity.world.AgentWorldDirectiveType;
import server.agents.runtime.activity.world.AgentWorldInterruptionPolicy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStandardWorldActivityBindingResolverTest {
    @Test
    void bindsEligibleKpqVisitWithoutStartingTheAggregateDuringPreflight() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(27);
        when(agent.getLevel()).thenReturn(25);
        AgentWorldDirective directive = new AgentWorldDirective(
                1, "join-kpq", 27, AgentWorldDirectiveType.START_ACTIVITY,
                AgentWorldDirectiveSource.OPERATOR, null, AgentActivityKind.PARTY_QUEST,
                AgentWorldActivityRequestType.PARTY_QUEST_VISIT, "kpq",
                Map.of("scenarioId", "kpq", "partySize", "4", "maximumRuns", "1"),
                AgentWorldInterruptionPolicy.WAIT_FOR_SAFE_BOUNDARY,
                AgentWorldCompletionPolicy.REQUEST_NEXT_DECISION,
                10, 1_000L, 0L, "join lobby");
        AgentStandardWorldActivityBindingResolver resolver =
                new AgentStandardWorldActivityBindingResolver(
                        new AgentWorldDirectiveRequestCompiler(),
                        AgentStandardLiveActivityFacades.registry());

        AgentWorldActivityBinding binding = resolver.bind(
                directive, mock(AgentRuntimeEntry.class), agent, null, "");

        assertTrue(binding.targetPreflight().inspect(
                "27", AgentActivityKind.PARTY_QUEST, 1_001L).ready());
        assertTrue(AgentStandardWorldActivityBindingResolver.supportedTargets()
                .contains(AgentActivityKind.PARTY_QUEST));
    }

    @Test
    void ordinaryFieldVisitUsesNormalMapTransferWhenDestinationDiffers() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(27);
        when(agent.getMapId()).thenReturn(100000000);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        AgentWorldDirective directive = new AgentWorldDirective(
                1, "hunt-field", 27, AgentWorldDirectiveType.START_ACTIVITY,
                AgentWorldDirectiveSource.OPERATOR, null, AgentActivityKind.HUNTING,
                AgentWorldActivityRequestType.FIELD_VISIT, "field-visit",
                Map.of("mapId", "100040001", "intent", "FREE_GRIND",
                        "acceptingQuestVisitors", "true", "maximumParticipants", "6",
                        "restAllowed", "true", "narration", "SUMMARY"),
                AgentWorldInterruptionPolicy.WAIT_FOR_SAFE_BOUNDARY,
                AgentWorldCompletionPolicy.REQUEST_NEXT_DECISION,
                10, 1_000L, 0L, "visit field");
        AgentStandardWorldActivityBindingResolver resolver =
                new AgentStandardWorldActivityBindingResolver(
                        new AgentWorldDirectiveRequestCompiler(),
                        AgentStandardLiveActivityFacades.registry(),
                        (liveEntry, liveAgent, destinationMapId, nowMs) -> {
                            assertEquals(entry, liveEntry);
                            assertEquals(agent, liveAgent);
                            assertEquals(100040001, destinationMapId);
                            return server.agents.runtime.activity.session.AgentActivityTransferPort.Result
                                    .pending("normal route", nowMs + 500L);
                        });

        AgentWorldActivityBinding binding = resolver.bind(directive, entry, agent, null, "");

        assertTrue(binding.targetPreflight().inspect(
                "27", AgentActivityKind.HUNTING, 1_001L).ready());
        assertEquals(server.agents.runtime.activity.session.AgentActivityTransferPort.Result.Status.PENDING,
                binding.transfer().advance(1_001L).status());
    }
}
