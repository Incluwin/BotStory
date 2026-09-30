package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;

/** Independent lease watchdog so a KPQ cannot depend on one Agent tick for cleanup or progress. */
final class AgentKpqWatchdogRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentKpqWatchdogRuntime.class);
    private static ScheduledFuture<?> task;

    private AgentKpqWatchdogRuntime() {
    }

    static synchronized void ensureStarted() {
        if (task != null) return;
        long periodMs = Math.max(250L, config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqWatchdogRuntime.PERIOD_MS"));
        task = AgentSchedulerRuntime.register(AgentKpqWatchdogRuntime::tickSafely, periodMs);
    }

    private static void tickSafely() {
        long nowMs = System.currentTimeMillis();
        for (AgentKpqSession session : AgentKpqSessionRegistry.sessions()) {
            try {
                tick(session, nowMs);
            } catch (RuntimeException failure) {
                log.warn("KPQ watchdog failed for session {}", session.sessionId(), failure);
            }
        }
    }

    static void tick(AgentKpqSession session, long nowMs) {
        if (session == null || session.paused()
                || session.phase() == AgentKpqSession.Phase.COMPLETED
                || session.phase() == AgentKpqSession.Phase.FAILED) {
            return;
        }
        AgentRuntimeEntry candidateEntry = session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentKpqMemberState::partyNumber))
                .map(member -> AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(entry -> {
                    Character agent = AgentRuntimeIdentityRuntime.bot(entry);
                    return agent != null && agent.getHp() > 0;
                })
                .findFirst().orElse(null);
        if (candidateEntry == null) {
            if (session.companionAdopted() && awaitingRespawn(session, nowMs)) return;
            AgentKpqCoordinator.failWithoutLiveAgent(session, nowMs);
            return;
        }
        Character candidate = AgentRuntimeIdentityRuntime.bot(candidateEntry);
        long leaseMs = config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqRuntime.COORDINATOR_LEASE_MS");
        if (session.claimExpiredExecutionTick(candidate.getId(), nowMs, leaseMs)) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
    }

    /**
     * True when this watchdog handled a companion run whose Agents are all down. The stock respawn carries them out
     * of the event, where the coordinator asks the host to tap them out, so the run waits for it instead of failing
     * first. With fewer than three still standing the respawn would end the event anyway, so the run ends now and
     * says why.
     */
    static boolean awaitingRespawn(AgentKpqSession session, long nowMs) {
        long timeoutMs = config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");
        synchronized (session) {
            // Before entry a respawn stays in Kerning, so there is nothing to wait for.
            if (session.phase() == AgentKpqSession.Phase.PREPARING
                    || session.phase() == AgentKpqSession.Phase.ENTERING) {
                return false;
            }
            Character downed = null;
            for (AgentKpqMemberState member : session.members()) {
                if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
                Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
                if (agent == null) continue;
                if (agent.getHp() <= 0) member.markDown(nowMs);
                if (downed == null && member.down() && nowMs - member.downSinceMs() < timeoutMs) downed = agent;
            }
            if (downed == null) return false;
            if (AgentKpqCoordinator.shortHanded(session)) AgentKpqCoordinator.failShortHanded(session, downed, nowMs);
            return true;
        }
    }
}
