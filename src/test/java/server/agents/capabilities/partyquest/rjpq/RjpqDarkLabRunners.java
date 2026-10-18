package server.agents.capabilities.partyquest.rjpq;

import scripting.event.EventInstanceManager;

/** For tests outside this package: a registered Zenumist companion run bound to {@code event} with its lab runners. */
public final class RjpqDarkLabRunners {
    private RjpqDarkLabRunners() {
    }

    /** Human {@code leader} and three Agents {@code agents}; {@code labOne}/{@code labTwo} run the Dark Labs. */
    public static AgentRjpqSession registeredRun(EventInstanceManager event, int leader, int[] agents, int labOne,
                                                 int labTwo) {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ZENUMIST, 1L, leader, 1_000L);
        session.addMember(leader, AgentRjpqMemberState.MemberType.HUMAN);
        for (int id : agents) {
            session.addMember(id, AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(leader, agents[0]);
        session.setCompanionAdopted(true);
        session.bindEventInstance(event, 1_000L);
        session.chooseLabRunners(labOne, labTwo);
        AgentRjpqSessionRegistry.registerComplete(session);
        return session;
    }
}
