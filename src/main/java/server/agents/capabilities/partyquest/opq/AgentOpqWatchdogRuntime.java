package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * OPQ-only coordinator failover and orphan watchdog (a copy of LPQ's, with LMPQ's dead-crew rule): a session whose
 * lease holder stopped ticking (dead or unloaded) is driven by its first live Agent, or, while every loaded Agent is
 * dead (each respawns or is held on its own death tick), by the lowest-id one, so the run's own rules (stall timer,
 * leader, departures, the death departure gate) decide. Only a session with no Agent loaded at all is ended here, so a
 * run still ticks, fails or completes when none of its Agents' own ticks reach it.
 */
final class AgentOpqWatchdogRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqWatchdogRuntime.class);
    private static ScheduledFuture<?> task;

    private AgentOpqWatchdogRuntime() { }

    static synchronized void ensureStarted() {
        if (task != null) return;
        long period = Math.max(250L, config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.opq.AgentOpqWatchdogRuntime.PERIOD_MS"));
        task = AgentSchedulerRuntime.register(() -> {
            long now = System.currentTimeMillis();
            AgentOpqSessionRegistry.sessions().forEach(session -> {
                try {
                    tick(session, now);
                } catch (RuntimeException failure) {
                    log.warn("OPQ watchdog tick failed: session={}", session.sessionId(), failure);
                }
            });
        }, period);
    }

    static void tick(AgentOpqSession session, long nowMs) {
        if (session == null || session.paused() || session.terminal()) return;
        // The bodies found here are the ones used below: reading them again could find an Agent unloaded meanwhile.
        List<Character> loaded = session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentOpqMemberState::characterId))
                .map(member -> AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(AgentRuntimeIdentityRuntime::bot)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (loaded.isEmpty()) {
            AgentOpqTerminationService.fail(session, "No OPQ Agent is loaded", nowMs);
            return;
        }
        // A dead crew is not an ended run: stock OrbisPQ only unregisters a dead member, and only above its floor.
        Character agent = loaded.stream().filter(candidate -> candidate.getHp() > 0).findFirst()
                .orElse(loaded.getFirst());
        long lease = AgentOpqRuntime.coordinatorLeaseMs();
        if (session.claimExpiredExecutionTick(agent.getId(), nowMs, lease, AgentOpqRuntime::holderAlive)) {
            AgentOpqCoordinator.tickSession(session, nowMs);
        }
    }
}
