package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceObservation;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.living.LivingWorld;
import server.living.store.LivingStore.PqRunPaceRow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.PqPaceTestRuns.START;
import static server.living.roster.PqPaceTestRuns.estimate;
import static server.living.roster.PqPaceTestRuns.ids;
import static server.living.roster.PqPaceTestRuns.pace;
import static server.living.roster.PqPaceTestRuns.run;

/**
 * The PQ clear-time records (pq_run_pace, PqPaceLedger): as a companion run that got in ends, one row holds the entry
 * NPC's estimate at its Yes beside how long it took and its pace as JSON that reads back as the run reported; the
 * write, the refit and the snapshot swap run in one companion-ops task of their own, and one "PQ pace:" line logs it.
 * At boot the current epoch's newest rows are read and fitted per leader on record and for any other leader, an
 * excluded leader teaching no leader term. The lineup reads the snapshot, never the store, and the leader's last clear
 * with exactly the same companions. A cold profile, a failed read or a failed write never breaks a run's end.
 */
class PqPaceLedgerTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int DRIVER = 2;
    private static final AgentPqPaceEstimate SHOWN = estimate(810.4d, 1_062.2d, 1_398.7d, 2,
            Map.of("s1.open", 61.0d, "s1.grind", 118.6d, "s1.handin", 25.0d, "s5.boss", 21.0d));

    private final FakeLivingStore store = new FakeLivingStore();
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final List<Runnable> ops = DirectExecutor.queue();
    private AgentPqPaceProfile profile = new PqPaceTestRuns.Profile(1);
    private final PqPaceLedger ledger;
    private final LivingLogLines lines = new LivingLogLines(PqPaceLedger.class);

    PqPaceLedgerTest() {
        config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        config.COMPANION_PQ_PACE_EXCLUDED_LEADERS = "2";
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store.store);
        CompanionExecutor companionOps = DirectExecutor.queued(ops);
        when(world.executor()).thenReturn(companionOps);
        ledger = new PqPaceLedger(world, key -> "kpq".equals(key) ? profile : null);
    }

    @AfterEach
    void close() {
        lines.close();
    }

    @Test
    void aClearedRunIsWrittenWithTheEstimateItWasShownBesideItsTime() {
        AgentPqPace reported = pace("kpq-a", ANN, List.of(900, 901), true);

        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), reported, START + 2_000_000L);

        assertEquals(1, store.paces.size());
        PqRunPaceRow row = store.paces.getFirst();
        assertEquals("kpq kpq-a leader 10 size 3 humans 1 epoch 1 cleared est 810/1062/1399 records 2 actual 226",
                describe(row));
        assertEquals(START, row.startedAtMs());
        assertEquals(reported.clearedAtMs(), row.clearedAtMs());
        assertEquals(START + 2_000_000L, row.recordedAtMs());
    }

    @Test
    void aFailedRunHasNoTimeAndARunShownNoEstimateHasNone() {
        record(run("kpq-b", ANN, List.of(900, 901), null), pace("kpq-b", ANN, List.of(900, 901), false), START);
        CompanionRun clearedOnlyByTheEvent = run("kpq-c", ANN, List.of(900, 901), SHOWN);
        clearedOnlyByTheEvent.cleared = true; // clearPQ came, but the session ended before it saw the reward phase
        record(clearedOnlyByTheEvent, pace("kpq-c", ANN, List.of(900, 901), false), START);

        assertEquals(List.of("kpq kpq-b leader 10 size 3 humans 1 epoch 1 failed est -/-/- records - actual -",
                        "kpq kpq-c leader 10 size 3 humans 1 epoch 1 cleared est 810/1062/1399 records 2 actual -"),
                store.paces.stream().map(PqPaceLedgerTest::describe).toList());
    }

    @Test
    void anEstimateThatTheLineupCouldNotClearIsNoTimeButKeepsItsRecords() {
        AgentPqPaceEstimate never = new AgentPqPaceEstimate(false, 3_600.0d, 3_600.0d, 3_600.0d, 3_600.0d, 1.0d,
                Map.of(), Map.of(), List.of(), AgentPqPaceCalibration.Source.RECORDS, 4);
        CompanionRun shownNever = run("kpq-n", ANN, List.of(900, 901), never);

        record(shownNever, pace("kpq-n", ANN, List.of(900, 901), true), START);

        assertEquals("kpq kpq-n leader 10 size 3 humans 1 epoch 1 cleared est -/-/- records 4 actual 226",
                describe(store.paces.getFirst()));
        assertEquals(1, lines.startingWith("PQ pace: session=kpq-n leader=10 size=3 epoch=1 shown=never (records) ")
                .size());
    }

    @Test
    void theRowsJsonReadsBackAsTheRunItsSessionReported() {
        AgentPqPace reported = pace("kpq-a", ANN, List.of(900, 901, 902), true);

        record(run("kpq-a", ANN, List.of(900, 901, 902), SHOWN), reported, START);

        PqRunPaceRow row = store.paces.getFirst();
        assertEquals(reported, PqPaceJson.pace(row));
        assertEquals(ids(900, 901, 902), PqPaceJson.companions(row.members()));
        assertTrue(row.spans().startsWith("[{\"key\":\"s1.open\",\"fromMs\":" + (START + 1_000L)), row.spans());
        assertEquals("{\"s1.grind\":100,\"s2.checks\":3}", row.drawn());
    }

    @Test
    void aRunWithoutPowerStillNamesItsCompanionsAndReadsBack() {
        AgentPqPace cold = pace("kpq-a", ANN, List.of(900, 901), true);
        cold = new AgentPqPace(cold.questKey(), cold.sessionId(), cold.leaderId(), cold.startedAtMs(),
                cold.clearedAtMs(), cold.spans(), List.of(), cold.runs().subList(0, 2), cold.drawn());

        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), cold, START);

        PqRunPaceRow row = store.paces.getFirst();
        assertEquals(cold, PqPaceJson.pace(row));
        assertEquals(ids(900, 901), PqPaceJson.companions(row.members()), "901 ran no member record: the lineup's");
    }

    @Test
    void theWriteAndTheRefitRunInACompanionOpsTaskOfTheirOwn() {
        ledger.load();
        ops.forEach(Runnable::run);
        ops.clear();
        AgentPqPaceCalibration before = ledger.calibration("kpq", ANN);

        ledger.record(run("kpq-a", ANN, List.of(900, 901), SHOWN),
                pace("kpq-a", ANN, List.of(900, 901), 60L, 40L, true), START);

        assertEquals(1, ops.size(), "handed to companion-ops");
        assertEquals(List.of(), store.paces, "nothing written on the caller");
        assertEquals(before, ledger.calibration("kpq", ANN), "nothing learned on the caller");
        assertEquals(0, before.records());

        ops.removeFirst().run();

        assertEquals(1, store.paces.size());
        AgentPqPaceCalibration learned = ledger.calibration("kpq", ANN);
        assertEquals(1, learned.records());
        assertEquals(AgentPqPaceCalibration.Source.RECORDS, learned.source());
        assertEquals(fit(List.of(PqPaceJson.pace(store.paces.getFirst())), ANN), learned);
        assertNotEquals(before.leader().get("s1.handin"), learned.leader().get("s1.handin"));
    }

    @Test
    void theLineupReadsTheSnapshotAndNeverTheStore() {
        assertNull(ledger.calibration("kpq", ANN), "nothing read yet: the caller estimates from the priors");
        assertEquals(0.0d, ledger.lastClearS("kpq", ANN, ids(900, 901)));
        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), pace("kpq-a", ANN, List.of(900, 901), true), START);
        clearInvocations(store.store);

        for (int i = 0; i < 3; i++) {
            assertNotNull(ledger.calibration("kpq", ANN));
            assertNotNull(ledger.calibration("kpq", BEN));
            ledger.lastClearS("kpq", ANN, ids(900, 901));
        }

        verifyNoInteractions(store.store);
    }

    @Test
    void bootReadsTheCurrentEpochNewestFirstAndFitsEachLeader() {
        writeRows(List.of(pace("kpq-1", ANN, List.of(900, 901), 70L, 40L, true),
                pace("kpq-2", BEN, List.of(900, 901), 80L, 20L, true),
                pace("kpq-3", ANN, List.of(900, 902), 65L, 50L, false)));
        profile = new PqPaceTestRuns.Profile(2); // a fourth row, under another epoch, is never read
        writeRows(List.of(pace("kpq-4", ANN, List.of(900, 901), 300L, 300L, true)));
        profile = new PqPaceTestRuns.Profile(1);
        PqPaceLedger booted = new PqPaceLedger(world, key -> profile);

        booted.load();

        verify(store.store).pqRunPaces("kpq", 1, AgentPqPaceCalibration.maxRows());
        List<AgentPqPace> newestFirst = List.of(PqPaceJson.pace(store.paces.get(2)), PqPaceJson.pace(store.paces.get(1)),
                PqPaceJson.pace(store.paces.getFirst()));
        assertEquals(fit(newestFirst, ANN), booted.calibration("kpq", ANN));
        assertEquals(fit(newestFirst, BEN), booted.calibration("kpq", BEN));
        assertEquals(fit(newestFirst, PqPaceLedger.ANY_LEADER), booted.calibration("kpq", 77), "no run of its own");
        assertEquals(3, booted.calibration("kpq", ANN).records());
        assertEquals(1, lines.startingWith("PQ pace records of kpq read: epoch=1 runs=3 learned=records/3").size(),
                lines.startingWith("PQ pace records").toString());
    }

    @Test
    void anExcludedLeaderTeachesNoLeaderTermButHisCompanionsTimesCount() {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(profile);

        record(run("kpq-d", DRIVER, List.of(900, 901), SHOWN),
                pace("kpq-d", DRIVER, List.of(900, 901), 90L, 160L, true), START);
        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), pace("kpq-a", ANN, List.of(900, 901), 60L, 50L, true),
                START);

        AgentPqPaceCalibration driver = ledger.calibration("kpq", DRIVER);
        AgentPqPaceCalibration ann = ledger.calibration("kpq", ANN);
        assertEquals(prior.leader().get("s1.handin"), driver.leader().get("s1.handin"), "the driver's 160 s");
        assertTrue(ann.leader().get("s1.handin") > prior.leader().get("s1.handin"), ann.leader().toString());
        assertTrue(driver.companion().get("s1.open") > prior.companion().get("s1.open"),
                "his companions' 90 s opening counts");
        assertEquals(Set.of(DRIVER), config.companionPqPaceExcludedLeaders());
    }

    @Test
    void theLastClearIsTheLeadersNewestClearWithExactlyTheseCompanions() {
        record(run("kpq-1", ANN, List.of(900, 901), SHOWN), pace("kpq-1", ANN, List.of(900, 901), 60L, 25L, true),
                START);
        record(run("kpq-2", ANN, List.of(900, 901), SHOWN), pace("kpq-2", ANN, List.of(900, 901), 50L, 15L, true),
                START);
        record(run("kpq-3", ANN, List.of(900, 901), SHOWN), pace("kpq-3", ANN, List.of(900, 901), false), START);
        record(run("kpq-4", BEN, List.of(902), SHOWN), pace("kpq-4", BEN, List.of(902), true), START);

        assertEquals(206.0d, ledger.lastClearS("kpq", ANN, ids(900, 901)), "the newest clear, not the failed run");
        assertEquals(0.0d, ledger.lastClearS("kpq", ANN, ids(900)));
        assertEquals(0.0d, ledger.lastClearS("kpq", ANN, ids(900, 901, 902)));
        assertEquals(0.0d, ledger.lastClearS("kpq", BEN, ids(900, 901)));
        assertEquals(226.0d, ledger.lastClearS("kpq", BEN, ids(902)));
    }

    @Test
    void onlyTheNewestRunsAFitReadsAreKeptAsARestartWouldReadThem() {
        record(run("kpq-old", ANN, List.of(900, 901), SHOWN), pace("kpq-old", ANN, List.of(900, 901), true), START);
        for (int i = 0; i < AgentPqPaceCalibration.maxRows() - 1; i++) {
            record(run("kpq-" + i, BEN, List.of(902), SHOWN), pace("kpq-" + i, BEN, List.of(902), true), START);
        }
        assertEquals(226.0d, ledger.lastClearS("kpq", ANN, ids(900, 901)), "still among the newest");

        record(run("kpq-new", BEN, List.of(902), SHOWN), pace("kpq-new", BEN, List.of(902), true), START);

        assertEquals(0.0d, ledger.lastClearS("kpq", ANN, ids(900, 901)), "aged out of what a fit reads");
        PqPaceLedger restarted = new PqPaceLedger(world, key -> profile);
        restarted.load();
        assertEquals(0.0d, restarted.lastClearS("kpq", ANN, ids(900, 901)));
        assertEquals(restarted.calibration("kpq", ANN), ledger.calibration("kpq", ANN));
    }

    @Test
    void aColdProfileReadsNothingAndWritesItsRunUnderNoEpochLearningNothing() {
        profile = null;

        ledger.load();
        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), pace("kpq-a", ANN, List.of(900, 901), true), START);

        verify(store.store, never()).pqRunPaces(anyString(), anyInt(), anyInt());
        assertEquals(0, store.paces.getFirst().epoch());
        assertNull(ledger.calibration("kpq", ANN));
        assertEquals(1, lines.startingWith("PQ pace: session=kpq-a leader=10 size=3 epoch=0 ").size());
    }

    @Test
    void aFailedReadLeavesThePriorsAndTheNextRunIsStillLearned() {
        store.pacesUnreadable = true;

        ledger.load();

        assertNull(ledger.calibration("kpq", ANN));
        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), pace("kpq-a", ANN, List.of(900, 901), true), START);
        assertEquals(1, ledger.calibration("kpq", ANN).records());
    }

    @Test
    void aFailedWriteLearnsNothing() {
        store.pacesUnwritable = true;

        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), pace("kpq-a", ANN, List.of(900, 901), true), START);

        assertNull(ledger.calibration("kpq", ANN));
        assertEquals(0.0d, ledger.lastClearS("kpq", ANN, ids(900, 901)));
        assertTrue(lines.startingWith("PQ pace: session=kpq-a").getFirst().endsWith(" learned=none written=false"));
    }

    @Test
    void aRunThatNeverGotInOrReportedNoPaceWritesNothing() {
        CompanionRun neverIn = run("kpq-x", ANN, List.of(900, 901), SHOWN);
        neverIn.started = false;

        ledger.record(neverIn, pace("kpq-x", ANN, List.of(900, 901), true), START);
        ledger.record(run("kpq-y", ANN, List.of(900, 901), SHOWN), null, START);

        assertEquals(List.of(), ops, "nothing handed to companion-ops");
        assertEquals(List.of("PQ pace: session=kpq-y leader=10 size=3 not recorded: the run reported no pace"),
                lines.startingWith("PQ pace:"));
    }

    @Test
    void theRunEndLineSetsTheEstimateShownBesideEachSpan() {
        AgentPqPace reported = pace("kpq-a", ANN, List.of(900, 901), 60L, 25L, false);

        record(run("kpq-a", ANN, List.of(900, 901), SHOWN), reported, START);

        assertEquals(List.of("PQ pace: session=kpq-a leader=10 size=3 epoch=1 shown=1062s (810-1399, records) "
                + "actual=none outcome=failed s1.open=61/60 s1.grind=119/120 s1.handin=25/25 s5.boss=21/(20) "
                + "learned=records/1 notes={s5.boss.incomplete=1}"), lines.startingWith("PQ pace:"));
        PqRunPaceRow row = store.paces.getFirst();
        assertEquals("PQ pace: session=kpq-a leader=10 size=3 epoch=1 shown=none actual=none outcome=failed "
                + "s1.open=-/60 s1.grind=-/120 s1.handin=-/25 s5.boss=-/(20) learned=none written=false",
                PqPaceLedger.line(row, reported, null, null, false));
        AgentPqPaceEstimate never = new AgentPqPaceEstimate(false, 3_600.0d, 3_600.0d, 3_600.0d, 3_600.0d, 1.0d,
                Map.of(), Map.of(), List.of(), AgentPqPaceCalibration.Source.PRIOR, 0);
        assertTrue(PqPaceLedger.line(row, reported, never, null, true).contains(" shown=never (prior) "));
    }

    @Test
    void anUnseenSpanIsADashAndTheSpanThatTookItInCarriesItsEstimate() {
        // KPQ's descent is never seen: its span is zero long and incomplete, and the normals' span runs from arrival.
        AgentPqPace base = pace("kpq-a", ANN, List.of(900, 901), true);
        long arrival = base.spans().getLast().fromMs();
        List<AgentPqPace.Span> spans = new ArrayList<>(base.spans().subList(0, 3));
        spans.add(new AgentPqPace.Span("s5.descent", arrival, arrival, 0.0d, false));
        spans.add(new AgentPqPace.Span("s5.normals", arrival, arrival + 91_000L, 0.0d, true));
        AgentPqPace reported = new AgentPqPace(base.questKey(), base.sessionId(), base.leaderId(), base.startedAtMs(),
                base.clearedAtMs(), spans, base.members(), base.runs(), base.drawn());
        AgentPqPaceEstimate shown = estimate(810.4d, 1_062.2d, 1_398.7d, 2, Map.of("s1.open", 61.0d,
                "s1.grind", 118.6d, "s1.handin", 25.0d, "s5.descent", 30.0d, "s5.normals", 61.0d));
        PqRunPaceRow row = PqPaceLedger.row(run("kpq-a", ANN, List.of(900, 901), shown), reported, 1, START);

        assertEquals("PQ pace: session=kpq-a leader=10 size=3 epoch=1 shown=1062s (810-1399, records) actual=226s "
                + "outcome=cleared s1.open=61/60 s1.grind=119/120 s1.handin=25/25 s5.descent=- s5.normals=91/91 "
                + "learned=none", PqPaceLedger.line(row, reported, shown, null, true));
    }

    @Test
    void theRecordsAreReadOnCompanionOpsAtBootAfterThePaceDataIsWarm() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));
        int warm = start.indexOf("world.executor.submit(\"pq.pace\", world.partyQuests::warmPace);");
        int load = start.indexOf("world.executor.submit(\"pq.pace.records\", world.partyQuests.paceLedger()::load);");

        assertTrue(warm >= 0 && load > warm, "loaded on companion-ops, after the warm-up it fits against");
    }

    /** Ends the run and runs companion-ops' work at once. */
    private void record(CompanionRun run, AgentPqPace pace, long nowMs) {
        ledger.record(run, pace, nowMs);
        while (!ops.isEmpty()) {
            ops.removeFirst().run();
        }
    }

    /** Writes each run (oldest first) through a ledger of its own, as earlier boots did. */
    private void writeRows(List<AgentPqPace> paces) {
        PqPaceLedger earlier = new PqPaceLedger(world, key -> profile);
        for (AgentPqPace written : paces) {
            List<Integer> companions = new ArrayList<>();
            written.members().stream().filter(member -> !member.human())
                    .forEach(member -> companions.add(member.characterId()));
            earlier.record(run(written.sessionId(), written.leaderId(), companions, SHOWN), written, START);
            while (!ops.isEmpty()) {
                ops.removeFirst().run();
            }
        }
    }

    /** The calibration for {@code leaderId} fitted from these runs (newest first) with the config's exclusions. */
    private AgentPqPaceCalibration fit(List<AgentPqPace> newestFirst, int leaderId) {
        List<AgentPqPaceObservation> observations = new ArrayList<>();
        for (int rank = 0; rank < newestFirst.size(); rank++) {
            observations.add(new AgentPqPaceObservation(rank, profile.epoch(), newestFirst.get(rank)));
        }
        return AgentPqPaceCalibration.fit(profile, observations, leaderId, config.companionPqPaceExcludedLeaders());
    }

    private static String describe(PqRunPaceRow row) {
        return row.pqKey() + " " + row.sessionId() + " leader " + row.leaderId() + " size " + row.partySize()
                + " humans " + row.humans() + " epoch " + row.epoch() + " " + row.outcome() + " est "
                + text(row.estP10S()) + "/" + text(row.estP50S()) + "/" + text(row.estP90S()) + " records "
                + text(row.estRecords()) + " actual " + text(row.actualS());
    }

    private static String text(Integer value) {
        return value == null ? "-" : value.toString();
    }
}
