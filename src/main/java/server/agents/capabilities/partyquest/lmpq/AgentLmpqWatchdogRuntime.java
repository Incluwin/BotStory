package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * LMPQ-only coordinator failover and orphan watchdog (LMPQ base fix 8, after LPQ's). A run is ticked by its Agents'
 * own ticks; when none of them holds a live lease (its holder died, unloaded or stopped ticking) the watchdog ticks
 * it through its first live Agent, or, while every loaded Agent is dead (each respawns on its own death tick),
 * through the lowest-id one, so the run's own rules (timer, departures, stall, leader) decide. A run whose Agents
 * all departed lets its humans carry on (released, nothing disposed) while its event still holds one, and is failed
 * otherwise; a run whose remaining Agents are none of them loaded is failed. Every run still ticks, fails or completes
 * and is released.
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
        if (session == null) return;
        if (session.terminal()) {
            // A run that ended but was never released (no Agent ticked it after its end) is released here. finish is
            // idempotent: a release already under way keeps it.
            if (!session.terminationStarted()) AgentLmpqTerminationService.finish(
                    session, session.failure(), nowMs, session.phase() == AgentLmpqSession.Phase.FAILED);
            return;
        }
        if (session.paused()) return;
        List<AgentLmpqMemberState> remaining = session.members().stream()
                .filter(member -> member.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .filter(member -> !session.departed(member.characterId())) // handed back: no longer the run's
                .sorted(Comparator.comparingInt(AgentLmpqMemberState::characterId))
                .toList();
        List<Character> loaded = remaining.stream()
                .map(member -> AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(AgentRuntimeIdentityRuntime::bot)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (remaining.isEmpty()) {
            // Every companion departed: humans still in the maze carry on without the run (released, nothing
            // disposed); a run whose event holds nobody is failed as before.
            if (AgentLmpqTerminationService.humansCarryOn(session, nowMs)) return;
            AgentLmpqCoordinator.fail(session, "Every LMPQ Agent departed", nowMs);
        } else if (loaded.isEmpty()) {
            AgentLmpqCoordinator.fail(session, "No LMPQ Agent is loaded", nowMs);
        } else {
            Character agent = loaded.stream().filter(candidate -> candidate.getHp() > 0).findFirst()
                    .orElse(loaded.getFirst());
            if (!session.claimExpiredExecutionTick(agent.getId(), nowMs, AgentLmpqRuntime.coordinatorLeaseMs(),
                    AgentLmpqRuntime::holderAlive)) return;
            AgentLmpqRuntime.coordinate(session, nowMs);
        }
        if (session.terminal()) AgentLmpqTerminationService.finish(
                session, session.failure(), nowMs, session.phase() == AgentLmpqSession.Phase.FAILED);
    }
}
