package server.agents.runtime.activity.control.binding;

import org.junit.jupiter.api.Test;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentWorldActivityRequestType;
import server.agents.runtime.activity.world.AgentWorldCompletionPolicy;
import server.agents.runtime.activity.world.AgentWorldDirective;
import server.agents.runtime.activity.world.AgentWorldDirectiveSource;
import server.agents.runtime.activity.world.AgentWorldDirectiveType;
import server.agents.runtime.activity.world.AgentWorldInterruptionPolicy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentWorldDirectiveRequestCompilerTest {
    private final AgentWorldDirectiveRequestCompiler compiler =
            new AgentWorldDirectiveRequestCompiler();

    @Test
    void compilesPlanFieldTownAndPartyQuestContracts() {
        assertEquals(AgentActivityKind.QUESTING,
                compiler.compile(directive(AgentActivityKind.QUESTING,
                        AgentWorldActivityRequestType.AUTHORED_PLAN, Map.of())).kind());
        AgentWorldTypedActivityRequest.Questing individual =
                (AgentWorldTypedActivityRequest.Questing) compiler.compile(directive(
                        AgentActivityKind.QUESTING,
                        AgentWorldActivityRequestType.INDIVIDUAL_QUEST,
                        Map.of("questId", "2000", "targetLevel", "21")));
        assertEquals("victoria-individual-quest", individual.request().planId());
        assertEquals(2000, individual.request().inputs().get("questId"));
        assertEquals(AgentActivityKind.HUNTING,
                compiler.compile(directive(AgentActivityKind.HUNTING,
                        AgentWorldActivityRequestType.FIELD_VISIT, Map.of(
                                "mapId", "100000001", "maximumParticipants", "6",
                                "acceptingQuestVisitors", "true", "restAllowed", "true"))).kind());
        assertEquals(AgentActivityKind.TOWN_LIFE,
                compiler.compile(directive(AgentActivityKind.TOWN_LIFE,
                        AgentWorldActivityRequestType.TOWN_LIFE_VISIT, Map.of(
                                "mapId", "100000000", "purpose", "leisure",
                                "freeTimeBudgetMs", "180000"))).kind());
        assertEquals(AgentActivityKind.PARTY_QUEST,
                compiler.compile(directive(AgentActivityKind.PARTY_QUEST,
                        AgentWorldActivityRequestType.PARTY_QUEST_VISIT, Map.of(
                                "scenarioId", "kpq", "partySize", "4", "maximumRuns", "1"))).kind());
    }

    @Test
    void rejectsMismatchedOrIncompleteContractsBeforeHandoff() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(directive(
                AgentActivityKind.HUNTING, AgentWorldActivityRequestType.TOWN_LIFE_VISIT,
                Map.of("mapId", "100000000", "purpose", "leisure",
                        "freeTimeBudgetMs", "1"))));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(directive(
                AgentActivityKind.HUNTING, AgentWorldActivityRequestType.FIELD_VISIT,
                Map.of("mapId", "100000001"))));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(directive(
                AgentActivityKind.PARTY_QUEST, AgentWorldActivityRequestType.PARTY_QUEST_VISIT,
                Map.of("scenarioId", "kpq", "partySize", "4", "maximumRuns", "2"))));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(directive(
                AgentActivityKind.QUESTING, AgentWorldActivityRequestType.INDIVIDUAL_QUEST,
                Map.of())));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(directive(
                AgentActivityKind.COMMERCE, AgentWorldActivityRequestType.COMMERCE_VISIT, Map.of())),
                "Commerce visits ran on the agent economy engine");
    }

    private AgentWorldDirective directive(
            AgentActivityKind kind,
            AgentWorldActivityRequestType requestType,
            Map<String, String> parameters) {
        return new AgentWorldDirective(1, "directive-" + kind, 27,
                AgentWorldDirectiveType.START_ACTIVITY, AgentWorldDirectiveSource.OPERATOR,
                null, kind, requestType, "request-" + kind, parameters,
                AgentWorldInterruptionPolicy.WAIT_FOR_SAFE_BOUNDARY,
                AgentWorldCompletionPolicy.REQUEST_NEXT_DECISION, 10, 1_000L, 0L, "test");
    }
}
