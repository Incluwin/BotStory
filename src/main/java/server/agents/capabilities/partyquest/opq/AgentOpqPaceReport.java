package server.agents.capabilities.partyquest.opq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A companion run's pace as measured so far, following {@link AgentPqPace}'s contract under the segment keys of
 * {@link AgentOpqPaceProfile}: one span per part of the run it entered, from that part's first phase entry to the first
 * entry of a later part: "entrance" (ENTRANCE), "rooms" (SPLIT_ROOMS), "statue" (RESTORING_STATUE), "garden" (GARDEN),
 * "root" (RESTORING_MINERVA) and "claim" (CLAIMING_REWARD, to the completion). A part whose end was not seen runs to
 * the report's time and is incomplete. Every span is fixed time (work 0). The puzzle counters are drawn only when they
 * moved (they are capped at 9, and a human-led run does not advance them). At the release each span gets one
 * 'OPQ pace span:' line.
 */
final class AgentOpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqPaceReport.class);
    static final String QUEST_KEY = AgentOpqPaceProfile.QUEST_KEY;
    private static final List<AgentOpqSession.Phase> BOUNDARIES = List.of(
            AgentOpqSession.Phase.ENTRANCE, AgentOpqSession.Phase.SPLIT_ROOMS, AgentOpqSession.Phase.RESTORING_STATUE,
            AgentOpqSession.Phase.GARDEN, AgentOpqSession.Phase.RESTORING_MINERVA,
            AgentOpqSession.Phase.CLAIMING_REWARD, AgentOpqSession.Phase.COMPLETED);
    private static final List<String> KEYS = List.of(AgentOpqPaceProfile.ENTRANCE, AgentOpqPaceProfile.ROOMS,
            AgentOpqPaceProfile.STATUE, AgentOpqPaceProfile.GARDEN, AgentOpqPaceProfile.ROOT,
            AgentOpqPaceProfile.CLAIM);

    private AgentOpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, 0.0d, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}; cleared when the coordinator saw Minerva freed. */
    static AgentPqPace of(AgentOpqSession session, long nowMs) {
        long started = session.firstEnteredAtMs(AgentOpqSession.Phase.ENTRANCE);
        if (started == 0L) started = session.startedAtMs();
        Map<String, Integer> drawn = new LinkedHashMap<>();
        if (session.sealedAttempt() > 0) drawn.put("sealedWrongChecks", session.sealedAttempt());
        if (session.wayUpLeverAttempt() > 0) drawn.put("leverChecks", session.wayUpLeverAttempt());
        return new AgentPqPace(QUEST_KEY, session.sessionId(), session.eventLeaderId(), started,
                session.clearSeenAtMs(), spans(session, nowMs).stream().map(SpanReport::span).toList(),
                List.of(), List.of(), drawn);
    }

    /** At the release: one line per span. Never throws. */
    static void logSpans(AgentOpqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session, nowMs)) log.info(line(session, span));
        } catch (RuntimeException failure) {
            log.warn("OPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    static String line(AgentOpqSession session, SpanReport span) {
        return "OPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " theorySeconds=-1 complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why());
    }

    /** The entered parts' spans in run order; {@code endMs} closes one whose end was not seen. */
    static List<SpanReport> spans(AgentOpqSession session, long endMs) {
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
