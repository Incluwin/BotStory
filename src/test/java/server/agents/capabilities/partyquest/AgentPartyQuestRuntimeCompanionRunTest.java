package server.agents.capabilities.partyquest;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.capabilities.partyquest.lpq.AgentLpqMemberState;
import server.agents.capabilities.partyquest.lpq.AgentLpqSession;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion run is a party quest session a host adopted for a human leader and his companions
 * (AgentCompanionRunSession.companionAdopted); every quest system answers for its own members, from memory.
 */
class AgentPartyQuestRuntimeCompanionRunTest {
    private static final int KPQ_COMPANION = 742_701;
    private static final int LPQ_STOCK = 742_801;

    @Test
    void companionRunIsTrueOnlyForAnAdoptedSession() {
        AgentKpqSession adopted = new AgentKpqSession(AgentKpqSession.Mode.TEST_OBSERVATION, 1L, 742_700, 3, 1_000L);
        adopted.addMember(KPQ_COMPANION, AgentKpqMemberState.MemberType.AGENT);
        adopted.setCompanionAdopted(true);
        AgentLpqSession stock = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, 742_800, 6, 1_000L);
        stock.addMember(LPQ_STOCK, AgentLpqMemberState.MemberType.AGENT);
        AgentKpqSessionRegistry.registerComplete(adopted);
        AgentLpqSessionRegistry.registerComplete(stock);
        try {
            assertTrue(AgentPartyQuestRuntime.companionRun(KPQ_COMPANION));
            assertFalse(AgentPartyQuestRuntime.companionRun(LPQ_STOCK), "a stock LPQ run");
            assertFalse(AgentPartyQuestRuntime.companionRun(742_999), "in no run");
            assertFalse(AgentPartyQuestRuntime.ownsDryLine(KPQ_COMPANION, 103_000_800), "KPQ owns no dry news");
        } finally {
            AgentLpqSessionRegistry.remove(stock);
            AgentKpqSessionRegistry.remove(adopted);
        }
    }

    @Test
    void everySystemAnswersCompanionRun() {
        for (String key : List.of("hpq", "kpq", "lpq", "lmpq", "opq", "epq", "ppq")) {
            AgentPartyQuestSystem system = AgentPartyQuestRuntime.requireSystem(key);
            assertFalse(system.companionRun(742_999), key);
            assertFalse(system.ownsDryLine(742_999, 922_010_700), key);
            assertFalse(system.ownsPotionRest(742_999), key);
        }
        assertFalse(AgentPartyQuestRuntime.ownsPotionRest(742_999), "in no run");
    }
}
