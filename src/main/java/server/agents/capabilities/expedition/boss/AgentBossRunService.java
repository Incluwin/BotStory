package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.expeditions.Expedition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opens boss runs for claimed Agents and keeps their monitors going. An enlister adopts only Agents it may control
 * and the host claims (the host's own companions, never an unclaimed or GM-spawned Agent), each free of every other
 * boss run and party quest. One run serves one expedition; a second enlister joins it.
 */
public final class AgentBossRunService {
    private static final Logger log = LoggerFactory.getLogger(AgentBossRunService.class);
    private static final Object ENLIST = new Object();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

    /** The run's id (0 when none holds the expedition) and the Agents it took and turned away, in the given order. */
    public record Enlisted(long sessionId, List<Integer> accepted, List<Integer> refused) {
        public Enlisted {
            accepted = List.copyOf(accepted);
            refused = List.copyOf(refused);
        }
    }

    private AgentBossRunService() {
    }

    /** Enlists {@code agentIds} for {@code expedition} on behalf of {@code human}; any thread. */
    public static Enlisted enlist(Character human, AgentBossRunDefinition definition, Expedition expedition,
                                  List<Integer> agentIds, long nowMs) {
        return enlist(AgentBossRunPorts.LIVE, human, definition, expedition, agentIds, nowMs);
    }

    static Enlisted enlist(AgentBossRunPorts ports, Character human, AgentBossRunDefinition definition,
                           Expedition expedition, List<Integer> agentIds, long nowMs) {
        List<Integer> ids = agentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(agentIds));
        if (human == null || definition == null || expedition == null || !expedition.isRegistering()) {
            return new Enlisted(0L, List.of(), ids);
        }
        List<Integer> accepted = new ArrayList<>();
        List<Integer> refused = new ArrayList<>();
        synchronized (ENLIST) {
            List<Integer> candidates = new ArrayList<>();
            for (int agentId : ids) {
                if (adoptable(human, agentId)) {
                    candidates.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
            if (candidates.isEmpty()) {
                return new Enlisted(session == null ? 0L : session.sessionId(), accepted, refused);
            }
            if (session == null) {
                session = new AgentBossRunSession(NEXT_SESSION_ID.incrementAndGet(), definition, expedition,
                        definition.fight().get(), nowMs);
                AgentBossRunRegistry.put(session);
                ports.prepareScripts(definition.entryNpcId());
                AgentBossRunSession opened = session;
                ports.schedule(() -> monitor(ports, opened), monitorMs());
                log.info("Boss run opened: session={} boss={} enlister={}", session.sessionId(), definition.key(),
                        human.getId());
            }
            for (int agentId : candidates) {
                if (session.addMember(agentId, human.getId(), nowMs) != null) {
                    accepted.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            log.info("Boss run enlist: session={} boss={} enlister={} accepted={} refused={}", session.sessionId(),
                    definition.key(), human.getId(), accepted, refused);
            return new Enlisted(session.sessionId(), accepted, refused);
        }
    }

    /**
     * A logout handoff: the INSIDE member {@code agentId} of run {@code sessionId} stays with {@code humanId} (the heir)
     * from now on instead of its enlister, and after the clear leaves when the heir leaves. False when the run is gone
     * or the Agent is no INSIDE member of it. Memory only, any thread; it takes no lock.
     */
    public static boolean handTo(long sessionId, int agentId, int humanId) {
        AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
        return session != null && session.handTo(agentId, humanId);
    }

    /** The enlister controls it, the host claims it, and it is in no boss run and no party quest. */
    private static boolean adoptable(Character human, int agentId) {
        return AgentClaimGuard.mayControl(human, agentId)
                && AgentClaimGuard.isClaimed(agentId)
                && AgentBossRunRegistry.forMember(agentId) == null
                && !AgentPartyQuestRuntime.active(agentId);
    }

    /** One tick of the run, then the next one; a failing tick ends the run instead of leaving it open. */
    static void monitor(AgentBossRunPorts ports, AgentBossRunSession session) {
        if (session.phase() == AgentBossRunSession.Phase.ENDED) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        AgentBossRunHost.Rule host = AgentBossRunHost.installed();
        try {
            session.tick(ports, host, nowMs);
        } catch (RuntimeException failure) {
            log.warn("Boss run tick failed: session={} boss={}", session.sessionId(), session.definition().key(),
                    failure);
            session.end(host, "error", nowMs);
        }
        if (session.phase() != AgentBossRunSession.Phase.ENDED) {
            ports.schedule(() -> monitor(ports, session), monitorMs());
        }
    }

    static long monitorMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunService.MONITOR_MS");
    }
}
