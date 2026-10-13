package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The walk back after a death. A member that died in a boss fight comes to outside, where the battle map's return map
 * puts it. Follow would hold it in place there (its owner is in the event), so this step runs before follow on the
 * Agent's own tick (AgentTickPreflightRuntime). It sets the walk's next move and the tick goes on, so the movement step
 * makes it ({@link Step#MOVING}; a spent tick left the Agent standing where it came to, P15 live 2026-10-13). While a
 * supply trip of its own is under way the walk waits and the live tick runs the trip:
 * <ul>
 * <li>P15, decision 9: for a boss whose rules name a {@code deathWaitMap} (Papulatus: from Ludibrium to the gate
 * 220080000), a member down outside walks there on foot and waits for its owner.</li>
 * <li>P17: in a re-enter run (DeathMode.REENTER: Scarlion and Targa), a REENTERING member walks back to the recruit
 * map (from Kampung to 551030100) and up to the recruit NPC (Aldol), where its run's tick has it talk its way back in
 * (AgentBossRunSession.tickReentering).</li>
 * <li>P18, plan decision 11a: when the boss rules name a {@code reentryWarpFrom} map (Horntail: no route reaches
 * 240050400, players come in by the Slate with an item companions do not carry), the member walks to that map instead
 * (Leafre, where it comes to) and, REENTRY_WARP_AFTER_MS after its walk began, is moved to the recruit map, then walks
 * up to the recruit NPC (the Mark of the Squad). In Leafre it first tops up at the store, as a companion with its
 * owner would ({@link #reentryRestockKey}). A companion's restock runs only in the live tick (objective supervision),
 * which a consumed tick here skips, so the walk leaves the tick to it while the restock still decides and while its
 * trip is under way (on the store map too), and the move waits meanwhile.</li>
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
    /** P18: moves an Agent to a map beside its portal 0 (the run's own way, AgentBossRunPorts.moveOut). */
    private static volatile BiConsumer<Character, Integer> mover = AgentBossRunPorts.LIVE::moveOut;

    private record Walk(long sessionId, int deaths, long startedAtMs) {
        boolean stopped() {
            return startedAtMs < 0;
        }
    }

    /** Where the member walks: a map, and on it the NPC it walks up to (0: none). */
    private record Target(int mapId, int npcId) {
    }

    /** What one tick of the walk asks of the rest of the Agent's tick. */
    public enum Step {
        /** No walk now: the tick goes on as usual. */
        NONE,
        /** It walks: the tick goes on so the movement step makes the move this step set; nothing else plans. */
        MOVING,
        /** It stands (P18, before its move in): the tick is spent. */
        HOLD
    }

    private AgentBossDeathWalk() {
    }

    /** One Agent tick, before follow. */
    public static Step tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (entry == null || agent == null) {
            return Step.NONE;
        }
        int id = agent.getId();
        AgentBossRunSession session = AgentBossRunRegistry.forMember(id); // one map read: the fast path
        AgentBossRunMember member = session == null ? null : session.member(id);
        Target target = member == null ? null : target(session, member);
        if (target == null || !anchorInRun(session, member)) {
            if (!WALKS.isEmpty()) {
                WALKS.remove(id);
            }
            return Step.NONE;
        }
        Walk walk = WALKS.get(id);
        if (walk != null && (walk.sessionId() != session.sessionId() || walk.deaths() != member.deaths())) {
            walk = null;
        }
        if (walk != null && walk.stopped() || !agent.isAlive()) {
            return Step.NONE;
        }
        if (walk == null) {
            walk = new Walk(session.sessionId(), member.deaths(), nowMs);
            WALKS.put(id, walk);
            log.info("Boss death walk: session={} boss={} member={} from map={} to map={} npc={}",
                    session.sessionId(), session.definition().key(), id, agent.getMapId(), target.mapId(),
                    target.npcId());
        } else if (nowMs - walk.startedAtMs() >= timeoutMs()) {
            stop(id, session, member, agent, target, "timed out");
            return Step.NONE;
        }
        if (agent.getMapId() == target.mapId()) {
            if (target.npcId() > 0) {
                return towardNpc(entry, agent, session, member, target);
            }
            stop(id, session, member, agent, target, "arrived");
            return Step.NONE;
        }
        if (supplyTripUnderWay(entry)) {
            return Step.NONE; // its own shop trip (potions or ammo) moves it, in the live tick
        }
        int warpFrom = reentryWarpFrom(session, member);
        if (warpFrom > 0 && agent.getMapId() == warpFrom) {
            long since = nowMs - walk.startedAtMs();
            if (AgentSupplyReadiness.restockPending(entry, nowMs) && since < AgentBossRunSession.verdictWaitMs()) {
                return Step.NONE; // its restock decides in the live tick, which a spent tick would skip
            }
            if (since >= reentryWarpAfterMs()) {
                log.info("Boss death walk moved in: session={} boss={} member={} from map={} to map={}",
                        session.sessionId(), session.definition().key(), id, warpFrom, target.mapId());
                mover.accept(agent, target.mapId());
            }
            return Step.HOLD; // it comes to and says its line before it is moved in; it stands meanwhile
        }
        int walkTo = warpFrom > 0 ? warpFrom : target.mapId();
        AgentRouteOutcome outcome = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(entry, agent, walkTo,
                nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            stop(id, session, member, agent, target, "no route");
            return Step.NONE;
        }
        return outcome.status() == AgentRouteStatus.ARRIVED ? Step.NONE : Step.MOVING;
    }

    /** True while {@code agentId} walks back in an open run (tests and the runtime report). */
    public static boolean walking(int agentId) {
        Walk walk = WALKS.get(agentId);
        return walk != null && !walk.stopped();
    }

    static void resetForTest() {
        WALKS.clear();
        mover = AgentBossRunPorts.LIVE::moveOut;
    }

    static void moverForTest(BiConsumer<Character, Integer> replacement) {
        mover = replacement;
    }

    /**
     * P18 follow-up (user 2026-10-12): the boss key whose standard a REENTERING member of a re-enter run keeps while it
     * stands on the map it is moved in from (Horntail: Leafre, where it comes to), so it tops up at the store there
     * before it goes back in; null for every other Agent. Memory only, any thread.
     */
    public static String reentryRestockKey(Character agent) {
        AgentBossRunSession session = agent == null ? null : AgentBossRunRegistry.forMember(agent.getId());
        AgentBossRunMember member = session == null ? null : session.member(agent.getId());
        int warpFrom = member == null ? 0 : reentryWarpFrom(session, member);
        return warpFrom > 0 && agent.getMapId() == warpFrom ? session.definition().key() : null;
    }

    /** True while a supply trip of the member's own is under way (potions or ammo: the procurement is active). */
    private static boolean supplyTripUnderWay(AgentRuntimeEntry entry) {
        return entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false);
    }

    /**
     * P18: the map a REENTERING member of a re-enter run is moved to the recruit map from (the boss rules'
     * {@code reentryWarpFrom}: Leafre for Horntail); 0 for none.
     */
    private static int reentryWarpFrom(AgentBossRunSession session, AgentBossRunMember member) {
        AgentBossRunDefinition definition = session.definition();
        return member.phase() == AgentBossRunMember.Phase.REENTERING
                && definition.deathMode() == AgentBossRunDefinition.DeathMode.REENTER
                ? AgentBossRules.reentryWarpFrom(definition.key()) : 0;
    }

    private static long reentryWarpAfterMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.REENTRY_WARP_AFTER_MS");
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

    /** On the target map: walks up to the NPC (MOVING); beside it, stops and waits (NONE). */
    private static Step towardNpc(AgentRuntimeEntry entry, Character agent, AgentBossRunSession session,
                                     AgentBossRunMember member, Target target) {
        Point npc = AgentPrimitiveCapabilityGatewayRuntime.gateway().npcPosition(agent, target.npcId());
        Point at = agent.getPosition();
        if (npc == null || at == null) {
            stop(agent.getId(), session, member, agent, target, "no NPC");
            return Step.NONE;
        }
        long reach = AgentBossRunSession.exitReachPx();
        if (at.distanceSq(npc) <= reach * reach) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            stop(agent.getId(), session, member, agent, target, "arrived");
            return Step.NONE;
        }
        AgentPrimitiveCapabilityGatewayRuntime.gateway().navigate(entry, npc, true);
        return Step.MOVING;
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
