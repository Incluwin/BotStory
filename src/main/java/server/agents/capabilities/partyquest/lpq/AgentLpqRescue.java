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
