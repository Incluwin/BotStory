package server.agents.capabilities.partyquest.ppq;

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
 * A PPQ companion run's pace as the clear-time calibration reads it (P6i Step 5, the shape of AgentRjpqPaceReport):
 * one span per segment of {@link AgentPpqPaceProfile}, cut at the boundaries its {@link AgentPpqPaceMarks} saw:
 * <pre>
 * stage1   the party inside (the bind) -> the leader on the medal map
 * medals1  the leader on the medal map -> twenty Rookie medals in his bag
 * handin1  twenty in his bag -> the wave handed in (stage2 at 1)
 * medals2  the hand-in -> twenty Rising medals in his bag
 * handin2  -> stage2 at 2
 * medals3  -> twenty Veteran medals in his bag
 * handin3  -> stage2 at 3
 * deck1    the last hand-in -> the leader on the second deck
 * deck2    the second deck -> the door map
 * doors    the door map -> the boss map
 * boss     the boss map -> the Lord Pirate down
 * wuyang   the Lord Pirate down -> the event's clear (his talk to Wu Yang)
 * </pre>
 * A span is reported once it has begun; one whose end was not seen runs to the next boundary that was (or the report's
 * time) and is incomplete ("open"), one that ends before it begins is "out-of-order". A hand-in whose twenty was never
 * seen in his bag (he handed in within one pass) is reported at its end, zero long and incomplete ("unseen-full"), the
 * wave's medals span running to the hand-in, incomplete too. A deck span on which the leader went into its chest or
 * hidden room is incomplete ("detour"): its time holds more than the deck's clear. A medal wave's work is the kills of
 * its pirate the run counted (the drops in its live count between passes, a lower bound under the 10-second respawn);
 * every other span's work is 0. At the release each span gets one 'PPQ pace span:' line (measurement only).
 */
public final class AgentPpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqPaceReport.class);
    private static final List<String> KEYS = List.of(AgentPpqPaceProfile.STAGE_ONE, AgentPpqPaceProfile.MEDALS_ONE,
            AgentPpqPaceProfile.HANDIN_ONE, AgentPpqPaceProfile.MEDALS_TWO, AgentPpqPaceProfile.HANDIN_TWO,
            AgentPpqPaceProfile.MEDALS_THREE, AgentPpqPaceProfile.HANDIN_THREE, AgentPpqPaceProfile.DECK_ONE,
            AgentPpqPaceProfile.DECK_TWO, AgentPpqPaceProfile.DOORS, AgentPpqPaceProfile.BOSS,
            AgentPpqPaceProfile.WU_YANG);

    private AgentPpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete). */
    record SpanReport(String key, long fromMs, long toMs, double work, boolean complete, String why) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}. */
    public static AgentPqPace of(AgentPpqSession session, long nowMs) {
        AgentPpqPaceMarks.Snapshot marks = session.pace().snapshot();
        return pace(session, marks, spans(marks, nowMs));
    }

    /** At the release: one line per begun span. Never throws. */
    static void logSpans(AgentPpqSession session, long nowMs) {
        try {
            for (SpanReport span : spans(session.pace().snapshot(), nowMs)) {
                log.info("PPQ pace span: session={} key={} seconds={} work={} complete={} why={}", session.sessionId(),
                        span.key(), String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d),
                        String.format(Locale.ROOT, "%.0f", span.work()), span.complete(),
                        span.complete() ? "-" : span.why());
            }
        } catch (RuntimeException failure) {
            log.warn("PPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    private static AgentPqPace pace(AgentPpqSession session, AgentPpqPaceMarks.Snapshot marks,
                                    List<SpanReport> spans) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(session.eventLeaderId());
        marks.members().forEach(member -> ids.add(member.characterId()));
        ids.addAll(marks.entrants());
        ids.addAll(marks.deaths().keySet());
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (int id : ids) {
            runs.add(new AgentPqPace.MemberRun(id, marks.deaths().getOrDefault(id, 0), marks.departed().contains(id),
                    0, 0));
        }
        return new AgentPqPace(AgentPpqPaceProfile.QUEST_KEY, session.sessionId(), session.eventLeaderId(),
                session.startedAtMs(), marks.at(AgentPpqPaceMarks.Boundary.CLEARED),
                spans.stream().map(SpanReport::span).toList(), marks.members(), runs, Map.of());
    }

    /** The run's spans in the profile's order, cut from its marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentPpqPaceMarks.Snapshot marks, long endMs) {
        List<Long> bounds = List.of(marks.enteredAtMs(), marks.at(AgentPpqPaceMarks.Boundary.MEDALS),
                marks.at(AgentPpqPaceMarks.Boundary.WAVE_ONE_FULL), marks.at(AgentPpqPaceMarks.Boundary.WAVE_ONE_IN),
                marks.at(AgentPpqPaceMarks.Boundary.WAVE_TWO_FULL), marks.at(AgentPpqPaceMarks.Boundary.WAVE_TWO_IN),
                marks.at(AgentPpqPaceMarks.Boundary.WAVE_THREE_FULL),
                marks.at(AgentPpqPaceMarks.Boundary.WAVE_THREE_IN), marks.at(AgentPpqPaceMarks.Boundary.DECK_TWO),
                marks.at(AgentPpqPaceMarks.Boundary.DOORS), marks.at(AgentPpqPaceMarks.Boundary.BOSS),
                marks.at(AgentPpqPaceMarks.Boundary.BOSS_DOWN), marks.at(AgentPpqPaceMarks.Boundary.CLEARED));
        List<SpanReport> spans = new ArrayList<>();
        for (int i = 0; i < KEYS.size(); i++) {
            String key = KEYS.get(i);
            long from = bounds.get(i);
            long to = bounds.get(i + 1);
            boolean handIn = i == 2 || i == 4 || i == 6;
            if (from == 0L) {
                if (handIn && to != 0L) {
                    // the twenty and the hand-in fell within one pass: the hand-in is seen only where it ends
                    spans.add(new SpanReport(key, to, to, 0.0d, false, "unseen-full"));
                }
                continue;
            }
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
            String why = !closed ? (isMedals(i) && bounds.get(i + 2) != 0L ? "unseen-full" : "open")
                    : to < from ? "out-of-order"
                    : detour(marks, i) ? "detour" : "";
            spans.add(new SpanReport(key, from, to, work(marks, i), why.isEmpty(), why));
        }
        return spans;
    }

    private static boolean isMedals(int index) {
        return index == 1 || index == 3 || index == 5;
    }

    /** A deck span whose leader went into its chest or hidden room. */
    private static boolean detour(AgentPpqPaceMarks.Snapshot marks, int index) {
        return index == 7 && marks.detourDecks().contains(AgentPpqDefinition.DECK_ONE_MAP)
                || index == 8 && marks.detourDecks().contains(AgentPpqDefinition.DECK_TWO_MAP);
    }

    /** A medal wave's counted kills; 0 for every other span. */
    private static double work(AgentPpqPaceMarks.Snapshot marks, int index) {
        return isMedals(index) ? marks.waveKills().getOrDefault((index - 1) / 2, 0) : 0.0d;
    }
}
