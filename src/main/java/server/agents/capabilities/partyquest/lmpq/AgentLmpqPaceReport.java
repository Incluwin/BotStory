package server.agents.capabilities.partyquest.lmpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A companion run's pace as measured so far, following {@link AgentPqPace}'s contract with provisional keys: "farm"
 * (FARMING to REGROUPING), "regroup" (REGROUPING to CLEARING), "clear" (CLEARING to REWARD) and "reward" (REWARD to
 * the completion), each from the phase's first entry to the first entry of the next boundary the run reached. A span
 * whose end was not seen runs to the report's time and is incomplete ("open"). Every span is fixed time (work 0); the
 * coupons the leader held at the clearing are drawn content. Step 4's pace profile defines the real segments and this
 * report is rewritten to them before anything consumes it. At the release each span gets one 'LMPQ pace span:' line.
 */
final class AgentLmpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqPaceReport.class);
    static final String QUEST_KEY = "lmpq";
    private static final List<AgentLmpqSession.Phase> BOUNDARIES = List.of(
            AgentLmpqSession.Phase.FARMING, AgentLmpqSession.Phase.REGROUPING, AgentLmpqSession.Phase.CLEARING,
            AgentLmpqSession.Phase.REWARD, AgentLmpqSession.Phase.COMPLETED);
    private static final List<String> KEYS = List.of("farm", "regroup", "clear", "reward");

    private AgentLmpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, 0.0d, complete);
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
                spans(session, nowMs).stream().map(SpanReport::span).toList(), List.of(), List.of(), drawn);
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

    /** The entered phases' spans in phase order; {@code endMs} closes one whose end was not seen. */
    static List<SpanReport> spans(AgentLmpqSession session, long endMs) {
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < KEYS.size(); i++) {
            long from = session.firstEnteredAtMs(BOUNDARIES.get(i));
            if (from == 0L) continue;
            long to = 0L;
            for (int next = i + 1; next < BOUNDARIES.size() && to == 0L; next++) {
                to = session.firstEnteredAtMs(BOUNDARIES.get(next));
            }
            String why = to == 0L ? "open" : to < from ? "out-of-order" : "";
            if (to == 0L) to = Math.max(from, endMs);
            spans.add(new SpanReport(KEYS.get(i), from, to, why.isEmpty(), why));
        }
        return spans;
    }
}
