package server.agents.capabilities.partyquest.pace;

import java.util.List;
import java.util.Map;

/**
 * How one party-quest run went, segment by segment: the measurement a run reports when it ends, and the record the
 * calibration learns from. Nothing in it is an estimate.
 *
 * <p>Span contract (what a quest's report must produce so the fit can read it):</p>
 * <ul>
 *     <li>One span per leaf segment of the profile, under the segment's key, with wall-clock {@code fromMs}/{@code toMs}.
 *     {@code complete} is false when either end was not observed (the run ended first, or the boundary cannot be seen);
 *     the fit skips incomplete spans.</li>
 *     <li>A {@link AgentPqPaceSegment.Fixed} segment that cannot be seen on its own (KPQ's stage-5 descent before the
 *     first kill) is reported incomplete with {@code fromMs == toMs} at the start of the next span; that next span then
 *     includes it, and the fit subtracts the fixed segment's current value from it.</li>
 *     <li>{@code work}: a {@link AgentPqPaceSegment.Quota} reports the kills it needed (KPQ: the coupons Cloto asked
 *     for); a {@link AgentPqPaceSegment.Puzzle} the judged checks, the last being the accepted one, its span running
 *     from the first judged check to the accept; a {@link AgentPqPaceSegment.Clear} the HP killed; a fixed segment
 *     0.</li>
 * </ul>
 *
 * @param clearedAtMs 0 unless the run cleared
 * @param members     each member's power when the run began (humans included)
 * @param drawn       the run's random content as drawn (KPQ: coupons asked for, checks per puzzle stage)
 */
public record AgentPqPace(String questKey, String sessionId, int leaderId, long startedAtMs, long clearedAtMs,
                          List<Span> spans, List<AgentPqMemberPower> members, List<MemberRun> runs,
                          Map<String, Integer> drawn) {
    public AgentPqPace {
        questKey = questKey == null ? "" : questKey;
        sessionId = sessionId == null ? "" : sessionId;
        spans = spans == null ? List.of() : List.copyOf(spans);
        members = members == null ? List.of() : List.copyOf(members);
        runs = runs == null ? List.of() : List.copyOf(runs);
        drawn = drawn == null ? Map.of() : Map.copyOf(drawn);
    }

    /** One segment as it happened. */
    public record Span(String key, long fromMs, long toMs, double work, boolean complete) {
        public long durationMs() {
            return toMs - fromMs;
        }
    }

    /** One member's run: how often it went down, whether it tapped out, and its first-stage attacks and kills. */
    public record MemberRun(int characterId, int deaths, boolean tappedOut, int stage1Attacks, int stage1Kills) {
    }

    public boolean cleared() {
        return clearedAtMs > 0L;
    }

    /** The span reported under {@code key}, or null. */
    public Span span(String key) {
        for (Span span : spans) {
            if (span.key().equals(key)) return span;
        }
        return null;
    }

    /** The member's run record, or null. */
    public MemberRun run(int characterId) {
        for (MemberRun run : runs) {
            if (run.characterId() == characterId) return run;
        }
        return null;
    }
}
