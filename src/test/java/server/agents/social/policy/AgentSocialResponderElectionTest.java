package server.agents.social.policy;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSocialResponderElectionTest {
    @Test
    void electionIsStableAndSelectsExactlyOneCandidate() {
        Character speaker = character(1);
        List<AgentRuntimeEntry> candidates = List.of(entry(10), entry(11), entry(12));

        AgentRuntimeEntry first = AgentSocialResponderElection.elect(
                speaker, candidates, "hello", value -> value.bot().getId());
        AgentRuntimeEntry repeated = AgentSocialResponderElection.elect(
                speaker, candidates, "hello", value -> value.bot().getId());

        assertNotNull(first);
        assertEquals(first, repeated);
    }

    @Test
    void aHostPreferenceNarrowsTheElectionAndFallsBackToEveryone() {
        Character speaker = character(1);
        List<AgentRuntimeEntry> candidates = List.of(entry(10), entry(11), entry(12));
        try {
            AgentSocialResponderElection.installPreference((agentId, who, message) -> agentId == 12 && message.contains("zakum"));
            for (String line : List.of("where is zakum", "where is zakum?", "zakum helm drops where")) {
                assertEquals(12, AgentSocialResponderElection.elect(speaker, candidates, line, value -> value.bot().getId()).bot().getId(),
                        "only the companion that knows answers " + line);
            }
            AgentRuntimeEntry anyone = AgentSocialResponderElection.elect(speaker, candidates, "hello", value -> value.bot().getId());
            AgentSocialResponderElection.installPreference(null);
            assertEquals(AgentSocialResponderElection.elect(speaker, candidates, "hello", value -> value.bot().getId()), anyone,
                    "nobody preferred: the plain election");
            AgentSocialResponderElection.installPreference((agentId, who, message) -> {
                throw new IllegalStateException("host bug");
            });
            assertTrue(candidates.contains(AgentSocialResponderElection.elect(speaker, candidates, "where is zakum",
                    value -> value.bot().getId())));
        } finally {
            AgentSocialResponderElection.installPreference(null);
        }
    }

    private static AgentRuntimeEntry entry(int id) {
        return new AgentRuntimeEntry(character(id), null, null);
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }
}
