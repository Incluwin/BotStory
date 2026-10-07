package server.living.roster;

import server.agents.capabilities.partyquest.epq.AgentEpqAdmissionService;
import server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;

/** EPQ's entrance as companion runs go in by it: Ellin's, at the edge of the Forest of Poison Haze (P6g EPQ step 6.2). */
final class EpqEntrance {
    /**
     * Extras wait on the lobby floor (Map.wz 300030100: y 150, x -450 to 450) left of Ellin's ledge (footholds 11-16,
     * x 178-398), 30 px apart from x 164, so they never stand on her ledge or hide her name. Nobody stands by her: the
     * ledge is scenery above the floor, so the entrance keeps nothing clear around her.
     */
    private static final PqEntrance.Beside LEFT_OF_ELLINS_LEDGE = new PqEntrance.Beside(-150, -30, true);

    private EpqEntrance() {
    }

    /** Ellin's entrance, with EPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // EPQ: the trees, the flowers and the last golem as stat proxies; its admission prepares each companion on
        // companion-ops, never on an I/O thread; four to six go in (EllinPQ.js), her own choice six; no background EPQ
        return new PqEntrance("epq", 2_133_000, new PqEntrance.SeatRules(4, 6, 6),
                new int[] {9_300_172, 9_300_175, 9_300_182}, EllinLines.VOICE,
                (member, eventLeader) -> AgentEpqAdmissionService.memberProblem(member),
                AgentEpqSessionRegistry::registrationBlocker, AgentEpqPaceProfile::load,
                AgentEpqSessionRegistry::forOperator, AgentEpqSessionRegistry::forMember, false,
                () -> false, LEFT_OF_ELLINS_LEDGE, null,
                (leader, members, seed, nowMs) -> {
                    AgentEpqAdmissionService.AdmissionResult admitted =
                            AgentEpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
