package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.lpq.AgentLpqDefinition;
import server.agents.capabilities.partyquest.lpq.AgentLpqMemberState;
import server.agents.capabilities.partyquest.lpq.AgentLpqSession;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.List;

/**
 * LPQ as the companion-run fixture plays it (P6g LPQ C5.2): the Red Sign on its ledge on the 101st floor of Eos Tower,
 * the ledge and the steps below it as Map.wz has them, an admission that registers a real PRODUCTION LPQ session with
 * the lineup (Ann as operator and event leader, her first companion executing, as LPQ's admission leads it), LPQ's
 * registry removing it, and the run exiting to the tower's exit map.
 */
final class LpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** The Red Sign (Map.wz 221024500: npc 2040034 at x 189, y 1664, on foothold 97). */
    static final Point RED_SIGN_AT = new Point(189, 1664);
    /**
     * The footholds around the sign, highest first, each {x1, x2, y}: the sign's ledge (97 and 95), the first step
     * (116) and the floor of steps below it (117, 118, 120, 121).
     */
    private static final List<int[]> FOOTHOLDS = List.of(new int[] {82, 265, 1670}, new int[] {19, 81, 1714},
            new int[] {-265, 21, 1787});

    @Override
    public Point npcAt() {
        return RED_SIGN_AT;
    }

    @Override
    public Point groundBelow(Point at) {
        for (int[] foothold : FOOTHOLDS) {
            if (at.x >= foothold[0] && at.x <= foothold[1] && at.y <= foothold[2]) {
                return new Point(at.x, foothold[2]);
            }
        }
        return null;
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, seed, leader.getId(),
                members.size(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentLpqMemberState.MemberType.HUMAN : AgentLpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentLpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "LPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentLpqSession lpq) {
            AgentLpqSessionRegistry.remove(lpq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("lpq", session.sessionId(),
                ((AgentLpqSession) session).operatorId(), success, success ? "completed" : "LPQ stopped", 5_000L, pace);
    }

    @Override
    public int exitMap() {
        return AgentLpqDefinition.EXIT_MAP;
    }
}
