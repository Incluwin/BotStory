package server.living.roster;

import server.agents.capabilities.partyquest.ppq.AgentPpqAdmissionService;
import server.agents.capabilities.partyquest.ppq.AgentPpqDefinition;
import server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile;
import server.agents.capabilities.partyquest.ppq.AgentPpqSessionRegistry;

/** PPQ's entrance as companion runs go in by it: Guon's, over the pirate ship (P6i PPQ step 6, plan 11.2). */
final class PpqEntrance {
    /**
     * Guon (Map.wz 251010404: npc 2094000 at x -1277, y 28 on foothold 9) stands on his platform (footholds 8-11, x
     * -1466 to -1152, y 45-48) above the floor (footholds 4-7, x -2001 to -1152, y 242); the rope at x -1503 joins
     * them. Extras wait on the floor left of his platform, 30 px apart from x -1697: under those spots every surface
     * above the floor (footholds 12-21, the walkway at y -25 to -103) lies above Guon's height, so each spot drops to
     * the floor, clear of his platform, the rope and the slope (foothold 16) that comes down to it.
     */
    private static final PqEntrance.Beside LEFT_OF_GUONS_PLATFORM = new PqEntrance.Beside(-420, -30, true);
    /**
     * An owner stands at Guon while on his platform or the deck just above it: within 190 px of him (the platform runs
     * from 189 px left of him to 125 px right), from 40 px above him (the deck above, footholds 1-3, y -4 to 22) to 25
     * px below (the platform at y 45-48; the floor is 214 px down). Their companions wait at the extras' spots meanwhile.
     */
    private static final PqEntrance.KeepClear ON_GUONS_PLATFORM = new PqEntrance.KeepClear(190, 40, 25);

    private PpqEntrance() {
    }

    /** Guon's entrance, with PPQ's own admission, registry and pace profile. */
    static PqEntrance create() {
        // PPQ: the medal waves' pirates and the decks' as stat proxies; four to six go in (the user's stock sizes,
        // U1), his own choice six; its admission prepares each companion on companion-ops, never on an I/O thread;
        // no background PPQ population exists
        return new PqEntrance("ppq", AgentPpqDefinition.ENTRY_NPC,
                new PqEntrance.SeatRules(AgentPpqDefinition.COMPANION_MIN_PARTY_SIZE,
                        AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE, AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE),
                new int[] {9_300_114, 9_300_115, 9_300_116, 9_300_124, 9_300_125}, GuonLines.VOICE,
                (member, eventLeader) -> AgentPpqAdmissionService.memberProblem(member),
                AgentPpqSessionRegistry::registrationBlocker, AgentPpqPaceProfile::load,
                AgentPpqSessionRegistry::forOperator, AgentPpqSessionRegistry::forMember, false,
                () -> false, LEFT_OF_GUONS_PLATFORM, ON_GUONS_PLATFORM,
                (leader, members, seed, nowMs) -> {
                    AgentPpqAdmissionService.AdmissionResult admitted =
                            AgentPpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    return new PqEntryPort.Admitted(admitted.success(), admitted.message(), admitted.session());
                });
    }
}
