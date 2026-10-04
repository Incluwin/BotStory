package server.living.roster;

import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;

/** KPQ's entrance as companion runs go in by it: Lakelis's, at the Kerning City party-quest door. */
final class KpqEntrance {
    /** Extras wait beside Lakelis: 40 px on from her, 30 px apart, where she stands. */
    private static final PqEntrance.Beside BESIDE_LAKELIS = new PqEntrance.Beside(40, 30, false);

    private KpqEntrance() {
    }

    /** Lakelis's entrance, with KPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // KPQ: stage 1's Ligator, stage 5's Jr. Necki and King Slime; admitted and started in one I/O step, as before
        return new PqEntrance("kpq", 9_020_000, new PqEntrance.SeatRules(3, 4, 4),
                new int[] {9_300_001, 9_300_000, 9_300_003}, LakelisLines.VOICE,
                AgentKpqAdmissionService::memberProblem, AgentKpqSessionRegistry::registrationBlocker,
                AgentKpqPaceProfile::load, AgentKpqSessionRegistry::forOperator,
                AgentKpqSessionRegistry::forMember, true, () -> false,
                BESIDE_LAKELIS, null, (leader, members, seed, nowMs) -> {
                    AgentKpqAdmissionService.AdmissionResult admitted =
                            AgentKpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
