package server.agents.capabilities.partyquest;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberState;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A companion host reads a run's event leader off its session whichever quest it is: the leader the session has now,
 * after the lead passed inside the run, never the Agent that executes the run's shared steps.
 */
class AgentCompanionRunSessionTest {

    @Test
    void aKpqSessionReportsTheEventLeaderItHasNow() {
        AgentKpqSession kpq = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 7L, 101, 3, 1_000L);
        kpq.addMember(101, AgentKpqMemberState.MemberType.HUMAN);
        kpq.addMember(900, AgentKpqMemberState.MemberType.AGENT);
        kpq.addMember(901, AgentKpqMemberState.MemberType.AGENT);
        AgentCompanionRunSession session = kpq;
        assertEquals(101, session.eventLeaderId(), "its first member leads until the lead passes");

        kpq.setLeadership(900, 901);

        assertEquals(900, session.eventLeaderId(), "the event leader, not the execution Agent (901)");
    }

    @Test
    void anHpqSessionReportsTheEventLeaderItHasNow() {
        AgentHpqSession hpq = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, 201, 3, 1_000L);
        hpq.addMember(201, AgentHpqMemberState.MemberType.HUMAN);
        hpq.addMember(950, AgentHpqMemberState.MemberType.AGENT);
        hpq.addMember(951, AgentHpqMemberState.MemberType.AGENT);
        AgentCompanionRunSession session = hpq;

        hpq.setLeadership(950, 951);

        assertEquals(950, session.eventLeaderId(), "the event leader, not the execution Agent (951)");
    }
}
