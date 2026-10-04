package server.living.roster;

import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile;
import server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;

/** HPQ's entrance as companion runs go in by it: Tory's, on her ledge in Henesys Park (P6b HPQ step 5). */
final class HpqEntrance {
    /**
     * Extras wait off Tory's ledge (Henesys Park: x 1357-1523 at y 364), on the park floor below past its end, so they
     * never crowd her or hide her name (P6b HPQ step 0).
     */
    private static final PqEntrance.Beside BELOW_TORY = new PqEntrance.Beside(120, 30, true);
    /**
     * An owner stands at Tory while on her ledge: within 100 px of her (the ledge runs from 77 px left of her to 89 px
     * right), from 100 px above her (a jump on it) to 40 px below (its floor; the step below it is 132 px down). Their
     * companions wait at the extras' spots meanwhile (P6b HPQ step 0: followers crowding her ledge hid her).
     */
    private static final PqEntrance.KeepClear ON_TORYS_LEDGE = new PqEntrance.KeepClear(100, 100, 40);

    private HpqEntrance() {
    }

    /** Tory's entrance, with HPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // HPQ: the hill's three monsters' stat proxies; its admission runs on companion-ops, never on an I/O thread
        return new PqEntrance("hpq", 1_012_112, new PqEntrance.SeatRules(3, 6, 4),
                new int[] {9_300_062, 9_300_063, 9_300_064}, ToryLines.VOICE,
                (member, eventLeader) -> AgentHpqAdmissionService.memberProblem(member),
                AgentHpqSessionRegistry::registrationBlocker, AgentHpqPaceProfile::load,
                AgentHpqSessionRegistry::forOperator, AgentHpqSessionRegistry::forMember, false,
                AgentHpqPopulationRuntime::backgroundEnabled, BELOW_TORY, ON_TORYS_LEDGE,
                (leader, members, seed, nowMs) -> {
                    AgentHpqAdmissionService.AdmissionResult admitted =
                            AgentHpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
