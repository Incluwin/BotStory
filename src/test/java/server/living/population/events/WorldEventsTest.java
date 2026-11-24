package server.living.population.events;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession.Activity;
import server.living.population.Market;
import server.living.population.Population;
import server.living.population.PresenceScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The calendar (N4.1): planning, one row an occurrence across restarts, starts, ends and what they leave behind. */
class WorldEventsTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    /** A Monday whose week a fortnightly event is due in. */
    private static final int WEEK = (int) LocalDate.of(2026, 11, 23).toEpochDay();
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final EventFakes.Store store = new EventFakes.Store();
    private final List<String> notices = new ArrayList<>();

    @BeforeEach
    void on() {
        config.WORLD_EVENTS_ENABLED = true;
        config.WORLD_EVENT_MARKET_DAY_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.channels()).thenReturn(List.of(1, 2));
        when(population.presence()).thenReturn(mock(PresenceScheduler.class));
        when(population.market()).thenReturn(mock(Market.class));
    }

    private WorldEvents calendar() {
        WorldEvents events = new WorldEvents(population, store, EventCatalog.load(Path.of("scripts/config/population")), UTC,
                new Random(1));
        events.notice = notices::add;
        return events;
    }

    private static int dueWeek() {
        return Math.floorMod(Math.floorDiv(WEEK, 7), 2) == 0 ? WEEK : WEEK + 7;
    }

    private static long at(DayOfWeek day, int hour) {
        return HourPicker.atMs(dueWeek(), HourPicker.slot(day, hour), UTC);
    }

    @Test
    void offNothingIsPlanned() {
        config.WORLD_EVENTS_ENABLED = false;
        calendar().minute(at(DayOfWeek.MONDAY, 9));
        assertTrue(store.rows.isEmpty());
        config.WORLD_EVENTS_ENABLED = true;
        config.WORLD_EVENT_MARKET_DAY_ENABLED = false;
        calendar().minute(at(DayOfWeek.MONDAY, 9));
        assertTrue(store.rows.isEmpty(), "its own switch is off");
    }

    @Test
    void aWeekIsPlannedOnceWhateverTheRestarts() {
        for (int boot = 0; boot < 3; boot++) {
            WorldEvents events = calendar();
            events.minute(at(DayOfWeek.MONDAY, 9) + boot * HOUR);
            events.minute(at(DayOfWeek.MONDAY, 9) + boot * HOUR + MINUTE);
        }
        assertEquals(1, store.rows.size());
        WorldEvents.Occurrence row = store.rows.getFirst();
        assertEquals(dueWeek(), row.number());
        assertEquals(at(DayOfWeek.SUNDAY, 15), row.startsAtMs(), "no samples: its fallback slot");
        assertEquals(row.startsAtMs() + 180 * MINUTE, row.endsAtMs());
    }

    @Test
    void nextWeekIsPlannedInTheHoursBeforeThePaper() {
        int before = dueWeek() - 7;
        long paper = HourPicker.atMs(before, HourPicker.slot(DayOfWeek.SUNDAY, 0), UTC);
        calendar().minute(paper - 7 * HOUR);
        assertTrue(store.rows.isEmpty(), "this week has none (fortnightly), next week not yet");
        calendar().minute(paper - 5 * HOUR);
        assertEquals(dueWeek(), store.of("marketday").number());
    }

    @Test
    void storageDownDecidesNothing() {
        store.down = true;
        calendar().minute(at(DayOfWeek.MONDAY, 9));
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void itStartsOnceRunsAndEndsCleanly() {
        WorldEvents events = calendar();
        events.minute(at(DayOfWeek.MONDAY, 9));
        long start = store.of("marketday").startsAtMs();
        events.minute(start - 9 * MINUTE);
        events.minute(start - 8 * MINUTE);
        assertEquals(1, notices.size(), "one warning: " + notices);
        assertSame(WorldModifier.NONE, events.modifier());
        // the modifier runs on the wall clock: the run is moved there by a GM start
        assertTrue(events.start("marketday", null, System.currentTimeMillis()).contains("started"));
        assertEquals(12, events.modifier().stallCap(6));
        assertEquals(0.85d, events.modifier().askShare(), 1e-9);
        assertEquals(30, events.modifier().weigh(Activity.MARKET, "victoria", 10));
        assertEquals(10, events.modifier().weigh(Activity.TRAIN, "victoria", 10));
        assertEquals(0, events.modifier().weigh(Activity.MARKET, "victoria", 0), "never an activity the cohort does not do");
        assertTrue(events.modifier().holdsPrices());
        assertTrue(events.start("marketday", null, System.currentTimeMillis() + 5_000L).contains("could not start"), "one at a time");
        events.tick(System.currentTimeMillis() + 181 * MINUTE);
        assertTrue(events.runs().isEmpty());
        assertEquals(6, events.modifier().stallCap(6));
        assertEquals(1.0d, events.modifier().askShare());
        assertEquals(10, events.modifier().weigh(Activity.MARKET, "victoria", 10));
        assertTrue(events.modifier().holdsPrices(), "its stalls are still up for an hour");
        assertEquals("DONE", store.rows.get(1).state());
    }

    @Test
    void aRestartResumesARunningEventAndNeverStartsItAgain() {
        WorldEvents events = calendar();
        long now = System.currentTimeMillis();
        events.start("marketday", null, now);
        long endsAt = store.rows.getFirst().endsAtMs();
        events.shutdown();
        assertSame(WorldModifier.NONE, events.modifier());
        assertEquals("RUNNING", store.rows.getFirst().state(), "its row waits for the next start");
        int said = notices.size();

        WorldEvents again = calendar();
        again.minute(now + HOUR);
        assertEquals(1, again.runs().size());
        assertEquals(endsAt, again.runs().getFirst().endsAtMs, "it ends when it would have");
        assertEquals(12, again.modifier().stallCap(6));
        assertEquals(said, notices.size(), "a resumed event does not announce itself again");
        again.tick(endsAt);
        again.minute(endsAt + MINUTE);
        assertTrue(again.runs().isEmpty());
        assertEquals(1, store.rows.stream().filter(row -> row.id() == 1L && row.state().equals("DONE")).count());
    }

    @Test
    void aRestartAfterTheEndClosesTheRow() {
        WorldEvents events = calendar();
        long now = System.currentTimeMillis();
        events.start("marketday", null, now);
        events.shutdown();
        WorldEvents again = calendar();
        again.minute(now + 4 * HOUR);
        assertEquals("DONE", store.rows.getFirst().state());
        assertTrue(again.runs().isEmpty());
        assertFalse(again.modifier().holdsPrices());
    }

    @Test
    void aLateStartRunsInFullAndAVeryLateOneIsMissed() {
        calendar().minute(at(DayOfWeek.MONDAY, 9));
        long start = store.of("marketday").startsAtMs();
        WorldEvents late = calendar(); // the server was down over the start
        late.minute(start + 2 * HOUR);
        assertEquals("RUNNING", store.of("marketday").state());
        assertEquals(start + 2 * HOUR + 180 * MINUTE, store.of("marketday").endsAtMs());

        store.rows.clear();
        calendar().minute(at(DayOfWeek.MONDAY, 9));
        calendar().minute(start + 3 * HOUR);
        assertEquals("MISSED", store.of("marketday").state());
        calendar().minute(start + 3 * HOUR + MINUTE);
        assertEquals(1, store.rows.size(), "not planned again");
    }

    @Test
    void theKillSwitchEndsARunningEvent() {
        WorldEvents events = calendar();
        events.start("marketday", null, System.currentTimeMillis());
        config.WORLD_EVENT_MARKET_DAY_ENABLED = false;
        events.tick(System.currentTimeMillis());
        assertEquals("CANCELLED", store.rows.getFirst().state());
        assertEquals(6, events.modifier().stallCap(6));
        config.WORLD_EVENT_MARKET_DAY_ENABLED = true;
        events.start("marketday", null, System.currentTimeMillis() + 2_000L);
        config.WORLD_EVENTS_ENABLED = false;
        events.minute(System.currentTimeMillis());
        assertSame(WorldModifier.NONE, events.modifier());
        assertEquals("CANCELLED", store.rows.get(1).state());
    }

    @Test
    void aGmStopsOne() {
        WorldEvents events = calendar();
        assertTrue(events.stop("marketday").contains("not running"));
        events.start("marketday", null, System.currentTimeMillis());
        assertTrue(events.stop("marketday").contains("stopped"));
        assertEquals("CANCELLED", store.rows.getFirst().state());
        assertTrue(events.list(System.currentTimeMillis()).size() > 3);
    }

    @Test
    void anEffectPastItsTimeCountsForNothing() {
        WorldModifier modifier = WorldModifier.NONE
                .with(WorldModifier.Effect.marketDay("a", 2, 0.5d, 3.0d, System.currentTimeMillis() - 1L))
                .with(WorldModifier.Effect.avoid("b", 7, 30, Long.MAX_VALUE));
        assertEquals(6, modifier.stallCap(6));
        assertTrue(modifier.avoided(7, 29));
        assertFalse(modifier.avoided(7, 30));
        assertFalse(modifier.avoided(8, 1));
        assertSame(WorldModifier.NONE, modifier.without("a").without("b"));
    }

    @Test
    void theSchemaHoldsWhatTheStoreWrites() throws Exception {
        Path tables = Path.of("src/main/resources/db/tables");
        String up = Files.readString(tables.resolve("062-living-world-event.sql"));
        assertTrue(up.contains("UNIQUE KEY living_world_event_occurrence (event_id, occurrence)"));
        for (String column : List.of("event_id", "occurrence", "starts_at", "ends_at", "state", "channel", "site", "result")) {
            assertTrue(up.contains("    " + column + " "), column);
        }
        assertTrue(Files.readString(tables.resolve("062-living-world-event-down.sql")).contains("DROP TABLE IF EXISTS living_world_event;"));
        assertTrue(Files.readString(Path.of("src/main/resources/db/changelog-tables.xml")).contains("<changeSet id=\"71-living-world-event\""));
        EventCatalog catalog = EventCatalog.load(Path.of("scripts/config/population"));
        for (EventCatalog.Entry entry : catalog.entries()) {
            assertTrue(entry.id().length() <= 16, entry.id());
        }
        for (EventCatalog.Site site : catalog.sites()) {
            assertTrue(site.id().length() <= 16, site.id());
        }
    }
}
