package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The boss runs' own death modes, in place of the Agent's town respawn.
 *
 * <p>Revive inside (P12 G6, AgentBossRunDefinition.DeathMode.REVIVE_INSIDE: Pink Bean). A player who dies in such a
 * fight and presses OK revives inside the event: the script's playerRevive answers the standard path, and the respawn
 * goes to the event's own instance of the battle map's return map (ChangeMapHandler, Character.respawn). This gate
 * copies that path for a dead INSIDE member of such a run: the script is asked first (eim.revivePlayer, so its death
 * count and its rules hold), then the member respawns into the event's map. A script that takes the other way, or a
 * member the event no longer holds, gets the town respawn as before.</p>
 *
 * <p>Hold until the clear (DeathMode.HOLD_UNTIL_CLEAR: Crimsonwood Keep). A revive there unregisters the member and
 * ends the run for everyone once six or fewer are left (CWKPQ.js playerRevive), so a dead INSIDE member stays down
 * where it fell, as a player who has not pressed OK, and asks again on its next tick: while the event lives, is not
 * cleared and still holds it. After the clear it respawns in town as before.</p>
 *
 * <p>Every other Agent respawns exactly as before, in the same tick. It sits innermost in the death chain, directly
 * around the town respawn (AgentDeathTickCoordinator), and runs only once the respawn is due.</p>
 */
public final class AgentBossDeathDepartureGate {
    private static final Logger log = LoggerFactory.getLogger(AgentBossDeathDepartureGate.class);
    /** The held members, each logged once per stay down. */
    private static final Set<Integer> HELD = ConcurrentHashMap.newKeySet();

    private AgentBossDeathDepartureGate() {
    }

    /**
     * Revives a revive-inside member inside its event and answers true; holds a hold-until-clear member and answers
     * false; else runs {@code respawn} and answers true.
     */
    public static boolean respawnOrHold(Character agent, Runnable respawn, long nowMs) {
        if (agent != null && holdEvent(agent) != null) {
            if (HELD.add(agent.getId())) {
                log.info("Boss run member stays down until the clear: member={} map={}", agent.getId(),
                        agent.getMapId());
            }
            return false;
        }
        if (agent != null && HELD.remove(agent.getId())) {
            log.info("Boss run member released after its hold: member={} map={}", agent.getId(), agent.getMapId());
        }
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
        return insideEvent(agent, AgentBossRunDefinition.DeathMode.REVIVE_INSIDE);
    }

    /**
     * The event of the run the Agent is an INSIDE member of, while that run's dead stay down until the clear, the
     * event is not cleared and the Agent lies in it; else null.
     */
    static EventInstanceManager holdEvent(Character agent) {
        EventInstanceManager event = insideEvent(agent, AgentBossRunDefinition.DeathMode.HOLD_UNTIL_CLEAR);
        return event != null && !event.isEventCleared() ? event : null;
    }

    private static EventInstanceManager insideEvent(Character agent, AgentBossRunDefinition.DeathMode mode) {
        AgentBossRunSession session = AgentBossRunRegistry.forMember(agent.getId());
        AgentBossRunMember member = session == null ? null : session.member(agent.getId());
        EventInstanceManager event = session == null ? null : session.event();
        return member != null && member.phase() == AgentBossRunMember.Phase.INSIDE
                && session.definition().deathMode() == mode
                && event != null && !event.isEventDisposed() && agent.getEventInstance() == event ? event : null;
    }
}
