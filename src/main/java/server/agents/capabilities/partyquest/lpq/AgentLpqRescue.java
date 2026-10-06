package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 'LPQ rescue:' line: one WARN per rescue the coordinator applies (a step the game would not take for a player:
 * a teleport, a map move, an event re-registration, a vacuumed drop, an item granted or moved between bags, a box
 * hit from afar, mobs killed with their drops, an NPC run from beyond reach), in every mode. A live run's lines show
 * which stages still lean on which rescues. Logging only: a note never changes, and never stops, the rescue.
 */
final class AgentLpqRescue {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqRescue.class);

    enum Kind {
        EVENT_REREGISTER, MAP_MOVE, TRAP_RETURN, TELEPORT, REMOTE_REACTOR, VACUUM, FORCE_TRANSFER, PASS_AWARD,
        PASS_RESTORE, KEY_AWARD, KILL_WITH_DROPS, REMOTE_NPC, MARKER_GRANT
    }

    private AgentLpqRescue() {
    }

    /**
     * True when a companion run withholds this rescue (D-a: LPQ's built-in rescues are off in companion runs): the
     * primitive then does nothing and returns "not applied". The first withheld (kind, site, member, phase) of a run
     * logs one WARN 'LPQ rescue: ... applied=false companionRun=true'. A stock run, or a member in no run, gets false
     * and nothing is logged, so the primitive goes on exactly as before. The run's flag is read before the logging,
     * so a logging failure never lets a rescue through. It never throws.
     */
    static boolean withhold(AgentLpqSession session, Kind kind, String site, Character member, String detail) {
        AgentLpqSession run;
        try {
            run = session != null ? session
                    : member == null ? null : AgentLpqSessionRegistry.forMember(member.getId());
        } catch (RuntimeException failure) {
            return false;
        }
        if (run == null || !run.companionAdopted()) return false;
        try {
            String key = kind + "|" + site + "|" + (member == null ? 0 : member.getId()) + "|" + run.phase();
            if (run.claimWithheldNote(key)) {
                log.warn("LPQ rescue: session={} kind={} site={} phase={} member={}({}) applied={} companionRun={} "
                                + "detail={}", run.sessionId(), kind, site, run.phase(),
                        member == null ? "-" : member.getName(), member == null ? "-" : member.getId(), false, true,
                        detail == null || detail.isEmpty() ? "-" : detail);
            }
        } catch (RuntimeException failure) {
            try {
                log.warn("LPQ rescue note failed: kind={} site={}", kind, site, failure);
            } catch (RuntimeException ignored) {
                // The rescue stays withheld whatever the logging does.
            }
        }
        return true;
    }

    /**
     * Logs a rescue that was applied, after its world calls returned. A site without its session (null) finds it by
     * the member. It never throws.
     */
    static void note(AgentLpqSession session, Kind kind, String site, Character member, String detail) {
        try {
            AgentLpqSession run = session != null ? session
                    : member == null ? null : AgentLpqSessionRegistry.forMember(member.getId());
            log.warn("LPQ rescue: session={} kind={} site={} phase={} member={}({}) applied={} companionRun={} detail={}",
                    run == null ? "-" : run.sessionId(), kind, site, run == null ? "-" : run.phase(),
                    member == null ? "-" : member.getName(), member == null ? "-" : member.getId(), true,
                    run != null && run.companionAdopted(), detail == null || detail.isEmpty() ? "-" : detail);
        } catch (RuntimeException failure) {
            log.warn("LPQ rescue note failed: kind={} site={}", kind, site, failure);
        }
    }
}
