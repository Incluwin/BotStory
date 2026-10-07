package server.agents.capabilities.partyquest.lpq;

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
 * A companion run's pace as the clear-time calibration reads it: one span per segment of {@link AgentLpqPaceProfile},
 * cut at the boundaries the session's stage phases and its {@link AgentLpqPaceMarks} saw, following
 * {@link AgentPqPace}'s contract:
 * <pre>
 * yes                      admission (the Red Sign's Yes) -> stage 1
 * sN.collect/rooms/fight   stage N -> its passes at the leader's feet (N = 1-5, 7)
 * sN.handin                its passes at his feet -> stage N+1
 * s6.climb                 stage 6 -> stage 7
 * s8.entry                 stage 8 -> the first judged check
 * s8.checks                the first judged check -> stage 9 (work: the judged checks, the accepted one included)
 * s9.ratz                  stage 9 -> Alishar seen
 * s9.boss                  Alishar seen -> Alishar down (work: his HP)
 * s9.key                   Alishar down -> the clear
 * </pre>
 * A span is reported once it has begun (a stage a checkpoint skipped has none); one whose end was not seen runs to the
 * next boundary that was (or the report's time) and is incomplete ("open"; "out-of-order" when it ends before it
 * starts), so the calibration does not learn from it. The run's drawn content is its judged checks. At the release
 * each span gets one 'LPQ pace span:' line beside its time at the priors while the profile is warm.
 */
final class AgentLpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqPaceReport.class);
    static final String QUEST_KEY = AgentLpqPaceProfile.QUEST_KEY;

    private AgentLpqPaceReport() {
    }

    /** One span as the report cut it, with its work and why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, double work, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    static AgentPqPace of(AgentLpqSession session, long nowMs) {
        AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
        return pace(session, marks, spans(session, marks, nowMs));
    }

    /** At the release: one line per begun span, beside its time at the priors while the profile is warm. */
    static void logSpans(AgentLpqSession session, long nowMs) {
        try {
            AgentLpqPaceMarks.Snapshot marks = session.pace().snapshot();
            List<SpanReport> spans = spans(session, marks, nowMs);
            AgentPqPace pace = pace(session, marks, spans);
            AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(QUEST_KEY);
            for (SpanReport span : spans) log.info(line(session, span, theorySeconds(profile, pace, span)));
        } catch (RuntimeException failure) {
            log.warn("LPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    static String line(AgentLpqSession session, SpanReport span, double theorySeconds) {
        return "LPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " theorySeconds=" + (theorySeconds < 0.0d ? "-1" : String.format(Locale.ROOT, "%.1f", theorySeconds))
                + " complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why());
    }

    /** The segment's time at the priors for this run's members and work; -1 while the profile is cold or unread. */
    static double theorySeconds(AgentPqPaceProfile profile, AgentPqPace pace, SpanReport span) {
        if (profile == null) return -1.0d;
        int size = pace.members().size();
        if (size < profile.minSize() || size > profile.maxSize()) return -1.0d;
        try {
            double seconds = AgentPqPaceEstimator.theorySeconds(profile, pace.members(),
                    AgentPqPaceCalibration.prior(profile), span.key(), span.work());
            return Double.isFinite(seconds) ? seconds : -1.0d;
        } catch (RuntimeException unsupported) {
            return -1.0d;
        }
    }

    private static AgentPqPace pace(AgentLpqSession session, AgentLpqPaceMarks.Snapshot marks,
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
        Map<String, Integer> drawn = marks.checks() > 0
                ? Map.of(AgentLpqPaceProfile.S8_CHECKS, marks.checks()) : Map.of();
        return new AgentPqPace(QUEST_KEY, session.sessionId(), session.eventLeaderId(), session.startedAtMs(),
                clearedAtMs(session, marks), spans.stream().map(SpanReport::span).toList(), marks.members(), runs,
                drawn);
    }

    private static long clearedAtMs(AgentLpqSession session, AgentLpqPaceMarks.Snapshot marks) {
        return marks.clearedAtMs() != 0L ? marks.clearedAtMs() : session.firstEnteredAtMs(AgentLpqSession.Phase.BONUS);
    }

    /** One segment in run order: its key, the boundary it starts at, and its work once complete. */
    private record Cut(String key, long fromMs, double work) {
    }

    /** The run's spans in the profile's order, cut from its phases and marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentLpqSession session, AgentLpqPaceMarks.Snapshot marks, long endMs) {
        List<Cut> cuts = new ArrayList<>();
        cuts.add(new Cut(AgentLpqPaceProfile.YES, session.startedAtMs(), 0.0d));
        for (int stage : List.of(1, 2, 3, 4, 5)) {
            cuts.add(new Cut(AgentLpqPaceProfile.collectKey(stage), entered(session, stage), 0.0d));
            cuts.add(new Cut(AgentLpqPaceProfile.handinKey(stage), marks.passesAtFeetAtMs(stage), 0.0d));
        }
        cuts.add(new Cut(AgentLpqPaceProfile.S6_CLIMB, entered(session, 6), 0.0d));
        cuts.add(new Cut(AgentLpqPaceProfile.collectKey(7), entered(session, 7), 0.0d));
        cuts.add(new Cut(AgentLpqPaceProfile.handinKey(7), marks.passesAtFeetAtMs(7), 0.0d));
        cuts.add(new Cut(AgentLpqPaceProfile.S8_ENTRY, entered(session, 8), 0.0d));
        cuts.add(new Cut(AgentLpqPaceProfile.S8_CHECKS, marks.firstCheckAtMs(), marks.checks()));
        cuts.add(new Cut(AgentLpqPaceProfile.S9_RATZ, entered(session, 9), 0.0d));
        cuts.add(new Cut(AgentLpqPaceProfile.S9_BOSS, marks.alisharSeenAtMs(), marks.alisharHp()));
        cuts.add(new Cut(AgentLpqPaceProfile.S9_KEY, marks.alisharDownAtMs(), 0.0d));
        long cleared = clearedAtMs(session, marks);
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < cuts.size(); i++) {
            Cut cut = cuts.get(i);
            long from = cut.fromMs();
            if (from == 0L) continue;
            long to = i + 1 < cuts.size() ? cuts.get(i + 1).fromMs() : cleared;
            boolean closed = to != 0L;
            if (!closed) {
                to = Math.max(from, endMs);
                for (int next = i + 2; next <= cuts.size(); next++) {
                    long bound = next < cuts.size() ? cuts.get(next).fromMs() : cleared;
                    if (bound != 0L) {
                        to = Math.max(from, bound);
                        break;
                    }
                }
            }
            String why = !closed ? "open" : to < from ? "out-of-order" : "";
            spans.add(new SpanReport(cut.key(), from, to, cut.work(), why.isEmpty(), why));
        }
        return spans;
    }

    private static long entered(AgentLpqSession session, int stage) {
        return session.firstEnteredAtMs(AgentLpqSession.Phase.valueOf("STAGE_" + stage));
    }
}
