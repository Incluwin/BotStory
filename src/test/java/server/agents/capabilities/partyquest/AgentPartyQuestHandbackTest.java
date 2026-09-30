package server.agents.capabilities.partyquest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPartyQuestHandbackTest {
    private static final AgentPartyQuestHandback.TapOut TAP_OUT =
            new AgentPartyQuestHandback.TapOut("kpq", "session", 101, 103_000_890, 1_000L);
    private static final AgentPartyQuestHandback.RunEnded RUN_ENDED =
            new AgentPartyQuestHandback.RunEnded("kpq", "session", 100, true, "completed", 2_000L);

    @AfterEach
    void uninstall() {
        AgentPartyQuestHandback.install(null);
    }

    @Test
    void withNothingInstalledEveryAgentKeepsTheStockTownLifeRecovery() {
        assertEquals(AgentPartyQuestHandback.Handback.TOWN_LIFE, AgentPartyQuestHandback.handback(101, "kpq"));
        assertFalse(AgentPartyQuestHandback.tapOut(TAP_OUT));
        assertDoesNotThrow(() -> AgentPartyQuestHandback.runEnded(RUN_ENDED));
        assertTrue(AgentPartyQuestHandback.anyTownLife(List.of(101, 102), "kpq"));
    }

    @Test
    void aFailingRuleFallsBackToStockAndNeverReachesTheReleasingThread() {
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                throw new IllegalStateException("roster not loaded");
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                throw new IllegalStateException("roster not loaded");
            }

            @Override
            public void runEnded(AgentPartyQuestHandback.RunEnded ended) {
                throw new IllegalStateException("roster not loaded");
            }
        });

        assertEquals(AgentPartyQuestHandback.Handback.TOWN_LIFE, AgentPartyQuestHandback.handback(101, "kpq"));
        assertFalse(AgentPartyQuestHandback.tapOut(TAP_OUT));
        assertDoesNotThrow(() -> AgentPartyQuestHandback.runEnded(RUN_ENDED));
    }

    @Test
    void aNullAnswerKeepsTownLife() {
        AgentPartyQuestHandback.install((agentCharacterId, questKey) -> null);

        assertEquals(AgentPartyQuestHandback.Handback.TOWN_LIFE, AgentPartyQuestHandback.handback(101, "kpq"));
        assertFalse(AgentPartyQuestHandback.tapOut(TAP_OUT));
    }

    @Test
    void theInstalledRuleAnswersUntilItIsUninstalled() {
        List<AgentPartyQuestHandback.RunEnded> ended = new ArrayList<>();
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return agentCharacterId == 101
                        ? AgentPartyQuestHandback.Handback.FOLLOW_LEADER : AgentPartyQuestHandback.Handback.RELEASE;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                return true;
            }

            @Override
            public void runEnded(AgentPartyQuestHandback.RunEnded run) {
                ended.add(run);
            }
        });

        assertEquals(AgentPartyQuestHandback.Handback.FOLLOW_LEADER, AgentPartyQuestHandback.handback(101, "kpq"));
        assertTrue(AgentPartyQuestHandback.tapOut(TAP_OUT));
        AgentPartyQuestHandback.runEnded(RUN_ENDED);
        assertEquals(List.of(RUN_ENDED), ended);
        assertFalse(AgentPartyQuestHandback.anyTownLife(List.of(101, 102), "kpq"));

        AgentPartyQuestHandback.install(null);

        assertEquals(AgentPartyQuestHandback.Handback.TOWN_LIFE, AgentPartyQuestHandback.handback(101, "kpq"));
        assertFalse(AgentPartyQuestHandback.tapOut(TAP_OUT));
        AgentPartyQuestHandback.runEnded(RUN_ENDED);
        assertEquals(1, ended.size());
    }
}
