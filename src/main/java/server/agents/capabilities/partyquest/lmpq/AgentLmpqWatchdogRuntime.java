package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;

/**
 * LMPQ-only coordinator failover and orphan watchdog (LMPQ base fix 8, a copy of LPQ's). A run is ticked by its
 * Agents' own ticks; when none of them holds a live lease (its holder died, unloaded or stopped ticking) the watchdog
 * ticks it through the first live Agent, and when no Agent is alive at all it fails the run, so every run still
 * ticks, fails or completes and is released.
 */
final class AgentLmpqWatchdogRuntime {
    private static ScheduledFuture<?> task;

    private AgentLmpqWatchdogRuntime() { }

    static synchronized void ensureStarted() {
        if (task != null) return;
        long period = Math.max(250L, config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.lmpq.AgentLmpqWatchdogRuntime.PERIOD_MS"));
        task = AgentSchedulerRuntime.register(() -> {
            long now = System.currentTimeMillis();
            AgentLmpqSessionRegistry.sessions().forEach(session -> tick(session, now));
        }, period);
    }

    static void tick(AgentLmpqSession session, long nowMs) {
        if (session == null || session.paused() || session.terminal()) return;
        AgentRuntimeEntry entry = session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentLmpqMemberState::characterId))
                .map(member -> AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()))
                .filter(java.util.Objects::nonNull).filter(candidate -> {
                    Character agent = AgentRuntimeIdentityRuntime.bot(candidate);
                    return agent != null && agent.getHp() > 0;
                }).findFirst().orElse(null);
        if (entry == null) {
            AgentLmpqCoordinator.fail(session, "No live Agent remains", nowMs);
        } else {
            Character agent = AgentRuntimeIdentityRuntime.bot(entry);
            if (!session.claimExpiredExecutionTick(agent.getId(), nowMs, AgentLmpqRuntime.coordinatorLeaseMs(),
                    AgentLmpqRuntime::holderAlive)) return;
            AgentLmpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) AgentLmpqTerminationService.finish(
                session, session.failure(), nowMs, session.phase() == AgentLmpqSession.Phase.FAILED);
    }
}
