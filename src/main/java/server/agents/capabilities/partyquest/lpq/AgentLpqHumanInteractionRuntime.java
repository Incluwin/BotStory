package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receives authored LPQ outcomes that cannot be inferred safely from map polling. */
public final class AgentLpqHumanInteractionRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqHumanInteractionRuntime.class);

    private AgentLpqHumanInteractionRuntime() { }

    public static void stageEightChecked(Character leader, boolean correct, long nowMs) {
        if (leader == null) return;
        AgentLpqSession session = AgentLpqSessionRegistry.forMember(leader.getId());
        AgentLpqMemberState member = session == null ? null : session.member(leader.getId());
        if (session == null || member == null
                || member.memberType() != AgentLpqMemberState.MemberType.HUMAN
                || leader.getId() != session.eventLeaderId()
                || session.phase() != AgentLpqSession.Phase.STAGE_8) return;
        // The Blue Balloon counts any check by a GM above level 1 as correct (2040043.js).
        log.info("LPQ stage8 check: session={} attempt={} correct={} gmBypass={} leader={}({})",
                session.sessionId(), session.stage8Attempt() + 1, correct, correct && leader.gmLevel() > 1,
                leader.getName(), leader.getId());
        if (correct) session.markProgress(nowMs);
        else if (session.companionAdopted()) AgentLpqCoordinator.stageEightCheckMissed(session, leader, nowMs);
        else session.advanceStage8(nowMs);
    }
}
