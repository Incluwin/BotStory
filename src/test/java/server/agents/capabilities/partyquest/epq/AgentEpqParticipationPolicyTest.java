package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEpqParticipationPolicyTest {
    @Test
    void nineConsecutiveStageFiveBoxesUseEveryAgent() {
        List<Integer> agents = List.of(11, 12, 13, 14, 15);
        Set<Integer> assigned = new HashSet<>();
        for (int oid = 1_000; oid < 1_009; oid++) {
            assigned.add(AgentEpqParticipationPolicy.assignedAgent(7L, oid, agents));
        }
        assertEquals(Set.copyOf(agents), assigned);
    }

    @Test
    void consecutiveFlowersRotateAcrossTheParty() {
        List<Integer> agents = List.of(21, 22, 23, 24);
        assertEquals(List.of(21, 22, 23, 24), List.of(
                AgentEpqParticipationPolicy.assignedAgent(0L, 100, agents),
                AgentEpqParticipationPolicy.assignedAgent(0L, 101, agents),
                AgentEpqParticipationPolicy.assignedAgent(0L, 102, agents),
                AgentEpqParticipationPolicy.assignedAgent(0L, 103, agents)));
    }
}
