package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The walk back after a death (P15, decision 9). A member that died in a boss fight comes to outside, where the battle
 * map's return map puts it (Papulatus: Ludibrium 220000000). For a boss whose rules name a {@code deathWaitMap}
 * (Papulatus: the gate 220080000), it then walks there on foot, as a player would, and waits for its owner. Follow
 * would hold it in place meanwhile (its owner is in the event), so this step runs before follow on the Agent's own
 * tick, as a supply trip does (AgentTickPreflightRuntime), and spends the tick while it walks.
 *
 * <p>It walks while it is down outside (a follower out of the run by DIED), alive, its anchor is still in the run's
 * event and it is not on the wait map yet. Once there, or with no route, or after TIMEOUT_MS, it stops and waits where
 * it stands for the rest of that run. Memory only, besides the event's player read lock.</p>
 */
public final class AgentBossDeathWalk {
    private static final Logger log = LoggerFactory.getLogger(AgentBossDeathWalk.class);
    /** Agent id -> its walk in one run: the session id and when it started (stopped: -1). */
    private static final Map<Integer, Walk> WALKS = new ConcurrentHashMap<>();

    private record Walk(long sessionId, long startedAtMs) {
        boolean stopped() {
            return startedAtMs < 0;
        }
    }

    private AgentBossDeathWalk() {
    }

    /** One Agent tick, before follow: true spends the tick (it walks toward its boss's wait map). */
    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (entry == null || agent == null) {
            return false;
        }
        int id = agent.getId();
        AgentBossRunSession session = AgentBossRunRegistry.forMember(id); // one map read: the fast path
        AgentBossRunMember member = session == null ? null : session.member(id);
        int waitMap = member == null ? 0 : AgentBossRules.deathWaitMap(session.definition().key());
        if (waitMap <= 0 || !member.downOutside() || !anchorInRun(session, member)) {
            if (!WALKS.isEmpty()) {
                WALKS.remove(id);
            }
            return false;
        }
        Walk walk = WALKS.get(id);
        if (walk != null && walk.sessionId() != session.sessionId()) {
            walk = null;
        }
        if (walk != null && walk.stopped() || !agent.isAlive()) {
            return false;
        }
        if (agent.getMapId() == waitMap) {
            stop(id, session, agent, waitMap, "arrived");
            return false;
        }
        if (walk == null) {
            walk = new Walk(session.sessionId(), nowMs);
            WALKS.put(id, walk);
            log.info("Boss death walk: session={} boss={} member={} from map={} to wait map={}", session.sessionId(),
                    session.definition().key(), id, agent.getMapId(), waitMap);
        } else if (nowMs - walk.startedAtMs() >= timeoutMs()) {
            stop(id, session, agent, waitMap, "timed out");
            return false;
        }
        AgentRouteOutcome outcome = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(entry, agent, waitMap,
                nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            stop(id, session, agent, waitMap, "no route");
            return false;
        }
        return outcome.status() != AgentRouteStatus.ARRIVED;
    }

    /** True while {@code agentId} walks back in an open run (tests and the runtime report). */
    public static boolean walking(int agentId) {
        Walk walk = WALKS.get(agentId);
        return walk != null && !walk.stopped();
    }

    static void resetForTest() {
        WALKS.clear();
    }

    private static boolean anchorInRun(AgentBossRunSession session, AgentBossRunMember member) {
        EventInstanceManager event = session.event();
        return event != null && event.getPlayerById(member.anchorId()) != null;
    }

    private static void stop(int id, AgentBossRunSession session, Character agent, int waitMap, String why) {
        Walk was = WALKS.put(id, new Walk(session.sessionId(), -1L));
        if (was == null || !was.stopped()) {
            log.info("Boss death walk {}: session={} boss={} member={} on map={} wait map={}", why,
                    session.sessionId(), session.definition().key(), id, agent.getMapId(), waitMap);
        }
    }

    private static long timeoutMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.TIMEOUT_MS");
    }
}
