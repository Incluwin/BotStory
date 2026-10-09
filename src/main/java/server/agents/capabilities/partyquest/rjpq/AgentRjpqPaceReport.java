package server.agents.capabilities.partyquest.rjpq;

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
 * An RJPQ companion run's pace as the clear-time calibration reads it (the shape of AgentEpqPaceReport): one span per
 * segment of {@link AgentRjpqPaceProfile}, cut at the boundaries its {@link AgentRjpqPaceMarks} saw:
 * <pre>
 * books        the party inside (the bind) -> TUNNEL
 * tunnel       TUNNEL -> BEAKERS
 * beakers      BEAKERS -> KEYS
 * keys         KEYS -> both Dark Lab doors open
 * labs         both doors open -> both files in the leader's bag
 * handin       both files held -> the files handed in (statusStg4)
 * office       the files in -> UNITS
 * units        UNITS -> every companion out of the Lab Units
 * unitsLeader  the companions out -> the leader out of his room
 * boss         the leader out -> the event's clear
 * </pre>
 * A span is reported once it has begun; one whose end was not seen runs to the next boundary that was (or the report's
 * time) and is incomplete ("open"), and one that ends before it begins is "out-of-order". Every span is fixed time to
 * the calibration: its work is 0. At the release each span gets one 'RJPQ pace span:' line (measurement only).
 */
public final class AgentRjpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqPaceReport.class);

    private AgentRjpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, 0.0d, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    public static AgentPqPace of(AgentRjpqSession session, long nowMs) {
        AgentRjpqPaceMarks.Snapshot marks = session.pace().snapshot();
        return pace(session, marks, spans(session, marks, nowMs));
    }

    /** At the release: one line per begun span. Never throws. */
    static void logSpans(AgentRjpqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session, session.pace().snapshot(), nowMs)) {
                log.info("RJPQ pace span: session={} side={} key={} seconds={} complete={} why={}",
                        session.sessionId(), session.side().key(), span.key(),
                        String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d), span.complete(),
                        span.complete() ? "-" : span.why());
            }
        } catch (RuntimeException failure) {
            log.warn("RJPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    private static AgentPqPace pace(AgentRjpqSession session, AgentRjpqPaceMarks.Snapshot marks,
                                    List<SpanReport> spans) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(session.eventLeaderId());
        marks.members().forEach(member -> ids.add(member.characterId()));
        ids.addAll(marks.entrants());
        ids.addAll(marks.deaths().keySet());
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (int id : ids) {
            runs.add(new AgentPqPace.MemberRun(id, marks.deaths().getOrDefault(id, 0), false, 0, 0));
        }
        return new AgentPqPace(session.side().key(), session.sessionId(), session.eventLeaderId(),
                session.startedAtMs(), marks.at(AgentRjpqPaceMarks.Boundary.CLEARED),
                spans.stream().map(SpanReport::span).toList(), marks.members(), runs, Map.of());
    }

    /** The run's spans in the profile's order, cut from its marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentRjpqSession session, AgentRjpqPaceMarks.Snapshot marks, long endMs) {
        List<String> keys = List.of(AgentRjpqPaceProfile.BOOKS, AgentRjpqPaceProfile.TUNNEL,
                AgentRjpqPaceProfile.BEAKERS, AgentRjpqPaceProfile.KEYS, AgentRjpqPaceProfile.LABS,
                AgentRjpqPaceProfile.HANDIN, AgentRjpqPaceProfile.OFFICE, AgentRjpqPaceProfile.UNITS,
                AgentRjpqPaceProfile.UNITS_LEADER, AgentRjpqPaceProfile.BOSS);
        List<Long> bounds = List.of(marks.enteredAtMs(), marks.phase(AgentRjpqSession.Phase.TUNNEL),
                marks.phase(AgentRjpqSession.Phase.BEAKERS), marks.phase(AgentRjpqSession.Phase.KEYS),
                marks.at(AgentRjpqPaceMarks.Boundary.DOORS_OPEN), marks.at(AgentRjpqPaceMarks.Boundary.FILES_HELD),
                marks.at(AgentRjpqPaceMarks.Boundary.FILES_IN), marks.phase(AgentRjpqSession.Phase.UNITS),
                marks.at(AgentRjpqPaceMarks.Boundary.COMPANIONS_OUT), marks.at(AgentRjpqPaceMarks.Boundary.LEADER_OUT),
                marks.at(AgentRjpqPaceMarks.Boundary.CLEARED));
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            long from = bounds.get(i);
            if (from == 0L) {
                continue;
            }
            long to = bounds.get(i + 1);
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
            spans.add(new SpanReport(keys.get(i), from, to, why.isEmpty(), why));
        }
        return spans;
    }
}
