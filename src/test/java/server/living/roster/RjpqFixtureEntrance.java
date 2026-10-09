package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqDefinition;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqMemberState;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSession;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;

import java.awt.Point;
import java.util.List;

/**
 * RJPQ as the companion-run fixture plays it (plan W11), one per side: Romeo on his step in his hidden room
 * (261000011) or Juliet on her ledge in hers (261000021), each room's floor as Map.wz has it, an admission that
 * registers a real RJPQ session of the side with the lineup (Ann as operator and event leader, her first companion
 * executing, as RJPQ's admission leads it), RJPQ's registry removing it, and the run exiting to the side's exit map.
 */
final class RjpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    private final AgentRjpqSide side;

    RjpqFixtureEntrance(AgentRjpqSide side) {
        this.side = side;
    }

    /** Romeo (Map.wz 261000011: x 158, y 132) or Juliet (261000021: x 10, y 28). */
    @Override
    public Point npcAt() {
        return side == AgentRjpqSide.ZENUMIST ? new Point(158, 132) : new Point(10, 28);
    }

    /** His step (x 128-194, y 138) over the floor (x -401 to 400, y 182), or her ledge (x -40 to 53, y 31) over hers. */
    @Override
    public Point groundBelow(Point at) {
        if (side == AgentRjpqSide.ZENUMIST) {
            if (at.x >= 128 && at.x <= 194 && at.y <= 138) {
                return new Point(at.x, 138);
            }
            return at.x >= -401 && at.x <= 400 && at.y <= 182 ? new Point(at.x, 182) : null;
        }
        if (at.x >= -40 && at.x <= 53 && at.y <= 31) {
            return new Point(at.x, 31);
        }
        return at.x >= -400 && at.x <= 404 && at.y <= 148 ? new Point(at.x, 148) : null;
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentRjpqSession session = new AgentRjpqSession(side, seed, leader.getId(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentRjpqMemberState.MemberType.HUMAN : AgentRjpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentRjpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "RJPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentRjpqSession rjpq) {
            AgentRjpqSessionRegistry.remove(rjpq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded(side.key(), session.sessionId(),
                ((AgentRjpqSession) session).operatorId(), success, success ? "completed" : "RJPQ stopped", 5_000L,
                pace);
    }

    /** MagatiaPQ's exit map for the side (926100700 / 926110700), where a released companion comes out. */
    @Override
    public int exitMap() {
        return side.map(AgentRjpqDefinition.EXIT_MAP);
    }
}
