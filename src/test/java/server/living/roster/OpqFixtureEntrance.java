package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.opq.AgentOpqDefinition;
import server.agents.capabilities.partyquest.opq.AgentOpqMemberState;
import server.agents.capabilities.partyquest.opq.AgentOpqSession;
import server.agents.capabilities.partyquest.opq.AgentOpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.List;

/**
 * OPQ as the companion-run fixture plays it (P6h OPQ step 5): Wonky on her ledge at the foot of the Tower of Goddess,
 * her ledge and the floor below it as Map.wz has them, an admission that registers a real HUMAN_ASSISTED OPQ session
 * with the lineup (Ann as operator and event leader, her first companion executing, as OPQ's companion admission leads
 * it), OPQ's registry removing it, and the run exiting to the tower's exit map.
 */
final class OpqFixtureEntrance implements CompanionPqFixture.FixtureEntrance {
    /** Wonky (Map.wz 200080101: npc 2013000 at x 198, y -47, on foothold 14 of her ledge). */
    static final Point WONKY_AT = new Point(198, -47);
    /** Her ledge, footholds 12-17 of Map.wz 200080101, each {x1, y1, x2, y2}, left to right. */
    private static final List<int[]> LEDGE = List.of(new int[] {-9, -34, 61, -48}, new int[] {61, -48, 138, -36},
            new int[] {138, -36, 231, -45}, new int[] {231, -45, 302, -41}, new int[] {302, -41, 364, -59},
            new int[] {364, -59, 396, -62});
    /** The floor, footholds 23-31 of Map.wz 200080101, left to right. */
    private static final List<int[]> FLOOR = List.of(new int[] {-400, 174, 34, 174}, new int[] {34, 174, 144, 170},
            new int[] {144, 170, 174, 166}, new int[] {174, 166, 251, 174}, new int[] {251, 174, 300, 179},
            new int[] {300, 179, 431, 178}, new int[] {431, 178, 481, 166}, new int[] {481, 166, 577, 162},
            new int[] {577, 162, 625, 152});

    @Override
    public Point npcAt() {
        return WONKY_AT;
    }

    @Override
    public Point groundBelow(Point at) {
        Point ledge = below(LEDGE, at);
        return ledge != null ? ledge : below(FLOOR, at);
    }

    /** The first of {@code footholds} under {@code at}, at or below it; null when none is. */
    private static Point below(List<int[]> footholds, Point at) {
        for (int[] foothold : footholds) {
            if (at.x >= foothold[0] && at.x <= foothold[2]) {
                int y = foothold[1] + (foothold[3] - foothold[1]) * (at.x - foothold[0]) / (foothold[2] - foothold[0]);
                if (at.y <= y) {
                    return new Point(at.x, y);
                }
            }
        }
        return null;
    }

    @Override
    public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members, long seed,
                                      long nowMs) {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, seed, leader.getId(),
                members.size(), nowMs);
        for (Character member : members) {
            session.addMember(member.getId(), fixture.game.humans.contains(member.getId())
                    ? AgentOpqMemberState.MemberType.HUMAN : AgentOpqMemberState.MemberType.AGENT);
        }
        members.stream().filter(member -> !fixture.game.humans.contains(member.getId())).findFirst().ifPresent(agent ->
                session.setLeadership(leader.getId(), agent.getId())); // as its admission leads it
        AgentOpqSessionRegistry.registerComplete(session);
        return new PqEntryPort.Admitted(true, "OPQ party admitted", session);
    }

    @Override
    public void remove(AgentCompanionRunSession session) {
        if (session instanceof AgentOpqSession opq) {
            AgentOpqSessionRegistry.remove(opq);
        }
    }

    @Override
    public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession session, boolean success,
                                                     AgentPqPace pace) {
        return new AgentPartyQuestHandback.RunEnded("opq", session.sessionId(),
                ((AgentOpqSession) session).operatorId(), success, success ? "completed" : "OPQ stopped", 5_000L, pace);
    }

    /** OrbisPQ.js's exitMap (920011200), where a tapped-out companion comes out. */
    @Override
    public int exitMap() {
        return AgentOpqDefinition.EXIT_MAP;
    }
}
