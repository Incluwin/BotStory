package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDefinition;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqMemberState;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSession;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.List;

/**
 * LMPQ as the companion-run fixture plays it (P6h Step 5): Rolly on his sky platform above Ludibrium, the spawn ledge
 * and steps above it and the ground far below as Map.wz 220000000 has them, an admission that registers a real
 * HUMAN_LEADER LMPQ session with the lineup (Ann as operator and event leader, her first companion executing, as LMPQ's
 * companion admission leads it), LMPQ's registry removing it, and the run exiting to the maze's exit map.
 */
final class LmpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** Rolly (Map.wz 220000000: npc 9103001 at x 76, y -712, on foothold 251). */
    static final Point ROLLY_AT = new Point(76, -712);
    /**
     * Footholds of Map.wz 220000000 around him, each {x1, y1, x2, y2}, top to bottom: the spawn ledge (252), the steps
     * down from it (253-257), his platform (251) and the ground below it (150).
     */
    private static final List<int[]> FOOTHOLDS = List.of(new int[] {52, -780, 181, -780},
            new int[] {129, -758, 169, -758}, new int[] {136, -747, 178, -747}, new int[] {146, -737, 188, -737},
            new int[] {156, -727, 198, -727}, new int[] {167, -717, 209, -717}, new int[] {-162, -705, 356, -705},
            new int[] {-204, -211, 396, -211});

    @Override
    public Point npcAt() {
        return ROLLY_AT;
    }

    /** The first foothold under {@code at}, at or below it; null when none is. */
    @Override
    public Point groundBelow(Point at) {
        for (int[] foothold : FOOTHOLDS) {
            if (at.x >= foothold[0] && at.x <= foothold[2] && at.y <= foothold[1]) {
                return new Point(at.x, foothold[1]);
            }
        }
        return null;
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, seed, leader.getId(),
                members.size(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentLmpqMemberState.MemberType.HUMAN : AgentLmpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentLmpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "LMPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentLmpqSession lmpq) {
            AgentLmpqSessionRegistry.remove(lmpq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("lmpq", session.sessionId(),
                ((AgentLmpqSession) session).operatorId(), success, success ? "completed" : "LMPQ stopped", 5_000L,
                pace);
    }

    /** LudiMazePQ.js's exit map (809050017), where a tapped-out companion comes out. */
    @Override
    public int exitMap() {
        return AgentLmpqDefinition.EXIT_MAP;
    }
}
