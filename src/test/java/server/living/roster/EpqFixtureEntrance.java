package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.List;

/**
 * EPQ as the companion-run fixture plays it (P6g EPQ step 6.2): Ellin on her ledge at the edge of the Forest of Poison
 * Haze, her ledge and the lobby floor below it as Map.wz has them, an admission that registers a real HUMAN_ASSISTED
 * EPQ session with the lineup (Ann as operator and event leader, her first companion executing, as EPQ's companion
 * admission leads it), EPQ's registry removing it, and the run exiting to the forest's exit map.
 */
final class EpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** Ellin (Map.wz 300030100: npc 2133000 at x 314, y -63, on foothold 14 of her ledge). */
    static final Point ELLIN_AT = new Point(314, -63);
    /** The lobby floor (footholds 1-10): x -450 to 450 at y 150. */
    static final int FLOOR_Y = 150;
    /** Her ledge, footholds 11-16 of Map.wz 300030100, each {x1, y1, x2, y2}, left to right. */
    private static final List<int[]> LEDGE = List.of(new int[] {178, -27, 203, -38}, new int[] {203, -38, 269, -41},
            new int[] {269, -41, 298, -36}, new int[] {298, -36, 316, -42}, new int[] {316, -42, 360, -43},
            new int[] {360, -43, 398, -32});

    @Override
    public Point npcAt() {
        return ELLIN_AT;
    }

    @Override
    public Point groundBelow(Point at) {
        for (int[] foothold : LEDGE) {
            if (at.x >= foothold[0] && at.x <= foothold[2]) {
                int y = foothold[1] + (foothold[3] - foothold[1]) * (at.x - foothold[0]) / (foothold[2] - foothold[0]);
                if (at.y <= y) {
                    return new Point(at.x, y);
                }
            }
        }
        return at.x >= -450 && at.x <= 450 && at.y <= FLOOR_Y ? new Point(at.x, FLOOR_Y) : null;
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, seed, leader.getId(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentEpqMemberState.MemberType.HUMAN : AgentEpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentEpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "EPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentEpqSession epq) {
            AgentEpqSessionRegistry.remove(epq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("epq", session.sessionId(),
                ((AgentEpqSession) session).operatorId(), success, success ? "completed" : "EPQ stopped", 5_000L, pace);
    }

    /** EllinPQ.js's exitMap: the fountain at the forest's end (930000800), where a tapped-out companion comes out. */
    @Override
    public int exitMap() {
        return AgentEpqDefinition.REWARD_MAP;
    }
}
