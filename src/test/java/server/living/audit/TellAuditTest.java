package server.living.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.living.audit.TellAudit.Seen;
import server.living.audit.TellBands.Verdict;
import server.living.audit.TellSnapshot.Row;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tell audit fed synthetic events: a scripted-looking population (fixed delays, clockwork chatter, a stack of idle
 * bots, a citizen online all day, AFK on a field, one route walked again and again, every drink and recast at one
 * point) comes out red on each tell, and a varied one green.
 */
class TellAuditTest {
    private static final long HOUR = 3_600_000L;
    private static final long START = 1_000L * HOUR;
    private final List<String> written = new ArrayList<>();

    @AfterEach
    void tearDown() {
        TellAudit.uninstall();
    }

    @Test
    void aScriptedPopulationIsRedOnEveryTell() {
        TellAudit audit = new TellAudit("abc123def", START, written::add);
        for (int bot = 1; bot <= 5; bot++) {
            for (int i = 0; i < 20; i++) {
                audit.combatReaction(bot, 120);              // the same 120 ms every time
            }
        }
        for (int i = 0; i < 40; i++) {
            audit.chatReply(2_000);
            audit.potionPoint(70);
            audit.buffRecast(90);
        }
        for (int speaker = 1; speaker <= 3; speaker++) {
            for (int line = 0; line < 20; line++) {
                audit.line(speaker, 100_000_000, "map", "selling stuff, pm me", START + line * 45_000L + speaker);
            }
        }
        List<Seen> stack = new ArrayList<>();
        Object henesys = new Object();
        for (int bot = 1; bot <= 5; bot++) {
            stack.add(new Seen(henesys, bot, 100 + bot, 0, 112, true));
        }
        for (int minute = 0; minute <= 25; minute++) {
            audit.sample(stack, START + minute * 60_000L);
        }
        audit.sessionEnded(42, START, START + 22 * HOUR);
        audit.afkStarted("field", 10 * 60_000L);
        for (int walk = 0; walk < 11; walk++) {
            audit.routeWalked(7, 100_000_000, 101_000_000, 0xBEEFL);
        }

        TellSnapshot snapshot = audit.snapshot(START + 24 * HOUR);

        assertEquals(Verdict.RED, verdict(snapshot, "rc.cv"), "fixed reaction times");
        assertEquals(Verdict.RED, verdict(snapshot, "rc.p5"), "faster than a person");
        assertEquals(Verdict.RED, verdict(snapshot, "rh.cv"), "every reply after the same 2 s");
        assertEquals(Verdict.RED, verdict(snapshot, "cc.cv"), "a line every 45 s like clockwork");
        assertEquals(Verdict.RED, verdict(snapshot, "cr"), "the same line again and again on one map");
        assertEquals(Verdict.RED, verdict(snapshot, "st"), "five bots idling on one spot");
        assertEquals(Verdict.RED, verdict(snapshot, "po"), "online 22 h of the day");
        assertEquals(Verdict.RED, verdict(snapshot, "fa"), "10 min AFK on a field");
        assertEquals(Verdict.RED, verdict(snapshot, "ro"), "the same route every time");
        assertEquals(Verdict.RED, verdict(snapshot, "pp"), "every drink at 70%");
        assertEquals(Verdict.RED, verdict(snapshot, "br"), "every recast at 90%");
        assertEquals(Verdict.GREEN, verdict(snapshot, "rh.p5"), "chat replies are never too fast");
        assertEquals(Verdict.INFO, verdict(snapshot, "se"));
        assertEquals(70.0, row(snapshot, "pp").detail().get("percent"));
        assertEquals(22 * 60, row(snapshot, "se").value(), "the session's minutes");
        assertEquals(24.0, snapshot.hours(), 1e-9);
    }

    @Test
    void aVariedPopulationIsGreen() {
        TellAudit audit = new TellAudit("abc123def", START, written::add);
        SplittableRandom random = new SplittableRandom(7);
        for (int bot = 1; bot <= 5; bot++) {
            for (int i = 0; i < 40; i++) {
                audit.combatReaction(bot, 200 + random.nextInt(900));
            }
        }
        for (int i = 0; i < 60; i++) {
            audit.chatReply(800 + random.nextInt(9_000));
            audit.potionPoint(55 + random.nextInt(25));
            audit.buffRecast(80 + random.nextInt(20));
        }
        for (int speaker = 1; speaker <= 3; speaker++) {
            long at = START;
            for (int line = 0; line < 20; line++) {
                at += 20_000 + random.nextInt(600_000);
                audit.line(speaker, 100_000_000 + speaker, "map", "line " + speaker + " " + line, at);
            }
        }
        for (int minute = 0; minute <= 40; minute++) {
            List<Seen> spread = new ArrayList<>();
            for (int bot = 1; bot <= 5; bot++) {
                spread.add(new Seen("henesys", bot, bot * 200, 0, 412, true)); // idle, but 200 px apart
            }
            audit.sample(spread, START + minute * 60_000L);
        }
        audit.sessionEnded(42, START, START + 4 * HOUR);
        audit.afkStarted("town", 10 * 60_000L);
        audit.afkStarted("fm", 12 * 60_000L);
        for (int walk = 0; walk < 11; walk++) {
            audit.routeWalked(7, 100_000_000, 101_000_000, walk);
        }

        TellSnapshot snapshot = audit.snapshot(START + 24 * HOUR);

        for (Row row : snapshot.rows()) {
            if (row.band() != null) {
                assertEquals(Verdict.GREEN, row.verdict(), row.id() + " = " + row.value());
            }
        }
        assertEquals(2, row(snapshot, "fa").n(), "two AFK spells seen");
        assertEquals(1.0, row(snapshot, "fa").detail().get("town"));
    }

    @Test
    void anEmptyAuditHasNoVerdictsYet() {
        TellSnapshot snapshot = new TellAudit("x", START, written::add).snapshot(START + HOUR);
        for (Row row : snapshot.rows()) {
            assertTrue(row.verdict() == Verdict.NO_DATA || row.verdict() == Verdict.INFO, row.id());
        }
        assertEquals("nan", TellSnapshot.number(row(snapshot, "rc.cv").value()));
    }

    @Test
    void repeatsCountOnlyOnOneMapInsideTenMinutes() {
        TellAudit audit = new TellAudit("x", START, written::add);
        audit.line(1, 100, "map", "hi all", START);
        audit.line(2, 200, "map", "hi all", START + 1_000);               // another map
        audit.line(3, 100, "map", "Hi All ", START + 11 * 60_000L);       // past ten minutes
        audit.line(4, 100, "map", "hi all", START + 12 * 60_000L);        // a repeat
        audit.line(5, 100, "party", "hi all", START + 12 * 60_000L);      // not map chat
        Row repeat = row(audit.snapshot(START + HOUR), "cr");
        assertEquals(4, repeat.n());
        assertEquals(1.0, repeat.detail().get("repeats"));
    }

    @Test
    void idleMeansAliveAndStillOnTheSameMap() {
        TellAudit audit = new TellAudit("x", START, written::add);
        Object map = new Object();
        audit.sample(List.of(new Seen(map, 1, 0, 0, 0, true), new Seen(map, 2, 0, 0, 0, false),
                new Seen(map, 3, 0, 0, 0, true)), START);
        audit.sample(List.of(new Seen(map, 1, 5, 3, 0, true), new Seen(map, 2, 0, 0, 0, false),
                new Seen(new Object(), 3, 0, 0, 0, true)), START + 60_000L);
        Row stacking = row(audit.snapshot(START + HOUR), "st");
        assertEquals(1, stacking.n(), "only bot 1: bot 2 is dead, bot 3 changed maps");
    }

    @Test
    void deathsCountByClassAndActivityWithTheBotHoursBehindThem() {
        TellAudit audit = new TellAudit("abc123def", START, written::add);
        List<Seen> bots = List.of(new Seen("m", 1, 0, 0, 112, true), new Seen("m", 2, 900, 0, 1311, true));
        for (int minute = 0; minute <= 60; minute++) {
            audit.sample(bots, START + minute * 60_000L);
        }
        audit.died("warrior", "field");
        audit.died("warrior", "field");
        audit.died("bowman", "boss");

        TellSnapshot snapshot = audit.snapshot(START + HOUR);
        assertEquals(1.0, snapshot.botHoursByClass().get("warrior"), 1e-9);
        assertEquals(1.0, snapshot.botHoursByClass().get("bowman"), 1e-9, "Wind Archer counts as a bowman");
        assertEquals(List.of(new TellSnapshot.Deaths("bowman", "boss", 1), new TellSnapshot.Deaths("warrior", "field", 2)),
                snapshot.deaths());
        String line = snapshot.compact();
        assertTrue(line.startsWith("b=abc123def h=1 rc.cv=nan:- "), line);
        assertTrue(line.contains(" d=warrior:2/1,bowman:1/1 da=field:2,boss:1,instance:0"), line);
    }

    @Test
    void theFoldWritesOneRowAnHourThatFitsTheEventLog() {
        TellAudit audit = new TellAudit("abc123def", START, written::add);
        audit.foldIfDue(START + HOUR - 1);
        assertTrue(written.isEmpty());
        assertNull(audit.lastFold());
        audit.foldIfDue(START + HOUR);
        audit.foldIfDue(START + HOUR + 60_000L);
        assertEquals(1, written.size());
        assertEquals(written.get(0), audit.lastFold().compact());
        for (int bot = 0; bot < 7; bot++) {
            audit.died(TellAudit.CLASSES[bot], "instance");
        }
        List<Seen> everyClass = new ArrayList<>();
        for (int job = 0; job < 7; job++) {
            everyClass.add(new Seen("m", job, 0, 0, job == 6 ? 900 : job * 100 + 12, true));
        }
        audit.sample(everyClass, START + HOUR);
        audit.sample(everyClass, START + HOUR + 61_234L);
        audit.foldIfDue(START + 2 * HOUR);
        assertEquals(2, written.size());
        assertTrue(written.get(1).length() <= 512, written.get(1).length() + ": " + written.get(1));
    }

    @Test
    void theProbeReachesTheRunningAuditOnly() {
        TellAudit audit = new TellAudit("x", START, written::add);
        assertFalse(AgentTellProbe.on());
        AgentTellProbe.combatReaction(1, 400); // nobody listens
        TellAudit.start(audit);
        assertTrue(AgentTellProbe.on());
        assertEquals(audit, TellAudit.active());
        AgentTellProbe.combatReaction(1, 400);
        AgentTellProbe.potionPoint(70);
        TellAudit.uninstall();
        assertFalse(AgentTellProbe.on());
        AgentTellProbe.potionPoint(70);
        TellSnapshot snapshot = audit.snapshot(START + HOUR);
        assertEquals(1, row(snapshot, "rc.p5").n());
        assertEquals(1, row(snapshot, "pp").n());
    }

    @Test
    void classesFollowTheJobBranch() {
        assertEquals("beginner", TellAudit.CLASSES[TellAudit.classIndex(0)]);
        assertEquals("warrior", TellAudit.CLASSES[TellAudit.classIndex(132)]);
        assertEquals("magician", TellAudit.CLASSES[TellAudit.classIndex(232)]);
        assertEquals("bowman", TellAudit.CLASSES[TellAudit.classIndex(322)]);
        assertEquals("thief", TellAudit.CLASSES[TellAudit.classIndex(422)]);
        assertEquals("pirate", TellAudit.CLASSES[TellAudit.classIndex(522)]);
        assertEquals("warrior", TellAudit.CLASSES[TellAudit.classIndex(1111)], "Dawn Warrior");
        assertEquals("warrior", TellAudit.CLASSES[TellAudit.classIndex(2112)], "Aran");
        assertEquals("beginner", TellAudit.CLASSES[TellAudit.classIndex(2000)], "Legend");
        assertEquals("other", TellAudit.CLASSES[TellAudit.classIndex(900)], "GM");
    }

    @Test
    void theBuildIsTheCheckoutsHead(@TempDir Path dir) throws Exception {
        Path main = Files.createDirectories(dir.resolve("main"));
        Files.createDirectories(main.resolve(".git/refs/heads/feat"));
        Files.writeString(main.resolve(".git/HEAD"), "ref: refs/heads/feat/x\n");
        Files.writeString(main.resolve(".git/refs/heads/feat/x"), "a435dc14b1735039033fc503d4ef1ae3afa7f201\n");
        assertEquals("a435dc14b", TellAudit.buildSha(main));

        Files.delete(main.resolve(".git/refs/heads/feat/x"));
        Files.writeString(main.resolve(".git/packed-refs"),
                "# pack-refs with: peeled\n1234567890abcdef1234567890abcdef12345678 refs/heads/feat/x\n");
        assertEquals("123456789", TellAudit.buildSha(main), "a packed ref");

        Path worktree = Files.createDirectories(dir.resolve("wt"));
        Path gitDir = Files.createDirectories(main.resolve(".git/worktrees/wt"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir.toAbsolutePath() + "\n");
        Files.writeString(gitDir.resolve("commondir"), "../..\n");
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feat/x\n");
        assertEquals("123456789", TellAudit.buildSha(worktree), "a worktree reads the shared packed refs");

        Files.writeString(gitDir.resolve("HEAD"), "fedcba9876543210fedcba9876543210fedcba98\n");
        assertEquals("fedcba987", TellAudit.buildSha(worktree), "a detached head");
        assertEquals("unknown", TellAudit.buildSha(dir.resolve("nowhere")));
    }

    private static Row row(TellSnapshot snapshot, String code) {
        return snapshot.rows().stream().filter(row -> row.code().equals(code)).findFirst().orElseThrow();
    }

    private static Verdict verdict(TellSnapshot snapshot, String code) {
        return row(snapshot, code).verdict();
    }
}
