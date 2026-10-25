package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.partyquest.apq.AgentApqMemberState;
import server.agents.capabilities.partyquest.apq.AgentApqSession;
import server.agents.capabilities.partyquest.apq.AgentApqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.List;

/**
 * APQ as the companion-run fixture plays it (APQ plan A7): Amos on the floor of the Entrance of the Amorian Challenge,
 * the floor, the short platform above it and the slope down to the west as Map.wz 670010100 has them, an admission that
 * registers a real APQ session with the lineup (the leader as operator and event leader, the first companion
 * executing, as APQ's admission leads it), APQ's registry removing it, and a released companion held on Amos's map.
 */
final class ApqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** Amos (Map.wz 670010100: npc 9201048 at x 926, y 2095, on foothold 49). */
    static final Point AMOS_AT = new Point(926, 2_095);
    /** The footholds of Map.wz 670010100 around Amos, each {x1, y1, x2, y2} (28-58, the vertical ones left out). */
    private static final List<int[]> FOOTHOLDS = List.of(new int[] {14, 2220, 315, 2220},
            new int[] {315, 2220, 405, 2160}, new int[] {405, 2160, 495, 2100}, new int[] {503, 2045, 632, 2045},
            new int[] {495, 2100, 1244, 2100});

    @Override
    public Point npcAt() {
        return AMOS_AT;
    }

    /** The nearest foothold surface at or below {@code at}; null when none is. */
    @Override
    public Point groundBelow(Point at) {
        Integer best = null;
        for (int[] foothold : FOOTHOLDS) {
            if (at.x >= foothold[0] && at.x <= foothold[2]) {
                int y = foothold[1] + (foothold[3] - foothold[1]) * (at.x - foothold[0]) / (foothold[2] - foothold[0]);
                if (at.y <= y && (best == null || y < best)) {
                    best = y;
                }
            }
        }
        return best == null ? null : new Point(at.x, best);
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentApqSession session = new AgentApqSession(seed, leader.getId(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentApqMemberState.MemberType.HUMAN : AgentApqMemberState.MemberType.AGENT, member.getGender());
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentApqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "APQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentApqSession apq) {
            AgentApqSessionRegistry.remove(apq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("apq", session.sessionId(),
                ((AgentApqSession) session).operatorId(), success, success ? "completed" : "APQ stopped", 5_000L, pace);
    }

    /** Amos's map (670010100): the stock exit sends players out to Amos' Training Ground, inside his gate. */
    @Override
    public int exitMap() {
        return AgentApqDefinition.RECRUIT_MAP;
    }
}
