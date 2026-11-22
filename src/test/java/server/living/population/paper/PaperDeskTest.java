package server.living.population.paper;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The desk's week (docs/WORLD_SYSTEMS_PLAN.md N1.1): one issue each Sunday, kept once across restarts. */
class PaperDeskTest {
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    private static final int WEEK = (int) MONDAY.toEpochDay();
    private static final long MINUTE = 60_000L;

    private final PaperFakes.Store store = new PaperFakes.Store();
    private final PaperFakes.Sources sources = new PaperFakes.Sources();
    private final PopulationConfig config = new PopulationConfig();

    private PaperDesk desk() {
        return new PaperDesk(store, sources, PaperFakes::shippedCatalog, () -> config, Runnable::run, ZONE);
    }

    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @AfterEach
    void uninstall() {
        PaperDesk.install(null);
    }

    @Test
    void theIssueTimeIsSundayMidnightOfTheWeek() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        assertEquals(WEEK, desk.weekOf(at(22, 23, 59)), "Sunday is the week's last day");
        assertEquals(WEEK + 7, desk.weekOf(at(23, 0, 0)));
        assertEquals(at(22, 0, 0), desk.issueAtMs(WEEK));
        assertEquals(WEEK - 7, desk.lastDueWeek(at(21, 23, 0)));
        assertEquals(WEEK, desk.lastDueWeek(at(22, 0, 0)));
        config.PAPER_DAY = "saturday";
        config.PAPER_HOUR = 22;
        assertEquals(at(21, 22, 0), desk.issueAtMs(WEEK));
    }

    @Test
    void theSocialMinuteIssuesInTheWindowOnly() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        desk.minute(at(21, 23, 59));
        assertEquals(0, store.inserts, "not before the hour");
        desk.minute(at(22, 0, 0));
        assertEquals(1, store.inserts);
        assertTrue(store.issues.containsKey(WEEK));
        assertEquals(at(22, 0, 0), store.issues.get(WEEK).issuedAtMs(), "issued as of the hour, not the minute it ran");
        desk.minute(at(22, 0, 1));
        assertEquals(1, store.inserts);
    }

    @Test
    void anIssueMoreThanADayLateIsSkipped() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        desk.minute(at(22, 23, 59));
        assertEquals(1, store.inserts, "down at midnight, back the same day: issued late");
        PaperFakes.Store other = new PaperFakes.Store();
        new PaperDesk(other, sources, PaperFakes::shippedCatalog, () -> config, Runnable::run, ZONE).minute(at(23, 0, 1));
        assertEquals(0, other.inserts, "Monday is the next week, whose issue is six days off");
    }

    @Test
    void aRestartInTheIssueHourComposesNothingNew() {
        config.PAPER_ENABLED = true;
        desk().minute(at(22, 0, 0));
        String first = store.issues.get(WEEK).body();
        sources.items.add(new PaperItem(at(21, 12, 0), "town.wedding", 70, Map.of("name", "Ann", "partner", "Ben")));
        PaperDesk restarted = desk(); // a new desk, same table: what a restart leaves
        restarted.minute(at(22, 0, 30));
        restarted.minute(at(22, 1, 0));
        assertEquals(1, store.inserts, "exactly one issue");
        assertEquals(first, store.issues.get(WEEK).body(), "the kept issue is not rewritten");
        assertTrue(restarted.issue(WEEK).isPresent());
        assertEquals(1, store.inserts, "a GM's !paper issue after it writes nothing either");
    }

    @Test
    void storageDownIssuesNothingAndTriesAgain() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        store.down = true;
        desk.minute(at(22, 0, 0));
        assertEquals(0, store.inserts);
        store.down = false;
        desk.minute(at(22, 0, 1));
        assertEquals(1, store.inserts);
    }

    @Test
    void offIssuesNothingAndFilesNothing() {
        PaperDesk desk = desk();
        PaperDesk.install(desk);
        desk.minute(at(22, 0, 0));
        PaperDesk.file("town.wedding", 70, Map.of("name", "Ann", "partner", "Ben"));
        assertEquals(0, store.inserts);
        assertTrue(store.filed.isEmpty());
    }

    @Test
    void fileWritesOneRowWithTheKeyWeightAndSlots() {
        config.PAPER_ENABLED = true;
        PaperDesk.install(desk());
        PaperDesk.file("town.wedding", 5000, Map.of("name", "Ann", "partner", "Ben"));
        PaperDesk.file("nosection", 10, Map.of());
        PaperDesk.file("town.level", 10, Map.of("name", "x".repeat(600), "level", "70"));
        assertEquals(1, store.filed.size(), "a key with no section, or a row past 512 characters, is not filed");
        PaperItem item = PaperItem.parse(1L, store.filed.get(0));
        assertEquals("town.wedding", item.key());
        assertEquals(1000, item.weight(), "weights are capped");
        assertEquals(Map.of("name", "Ann", "partner", "Ben"), item.slots());
    }

    @Test
    void fileNamingLooksTheCharacterUp() {
        config.PAPER_ENABLED = true;
        store.names.put(42, "Cole");
        PaperDesk.install(desk());
        PaperDesk.fileNaming("town.guild", 50, Map.of("guild", "Lumen"), "leader", 42);
        PaperDesk.fileNaming("town.guild", 50, Map.of("guild", "Ghost"), "leader", 43);
        assertEquals(1, store.filed.size(), "no name, no story");
        assertEquals("Cole", PaperItem.parse(1L, store.filed.get(0)).slots().get("leader"));
    }

    @Test
    void aPlayerSeesEachIssueOnce() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        desk.minute(at(22, 0, 0));
        long later = at(22, 9, 0);
        assertTrue(desk.unseen(7, later).isPresent());
        desk.seen(7, WEEK);
        assertFalse(desk.unseen(7, later).isPresent());
        desk.seen(7, WEEK);
        assertEquals(1, store.seenWrites, "seeing it again writes no second marker");
        assertFalse(desk.unseen(8, at(22, 0, 0) + 7 * 24 * 60 * MINUTE).isPresent(), "a week on, it is old news");
    }

    @Test
    void theLatestIssueAndTheOneBeforeIt() {
        config.PAPER_ENABLED = true;
        PaperDesk desk = desk();
        desk.issue(WEEK - 7);
        desk.issue(WEEK);
        var latest = desk.latest(at(22, 1, 0));
        assertTrue(latest.isPresent());
        assertEquals(WEEK, latest.get().weekOf());
        assertEquals(WEEK - 7, desk.before(latest.get()).orElseThrow().weekOf());
        assertTrue(desk.before(desk.before(latest.get()).orElseThrow()).isEmpty());
        assertTrue(desk.fresh(at(22, 23, 0)).isPresent());
        assertTrue(desk.fresh(at(23, 0, 1)).isEmpty(), "talked about on its first day only");
    }
}
