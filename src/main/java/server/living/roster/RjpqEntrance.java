package server.living.roster;

import server.agents.capabilities.partyquest.rjpq.AgentRjpqAdmissionService;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;

/**
 * Romeo and Juliet PQ's entrances as companion runs go in by them (plan W9), one per side: Romeo's hidden room for the
 * Zenumist side ("rjpq"), Juliet's for the Alcadno side ("rjpqj").
 */
final class RjpqEntrance {
    /**
     * Romeo (Map.wz 261000011: npc 2112004 at x 158, y 132 on his step, footholds 5-6, x 128-194, y 138) above the
     * floor (y 182, x -401 to 400): extras wait on the floor left of his step, 30 px apart from x 48, so they never
     * stand on it or hide his name.
     */
    private static final PqEntrance.Beside LEFT_OF_ROMEOS_STEP = new PqEntrance.Beside(-110, -30, true);
    /** An owner within 100 px of Romeo, from 100 above to 60 below him (the floor under his step): his companions wait. */
    private static final PqEntrance.KeepClear AT_ROMEOS_STEP = new PqEntrance.KeepClear(100, 100, 60);
    /**
     * Juliet (Map.wz 261000021: npc 2112003 at x 10, y 28 on her ledge, footholds 6-7, x -40 to 53, y 31) above the
     * floor (y 148, x -400 to 404): extras wait on the floor left of her ledge, from x -120.
     */
    private static final PqEntrance.Beside LEFT_OF_JULIETS_LEDGE = new PqEntrance.Beside(-130, -30, true);
    /** An owner within 100 px of Juliet, from 100 above to 130 below her (the floor under her ledge). */
    private static final PqEntrance.KeepClear AT_JULIETS_LEDGE = new PqEntrance.KeepClear(100, 100, 130);
    /** The mobs a companion is weighed against: the tunnel's, the beakers' Homunculus, the Neo Huroid, a Mutae. */
    private static final int[] DPS_MOBS = {9_300_145, 9_300_146, 9_300_147, 9_300_148, 9_300_143};

    private RjpqEntrance() {
    }

    /** The side's entrance, with RJPQ's own admission, registry and pace profile. */
    static PqEntrance create(AgentRjpqSide side) {
        // RJPQ: exactly four (MagatiaPQ_Z.js / _A.js); its admission prepares each companion on companion-ops, never
        // on an I/O thread; no background RJPQ
        boolean zenumist = side == AgentRjpqSide.ZENUMIST;
        return new PqEntrance(side.key(), side.entryNpcId(),
                new PqEntrance.SeatRules(AgentRjpqSide.PARTY_SIZE, AgentRjpqSide.PARTY_SIZE, AgentRjpqSide.PARTY_SIZE),
                DPS_MOBS, RjpqLines.voice(side),
                (member, eventLeader) -> AgentRjpqAdmissionService.memberProblem(side, member),
                AgentRjpqSessionRegistry::registrationBlocker, () -> AgentRjpqPaceProfile.load(side),
                AgentRjpqSessionRegistry::forOperator, AgentRjpqSessionRegistry::forMember, false,
                () -> false, zenumist ? LEFT_OF_ROMEOS_STEP : LEFT_OF_JULIETS_LEDGE,
                zenumist ? AT_ROMEOS_STEP : AT_JULIETS_LEDGE,
                (leader, members, seed, nowMs) -> {
                    AgentRjpqAdmissionService.AdmissionResult admitted =
                            AgentRjpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
