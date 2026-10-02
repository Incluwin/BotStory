package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceObservation;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * A KPQ run's pace as the clear-time calibration reads it (estimator synthesis section 2): one span per profile
 * segment cut at the boundaries the run saw, each "KPQ pace span" line written once, and a span the calibration must
 * not learn from marked incomplete with its reason.
 */
class AgentKpqPaceReportTest {
    /** Map.wz and Mob.wz read directly (LifeFactory needs the database for drops, which tests do not have). */
    private static final AgentKpqPaceProfile PROFILE = AgentKpqPaceProfile.load(AgentKpqPaceProfile.Census.WZ);
    private static final double THROTTLED_SHARE_MAX = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceReport.THROTTLED_SHARE_MAX");
    private static final int LIGATOR = 9_300_001;
    private static final int NECKI = 9_300_000;
    private static final int CURSE_EYE = 9_300_002;
    private static final int KING_SLIME = 9_300_003;
    private static final int BEN = 1;

    // One cleared four-member run, ms on the server clock; the session starts at Lakelis's Yes.
    private static final long YES = 1_000L;
    private static final long STAGE1 = 1_227L;
    private static final long FIRST_NUMBER = 16_400L;
    private static final long LAST_PASS = 520_000L;
    private static final long STAGE2 = 700_000L;
    private static final long JUDGED2 = 850_000L;
    private static final long ACCEPT2 = 900_000L;
    private static final long STAGE3 = 930_000L;
    private static final long JUDGED3 = 1_030_000L;
    private static final long ACCEPT3 = 1_100_000L;
    private static final long STAGE4 = 1_120_000L;
    private static final long JUDGED4 = 1_200_000L;
    private static final long ACCEPT4 = 1_280_000L;
    private static final long ARRIVAL = 1_283_000L;
    private static final long LEADER_ON_5 = 1_300_000L;
    private static final long NORMALS_DOWN = 1_340_000L;
    private static final long KING_SLIME_DOWN = 1_400_000L;
    private static final long LOOT_FROM = KING_SLIME_DOWN + 3_000L;
    private static final long PASSES_HELD = 1_440_000L;
    private static final long CLEARED = 1_455_000L;

    @Test
    void theGrindSpanStartsAfterTheOpeningAndEndsWithTheLastPass() {
        AgentKpqSession session = companionRun();
        throughStageOne(session);

        Map<String, AgentPqPace.Span> spans = spans(AgentKpqPaceReport.of(session, STAGE2, PROFILE));
        assertEquals(span("yes", YES, STAGE1, 0, true), spans.get("yes"));
        assertEquals(span("s1.open", STAGE1, FIRST_NUMBER, 0, true), spans.get("s1.open"));
        assertEquals(span("s1.grind", FIRST_NUMBER, LAST_PASS, 63, true), spans.get("s1.grind"));
        assertEquals(span("s1.handin", LAST_PASS, STAGE2, 0, true), spans.get("s1.handin"));
    }

    @Test
    void theGrindIsOpenUntilTheLastCollectorsPassAndKnowsItsWorkOnlyFromEveryNumber() {
        AgentKpqSession session = companionRun();
        session.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
        session.pace().beginStageOne(List.of(), List.of(2, 3, 4));
        session.assignCouponTarget(session.member(2), 20, FIRST_NUMBER);
        session.assignCouponTarget(session.member(3), 25, FIRST_NUMBER + 600L);
        session.markPassCreated(session.member(2), 300_000L);

        AgentKpqPaceReport.SpanReport grind = report(session, 400_000L, "s1.grind");
        assertFalse(grind.closed());
        assertFalse(grind.complete());
        assertEquals(400_000L, grind.toMs());
        assertEquals("open", grind.why());

        session.markPassCreated(session.member(3), 410_000L);
        session.markPassCreated(session.member(4), 420_000L);
        grind = report(session, 450_000L, "s1.grind");
        assertTrue(grind.closed());
        assertEquals(420_000L, grind.toMs());
        // Tobi's number was never heard, so the coupons Cloto asked for are unknown.
        assertFalse(grind.complete());
        assertEquals("coupons-unknown", grind.why());
    }

    @Test
    void eachPuzzleStageCountsTheChecksUpToTheAcceptedOne() {
        AgentKpqSession session = companionRun();
        throughStageOne(session);
        session.markPuzzleVerdict(JUDGED2);
        session.markPuzzleVerdict(JUDGED2 + 20_000L);
        session.setAttemptIndex(2);
        session.pace().markAccepted(2, session.attemptIndex() + 1, ACCEPT2);
        // Seen again by the next tick, the accept keeps its first sighting.
        session.pace().markAccepted(2, 7, ACCEPT2 + 500L);
        session.transition(AgentKpqSession.Phase.STAGE_3, STAGE3);

        AgentPqPace pace = AgentKpqPaceReport.of(session, STAGE3, PROFILE);
        Map<String, AgentPqPace.Span> spans = spans(pace);
        assertEquals(span("s2.entry", STAGE2, JUDGED2, 0, true), spans.get("s2.entry"));
        assertEquals(span("s2.checks", JUDGED2, ACCEPT2, 3, true), spans.get("s2.checks"));
        assertEquals(span("s2.accept", ACCEPT2, STAGE3, 0, true), spans.get("s2.accept"));
        assertEquals(3, pace.drawn().get("s2.checks"));
        assertEquals(63, pace.drawn().get("s1.grind"));
    }

    @Test
    void aClearedRunReportsEveryProfileSegmentAndNoOther() {
        for (int size = 3; size <= 4; size++) {
            AgentKpqSession session = cleared(size);
            AgentPqPace pace = AgentKpqPaceReport.of(session, CLEARED + 60_000L, PROFILE);

            assertEquals(new LinkedHashSet<>(PROFILE.segmentKeys(size)),
                    new LinkedHashSet<>(pace.spans().stream().map(AgentPqPace.Span::key).toList()));
            assertEquals(PROFILE.segmentKeys(size), pace.spans().stream().map(AgentPqPace.Span::key).toList());
            assertEquals(CLEARED, pace.clearedAtMs());
            assertEquals(Set.of("s5.descent"), pace.spans().stream().filter(span -> !span.complete())
                    .map(AgentPqPace.Span::key).collect(Collectors.toSet()));
        }
        Map<String, AgentPqPace.Span> spans = spans(AgentKpqPaceReport.of(cleared(4), CLEARED, PROFILE));
        assertEquals(span("s5.move", ACCEPT4, ARRIVAL, 0, true), spans.get("s5.move"));
        assertEquals(span("s5.descent", ARRIVAL, ARRIVAL, 0, false), spans.get("s5.descent"));
        assertEquals(span("s5.normals", ARRIVAL, NORMALS_DOWN, 6 * 320 + 3 * 2_000, true), spans.get("s5.normals"));
        assertEquals(span("s5.boss", NORMALS_DOWN, KING_SLIME_DOWN, 8_000, true), spans.get("s5.boss"));
        assertEquals(span("s5.loot", KING_SLIME_DOWN, LOOT_FROM, 0, true), spans.get("s5.loot"));
        assertEquals(span("s5.carry", LOOT_FROM, PASSES_HELD, 0, true), spans.get("s5.carry"));
        assertEquals(span("s5.climb", ACCEPT4, LEADER_ON_5, 0, true), spans.get("s5.climb"));
        assertEquals(span("s5.talks", PASSES_HELD, CLEARED, 0, true), spans.get("s5.talks"));
    }

    @Test
    void aRecordedRunIsOneTheCalibrationLearnsFrom() {
        AgentPqPace pace = AgentKpqPaceReport.of(cleared(4), CLEARED, PROFILE);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE,
                List.of(new AgentPqPaceObservation(0, PROFILE.epoch(), pace)), BEN, Set.of());

        assertEquals(1, fit.records());
        assertEquals(Map.of(), fit.fitNotes());
        assertEquals(List.of(new AgentPqPace.MemberRun(BEN, 0, false, 0, 0),
                new AgentPqPace.MemberRun(2, 0, false, 228, 26), new AgentPqPace.MemberRun(3, 0, false, 226, 17),
                new AgentPqPace.MemberRun(4, 0, false, 195, 22)), pace.runs());
        assertEquals(members(4), pace.members());
        assertEquals(BEN, pace.leaderId());
        assertEquals(YES, pace.startedAtMs());
    }

    @Test
    void aRunFailedInStageFiveLeavesItsCarryIncompleteAndNoClear() {
        AgentKpqSession session = cleared(4, false);
        session.fail("The KPQ event instance disappeared or the leader left it", 1_500_000L);

        AgentPqPace pace = AgentKpqPaceReport.of(session, 1_500_000L, PROFILE);
        Map<String, AgentPqPace.Span> spans = spans(pace);

        assertEquals(0L, pace.clearedAtMs());
        assertFalse(pace.cleared());
        assertEquals(span("s5.carry", LOOT_FROM, 1_500_000L, 0, false), spans.get("s5.carry"));
        assertFalse(spans.containsKey("s5.talks"));
        assertTrue(spans.get("s5.boss").complete());
    }

    @Test
    void aSpanTheCalibrationMustNotLearnFromSaysWhy() {
        AgentKpqSession stock = cleared(4);
        stock.setCompanionAdopted(false);
        assertEquals("not-a-companion-run", report(stock, CLEARED, "yes").why());

        AgentKpqSession down = cleared(4);
        down.pace().markDown(3, KING_SLIME_DOWN - 1L);
        assertEquals("member-down", report(down, CLEARED, "s5.boss").why());
        assertTrue(report(down, CLEARED, "s5.normals").complete());
        assertTrue(report(down, CLEARED, "s5.loot").complete());

        AgentKpqSession left = cleared(4);
        left.pace().markDeparture(NORMALS_DOWN + 1L);
        assertTrue(report(left, CLEARED, "s5.normals").complete());
        assertEquals("member-left", report(left, CLEARED, "s5.boss").why());
        // Only a kill segment's fighters change when one leaves: the climb and the talks still count.
        assertTrue(report(left, CLEARED, "s5.talks").complete());

        AgentKpqSession lateLoot = timeline(4, true, LOOT_FROM - 1L, LEADER_ON_5);
        assertEquals("loot-delay-late", report(lateLoot, CLEARED, "s5.loot").why());
        AgentKpqSession waited = timeline(4, true, KING_SLIME_DOWN, LOOT_FROM + 5_000L);
        assertEquals("waited-for-leader", report(waited, CLEARED, "s5.carry").why());

        AgentKpqSession restored = cleared(4);
        restored.pace().markStageOnePassesRestored();
        restored.pace().markStageFivePassesRestored();
        assertEquals("passes-restored", report(restored, CLEARED, "s1.handin").why());
        assertEquals("passes-restored", report(restored, CLEARED, "s5.carry").why());
    }

    @Test
    void aKillSegmentFoughtMostlyBelowPresentationRateIsThrottled() {
        AgentKpqSession session = companionRun();
        session.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
        session.pace().beginStageOne(List.of(), List.of(2, 3, 4));
        session.assignCouponTarget(session.member(2), 20, FIRST_NUMBER);
        session.assignCouponTarget(session.member(3), 25, FIRST_NUMBER);
        session.assignCouponTarget(session.member(4), 18, FIRST_NUMBER);
        AgentKpqPaceMarks pace = session.pace();
        String grind = pace.openKillSegment(session.phase());
        assertEquals("s1.grind", grind);
        // Three companions for 100 s: one of them throttled for the first 40 s.
        pace.sampleSimulation(grind, 3, 0, 100_000L);
        pace.sampleSimulation(grind, 3, 1, 140_000L);
        pace.sampleSimulation(grind, 3, 0, 200_000L);
        for (int id = 2; id <= 4; id++) session.markPassCreated(session.member(id), 200_000L);
        assertNull(pace.openKillSegment(session.phase()));

        AgentKpqPaceReport.SpanReport span = report(session, 200_000L, "s1.grind");
        assertEquals(40.0d / 300.0d, span.throttledShare(), 1e-12);
        assertEquals(40.0d / 300.0d > THROTTLED_SHARE_MAX, !span.complete());
        assertEquals(40.0d / 300.0d > THROTTLED_SHARE_MAX ? "throttled" : "", span.why());

        AgentKpqSession presentation = companionRun();
        presentation.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
        presentation.pace().beginStageOne(List.of(), List.of(2, 3, 4));
        presentation.assignCouponTarget(presentation.member(2), 20, FIRST_NUMBER);
        presentation.pace().sampleSimulation("s1.grind", 3, 0, 100_000L);
        presentation.pace().sampleSimulation("s1.grind", 3, 0, 200_000L);
        assertEquals(0.0d, report(presentation, 200_000L, "s1.grind").throttledShare(), 1e-12);
    }

    @Test
    void eachSpanGetsOneLineAndTheGrindsWaitsForTheStageOneSummary() {
        try (KpqLogLines log = new KpqLogLines(AgentKpqPaceReport.class)) {
            AgentKpqSession session = companionRun();
            session.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
            session.pace().beginStageOne(List.of(), List.of(2, 3, 4));
            AgentKpqPaceReport.logClosedSpans(session, STAGE1);
            session.assignCouponTarget(session.member(2), 20, FIRST_NUMBER);
            session.assignCouponTarget(session.member(3), 25, FIRST_NUMBER);
            session.assignCouponTarget(session.member(4), 18, FIRST_NUMBER);
            for (long now = FIRST_NUMBER; now < LAST_PASS; now += 50_000L) AgentKpqPaceReport.logClosedSpans(session, now);
            for (int id = 2; id <= 4; id++) session.markPassCreated(session.member(id), LAST_PASS);
            AgentKpqPaceReport.logClosedSpans(session, LAST_PASS);
            AgentKpqPaceReport.logClosedSpans(session, LAST_PASS + 1_000L);
            assertEquals(List.of("yes", "s1.open"), keys(log));

            session.pace().markStageOneCombatRecorded();
            AgentKpqPaceReport.logClosedSpans(session, STAGE2 - 1_000L);
            session.transition(AgentKpqSession.Phase.STAGE_2, STAGE2);
            AgentKpqPaceReport.logClosedSpans(session, STAGE2);
            AgentKpqPaceReport.logClosedSpans(session, STAGE2 + 1_000L);
            assertEquals(List.of("yes", "s1.open", "s1.grind", "s1.handin"), keys(log));

            // The run ends in stage 2: the entry still open is written once, with why it is incomplete.
            session.fail("test stopped", STAGE2 + 9_000L);
            AgentKpqPaceReport.logRemainingSpans(session, STAGE2 + 9_000L);
            AgentKpqPaceReport.logClosedSpans(session, STAGE2 + 9_000L);
            AgentKpqPaceReport.logRemainingSpans(session, STAGE2 + 10_000L);
            assertEquals(List.of("yes", "s1.open", "s1.grind", "s1.handin", "s2.entry"), keys(log));
            String entry = log.startingWith("KPQ pace span: ").getLast();
            assertEquals("KPQ pace span: session=" + session.sessionId() + " key=s2.entry seconds=9.0 work=0 "
                    + "theorySeconds=-1 implied={} complete=false why=open throttled=- companionRun=true", entry);
        }
    }

    @Test
    void aSpanLinePrintsItsTheoryAtThePriorsAndWhatItImplies() {
        AgentKpqSession session = cleared(4);
        try (KpqLogLines log = new KpqLogLines(AgentKpqPaceReport.class);
             MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(PROFILE);
            AgentKpqPaceReport.logClosedSpans(session, CLEARED);

            AgentPqPace pace = AgentKpqPaceReport.of(session, CLEARED, PROFILE);
            AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
            Map<String, Map<String, Double>> implied = AgentPqPaceCalibration.implied(PROFILE, pace);
            double grindTheory = AgentPqPaceEstimator.theorySeconds(PROFILE, members(4), prior, "s1.grind", 63);
            double normalsTheory = AgentPqPaceEstimator.theorySeconds(PROFILE, members(4), prior, "s5.normals", 7_920)
                    + AgentPqPaceEstimator.theorySeconds(PROFILE, members(4), prior, "s5.descent", 0);

            String grind = line(log, "s1.grind");
            assertTrue(grind.startsWith("KPQ pace span: session=" + session.sessionId() + " key=s1.grind seconds="
                    + String.format(java.util.Locale.ROOT, "%.1f", (LAST_PASS - FIRST_NUMBER) / 1000.0d)
                    + " work=63 theorySeconds=" + String.format(java.util.Locale.ROOT, "%.1f", grindTheory)
                    + " implied={s1.grind.combat="), grind);
            assertTrue(grind.endsWith(" complete=true why=- throttled=- companionRun=true"), grind);
            Map<String, Double> grindTerms = implied.get("s1.grind");
            assertEquals(Set.of("s1.grind.combat", "s1.grind.seek"), grindTerms.keySet());
            assertTrue(grind.contains("s1.grind.seek=" + Math.round(grindTerms.get("s1.grind.seek"))), grind);
            String normals = line(log, "s5.normals");
            assertTrue(normals.contains(" work=7920 theorySeconds="
                    + String.format(java.util.Locale.ROOT, "%.1f", normalsTheory) + " implied={s5.normals="), normals);
            assertTrue(line(log, "s2.entry").contains(" implied={puzzle.entry=" + (JUDGED2 - STAGE2) + "}"));
            assertTrue(line(log, "s5.descent").contains(" complete=false why=unseen "));
            assertEquals(PROFILE.segmentKeys(4), keys(log));
        }
    }

    @Test
    void aBackgroundRunKeepsItsSpanLinesOutOfTheInfoLog() {
        try (KpqLogLines log = new KpqLogLines(AgentKpqPaceReport.class)) {
            AgentKpqSession background = new AgentKpqSession(AgentKpqSession.Mode.BACKGROUND_POPULATION, 7L, 2, 3, YES);
            background.addMember(2, AgentKpqMemberState.MemberType.AGENT);
            background.addMember(3, AgentKpqMemberState.MemberType.AGENT);
            background.addMember(4, AgentKpqMemberState.MemberType.AGENT);
            background.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
            AgentKpqPaceReport.logClosedSpans(background, STAGE1);
            AgentKpqPaceReport.logRemainingSpans(background, STAGE1 + 5_000L);

            assertEquals(List.of(), keys(log));
        }
    }

    private static List<String> keys(KpqLogLines log) {
        return log.startingWith("KPQ pace span: ").stream()
                .map(line -> line.substring(line.indexOf(" key=") + 5, line.indexOf(" seconds="))).toList();
    }

    private static String line(KpqLogLines log, String key) {
        List<String> lines = log.startingWith("KPQ pace span: ").stream()
                .filter(line -> line.contains(" key=" + key + " ")).toList();
        assertEquals(1, lines.size(), key + " " + lines);
        return lines.getFirst();
    }

    private static AgentKpqPaceReport.SpanReport report(AgentKpqSession session, long nowMs, String key) {
        return AgentKpqPaceReport.spans(session, session.pace().snapshot(), nowMs, PROFILE).stream()
                .filter(span -> span.key().equals(key)).findFirst().orElseThrow();
    }

    private static Map<String, AgentPqPace.Span> spans(AgentPqPace pace) {
        return pace.spans().stream().collect(Collectors.toMap(AgentPqPace.Span::key, span -> span));
    }

    private static AgentPqPace.Span span(String key, long fromMs, long toMs, double work, boolean complete) {
        return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
    }

    /** Ben (human) leads Mira, Pip and Tobi, a companion run that started at Lakelis's Yes. */
    private static AgentKpqSession companionRun() {
        return companionRun(4);
    }

    private static AgentKpqSession companionRun(int size) {
        AgentKpqSession session = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 7L, BEN, size, YES);
        session.addMember(BEN, AgentKpqMemberState.MemberType.HUMAN);
        for (int id = 2; id <= size; id++) session.addMember(id, AgentKpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, 2);
        session.setCompanionAdopted(true);
        return session;
    }

    private static void throughStageOne(AgentKpqSession session) {
        session.transition(AgentKpqSession.Phase.STAGE_1, STAGE1);
        session.pace().beginStageOne(members(session.memberCount()), List.of(2, 3, 4).subList(0,
                session.memberCount() - 1));
        int[] targets = {20, 25, 18};
        for (int id = 2; id <= session.memberCount(); id++) {
            session.assignCouponTarget(session.member(id), targets[id - 2], FIRST_NUMBER + (id - 2) * 600L);
        }
        session.markPassCreated(session.member(2), 300_000L);
        if (session.memberCount() > 3) session.markPassCreated(session.member(4), 400_000L);
        session.markPassCreated(session.member(3), LAST_PASS);
        session.pace().recordStageOneCombat(2, 228, 26);
        session.pace().recordStageOneCombat(3, 226, 17);
        if (session.memberCount() > 3) session.pace().recordStageOneCombat(4, 195, 22);
        session.pace().markStageOneCombatRecorded();
        session.transition(AgentKpqSession.Phase.STAGE_2, STAGE2);
    }

    private static AgentKpqSession cleared(int size) {
        return cleared(size, true);
    }

    /** A whole run to the stage-5 clear (to the passes held when {@code clears} is false). */
    private static AgentKpqSession cleared(int size, boolean clears) {
        return timeline(size, clears, KING_SLIME_DOWN, LEADER_ON_5);
    }

    private static AgentKpqSession timeline(int size, boolean clears, long lootDelayFromMs, long leaderOnFiveMs) {
        AgentKpqSession session = companionRun(size);
        throughStageOne(session);
        long[][] puzzle = {{JUDGED2, ACCEPT2, STAGE3, 3}, {JUDGED3, ACCEPT3, STAGE4, 6}, {JUDGED4, ACCEPT4, 0, 5}};
        for (int stage = 2; stage <= 4; stage++) {
            long[] times = puzzle[stage - 2];
            session.markPuzzleVerdict(times[0]);
            session.pace().markAccepted(stage, (int) times[3], times[1]);
            if (times[2] != 0L) session.transition(stage == 2 ? AgentKpqSession.Phase.STAGE_3
                    : AgentKpqSession.Phase.STAGE_4, times[2]);
        }
        AgentKpqPaceMarks pace = session.pace();
        pace.markCompanionsOnStageFive(ARRIVAL);
        pace.observeStageFiveMobs(9, 1, ARRIVAL);
        pace.markLeaderOnStageFive(leaderOnFiveMs);
        pace.observeStageFiveMobs(0, 1, NORMALS_DOWN);
        pace.observeStageFiveMobs(0, 0, KING_SLIME_DOWN);
        pace.markLootDelay(lootDelayFromMs, LOOT_FROM);
        if (!clears) return session;
        session.transition(AgentKpqSession.Phase.STAGE_5, LEADER_ON_5);
        pace.markPassesAtLeader(PASSES_HELD);
        session.transition(AgentKpqSession.Phase.CLAIMING_REWARDS, CLEARED);
        return session;
    }

    /** Ben and the companions' policy figures (session kpq-8353c615's party, as in the calibration fixture). */
    private static List<AgentPqMemberPower> members(int size) {
        List<AgentPqMemberPower> all = List.of(
                member(BEN, "Ben", true, 26, 0, 0, 0, 0, 0, 0.01d, 0.01d, 0.01d, 0.01d, 0.0d),
                member(2, "Mira", false, 25, 100, 236, 205, 259, 120, 0.88d, 0.65d, 1.0d, 0.56d, 800.0d),
                member(3, "Pip", false, 26, 300, 164, 205, 158, 95, 1.0d, 1.0d, 1.0d, 1.0d, 800.0d),
                member(4, "Tobi", false, 21, 400, 228, 322, 215, 84, 1.0d, 1.0d, 1.0d, 1.0d, 702.0d));
        return all.subList(0, size);
    }

    private static AgentPqMemberPower member(int id, String name, boolean leader, int level, int jobId, double ligator,
                                             double necki, double curseEye, double kingSlime, double hitLigator,
                                             double hitNecki, double hitCurseEye, double hitKingSlime,
                                             double attackMs) {
        return new AgentPqMemberPower(id, name, leader, leader, level, jobId,
                Map.of(LIGATOR, ligator, NECKI, necki, CURSE_EYE, curseEye, KING_SLIME, kingSlime),
                Map.of(LIGATOR, hitLigator, NECKI, hitNecki, CURSE_EYE, hitCurseEye, KING_SLIME, hitKingSlime),
                Map.of(LIGATOR, attackMs, NECKI, attackMs, CURSE_EYE, attackMs, KING_SLIME, attackMs),
                Double.MAX_VALUE);
    }
}
