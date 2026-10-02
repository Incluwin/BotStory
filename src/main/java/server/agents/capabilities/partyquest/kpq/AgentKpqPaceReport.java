package server.agents.capabilities.partyquest.kpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S1_GRIND;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S1_HANDIN;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S1_OPEN;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_BOSS;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_CARRY;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_CLIMB;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_DESCENT;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_LOOT;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_MOVE;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_NORMALS;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.S5_TALKS;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.YES;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.acceptKey;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.checksKey;
import static server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.entryKey;

/**
 * A KPQ run's pace as the clear-time calibration reads it: one span per segment of {@link AgentKpqPaceProfile}, cut at
 * the boundaries the run's {@link AgentKpqPaceMarks} saw, and one "KPQ pace span" line per span as it closes, for every
 * run (measurement only). The spans follow {@link AgentPqPace}'s contract:
 * <pre>
 * yes        session start (Lakelis's Yes) -> stage 1     s1.open   stage 1 -> the first coupon number
 * s1.grind   first coupon number -> the last pass made     s1.handin last pass -> stage 2
 * sN.entry   stage N -> its first judged check            sN.checks first judged check -> accept (work: checks)
 * sN.accept  accept -> stage N+1 (stages 2 and 3)         s5.move   stage-4 accept -> the first companion on stage 5
 * s5.descent unseen: incomplete, zero long at the arrival, so the normals' span includes it
 * s5.normals arrival -> every normal mob down              s5.boss   normals down -> King Slime down
 * s5.loot    King Slime down -> the loot delay's end       s5.carry  loot delay's end -> the leader holding 10 passes
 * s5.climb   stage-4 accept -> the leader on stage 5      s5.talks  10 passes held -> the stage-5 clear
 * </pre>
 * A span is reported once it has begun; one still open, or whose end was never seen, runs to the next boundary seen
 * (or the report's time) and is incomplete. A complete span is one the calibration may learn from, so these are
 * incomplete too: a member went down inside it (the stage pauses for the respawn); a kill span after a member left
 * the run, or with more than THROTTLED_SHARE_MAX of its Agents' time below presentation rate; stage 1's grind without
 * every coupon number; a hand-in or carry that ended on passes the run made up; a carry that waited for a leader not
 * yet on stage 5; and the yes span of a run that is not a companion run, whose session did not start at a Yes.
 */
public final class AgentKpqPaceReport {
    private static final Logger log = LoggerFactory.getLogger(AgentKpqPaceReport.class);
    private static final double THROTTLED_SHARE_MAX = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceReport.THROTTLED_SHARE_MAX");
    private static final Set<String> KILL_SPANS = Set.of(S1_GRIND, S5_NORMALS, S5_BOSS);

    private AgentKpqPaceReport() {
    }

    /** One span as the report cut it, with why it is incomplete ("" when complete) and whether its end was seen. */
    record SpanReport(String key, long fromMs, long toMs, double work, boolean complete, String why, boolean closed,
                      double throttledShare) {
        AgentPqPace.Span span() {
            return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
        }
    }

    /** The run's pace record as of {@code nowMs}, its members read against the warmed profile (none while cold). */
    public static AgentPqPace of(AgentKpqSession session, long nowMs) {
        return of(session, nowMs, AgentPqPaceProfiles.cached(AgentKpqPaceProfile.QUEST_KEY));
    }

    static AgentPqPace of(AgentKpqSession session, long nowMs, AgentPqPaceProfile profile) {
        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        return pace(session, marks, spans(session, marks, nowMs, profile));
    }

    /** Each tick: a line for every span that closed since the last one. Never throws into the tick. */
    static void logClosedSpans(AgentKpqSession session, long nowMs) {
        try {
            if (session.pace().claimRevision()) write(session, nowMs, false);
        } catch (RuntimeException failure) {
            log.warn("KPQ pace span lines failed: session={}", session.sessionId(), failure);
        }
    }

    /** At the run's end: a line for every begun span not yet reported, open ones included. Never throws. */
    static void logRemainingSpans(AgentKpqSession session, long nowMs) {
        try {
            write(session, nowMs, true);
        } catch (RuntimeException failure) {
            log.warn("KPQ pace span lines failed at the run's end: session={}", session.sessionId(), failure);
        }
    }

    private static void write(AgentKpqSession session, long nowMs, boolean ended) {
        boolean background = session.mode() == AgentKpqSession.Mode.BACKGROUND_POPULATION;
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentKpqPaceProfile.QUEST_KEY);
        AgentKpqPaceMarks.Snapshot marks = session.pace().snapshot();
        List<SpanReport> spans = spans(session, marks, nowMs, profile);
        AgentPqPace pace = null;
        Map<String, Map<String, Double>> implied = Map.of();
        for (SpanReport span : spans) {
            // The grind's line waits for the stage-1 summary's swings and kills, which its implied efficiency reads.
            boolean ready = ended || span.closed() && (!S1_GRIND.equals(span.key()) || marks.stageOneCombatRecorded());
            if (!ready || !session.pace().claimSpanLine(span.key())) continue;
            if (background && !log.isDebugEnabled()) continue;
            if (pace == null) {
                pace = pace(session, marks, spans);
                implied = profile == null ? Map.of() : AgentPqPaceCalibration.implied(profile, pace);
            }
            String line = line(session, span, theorySeconds(profile, pace, spans, span),
                    implied.getOrDefault(span.key(), Map.of()));
            if (background) log.debug(line);
            else log.info(line);
        }
    }

    static String line(AgentKpqSession session, SpanReport span, double theorySeconds, Map<String, Double> implied) {
        StringJoiner terms = new StringJoiner(", ", "{", "}");
        implied.forEach((term, value) -> terms.add(term + "=" + number(value)));
        return "KPQ pace span: session=" + session.sessionId() + " key=" + span.key()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", (span.toMs() - span.fromMs()) / 1000.0d)
                + " work=" + Math.round(span.work())
                + " theorySeconds=" + (theorySeconds < 0.0d ? "-1"
                : String.format(Locale.ROOT, "%.1f", theorySeconds))
                + " implied=" + terms
                + " complete=" + span.complete()
                + " why=" + (span.complete() ? "-" : span.why())
                + " throttled=" + (span.throttledShare() < 0.0d ? "-"
                : String.format(Locale.ROOT, "%.2f", span.throttledShare()))
                + " companionRun=" + session.companionAdopted();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, Math.abs(value) >= 100.0d ? "%.0f" : "%.3f", value);
    }

    /**
     * The segment's time at the priors for this run's members and its realised work, plus the unseen descent the
     * normals' span takes in; -1 while the profile is cold or the party is not one the profile takes.
     */
    private static double theorySeconds(AgentPqPaceProfile profile, AgentPqPace pace, List<SpanReport> spans,
                                        SpanReport span) {
        if (profile == null) return -1.0d;
        int size = pace.members().size();
        if (size < profile.minSize() || size > profile.maxSize()) return -1.0d;
        try {
            AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(profile);
            double seconds = AgentPqPaceEstimator.theorySeconds(profile, pace.members(), prior, span.key(),
                    span.work());
            if (S5_NORMALS.equals(span.key()) && spans.stream().anyMatch(other -> S5_DESCENT.equals(other.key()))) {
                seconds += AgentPqPaceEstimator.theorySeconds(profile, pace.members(), prior, S5_DESCENT, 0.0d);
            }
            return Double.isFinite(seconds) ? seconds : -1.0d;
        } catch (RuntimeException unsupported) {
            return -1.0d;
        }
    }

    private static AgentPqPace pace(AgentKpqSession session, AgentKpqPaceMarks.Snapshot marks,
                                    List<SpanReport> spans) {
        Set<Integer> ids = new LinkedHashSet<>();
        marks.members().forEach(member -> ids.add(member.characterId()));
        ids.add(session.eventLeaderId());
        ids.addAll(marks.collectors());
        // A run joined past stage 1 (a test checkpoint) knows its members only from what happened to them.
        ids.addAll(marks.deaths().keySet());
        ids.addAll(marks.tappedOut());
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (int id : ids) {
            AgentKpqPaceMarks.StageOneCombat combat = marks.stageOneCombat().get(id);
            runs.add(new AgentPqPace.MemberRun(id, marks.deaths().getOrDefault(id, 0), marks.tappedOut().contains(id),
                    combat == null ? 0 : (int) Math.min(Integer.MAX_VALUE, combat.attacks()),
                    combat == null ? 0 : (int) Math.min(Integer.MAX_VALUE, combat.kills())));
        }
        Map<String, Integer> drawn = new LinkedHashMap<>();
        if (marks.couponsAsked() >= 0) drawn.put(S1_GRIND, marks.couponsAsked());
        marks.checks().forEach((stage, count) -> drawn.put(checksKey(stage), count));
        return new AgentPqPace(AgentKpqPaceProfile.QUEST_KEY, session.sessionId(), session.eventLeaderId(),
                session.startedAtMs(), marks.phase(AgentKpqSession.Phase.CLAIMING_REWARDS),
                spans.stream().map(SpanReport::span).toList(), marks.members(), runs, drawn);
    }

    /** The run's spans in the profile's order, cut from its marks; {@code endMs} closes any still open. */
    static List<SpanReport> spans(AgentKpqSession session, AgentKpqPaceMarks.Snapshot marks, long endMs,
                                  AgentPqPaceProfile profile) {
        long accept4 = marks.acceptedAtMs().getOrDefault(4, 0L);
        long passes = marks.passesAtLeaderAtMs();
        List<String> mainKeys = new ArrayList<>(List.of(YES, S1_OPEN, S1_GRIND, S1_HANDIN));
        List<Long> mainBounds = new ArrayList<>(List.of(session.startedAtMs(), marks.phase(AgentKpqSession.Phase.STAGE_1),
                marks.grindStartMs(), marks.lastPassAtMs()));
        for (int stage = 2; stage <= 4; stage++) {
            mainKeys.add(entryKey(stage));
            mainKeys.add(checksKey(stage));
            mainBounds.add(marks.phase(phaseFor(stage)));
            mainBounds.add(marks.firstJudgedAtMs().getOrDefault(stage, 0L));
            if (stage < 4) {
                mainKeys.add(acceptKey(stage));
                mainBounds.add(marks.acceptedAtMs().getOrDefault(stage, 0L));
            }
        }
        mainBounds.add(accept4);

        Map<String, SpanReport> cut = new LinkedHashMap<>();
        chain(cut, mainKeys, mainBounds, endMs);
        chain(cut, List.of(S5_MOVE), List.of(accept4, marks.companionsOnStageFiveAtMs()), endMs);
        if (marks.companionsOnStageFiveAtMs() != 0L) {
            cut.put(S5_DESCENT, new SpanReport(S5_DESCENT, marks.companionsOnStageFiveAtMs(),
                    marks.companionsOnStageFiveAtMs(), 0.0d, false, "unseen", true, -1.0d));
        }
        chain(cut, List.of(S5_NORMALS, S5_BOSS, S5_LOOT, S5_CARRY), List.of(marks.companionsOnStageFiveAtMs(),
                marks.normalsClearedAtMs(), marks.bossDeadAtMs(), marks.lootDelayUntilMs(), passes), endMs);
        chain(cut, List.of(S5_CLIMB), List.of(accept4, marks.leaderOnStageFiveAtMs()), endMs);
        chain(cut, List.of(S5_TALKS), List.of(passes, marks.phase(AgentKpqSession.Phase.CLAIMING_REWARDS)), endMs);

        List<SpanReport> spans = new ArrayList<>();
        for (SpanReport raw : cut.values()) {
            spans.add(S5_DESCENT.equals(raw.key()) ? raw : judge(session, marks, raw, work(marks, raw.key(), profile)));
        }
        return spans;
    }

    /**
     * Cuts consecutive spans {@code keys[i]} from {@code bounds[i]} to {@code bounds[i + 1]} (0 = not seen). A span
     * that never began is left out; one whose end was not seen runs to the next boundary that was, or to {@code endMs}.
     */
    private static void chain(Map<String, SpanReport> cut, List<String> keys, List<Long> bounds, long endMs) {
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
            cut.put(keys.get(i), new SpanReport(keys.get(i), from, to, 0.0d, closed, closed ? "" : "open", closed,
                    -1.0d));
        }
    }

    /** The span with its work, its kill segment's throttled share, and whether the calibration may learn from it. */
    private static SpanReport judge(AgentKpqSession session, AgentKpqPaceMarks.Snapshot marks, SpanReport raw,
                                    double work) {
        String key = raw.key();
        AgentKpqPaceMarks.Simulation simulation = marks.killSimulation().get(key);
        double throttled = KILL_SPANS.contains(key) && simulation != null ? simulation.throttledShare() : -1.0d;
        String why = whyIncomplete(session, marks, raw, throttled);
        return new SpanReport(key, raw.fromMs(), raw.toMs(), work, why.isEmpty(), why, raw.closed(), throttled);
    }

    private static String whyIncomplete(AgentKpqSession session, AgentKpqPaceMarks.Snapshot marks, SpanReport span,
                                        double throttled) {
        if (!span.closed()) return "open";
        if (span.toMs() < span.fromMs()) return "out-of-order";
        String key = span.key();
        for (long down : marks.downAtMs()) {
            if (down >= span.fromMs() && down < span.toMs()) return "member-down";
        }
        if (YES.equals(key) && !session.companionAdopted()) return "not-a-companion-run";
        if (S1_GRIND.equals(key) && marks.couponsAsked() < 0) return "coupons-unknown";
        if (KILL_SPANS.contains(key)) {
            if (marks.firstDepartureAtMs() != 0L && marks.firstDepartureAtMs() < span.toMs()) return "member-left";
            if (throttled > THROTTLED_SHARE_MAX) return "throttled";
        }
        if (S1_HANDIN.equals(key) && marks.stageOnePassesRestored()) return "passes-restored";
        if (S5_LOOT.equals(key) && marks.lootDelayFromMs() != marks.bossDeadAtMs()) return "loot-delay-late";
        if (S5_CARRY.equals(key)) {
            if (marks.stageFivePassesRestored()) return "passes-restored";
            if (marks.leaderOnStageFiveAtMs() == 0L || marks.leaderOnStageFiveAtMs() > span.fromMs()) {
                return "waited-for-leader";
            }
        }
        return "";
    }

    /** What the segment did: the coupons Cloto asked for, a puzzle's judged checks, a clear's census HP. */
    private static double work(AgentKpqPaceMarks.Snapshot marks, String key, AgentPqPaceProfile profile) {
        if (S1_GRIND.equals(key)) return Math.max(0, marks.couponsAsked());
        for (int stage = 2; stage <= 4; stage++) {
            if (checksKey(stage).equals(key)) return marks.checks().getOrDefault(stage, 0);
        }
        if ((S5_NORMALS.equals(key) || S5_BOSS.equals(key)) && profile != null) {
            return Math.max(0.0d, censusHp(profile.segments(profile.maxSize()), key));
        }
        return 0.0d;
    }

    /** The HP of a clear's census (every mob of it once), or -1 when no clear has this key. */
    private static double censusHp(List<AgentPqPaceSegment> segments, String key) {
        for (AgentPqPaceSegment segment : segments) {
            if (segment instanceof AgentPqPaceSegment.Parallel parallel) {
                for (List<AgentPqPaceSegment> branch : parallel.branches()) {
                    double hp = censusHp(branch, key);
                    if (hp >= 0.0d) return hp;
                }
            } else if (segment instanceof AgentPqPaceSegment.Clear clear && clear.key().equals(key)) {
                double hp = 0.0d;
                for (Map.Entry<Integer, Integer> count : clear.counts().entrySet()) {
                    hp += (double) count.getValue() * clear.hp().getOrDefault(count.getKey(), 0);
                }
                return hp;
            }
        }
        return -1.0d;
    }

    private static AgentKpqSession.Phase phaseFor(int stage) {
        return switch (stage) {
            case 2 -> AgentKpqSession.Phase.STAGE_2;
            case 3 -> AgentKpqSession.Phase.STAGE_3;
            default -> AgentKpqSession.Phase.STAGE_4;
        };
    }
}
