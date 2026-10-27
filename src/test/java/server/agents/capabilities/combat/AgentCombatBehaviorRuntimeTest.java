package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.behavior.AgentBehaviorCalibrationState;
import server.agents.behavior.AgentBehaviorPolicyProfile;
import server.agents.behavior.AgentBehaviorPolicyRepository;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCombatBehaviorRuntimeTest {
    @Test
    void claimAvoidanceIsAProfileChanceRatherThanAnUnconditionalFilter() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), null, null);
        AgentBehaviorPolicyProfile policy =
                AgentBehaviorPolicyRepository.defaultRepository().resolve("efficient-v1");
        entry.capabilityStates().require(AgentBehaviorCalibrationState.STATE_KEY)
                .configure(policy, 91234L, true);
        Monster crowded = mock(Monster.class);
        Monster open = mock(Monster.class);
        List<Monster> candidates = List.of(crowded, open);
        Map<Monster, Integer> occupancy = Map.of(crowded, 10, open, 0);

        int avoidedClaims = 0;
        int acceptedClaims = 0;
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (AgentCombatBehaviorRuntime.respectClaims(entry, candidates, occupancy).size() == 1) {
                avoidedClaims++;
            } else {
                acceptedClaims++;
            }
        }

        assertTrue(avoidedClaims >= 250 && avoidedClaims <= 450);
        assertTrue(acceptedClaims > 0);
    }

    @Test
    void withoutABehaviorProfileTheTellAuditTimesAnInstantReactionOnceAWait() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(5);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        List<Monster> candidates = List.of(mock(Monster.class));
        List<Long> reactions = new ArrayList<>();
        assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 1_000L), "nobody listens yet");
        AgentTellProbe.install(new AgentTellProbe.Sink() {
            @Override public void combatReaction(int agentId, long reactionMs) { reactions.add(reactionMs); }
            @Override public void chatReply(long delayMs) { }
            @Override public void potionPoint(int alertPercent) { }
            @Override public void buffRecast(int percentOfDuration) { }
            @Override public void routeWalked(int agentId, int fromMapId, int toMapId, long routeHash) { }
        });
        try {
            AgentCombatBehaviorRuntime.targetAcquired(entry);
            assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 2_000L));
            assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 2_100L));
            AgentCombatBehaviorRuntime.targetAcquired(entry);
            assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, candidates, 3_000L));
            assertTrue(AgentCombatBehaviorRuntime.responseReady(entry, agent, List.of(), 3_100L));
        } finally {
            AgentTellProbe.install(null);
        }
        assertEquals(List.of(0L, 0L), reactions, "it acts on the tick it sees them: one 0 ms reaction a wait");
        assertFalse(AgentCombatBehaviorRuntime.waitingForResponse(entry));
    }
}
