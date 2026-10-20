package server.agents.capabilities.partyquest.lmpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A companion run's pace as measured so far, following {@link AgentPqPace}'s contract under the segment keys of
 * {@link AgentLmpqPaceProfile} (P6h Step 4): "farm" (FARMING to REGROUPING, its work the coupons the run counted as
 * secured when it regrouped), "walk" (FARMING to the leader's first arrival in the rendezvous room, run alongside the
 * farm), "handin" (REGROUPING to CLEARING), "pietri" (CLEARING to REWARD) and "claim" (REWARD to the completion), each
 * from its first entry to the first entry of the next boundary the run reached. A span whose end was not seen runs to
 * the report's time and is incomplete ("open"). The members are their power as the run entered the maze (none while
 * the profile was cold); the coupons the leader held at the clearing are drawn content. At the release each span gets
 * one 'LMPQ pace span:' line.
 */
final class AgentLmpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqPaceReport.class);
    static final String QUEST_KEY = AgentLmpqPaceProfile.QUEST_KEY;
    /** The phase spans, in run order, each from its phase to the next boundary reached. */
    private static final List<AgentLmpqSession.Phase> BOUNDARIES = List.of(
            AgentLmpqSession.Phase.FARMING, AgentLmpqSession.Phase.REGROUPING, AgentLmpqSession.Phase.CLEARING,
            AgentLmpqSession.Phase.REWARD, AgentLmpqSession.Phase.COMPLETED);
    private static final List<String> PHASE_KEYS = List.of(AgentLmpqPaceProfile.FARM, AgentLmpqPaceProfile.HANDIN,
            AgentLmpqPaceProfile.PIETRI, AgentLmpqPaceProfile.CLAIM);

    private AgentLmpqPaceReport() {
    }

    /** One span as the report cut it, with its work and why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, double work, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    static AgentPqPace of(AgentLmpqSession session, long nowMs) {
        long started = session.firstEnteredAtMs(AgentLmpqSession.Phase.FARMING);
        if (started == 0L) started = session.startedAtMs();
        Map<String, Integer> drawn = session.couponsAtClearing() > 0
                ? Map.of("coupons", session.couponsAtClearing()) : Map.of();
        return new AgentPqPace(QUEST_KEY, session.sessionId(), session.eventLeaderId(), started,
                session.firstEnteredAtMs(AgentLmpqSession.Phase.REWARD),
                spans(session, nowMs).stream().map(SpanReport::span).toList(), session.powersAtEntry(), List.of(),
                drawn);
    }

    /** At the release: one line per span. Never throws. */
    static void logSpans(AgentLmpqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session, nowMs)) log.info(line(session, span));
        } catch (RuntimeException failure) {
            log.warn("LMPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    static String line(AgentLmpqSession session, SpanReport span) {
        return "LMPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " theorySeconds=-1 complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why());
    }

    /**
     * The entered spans in the profile's leaf order (farm, walk, handin, pietri, claim); {@code endMs} closes one whose
     * end was not seen.
     */
    static List<SpanReport> spans(AgentLmpqSession session, long endMs) {
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < PHASE_KEYS.size(); i++) {
            long from = session.firstEnteredAtMs(BOUNDARIES.get(i));
            if (from == 0L) continue;
            long to = 0L;
            for (int next = i + 1; next < BOUNDARIES.size() && to == 0L; next++) {
                to = session.firstEnteredAtMs(BOUNDARIES.get(next));
            }
            String key = PHASE_KEYS.get(i);
            double work = key.equals(AgentLmpqPaceProfile.FARM) && to > 0L ? session.couponsAtRegroup() : 0.0d;
            spans.add(span(key, from, to, work, endMs));
            if (key.equals(AgentLmpqPaceProfile.FARM)) {
                spans.add(span(AgentLmpqPaceProfile.WALK, from, session.leaderArrivedAtMs(), 0.0d, endMs));
            }
        }
        return spans;
    }

    private static SpanReport span(String key, long from, long to, double work, long endMs) {
        String why = to == 0L ? "open" : to < from ? "out-of-order" : "";
        long end = to == 0L ? Math.max(from, endMs) : to;
        return new SpanReport(key, from, end, why.isEmpty() ? work : 0.0d, why.isEmpty(), why);
    }
}
