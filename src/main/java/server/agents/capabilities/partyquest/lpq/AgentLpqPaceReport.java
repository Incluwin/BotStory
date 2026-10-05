package server.agents.capabilities.partyquest.lpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A companion run's pace as measured so far, following {@link AgentPqPace}'s contract with provisional keys: one span
 * per stage the run entered, "stage1".."stage9", from the stage's first entry to the first entry of a later stage or
 * the bonus (the clear). A stage whose end was not seen runs to the report's time and is incomplete. Every span is
 * fixed time (work 0); stage 8 records the checks it took. Step 4's pace profile defines the real segments and this
 * report is rewritten to them before anything consumes it. At the release each span gets one 'LPQ pace span:' line.
 */
final class AgentLpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqPaceReport.class);
    static final String QUEST_KEY = "lpq";

    private AgentLpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, 0.0d, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    static AgentPqPace of(AgentLpqSession session, long nowMs) {
        long started = session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_1);
        if (started == 0L) started = session.startedAtMs();
        Map<String, Integer> drawn = session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_8) == 0L
                ? Map.of() : Map.of("stage8Checks", session.stage8Attempt() + 1);
        return new AgentPqPace(QUEST_KEY, session.sessionId(), session.eventLeaderId(), started,
                session.firstEnteredAtMs(AgentLpqSession.Phase.BONUS),
                spans(session, nowMs).stream().map(SpanReport::span).toList(), List.of(), List.of(), drawn);
    }

    /** At the release: one line per span. Never throws. */
    static void logSpans(AgentLpqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session, nowMs)) log.info(line(session, span));
        } catch (RuntimeException failure) {
            log.warn("LPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    static String line(AgentLpqSession session, SpanReport span) {
        return "LPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " theorySeconds=-1 complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why());
    }

    /** The entered stages' spans in stage order; {@code endMs} closes one whose end was not seen. */
    static List<SpanReport> spans(AgentLpqSession session, long endMs) {
        List<AgentLpqSession.Phase> boundaries = new ArrayList<>();
        for (AgentLpqDefinition.Stage stage : AgentLpqDefinition.stages()) {
            boundaries.add(AgentLpqSession.Phase.valueOf("STAGE_" + stage.number()));
        }
        boundaries.add(AgentLpqSession.Phase.BONUS);
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            long from = session.firstEnteredAtMs(boundaries.get(i));
            if (from == 0L) continue;
            long to = 0L;
            for (int next = i + 1; next < boundaries.size() && to == 0L; next++) {
                to = session.firstEnteredAtMs(boundaries.get(next));
            }
            String why = to == 0L ? "open" : to < from ? "out-of-order" : "";
            if (to == 0L) to = Math.max(from, endMs);
            spans.add(new SpanReport("stage" + (i + 1), from, to, why.isEmpty(), why));
        }
        return spans;
    }
}
