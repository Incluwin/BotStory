package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestSessionView;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each RJPQ side is its own router system over the one shared registry: a member of an Alcadno run never answers for
 * the Zenumist system (the router falls back to the first system that claims a member), and nobody queues for RJPQ.
 */
class AgentRjpqSystemTest {
    private final List<AgentRjpqSession> made = new ArrayList<>();

    @AfterEach
    void forget() {
        made.forEach(AgentRjpqSessionRegistry::remove);
    }

    private AgentRjpqSession register(AgentRjpqSide side, int operator, int... agents) {
        AgentRjpqSession session = new AgentRjpqSession(side, 1L, operator, 1_000L);
        session.addMember(operator, AgentRjpqMemberState.MemberType.HUMAN);
        for (int agent : agents) {
            session.addMember(agent, AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(operator, agents[0]);
        AgentRjpqSessionRegistry.registerComplete(session);
        made.add(session);
        return session;
    }

    @Test
    void eachSystemAnswersOnlyForItsOwnSide() {
        register(AgentRjpqSide.ALCADNO, 91_001, 91_011, 91_012, 91_013);
        AgentRjpqSystem zenumist = new AgentRjpqSystem(AgentRjpqSide.ZENUMIST);
        AgentRjpqSystem alcadno = new AgentRjpqSystem(AgentRjpqSide.ALCADNO);

        assertFalse(zenumist.sessionActive(91_012));
        assertNull(zenumist.sessionView(91_012));
        assertFalse(zenumist.pause(91_012));
        assertTrue(alcadno.sessionActive(91_012));
        AgentPartyQuestSessionView view = alcadno.sessionView(91_012);
        assertEquals("rjpqj", view.questKey());
        assertEquals("COMPANION", view.mode());
        assertEquals(4, view.memberCount());
    }

    @Test
    void theDefinitionIsTheSidesOwnAndNobodyQueues() {
        AgentRjpqSystem zenumist = new AgentRjpqSystem(AgentRjpqSide.ZENUMIST);

        assertEquals("rjpq", zenumist.definition().questKey());
        assertEquals("MagatiaPQ_Z", zenumist.definition().eventManagerName());
        assertEquals(AgentRjpqSystem.NO_QUEUE, zenumist.entryBlocker(null, "s", 4, 1));
        assertEquals(server.agents.runtime.activity.session.AgentActivityAdmissionResult.Status.REJECTED,
                zenumist.requestEntry(null, null, "s", 4, 1, 0L).status());
    }

    @Test
    void aPausedRunResumesOnlyByItsOwnId() {
        AgentRjpqSession session = register(AgentRjpqSide.ZENUMIST, 91_101, 91_111, 91_112, 91_113);
        AgentRjpqSystem zenumist = new AgentRjpqSystem(AgentRjpqSide.ZENUMIST);

        assertTrue(zenumist.pause(91_111));
        assertFalse(zenumist.resumeExact(91_111, "another", 5_000L));
        assertTrue(zenumist.resumeExact(91_111, session.sessionId(), 5_000L));
        assertFalse(session.paused());
    }
}
