package server.living.roster;

import server.agents.capabilities.partyquest.opq.AgentOpqAdmissionService;
import server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile;
import server.agents.capabilities.partyquest.opq.AgentOpqSessionRegistry;

/** OPQ's entrance as companion runs go in by it: Wonky's, at the foot of the Tower of Goddess (P6h OPQ step 5). */
final class OpqEntrance {
    /**
     * Wonky (Map.wz 200080101: npc 2013000 at x 198, y -47 on foothold 14) stands on a ledge (footholds 12-17, x -9 to
     * 396, y -34 to -62) above the floor (footholds 23-31, x -400 to 625, y 152-179), reached by the rope at x 30.
     * Extras wait on the floor past the ledge's right end, 30 px apart from x 408, so they never stand on her ledge,
     * hide her name or crowd the rope and the spawn point (x -233) on the left.
     */
    private static final PqEntrance.Beside RIGHT_OF_WONKYS_LEDGE = new PqEntrance.Beside(210, 30, true);
    /**
     * An owner stands at Wonky while on her ledge: within 210 px of her (the ledge runs from 207 px left of her to 198
     * px right), from 100 px above her (a jump on it) to 40 px below (its lowest point is 13 px below her; the floor is
     * 199 px down). Their companions wait at the extras' spots meanwhile, off the ledge.
     */
    private static final PqEntrance.KeepClear ON_WONKYS_LEDGE = new PqEntrance.KeepClear(210, 100, 40);

    private OpqEntrance() {
    }

    /** Wonky's entrance, with OPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // OPQ: the walkway's Lunar Pixie, the lounge's Cellion and the garden's Nependeath as stat proxies; five or six
        // go in (OrbisPQ.js), her own choice six; its admission prepares each companion on companion-ops, never on an
        // I/O thread; no background OPQ population exists
        return new PqEntrance("opq", 2_013_000, new PqEntrance.SeatRules(5, 6, 6),
                new int[] {9_300_045, 9_300_041, 9_300_048}, WonkyLines.VOICE,
                (member, eventLeader) -> AgentOpqAdmissionService.memberProblem(member),
                AgentOpqSessionRegistry::registrationBlocker, AgentOpqPaceProfile::load,
                AgentOpqSessionRegistry::forOperator, AgentOpqSessionRegistry::forMember, false,
                () -> false, RIGHT_OF_WONKYS_LEDGE, ON_WONKYS_LEDGE,
                (leader, members, seed, nowMs) -> {
                    AgentOpqAdmissionService.AdmissionResult admitted =
                            AgentOpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
