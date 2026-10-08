package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.runtime.AgentSchedulerRuntime;

import java.util.concurrent.ScheduledFuture;

/**
 * Runs an RJPQ run's tick when no companion does: every companion can lie dead on its map (a dead Agent's frame ends
 * at its dead tick), and a finished run still needs its release. It takes the lease only when the lease has expired.
 */
final class AgentRjpqWatchdogRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqWatchdogRuntime.class);
    private static final long PERIOD_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqWatchdogRuntime.PERIOD_MS");
    private static final long LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqRuntime.COORDINATOR_LEASE_MS");
    private static ScheduledFuture<?> task;

    private AgentRjpqWatchdogRuntime() {
    }

    static synchronized void ensureStarted() {
        if (task == null) {
            task = AgentSchedulerRuntime.register(AgentRjpqWatchdogRuntime::tickSafely, PERIOD_MS);
        }
    }

    private static void tickSafely() {
        long nowMs = System.currentTimeMillis();
        for (AgentRjpqSession session : AgentRjpqSessionRegistry.sessions()) {
            try {
                tick(session, nowMs);
            } catch (RuntimeException failure) {
                log.warn("RJPQ watchdog failed for session {}", session.sessionId(), failure);
            }
        }
    }

    static void tick(AgentRjpqSession session, long nowMs) {
        if (session == null || session.paused()) {
            return;
        }
        if (!session.terminal() && session.claimExpiredExecutionTick(firstCompanionId(session), nowMs, LEASE_MS)) {
            AgentRjpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) {
            AgentRjpqTerminationService.release(session, session.failure(), nowMs,
                    session.phase() == AgentRjpqSession.Phase.FAILED);
        }
    }

    /** The lowest-id companion standing, else the lowest-id companion: the lease holder of a watchdog tick. */
    private static int firstCompanionId(AgentRjpqSession session) {
        int first = 0;
        for (AgentRjpqMemberState member : session.members()) {
            if (!member.agent()) {
                continue;
            }
            Character companion = AgentRjpqCoordinator.character(member.characterId());
            if (companion != null && companion.getHp() > 0) {
                return member.characterId();
            }
            if (first == 0) {
                first = member.characterId();
            }
        }
        return first;
    }
}
