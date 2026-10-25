package server.agents.commands;

import client.Character;
import server.agents.runtime.AgentRuntimeHandle;

import java.util.function.Supplier;

/**
 * Where one reply goes, fixed when the line it answers was handled: the channel that line came on and, for a whisper,
 * whom to whisper back. A reply that waits (a model call, a judgment, a typing delay, the reply queue) keeps it, so a
 * party or map line from the owner in the meantime, which moves the Agent's reply channel, cannot send a whispered
 * answer to the party or the map (deploy review 2026-10-10).
 *
 * <p>While an Agent handles one line, that line's route is in scope for that Agent ({@link #within}): its replies said
 * or queued with no route of their own take it, and an answer the line's flow schedules for later
 * (AgentSchedulerRuntime.afterRandomDelay) carries it ({@link #carried}). So a command's answer that comes 400-2000 ms
 * later still goes back on the line's own channel, to the one who whispered it, whatever channel or interaction target
 * the Agent has by then (second deploy review 2026-10-10). Another Agent's replies said meanwhile keep their own.
 */
public record AgentReplyRoute(AgentReplyChannel channel, Character whisperTo) {
    /** The line in hand on this thread: whose it is and its route. */
    private record Scope(AgentRuntimeHandle agent, AgentReplyRoute route) {
    }

    private static final ThreadLocal<Scope> IN_SCOPE = new ThreadLocal<>();

    /** The route of a line {@code speaker} said on {@code channel}; null (read when sent) without a channel. */
    public static AgentReplyRoute of(AgentReplyChannel channel, Character speaker) {
        return channel == null ? null
                : new AgentReplyRoute(channel, channel == AgentReplyChannel.WHISPER ? speaker : null);
    }

    /** The route of the line {@code agent} is handling on this thread, or null outside one. */
    public static AgentReplyRoute current(AgentRuntimeHandle agent) {
        Scope scope = IN_SCOPE.get();
        return scope != null && agent != null && scope.agent() == agent ? scope.route() : null;
    }

    /**
     * Runs {@code work} while {@code agent} handles a line with {@code route} (null: no route, its replies read its
     * reply channel when sent), then puts back whatever was in scope before.
     */
    public static <T> T within(AgentRuntimeHandle agent, AgentReplyRoute route, Supplier<T> work) {
        return within(agent == null || route == null ? null : new Scope(agent, route), work);
    }

    /** {@code action} with the line in scope now, to run later in that scope; {@code action} itself outside one. */
    public static Runnable carried(Runnable action) {
        Scope scope = IN_SCOPE.get();
        if (scope == null || action == null) {
            return action;
        }
        return () -> within(scope, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T within(Scope scope, Supplier<T> work) {
        Scope before = IN_SCOPE.get();
        if (scope == null) {
            IN_SCOPE.remove();
        } else {
            IN_SCOPE.set(scope);
        }
        try {
            return work.get();
        } finally {
            if (before == null) {
                IN_SCOPE.remove();
            } else {
                IN_SCOPE.set(before);
            }
        }
    }
}
