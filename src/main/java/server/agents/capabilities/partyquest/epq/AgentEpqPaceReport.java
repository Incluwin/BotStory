package server.agents.capabilities.partyquest.epq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A companion run's pace as the clear-time calibration reads it (the shape of AgentHpqPaceReport): one span per
 * segment of {@link AgentEpqPaceProfile}, cut at the boundaries its {@link AgentEpqPaceMarks} saw, following
 * {@link AgentPqPace}'s contract:
 * <pre>
 * yes      admission (the desk's Yes) -> the party inside (the bind)
 * trees    the party inside -> STAGE_TWO
 * bottles  STAGE_TWO -> STAGE_THREE
 * maze     STAGE_THREE -> STAGE_FOUR
 * marbles  STAGE_FOUR -> the twentieth marble at the leader's feet (or in his bag)
 * handin   the twentieth marble -> STAGE_FIVE
 * climb    STAGE_FIVE -> the purple stone at his feet (or in his bag)
 * sprite   the stone -> BOSS
 * altar    BOSS -> Poison Golem 9300180 first alive
 * golem    9300180 alive -> the event's clear
 * </pre>
 * A span is reported once it has begun; one whose end was not seen runs to the next boundary that was (or the report's
 * time) and is incomplete ("open"), and one that ends before it begins is "out-of-order", so the calibration does not
 * learn from either. Every span is fixed time to the calibration: its work is 0. At the release each span gets one
 * 'EPQ pace span:' line (measurement only).
 */
public final class AgentEpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqPaceReport.class);

    private AgentEpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, 0.0d, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    public static AgentPqPace of(AgentEpqSession session, long nowMs) {
        AgentEpqPaceMarks.Snapshot marks = session.pace().snapshot();
        return pace(session, marks, spans(session, marks, nowMs));
    }

    /** At the release: one line per begun span, beside its time at the priors while the profile is warm. Never throws. */
    static void logSpans(AgentEpqSession session, long nowMs) {
        try {
            AgentEpqPaceMarks.Snapshot marks = session.pace().snapshot();
            List<SpanReport> spans = spans(session, marks, nowMs);
            AgentPqPace pace = pace(session, marks, spans);
            AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentEpqPaceProfile.QUEST_KEY);
            for (SpanReport span : spans) log.info(line(session, span, theorySeconds(profile, pace, span)));
        } catch (RuntimeException failure) {
            log.warn("EPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    static String line(AgentEpqSession session, SpanReport span, double theorySeconds) {
        return "EPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " theorySeconds=" + (theorySeconds < 0.0d ? "-1" : String.format(Locale.ROOT, "%.1f", theorySeconds))
                + " complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why());
    }

    /** The segment's time at the priors for this run's members; -1 while the profile is cold or the party unread. */
    private static double theorySeconds(AgentPqPaceProfile profile, AgentPqPace pace, SpanReport span) {
        if (profile == null) return -1.0d;
        int size = pace.members().size();
        if (size < profile.minSize() || size > profile.maxSize()) return -1.0d;
        try {
            double seconds = AgentPqPaceEstimator.theorySeconds(profile, pace.members(),
                    AgentPqPaceCalibration.prior(profile), span.key(), 0.0d);
            return Double.isFinite(seconds) ? seconds : -1.0d;
        } catch (RuntimeException unsupported) {
            return -1.0d;
        }
    }

    private static AgentPqPace pace(AgentEpqSession session, AgentEpqPaceMarks.Snapshot marks,
                                    List<SpanReport> spans) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(session.eventLeaderId());
        marks.members().forEach(member -> ids.add(member.characterId()));
        ids.addAll(marks.entrants());
        // A member who left the run is still on record by what happened to it.
        ids.addAll(marks.deaths().keySet());
        ids.addAll(marks.tappedOut());
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (int id : ids) {
            runs.add(new AgentPqPace.MemberRun(id, marks.deaths().getOrDefault(id, 0), marks.tappedOut().contains(id),
                    0, 0));
        }
        return new AgentPqPace(AgentEpqPaceProfile.QUEST_KEY, session.sessionId(), session.eventLeaderId(),
                session.startedAtMs(), marks.clearedAtMs(), spans.stream().map(SpanReport::span).toList(),
                marks.members(), runs, Map.of());
    }

    /** The run's spans in the profile's order, cut from its marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentEpqSession session, AgentEpqPaceMarks.Snapshot marks, long endMs) {
        List<String> keys = List.of(AgentEpqPaceProfile.YES, AgentEpqPaceProfile.TREES, AgentEpqPaceProfile.BOTTLES,
                AgentEpqPaceProfile.MAZE, AgentEpqPaceProfile.MARBLES, AgentEpqPaceProfile.HANDIN,
                AgentEpqPaceProfile.CLIMB, AgentEpqPaceProfile.SPRITE, AgentEpqPaceProfile.ALTAR,
                AgentEpqPaceProfile.GOLEM);
        List<Long> bounds = List.of(session.startedAtMs(), marks.enteredAtMs(),
                marks.phase(AgentEpqSession.Phase.STAGE_TWO), marks.phase(AgentEpqSession.Phase.STAGE_THREE),
                marks.phase(AgentEpqSession.Phase.STAGE_FOUR), marks.marblesAtMs(),
                marks.phase(AgentEpqSession.Phase.STAGE_FIVE), marks.stoneAtMs(),
                marks.phase(AgentEpqSession.Phase.BOSS), marks.golemAtMs(), marks.clearedAtMs());
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            long from = bounds.get(i);
            if (from == 0L) continue;
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
