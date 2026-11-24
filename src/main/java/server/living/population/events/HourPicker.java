package server.living.population.events;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When a world event starts (docs/WORLD_SYSTEMS_PLAN.md N4.1): the hour of the week with the most humans online over the
 * last four weeks, out of the player samples ({@code admin_player_samples}), that does not run into anything taken (the
 * tournament's slot, the week's other events). Pure.
 */
public final class HourPicker {
    public static final int SLOTS = 7 * 24;
    /** Fewer sampled days than this (a new server, the admin port off) is too little to go by. */
    static final int MIN_DAYS = 7;
    private static final long HOUR_MS = 3_600_000L;

    /** Busy time another start may not run into, epoch ms. */
    public record Taken(long fromMs, long toMs) {
    }

    /** Humans online by hour of the week (Monday 00:00 is slot 0), summed over the weeks; {@code days} sampled. */
    public record Load(double[] humans, int days) {
        boolean enough() {
            if (days < MIN_DAYS) {
                return false;
            }
            for (double one : humans) {
                if (one > 0.0d) {
                    return true;
                }
            }
            return false;
        }

        public double at(int slot) {
            return humans[slot];
        }
    }

    private HourPicker() {
    }

    /** The load of hourly averages: epoch hour -> humans online in it (all channels), in {@code zone}'s hours. */
    public static Load load(Map<Long, Double> byEpochHour, ZoneId zone) {
        double[] humans = new double[SLOTS];
        Set<LocalDate> days = new HashSet<>();
        byEpochHour.forEach((hour, count) -> {
            ZonedDateTime at = Instant.ofEpochMilli(hour * HOUR_MS).atZone(zone);
            humans[slot(at.getDayOfWeek(), at.getHour())] += Math.max(0.0d, count);
            days.add(at.toLocalDate());
        });
        return new Load(humans, days.size());
    }

    static int slot(DayOfWeek day, int hour) {
        return (day.getValue() - 1) * 24 + hour;
    }

    /** Slot {@code slot} of the week whose Monday is {@code weekOf} (days since 1970-01-01), epoch ms. */
    static long atMs(int weekOf, int slot, ZoneId zone) {
        return LocalDate.ofEpochDay(weekOf).plusDays(slot / 24).atTime(slot % 24, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * The start in week {@code weekOf} for an event of {@code durationMs} among {@code allowed} slots (null: every one),
     * not before {@code notBeforeMs}: the busiest free slot, ties to the one nearest {@code fallback}. With too little
     * data the free slot nearest {@code fallback}. -1 when none is free.
     */
    public static long pick(Load load, int weekOf, long durationMs, Set<Integer> allowed, int fallback, List<Taken> taken,
                            long notBeforeMs, ZoneId zone) {
        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (allowed != null && !allowed.contains(slot)) {
                continue;
            }
            long start = atMs(weekOf, slot, zone);
            boolean clash = start < notBeforeMs;
            for (Taken one : taken) {
                clash |= start < one.toMs() && one.fromMs() < start + durationMs;
            }
            if (!clash) {
                free.add(slot);
            }
        }
        boolean enough = load != null && load.enough();
        Comparator<Integer> order = Comparator.comparingInt(slot -> Math.abs(slot - fallback));
        if (enough) {
            order = Comparator.comparingDouble((Integer slot) -> -load.at(slot)).thenComparing(order);
        }
        return free.stream().min(order).map(slot -> atMs(weekOf, slot, zone)).orElse(-1L);
    }
}
