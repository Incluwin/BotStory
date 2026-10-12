package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

/**
 * The revive-inside death mode (P12 G6, AgentBossRunDefinition.DeathMode.REVIVE_INSIDE: Pink Bean). A player who dies
 * in such a fight and presses OK revives inside the event: the script's playerRevive answers the standard path, and
 * the respawn goes to the event's own instance of the battle map's return map (ChangeMapHandler, Character.respawn).
 * This gate copies that path for a dead INSIDE member of such a run, in place of the Agent's town respawn: the script
 * is asked first (eim.revivePlayer, so its death count and its rules hold), then the member respawns into the event's
 * map. A script that takes the other way, or a member the event no longer holds, gets the town respawn as before.
 *
 * <p>Every other Agent respawns exactly as before, in the same tick. It sits innermost in the death chain, directly
 * around the town respawn (AgentDeathTickCoordinator), and runs only once the respawn is due.</p>
 */
public final class AgentBossDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentBossDeathDepartureGate.class);

    private AgentBossDeathDepartureGate() {
    }

    /** Revives a revive-inside member inside its event, else runs {@code respawn}; answers true either way. */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        EventInstanceManager event = agent == null ? null : reviveEvent(agent);
        AgentRuntimeEntry entry = event == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        MapleMap map = entry == null ? null : agent.getMap();
        if (map == null) {
            respawn.run();
            return true;
        }
        boolean standard;
        try {
            standard = event.revivePlayer(agent);
        } catch (RuntimeException failure) {
            log.warn("Boss run revive script failed for member {}; respawning in town", agent.getId(), failure);
            standard = false;
        }
        if (!standard || agent.getEventInstance() != event) {
            respawn.run();
            return true;
        }
        MapleMap inside = event.getMapInstance(map.getReturnMapId());
        AgentRespawnCoordinator.respawnInto(entry, agent, inside);
        log.info("Boss run member revived inside: member={} from={} to={}", agent.getId(), map.getId(),
                inside == null ? -1 : inside.getId());
        return true;
    }

    /**
     * The event of the run the Agent is an INSIDE member of, while that run's dead revive inside and the Agent lies in
     * its event; else null.
     */
    static EventInstanceManager reviveEvent(Character agent) {
        AgentBossRunSession session = AgentBossRunRegistry.forMember(agent.getId());
        AgentBossRunMember member = session == null ? null : session.member(agent.getId());
        EventInstanceManager event = session == null ? null : session.event();
        return member != null && member.phase() == AgentBossRunMember.Phase.INSIDE
                && session.definition().deathMode() == AgentBossRunDefinition.DeathMode.REVIVE_INSIDE
                && event != null && !event.isEventDisposed() && agent.getEventInstance() == event ? event : null;
    }
}
