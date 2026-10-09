package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 'LMPQ rescue:' line: one WARN per rescue the coordinator applies (a step the game would not take for a player),
 * in every mode. LMPQ has one: VACUUM, the map-wide coupon pickup (the gateway's lootNearby picks up every coupon on
 * the map, whatever its distance). A live run's lines show where the run still leans on it. Logging only: a note
 * never changes, and never stops, the rescue. Its shape is LPQ's, so pqkit parses both.
 */
final class AgentLmpqRescue {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqRescue.class);

    enum Kind { VACUUM }

    private AgentLmpqRescue() {
    }

    /**
     * Logs a rescue that was applied, after its world call returned. A site without its session (null) finds it by
     * the member. It never throws.
     */
    static void note(AgentLmpqSession session, Kind kind, String site, Character member, String detail) {
        try {
            AgentLmpqSession run = session != null ? session
                    : member == null ? null : AgentLmpqSessionRegistry.forMember(member.getId());
            log.warn("LMPQ rescue: session={} kind={} site={} phase={} member={}({}) applied={} companionRun={} "
                            + "detail={}", run == null ? "-" : run.sessionId(), kind, site,
                    run == null ? "-" : run.phase(), member == null ? "-" : member.getName(),
                    member == null ? "-" : member.getId(), true, run != null && run.companionAdopted(),
                    detail == null || detail.isEmpty() ? "-" : detail);
        } catch (RuntimeException failure) {
            log.warn("LMPQ rescue note failed: kind={} site={}", kind, site, failure);
        }
    }
}
