package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.ppq.AgentPpqDefinition;
import server.agents.capabilities.partyquest.ppq.AgentPpqMemberState;
import server.agents.capabilities.partyquest.ppq.AgentPpqSession;
import server.agents.capabilities.partyquest.ppq.AgentPpqSessionRegistry;

import java.awt.Point;
import java.util.List;

/**
 * PPQ as the companion-run fixture plays it (P6i PPQ step 6, plan 11.4): Guon on his platform over the pirate ship, his
 * platform, the floor below it and the walkway above as Map.wz has them, an admission that registers a real
 * HUMAN_LEADER PPQ session with the lineup (Ann as operator and event leader, her first companion executing, as PPQ's
 * companion admission leads it), PPQ's registry removing it, and a tapped-out companion held on Guon's map.
 */
final class PpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** Guon (Map.wz 251010404: npc 2094000 at x -1277, y 28, on foothold 9 of his platform). */
    static final Point GUON_AT = new Point(-1_277, 28);
    /** Every non-vertical foothold of Map.wz 251010404, each {x1, y1, x2, y2}: the deck over him, the floor, the
     *  walkway and slope on the left, and his platform. */
    private static final List<int[]> FOOTHOLDS = List.of(new int[] {-1364, -4, -1278, -2},
            new int[] {-1278, -2, -1172, 11}, new int[] {-1172, 11, -1135, 22}, new int[] {-2001, 242, -1893, 242},
            new int[] {-1893, 242, -1576, 242}, new int[] {-2001, -25, -1893, -25}, new int[] {-1893, -25, -1812, -25},
            new int[] {-1787, -25, -1681, -25}, new int[] {-1683, -26, -1681, -25}, new int[] {-1681, -25, -1575, 49},
            new int[] {-1812, -25, -1700, -103}, new int[] {-1812, -25, -1787, -25},
            new int[] {-1593, -103, -1489, -103}, new int[] {-1576, 242, -1258, 242}, new int[] {-1365, 45, -1260, 45},
            new int[] {-1466, 48, -1394, 45}, new int[] {-1394, 45, -1365, 45}, new int[] {-1575, 49, -1558, 49},
            new int[] {-1258, 242, -1152, 242}, new int[] {-1260, 45, -1152, 45}, new int[] {-1489, -103, -1469, -103},
            new int[] {-1700, -103, -1593, -103});

    @Override
    public Point npcAt() {
        return GUON_AT;
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
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, seed, leader.getId(), false,
                nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentPpqMemberState.MemberType.HUMAN : AgentPpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentPpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "PPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentPpqSession ppq) {
            AgentPpqSessionRegistry.remove(ppq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("ppq", session.sessionId(),
                ((AgentPpqSession) session).operatorId(), success, success ? "completed" : "PPQ stopped", 5_000L, pace);
    }

    /** Guon's map (251010404): a tapped-out PPQ companion is held there, as Guon's own exit sends players. */
    @Override
    public int exitMap() {
        return AgentPpqDefinition.RECRUIT_MAP;
    }
}
