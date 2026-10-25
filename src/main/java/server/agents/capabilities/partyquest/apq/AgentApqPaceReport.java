package server.agents.capabilities.partyquest.apq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An APQ run's pace as the clear-time calibration reads it (the shape of AgentPpqPaceReport): one span per segment of
 * {@link AgentApqPaceProfile}, cut at the boundaries its {@link AgentApqPaceMarks} saw:
 * <pre>
 * mirror     the party inside (the bind) -> the leader on Heart Strings
 * ropes      Heart Strings -> the Twisted Switcher        (work: judged checks)
 * platforms  the Twisted Switcher -> Last Man Standing    (work: judged checks)
 * pieces     Last Man Standing -> fifty pieces in his bag (work: fifty kills)
 * handin     fifty in his bag -> Fluttering Hearts
 * gates      Fluttering Hearts -> Love Hurts
 * boss       Love Hurts -> Geist Balrog down
 * fang       Geist Balrog down -> the event's clear (his talk to Amos)
 * </pre>
 * A span is reported once it has begun; one whose end was not seen runs to the next boundary that was (or the report's
 * time) and is incomplete ("open"). At the release each span gets one 'APQ pace span:' line.
 */
public final class AgentApqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentApqPaceReport.class);
    private static final List<String> KEYS = List.of(AgentApqPaceProfile.MIRROR, AgentApqPaceProfile.ROPES,
            AgentApqPaceProfile.PLATFORMS, AgentApqPaceProfile.PIECES, AgentApqPaceProfile.HANDIN,
            AgentApqPaceProfile.GATES, AgentApqPaceProfile.BOSS, AgentApqPaceProfile.FANG);

    private AgentApqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, double work, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    public static AgentPqPace of(AgentApqSession session, long nowMs) {
        AgentApqPaceMarks.Snapshot marks = session.pace().snapshot();
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(session.eventLeaderId());
        marks.members().forEach(member -> ids.add(member.characterId()));
        ids.addAll(marks.entrants());
        ids.addAll(marks.deaths().keySet());
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (int id : ids) {
            runs.add(new AgentPqPace.MemberRun(id, marks.deaths().getOrDefault(id, 0), false, 0, 0));
        }
        return new AgentPqPace(AgentApqPaceProfile.QUEST_KEY, session.sessionId(), session.eventLeaderId(),
                session.startedAtMs(), marks.at(AgentApqPaceMarks.Boundary.CLEARED),
                spans(marks, nowMs).stream().map(SpanReport::span).toList(), marks.members(), runs,
                Map.copyOf(marks.checks()));
    }

    /** At the release: one line per begun span. Never throws. */
    static void logSpans(AgentApqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session.pace().snapshot(), nowMs)) {
                log.info("APQ pace span: session={} key={} seconds={} work={} complete={} why={}", session.sessionId(),
                        span.key(), String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d),
                        String.format(Locale.ROOT, "%.0f", span.work()), span.complete(),
                        span.complete() ? "-" : span.why());
            }
        } catch (RuntimeException failure) {
            log.warn("APQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    /** The run's spans in the profile's order, cut from its marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentApqPaceMarks.Snapshot marks, long endMs) {
        List<Long> bounds = List.of(marks.enteredAtMs(), marks.at(AgentApqPaceMarks.Boundary.ROPES),
                marks.at(AgentApqPaceMarks.Boundary.PLATFORMS), marks.at(AgentApqPaceMarks.Boundary.PIECES),
                marks.at(AgentApqPaceMarks.Boundary.PIECES_FULL), marks.at(AgentApqPaceMarks.Boundary.GATES),
                marks.at(AgentApqPaceMarks.Boundary.BOSS), marks.at(AgentApqPaceMarks.Boundary.BOSS_DOWN),
                marks.at(AgentApqPaceMarks.Boundary.CLEARED));
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < KEYS.size(); i++) {
            String key = KEYS.get(i);
            long from = bounds.get(i);
            long to = bounds.get(i + 1);
            if (from == 0L) continue;
            boolean closed = to != 0L;
            if (!closed) {
                to = Math.max(from, endMs);
                for (int next = i + 2; next < bounds.size(); next++) {
                    if (bounds.get(next) != 0L) {
                        to = Math.max(from, bounds.get(next));
                        break;
                    }
                }
            }
            String why = !closed ? "open" : to < from ? "out-of-order" : "";
            spans.add(new SpanReport(key, from, to, work(marks, key), why.isEmpty(), why));
        }
        return spans;
    }

    private static double work(AgentApqPaceMarks.Snapshot marks, String key) {
        if (AgentApqPaceProfile.ROPES.equals(key) || AgentApqPaceProfile.PLATFORMS.equals(key)) {
            return marks.checks().getOrDefault(key, 0);
        }
        return AgentApqPaceProfile.PIECES.equals(key) ? AgentApqDefinition.PIECES_FOR_GATES : 0.0d;
    }
}
