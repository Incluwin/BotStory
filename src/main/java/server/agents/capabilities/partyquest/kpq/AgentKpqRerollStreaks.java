package server.agents.capabilities.partyquest.kpq;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each human leader's stage-1 re-rolls in a row (round 2b), so a leader is not offered one run after run: a re-roll
 * adds one, a run of his that ends any other way (a clear, a normal failure) clears it, and so does
 * {@code streakResetMs} without a re-roll. Written as a run is released, read as the next one decides; any thread.
 */
final class AgentKpqRerollStreaks {
    private static final Map<Integer, Streak> STREAKS = new ConcurrentHashMap<>();

    /** {@code count} re-rolls in a row, the last at {@code lastAtMs}. */
    private record Streak(int count, long lastAtMs) {
    }

    private AgentKpqRerollStreaks() {
    }

    /** The leader's re-rolls in a row as of {@code nowMs}: 0 once the last is {@code resetMs} old. */
    static int current(int leaderId, long nowMs, long resetMs) {
        Streak streak = STREAKS.get(leaderId);
        return streak == null || nowMs - streak.lastAtMs() >= resetMs ? 0 : streak.count();
    }

    /**
     * When the leader's last run was re-rolled (its release), while that counts toward his streak as of {@code nowMs};
     * 0 otherwise. The next run's decision measures what the restart cost from it.
     */
    static long lastRerollAtMs(int leaderId, long nowMs, long resetMs) {
        Streak streak = STREAKS.get(leaderId);
        return streak == null || nowMs - streak.lastAtMs() >= resetMs ? 0L : streak.lastAtMs();
    }

    /** The leader's run was re-rolled at {@code nowMs}. */
    static void rerolled(int leaderId, long nowMs, long resetMs) {
        STREAKS.compute(leaderId, (id, streak) -> new Streak(
                streak == null || nowMs - streak.lastAtMs() >= resetMs ? 1 : streak.count() + 1, nowMs));
    }

    /** The leader's run ended any other way: his streak starts over. */
    static void ended(int leaderId) {
        STREAKS.remove(leaderId);
    }

    /** Tests only: no leader has a streak. */
    static void resetForTest() {
        STREAKS.clear();
    }
}
