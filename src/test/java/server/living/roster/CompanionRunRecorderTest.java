package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionRunStats;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The companions' KPQ records (P6, companion_performance): as a run that got in ends, each lineup companion gets a
 * row (tapped out, else cleared or failed with the run; its deaths in the run, the party's size, how long it took),
 * and its record grows by it once the row is written. A tap-out whose hold runs after the clear, the coordinator
 * having asked before it saw it, counts the clear. A run that never got in writes nothing. The records are read
 * once, on companion-ops at boot; a failed read leaves them empty, and a failed write leaves a record as it was.
 */
class CompanionRunRecorderTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona mira;
    private Persona tobi;
    private CompanionRunRecorder records;

    @BeforeEach
    void annAndThreeCompanionsAtLakelis() {
        mira = fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        tobi = fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        records = fx.ready().records();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aStartedRunWritesOneRowPerCompanionAsItEnds() {
        CompanionRun run = fx.yes();
        game.annEntersInstance(103000802);
        Character miraBot = game.bot(mira);
        when(miraBot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        fx.service.died(miraBot);
        fx.service.died(miraBot);
        game.bot(tobi).changeMap(game.map(CompanionPqFixture.EXIT), new Point(100, 200));
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 3_000L));
        fx.service.cleared(List.of(game.ann));

        fx.sessionEnds(true);

        List<CompanionRunRow> rows = game.store.performance.stream()
                .sorted(Comparator.comparingInt(CompanionRunRow::companionCharacterId)).toList();
        assertEquals(List.of("900 mira kpq cleared, deaths 2, party 4", "901 tobi kpq tapped_out, deaths 0, party 4",
                "902 pip kpq cleared, deaths 0, party 4"), rows.stream().map(CompanionRunRecorderTest::line).toList());
        assertTrue(rows.stream().allMatch(row -> row.ownerCharacterId() == ANN));
        assertEquals(new CompanionRunStats(1, 1), records.stats(900));
        assertEquals(new CompanionRunStats(1, 0), records.stats(901));
    }

    @Test
    void aTapOutThatRacesTheClearCountsTheClear() {
        CompanionRun run = fx.yes();
        game.annEntersInstance(103000802);
        game.bot(tobi).changeMap(game.map(CompanionPqFixture.EXIT), new Point(100, 200));
        // Ann's Cloto click clears stage 5 after the coordinator read the clear for this tick
        fx.service.cleared(List.of(game.ann));
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 3_000L));

        fx.sessionEnds(true);

        assertEquals(List.of("901 tobi kpq cleared, deaths 0, party 4"), game.store.performance.stream()
                .filter(row -> row.companionCharacterId() == 901).map(CompanionRunRecorderTest::line).toList());
        assertEquals(new CompanionRunStats(1, 1), records.stats(901));
    }

    @Test
    void aTapOutWhoseHoldRunsAfterTheClearCountsTheClear() {
        CompanionRun run = fx.yes();
        game.annEntersInstance(103000802);
        game.bot(tobi).changeMap(game.map(CompanionPqFixture.EXIT), new Point(100, 200));
        List<Runnable> ops = fx.holdCompanionOps();
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 3_000L));
        fx.service.cleared(List.of(game.ann)); // while its hold waits on companion-ops, still in the party
        CompanionPqFixture.drain(ops);

        fx.sessionEnds(true);
        CompanionPqFixture.drain(ops);

        assertEquals(List.of("901 tobi kpq cleared, deaths 0, party 4"), game.store.performance.stream()
                .filter(row -> row.companionCharacterId() == 901).map(CompanionRunRecorderTest::line).toList());
    }

    @Test
    void aRunIsRecordedWithItsLengthInSeconds() {
        CompanionRun run = fx.yes();

        records.record(run, run.startedAtMs + 754_900L);

        assertEquals(3, game.store.performance.size());
        assertTrue(game.store.performance.stream()
                .allMatch(row -> row.durationS() == 754 && row.outcome().equals("failed")));
    }

    @Test
    void anAbortedRunWritesNothing() {
        fx.doors.start = PqEntryPort.Start.NO_LOBBY;

        fx.yes(); // admitted, but the event did not start: stopped again, and its run ended

        assertTrue(game.calls.contains("answer " + LakelisLines.NO_LOBBY), game.calls.toString());
        assertEquals(List.of(), game.store.performance);
        assertEquals(CompanionRunRecorder.NONE, records.stats(900));
    }

    @Test
    void theRecordsAreReadOnceAndGrowWithEachRowWritten() {
        game.store.performance.addAll(List.of(row(900, "cleared"), row(900, "failed"), row(901, "tapped_out")));
        records.load();
        assertEquals(new CompanionRunStats(2, 1), records.stats(900));
        assertEquals(new CompanionRunStats(1, 0), records.stats(901));
        assertEquals(CompanionRunRecorder.NONE, records.stats(902));

        fx.yes();
        fx.service.cleared(List.of(game.ann));
        fx.sessionEnds(true);

        assertEquals(new CompanionRunStats(3, 2), records.stats(900));
        assertEquals(new CompanionRunStats(2, 1), records.stats(901));
        assertEquals(new CompanionRunStats(1, 1), records.stats(902));
        verify(game.store.store, times(1)).companionRunStats("kpq");
    }

    @Test
    void theRecordsAreReadOnCompanionOpsAtBoot() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        String start = source.substring(source.indexOf("public static synchronized void start()"),
                source.indexOf("public static synchronized void stop()"));

        assertTrue(start.contains("world.executor.submit(\"pq.records.load\", world.partyQuests.records()::load);"));
    }

    @Test
    void aFailedReadLeavesTheRecordsEmpty() {
        game.store.performance.add(row(900, "cleared"));
        game.store.performanceUnreadable = true;

        records.load();

        assertEquals(CompanionRunRecorder.NONE, records.stats(900));
    }

    @Test
    void aFailedWriteLeavesTheRecordAsItWas() {
        game.store.performance.addAll(List.of(row(900, "cleared"), row(900, "failed")));
        records.load();
        game.store.performanceUnwritable = true;

        fx.yes();
        fx.service.cleared(List.of(game.ann));
        fx.sessionEnds(true);

        assertEquals(new CompanionRunStats(2, 1), records.stats(900));
        assertEquals(CompanionRunRecorder.NONE, records.stats(902));
    }

    private static CompanionRunRow row(int companionId, String outcome) {
        return new CompanionRunRow(ANN, companionId, "p" + companionId, "kpq", outcome, 600, 0, 4);
    }

    private static String line(CompanionRunRow row) {
        return row.companionCharacterId() + " " + row.personaId() + " " + row.activity() + " " + row.outcome()
                + ", deaths " + row.deaths() + ", party " + row.partySize();
    }
}
