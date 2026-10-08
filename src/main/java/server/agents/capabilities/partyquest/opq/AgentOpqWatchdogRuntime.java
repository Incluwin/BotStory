package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;

/**
 * OPQ-only coordinator failover and orphan watchdog (a copy of LPQ's): a session whose lease holder stopped ticking
 * (dead or unloaded) is driven by its first live Agent, and a session with no live Agent left is ended, so a run
 * still ticks, fails or completes when none of its Agents' own ticks reach it.
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
        AgentRuntimeEntry entry = session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentOpqMemberState::characterId))
                .map(member -> AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()))
                .filter(java.util.Objects::nonNull).filter(candidate -> {
                    Character agent = AgentRuntimeIdentityRuntime.bot(candidate);
                    return agent != null && agent.getHp() > 0;
                }).findFirst().orElse(null);
        if (entry == null) {
            AgentOpqTerminationService.fail(session, "No live Agent remains", nowMs);
            return;
        }
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        long lease = AgentOpqRuntime.coordinatorLeaseMs();
        if (session.claimExpiredExecutionTick(agent.getId(), nowMs, lease, AgentOpqRuntime::holderAlive)) {
            AgentOpqCoordinator.tickSession(session, nowMs);
        }
    }
}
