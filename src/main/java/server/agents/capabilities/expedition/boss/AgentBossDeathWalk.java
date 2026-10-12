package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The walk back after a death. A member that died in a boss fight comes to outside, where the battle map's return map
 * puts it. Follow would hold it in place there (its owner is in the event), so this step runs before follow on the
 * Agent's own tick, as a supply trip does (AgentTickPreflightRuntime), and spends the tick while it walks:
 * <ul>
 * <li>P15, decision 9: for a boss whose rules name a {@code deathWaitMap} (Papulatus: from Ludibrium to the gate
 * 220080000), a member down outside walks there on foot and waits for its owner.</li>
 * <li>P17: in a re-enter run (DeathMode.REENTER: Scarlion and Targa), a REENTERING member walks back to the recruit
 * map (from Kampung to 551030100) and up to the recruit NPC (Aldol), where its run's tick has it talk its way back in
 * (AgentBossRunSession.tickReentering).</li>
 * </ul>
 *
 * <p>It walks while it is alive, its anchor is still in the run's event, and it is not there yet. Once there, or with
 * no route, or after TIMEOUT_MS, it stops for the rest of that death. Memory only, besides the event's player read
 * lock.</p>
 */
public final class AgentBossDeathWalk {
    private static final Logger log = LoggerFactory.getLogger(AgentBossDeathWalk.class);
    /** Agent id -> its walk after one death in one run: the session, the death count, when it began (stopped: -1). */
    private static final Map<Integer, Walk> WALKS = new ConcurrentHashMap<>();

    private record Walk(long sessionId, int deaths, long startedAtMs) {
        boolean stopped() {
            return startedAtMs < 0;
        }
    }

    /** Where the member walks: a map, and on it the NPC it walks up to (0: none). */
    private record Target(int mapId, int npcId) {
    }

    private AgentBossDeathWalk() {
    }

    /** One Agent tick, before follow: true spends the tick (it walks toward its target). */
    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (entry == null || agent == null) {
            return false;
        }
        int id = agent.getId();
        AgentBossRunSession session = AgentBossRunRegistry.forMember(id); // one map read: the fast path
        AgentBossRunMember member = session == null ? null : session.member(id);
        Target target = member == null ? null : target(session, member);
        if (target == null || !anchorInRun(session, member)) {
            if (!WALKS.isEmpty()) {
                WALKS.remove(id);
            }
            return false;
        }
        Walk walk = WALKS.get(id);
        if (walk != null && (walk.sessionId() != session.sessionId() || walk.deaths() != member.deaths())) {
            walk = null;
        }
        if (walk != null && walk.stopped() || !agent.isAlive()) {
            return false;
        }
        if (walk == null) {
            walk = new Walk(session.sessionId(), member.deaths(), nowMs);
            WALKS.put(id, walk);
            log.info("Boss death walk: session={} boss={} member={} from map={} to map={} npc={}",
                    session.sessionId(), session.definition().key(), id, agent.getMapId(), target.mapId(),
                    target.npcId());
        } else if (nowMs - walk.startedAtMs() >= timeoutMs()) {
            stop(id, session, member, agent, target, "timed out");
            return false;
        }
        if (agent.getMapId() == target.mapId()) {
            if (target.npcId() > 0) {
                return towardNpc(entry, agent, session, member, target);
            }
            stop(id, session, member, agent, target, "arrived");
            return false;
        }
        AgentRouteOutcome outcome = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(entry, agent,
                target.mapId(), nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            stop(id, session, member, agent, target, "no route");
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

    /** The member's walk target now, or null for none. */
    private static Target target(AgentBossRunSession session, AgentBossRunMember member) {
        AgentBossRunDefinition definition = session.definition();
        if (member.phase() == AgentBossRunMember.Phase.REENTERING
                && definition.deathMode() == AgentBossRunDefinition.DeathMode.REENTER) {
            return new Target(definition.recruitMapId(), definition.entryNpcId());
        }
        int waitMap = member.downOutside() ? AgentBossRules.deathWaitMap(definition.key()) : 0;
        return waitMap > 0 ? new Target(waitMap, 0) : null;
    }

    /** On the target map: walks up to the NPC and spends the tick; beside it, stops and waits (false). */
    private static boolean towardNpc(AgentRuntimeEntry entry, Character agent, AgentBossRunSession session,
                                     AgentBossRunMember member, Target target) {
        Point npc = AgentPrimitiveCapabilityGatewayRuntime.gateway().npcPosition(agent, target.npcId());
        Point at = agent.getPosition();
        if (npc == null || at == null) {
            stop(agent.getId(), session, member, agent, target, "no NPC");
            return false;
        }
        long reach = AgentBossRunSession.exitReachPx();
        if (at.distanceSq(npc) <= reach * reach) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            stop(agent.getId(), session, member, agent, target, "arrived");
            return false;
        }
        AgentPrimitiveCapabilityGatewayRuntime.gateway().navigate(entry, npc, true);
        return true;
    }

    private static boolean anchorInRun(AgentBossRunSession session, AgentBossRunMember member) {
        EventInstanceManager event = session.event();
        return event != null && event.getPlayerById(member.anchorId()) != null;
    }

    private static void stop(int id, AgentBossRunSession session, AgentBossRunMember member, Character agent,
                             Target target, String why) {
        Walk was = WALKS.put(id, new Walk(session.sessionId(), member.deaths(), -1L));
        if (was == null || !was.stopped()) {
            log.info("Boss death walk {}: session={} boss={} member={} on map={} target map={} npc={}", why,
                    session.sessionId(), session.definition().key(), id, agent.getMapId(), target.mapId(),
                    target.npcId());
        }
    }

    private static long timeoutMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.TIMEOUT_MS");
    }
}
