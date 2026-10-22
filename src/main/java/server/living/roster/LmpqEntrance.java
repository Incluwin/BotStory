package server.living.roster;

import server.agents.capabilities.partyquest.lmpq.AgentLmpqAdmissionService;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSessionRegistry;

/** LMPQ's entrance as companion runs go in by it: Rolly's, on his sky platform above Ludibrium (P6h Step 5). */
final class LmpqEntrance {
    /**
     * Rolly (Map.wz 220000000: npc 9103001 at x 76, y -712, on foothold 251) stands on a sky platform (foothold 251, x
     * -162 to 356, y -705) that players reach by the hidden portal ph00_1 at its right (x 242), with the steps up to
     * the spawn ledge (footholds 253-257, x 129 to 209) just right of him. Extras wait on his platform to his left, 26
     * px apart from 100 px left of him (x -24 to -154 for six), so they never crowd his click spot (x 26 to 126), the
     * steps or the portal the party arrives by.
     */
    private static final PqEntrance.Beside LEFT_OF_ROLLY = new PqEntrance.Beside(-100, -26, true);
    /**
     * An owner stands at Rolly while within 90 px of him (x -14 to 166: his click spot and the foot of the steps), from
     * 100 px above him (the spawn ledge, foothold 252, is 68 px up) to 40 px below (his platform is 7 px below him; the
     * next ground is 366 px down). Their companions wait at the extras' spots meanwhile, which start just outside it,
     * so none waits where he stands; the portal at x 242 is outside it too.
     */
    private static final PqEntrance.KeepClear AT_ROLLY = new PqEntrance.KeepClear(90, 100, 40);

    private LmpqEntrance() {
    }

    /** Rolly's entrance, with LMPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // LMPQ: the maze's Dark Stone Golem (the coupon box mob), Dark Jr. Yeti and Flyeye as stat proxies; three to
        // six go in (LudiMazePQ.js), his own choice six (user decision D3); its admission prepares each companion on
        // companion-ops, never on an I/O thread; no background LMPQ population exists
        return new PqEntrance("lmpq", 9_103_001, new PqEntrance.SeatRules(3, 6, 6),
                new int[] {9_400_211, 9_400_213, 9_400_217}, RollyLines.VOICE,
                (member, eventLeader) -> AgentLmpqAdmissionService.memberProblem(member),
                AgentLmpqSessionRegistry::registrationBlocker, AgentLmpqPaceProfile::load,
                AgentLmpqSessionRegistry::forOperator, AgentLmpqSessionRegistry::forMember, false,
                () -> false, LEFT_OF_ROLLY, AT_ROLLY,
                (leader, members, seed, nowMs) -> {
                    AgentLmpqAdmissionService.AdmissionResult admitted =
                            AgentLmpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
