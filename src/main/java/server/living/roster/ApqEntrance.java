package server.living.roster;

import server.agents.capabilities.partyquest.apq.AgentApqAdmissionService;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.partyquest.apq.AgentApqPaceProfile;
import server.agents.capabilities.partyquest.apq.AgentApqSessionRegistry;

/** APQ's entrance as companion runs go in by it: Amos's, at the Entrance of the Amorian Challenge (APQ plan A7). */
final class ApqEntrance {
    /**
     * Amos (Map.wz 670010100: npc 9201048 at x 926, y 2095 on foothold 49) stands on the floor (footholds 40-58, x 495 to
     * 1244, y 2100), under a short platform (foothold 42, x 503-632, y 2045). Extras wait on the floor left of him, 30 px
     * apart from x 776: every spot lies on the floor, right of the platform, and clear of his name.
     */
    private static final PqEntrance.Beside LEFT_OF_AMOS = new PqEntrance.Beside(-150, -30, true);
    /** An owner within 100 px of Amos, from 60 above to 30 below him: their companions wait meanwhile. */
    private static final PqEntrance.KeepClear AT_AMOS = new PqEntrance.KeepClear(100, 60, 30);
    /** The mobs a companion is weighed against: the Mirror's three, Last Man Standing's, Geist Balrog's last phase. */
    private static final int[] DPS_MOBS = {9_400_533, 9_400_534, 9_400_535, 9_400_524, 9_400_514};

    private ApqEntrance() {
    }

    /** Amos's entrance, with APQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // APQ: exactly six (AmoriaPQ.js), every member married, both genders (LineupComposition); its admission
        // prepares each companion on companion-ops, never on an I/O thread; no background APQ
        return new PqEntrance("apq", AgentApqDefinition.ENTRY_NPC,
                new PqEntrance.SeatRules(AgentApqDefinition.PARTY_SIZE, AgentApqDefinition.PARTY_SIZE,
                        AgentApqDefinition.PARTY_SIZE),
                DPS_MOBS, AmosLines.VOICE,
                (member, eventLeader) -> AgentApqAdmissionService.memberProblem(member),
                AgentApqSessionRegistry::registrationBlocker, AgentApqPaceProfile::load,
                AgentApqSessionRegistry::forOperator, AgentApqSessionRegistry::forMember, false,
                () -> false, LEFT_OF_AMOS, AT_AMOS,
                (leader, members, seed, nowMs) -> {
                    AgentApqAdmissionService.AdmissionResult admitted =
                            AgentApqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
