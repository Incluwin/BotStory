package server.living.population.coliseum;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The weekly tournament's pure rules (docs/PVP_PLAN.md V5): who gets a seat, the draw, the week's start time and the
 * prizes. No clock, storage or game objects, so all of it is tested directly.
 */
final class TournamentRules {
    /** Trophies by how far a seat got: out in the first round, the semis, the final, or champion. */
    static final int[] TROPHIES = {5, 10, 20, 40};
    /** Shares of the entry pool, in percent: the finalist and the champion. The house keeps the rest. */
    static final int FINALIST_SHARE = 25;
    static final int CHAMPION_SHARE = 50;
    /** How far a seat got ({@link #TROPHIES}'s index); NONE before it has been knocked out or won. */
    static final int NONE = -1;
    static final int CHAMPION = 3;

    private TournamentRules() {
    }

    /** Someone who could take a seat: a character, its level, whether a player, and its rating in the bracket. */
    record Entrant(int characterId, String name, int level, boolean human, double rating) {
    }

    /**
     * The seats, best first (seed 1 first): every player who signed up (the best rated if more than {@code seats}),
     * then the best-rated citizens in the rest. The seeds go by rating over all seated, players and citizens alike.
     */
    static List<Entrant> seat(List<Entrant> players, List<Entrant> citizens, int seats) {
        Comparator<Entrant> best = Comparator.<Entrant>comparingDouble(e -> -e.rating())
                .thenComparingInt(Entrant::characterId);
        List<Entrant> seated = new ArrayList<>(players.stream().sorted(best).limit(seats).toList());
        citizens.stream().sorted(best).limit(Math.max(0, seats - seated.size())).forEach(seated::add);
        seated.sort(best);
        return seated;
    }

    /**
     * Where each seed starts in the draw for {@code seats} (2, 4 or 8): position i of the first round holds seed
     * {@code order[i]} (1 = the best); positions 0-1 meet, 2-3 meet, and so on. Seeds 1 and 2 can only meet in the final.
     */
    static int[] order(int seats) {
        return switch (seats) {
            case 2 -> new int[] {1, 2};
            case 4 -> new int[] {1, 4, 2, 3};
            case 8 -> new int[] {1, 8, 4, 5, 2, 7, 3, 6};
            default -> throw new IllegalArgumentException("seats must be 2, 4 or 8, not " + seats);
        };
    }

    /** Rounds in a draw of {@code seats}: 1, 2 or 3. */
    static int rounds(int seats) {
        return Integer.numberOfTrailingZeros(seats);
    }

    /**
     * How far a seat knocked out in round {@code round} (0 = the first) of a {@code rounds}-round draw got, as an index
     * into {@link #TROPHIES}: the final's loser is 2, a semi's 1, anything earlier 0.
     */
    static int reachedWhenOut(int round, int rounds) {
        return Math.max(0, 2 - (rounds - 1 - round));
    }

    /**
     * A drawn match (no side won two rounds) sends the better seed on; otherwise the winner. {@code seedA} and
     * {@code seedB} are 1-based, the better the lower. Returns side 0 or 1.
     */
    static int advancing(int winnerSide, int seedA, int seedB) {
        if (winnerSide == 0 || winnerSide == 1) {
            return winnerSide;
        }
        return seedA <= seedB ? 0 : 1;
    }

    /** Every seat's fee: {@code perLevel} mesos a level. */
    static long fee(int level, int perLevel) {
        return (long) perLevel * Math.max(1, level);
    }

    /** What a seat that got to {@code reached} wins: Trophies, and its share of the entry pool. */
    record Prize(int trophies, long mesos) {
    }

    static Prize prize(int reached, long pool) {
        if (reached < 0 || reached >= TROPHIES.length) {
            return new Prize(0, 0L);
        }
        long mesos = reached == CHAMPION ? pool * CHAMPION_SHARE / 100 : reached == 2 ? pool * FINALIST_SHARE / 100 : 0L;
        return new Prize(TROPHIES[reached], mesos);
    }

    /** "Out in the first round", "a semi-finalist", "the finalist", "the champion". */
    static String reachedWords(int reached) {
        return switch (reached) {
            case 0 -> "out in the first round";
            case 1 -> "a semi-finalist";
            case 2 -> "the finalist";
            case CHAMPION -> "the champion";
            default -> "seated";
        };
    }

    /** The name of round {@code round} (0 = the first) in a {@code rounds}-round draw. */
    static String roundName(int round, int rounds) {
        int fromEnd = rounds - 1 - round;
        return fromEnd == 0 ? "final" : fromEnd == 1 ? "semi-finals" : "quarter-finals";
    }

    // ---- the week -------------------------------------------------------------------------------------------------

    /** The Monday that starts the week of {@code at} (in its own zone), as days since 1970-01-01: the week's key. */
    static int weekOf(ZonedDateTime at) {
        return (int) at.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** The tournament's start in the week that begins on Monday {@code weekOf}: {@code day} at {@code hour}:00. */
    static ZonedDateTime start(int weekOf, DayOfWeek day, int hour, ZoneId zone) {
        LocalDate monday = LocalDate.ofEpochDay(weekOf);
        return monday.plusDays(day.getValue() - 1L).atTime(hour, 0).atZone(zone);
    }

    /** Sign-up for week {@code weekOf} runs from its Monday 00:00 until the start. */
    static boolean signUpOpen(ZonedDateTime now, int weekOf, DayOfWeek day, int hour) {
        ZonedDateTime start = start(weekOf, day, hour, now.getZone());
        ZonedDateTime opens = LocalDate.ofEpochDay(weekOf).atStartOfDay(now.getZone());
        return !now.isBefore(opens) && now.isBefore(start);
    }

    /** "Saturday 20:00": the day and hour as the window says them. */
    static String when(DayOfWeek day, int hour) {
        String name = day.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT) + " " + (hour < 10 ? "0" : "")
                + hour + ":00";
    }
}
