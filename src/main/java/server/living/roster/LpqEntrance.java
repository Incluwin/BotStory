package server.living.roster;

import server.agents.capabilities.partyquest.lpq.AgentLpqAdmissionService;
import server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;

/** LPQ's entrance as companion runs go in by it: the Red Sign's, on the 101st floor of Eos Tower (P6g LPQ step 5). */
final class LpqEntrance {
    /**
     * Extras wait on the steps left of the sign's ledge (Map.wz 221024500: the ledge runs x 82-265 at y 1670; the steps
     * below it are x 19-81 at y 1714 and x -253-21 at y 1787), never on the ledge, so they never crowd the sign or hide
     * it from the leader's click.
     */
    private static final PqEntrance.Beside ON_THE_STEPS = new PqEntrance.Beside(-110, -40, true);
    /**
     * An owner stands at the sign while on its ledge: within 110 px of it, from 100 px above it (a jump on the ledge)
     * to 40 px below (the ledge is 6 px below the sign; the first step is 50 px down). Their companions wait at the
     * extras' spots meanwhile.
     */
    private static final PqEntrance.KeepClear ON_THE_SIGN_LEDGE = new PqEntrance.KeepClear(110, 100, 40);

    private LpqEntrance() {
    }

    /** The Red Sign's entrance, with LPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // LPQ: the fights every member joins (stage 7's Rombard, Alishar); no background LPQ population exists, and its
        // admission runs on companion-ops (it prepares each companion's activity), never on an I/O thread
        return new PqEntrance("lpq", 2_040_034, new PqEntrance.SeatRules(5, 6, 6),
                new int[] {9_300_010, 9_300_012}, RedSignLines.VOICE,
                (member, eventLeader) -> AgentLpqAdmissionService.memberProblem(member),
                AgentLpqSessionRegistry::registrationBlocker, AgentLpqPaceProfile::load,
                AgentLpqSessionRegistry::forOperator, AgentLpqSessionRegistry::forMember, false, () -> false,
                ON_THE_STEPS, ON_THE_SIGN_LEDGE,
                (leader, members, seed, nowMs) -> {
                    AgentLpqAdmissionService.AdmissionResult admitted =
                            AgentLpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
