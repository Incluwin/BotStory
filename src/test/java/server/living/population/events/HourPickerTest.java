package server.living.population.events;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The hour of a world event (N4.1, live check E1): the busiest of the last four weeks, around what is taken. */
class HourPickerTest {
    private static final int WEEK = (int) LocalDate.of(2026, 11, 23).toEpochDay(); // a Monday
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final int SUNDAY_20 = HourPicker.slot(DayOfWeek.SUNDAY, 20);
    private static final long HOUR = 3_600_000L;

    /** Four weeks of samples before WEEK, an hour each, {@code humans} at the given day and hour, else none. */
    private static Map<Long, Double> samples(ZoneId zone, Map<Integer, Double> humans) {
        Map<Long, Double> out = new HashMap<>();
        for (int week = 1; week <= 4; week++) {
            for (int slot = 0; slot < HourPicker.SLOTS; slot++) {
                out.put(HourPicker.atMs(WEEK - 7 * week, slot, zone) / HOUR, humans.getOrDefault(slot, 0.0d));
            }
        }
        return out;
    }

    private static long pick(HourPicker.Load load, long minutes, List<HourPicker.Taken> taken, long notBefore, Set<Integer> allowed) {
        return HourPicker.pick(load, WEEK, minutes * 60_000L, allowed, SUNDAY_20, taken, notBefore, UTC);
    }

    @Test
    void theBusiestHourOfTheLastFourWeeksIsPicked() {
        int tuesday19 = HourPicker.slot(DayOfWeek.TUESDAY, 19);
        HourPicker.Load load = HourPicker.load(samples(UTC, Map.of(tuesday19, 3.0d, SUNDAY_20, 2.0d)), UTC);
        assertEquals(28, load.days());
        assertEquals(12.0d, load.at(tuesday19), 1e-9);
        assertEquals(HourPicker.atMs(WEEK, tuesday19, UTC), pick(load, 20, List.of(), 0L, null));
    }

    @Test
    void anHourSampledOnceIsNotBusierThanOneSampledEveryWeek() {
        int tuesday19 = HourPicker.slot(DayOfWeek.TUESDAY, 19);
        Map<Long, Double> samples = samples(UTC, Map.of(tuesday19, 1.0d));
        samples.put(HourPicker.atMs(WEEK - 7, 5, UTC) / HOUR, 2.0d);
        assertEquals(HourPicker.atMs(WEEK, tuesday19, UTC), pick(HourPicker.load(samples, UTC), 20, List.of(), 0L, null));
    }

    @Test
    void theTournamentsSlotAndTakenHoursAreSkipped() {
        int saturday20 = HourPicker.slot(DayOfWeek.SATURDAY, 20);
        int saturday19 = saturday20 - 1;
        HourPicker.Load load = HourPicker.load(samples(UTC, Map.of(saturday20, 9.0d, saturday19, 8.0d, SUNDAY_20, 2.0d)), UTC);
        long start = HourPicker.atMs(WEEK, saturday20, UTC);
        List<HourPicker.Taken> tournament = List.of(new HourPicker.Taken(start, start + 2 * HOUR));
        assertEquals(HourPicker.atMs(WEEK, saturday19, UTC), pick(load, 60, tournament, 0L, null), "it ends as the tournament starts");
        assertEquals(HourPicker.atMs(WEEK, SUNDAY_20, UTC), pick(load, 180, tournament, 0L, null), "three hours would run into it");
    }

    @Test
    void tooLittleDataFallsBack() {
        Map<Long, Double> threeDays = new HashMap<>();
        for (int hour = 0; hour < 72; hour++) {
            threeDays.put(HourPicker.atMs(WEEK - 7, hour, UTC) / HOUR, hour == 5 ? 4.0d : 0.0d);
        }
        assertEquals(HourPicker.atMs(WEEK, SUNDAY_20, UTC), pick(HourPicker.load(threeDays, UTC), 20, List.of(), 0L, null));
        assertEquals(HourPicker.atMs(WEEK, SUNDAY_20, UTC),
                pick(HourPicker.load(samples(UTC, Map.of()), UTC), 20, List.of(), 0L, null), "nobody was ever on");
        long taken = HourPicker.atMs(WEEK, SUNDAY_20, UTC);
        assertEquals(taken - HOUR, pick(HourPicker.load(Map.of(), UTC), 20,
                List.of(new HourPicker.Taken(taken, taken + HOUR)), 0L, null), "the free slot next to it");
    }

    @Test
    void neverBeforeNowAndNothingWhenTheWeekIsOver() {
        int tuesday19 = HourPicker.slot(DayOfWeek.TUESDAY, 19);
        HourPicker.Load load = HourPicker.load(samples(UTC, Map.of(tuesday19, 3.0d, SUNDAY_20, 2.0d)), UTC);
        assertEquals(HourPicker.atMs(WEEK, SUNDAY_20, UTC), pick(load, 20, List.of(), HourPicker.atMs(WEEK, tuesday19 + 1, UTC), null));
        assertEquals(-1L, pick(load, 20, List.of(), HourPicker.atMs(WEEK + 7, 0, UTC), null));
    }

    @Test
    void hoursAreTheServersZone() {
        ZoneId zone = ZoneId.of("America/New_York");
        int friday21 = HourPicker.slot(DayOfWeek.FRIDAY, 21);
        HourPicker.Load load = HourPicker.load(samples(zone, Map.of(friday21, 2.0d)), zone);
        long at = HourPicker.pick(load, WEEK, 20 * 60_000L, null, SUNDAY_20, List.of(), 0L, zone);
        ZonedDateTime local = java.time.Instant.ofEpochMilli(at).atZone(zone);
        assertEquals(DayOfWeek.FRIDAY, local.getDayOfWeek());
        assertEquals(21, local.getHour());
        assertNotEquals(21, java.time.Instant.ofEpochMilli(at).atZone(UTC).getHour());
    }
}
