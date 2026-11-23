package server.living.roster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A companion's traits (docs/WORLD_SYSTEMS_PLAN.md N9): what it lived through with its owner, read from its party-quest
 * and boss runs (companion_performance), its bench time and its stall and sell earnings. Pure: {@link #of} gives at
 * most {@link #MAX} traits from a {@link History} and the traits it held before, with a higher bar to gain a trait
 * than to keep it, so a trait does not come and go from one run to the next. Kept in living_bond.flags as
 * {@link Trait#flag()} ("trait:wary:zakum:died").
 */
public final class Traits {
    /** At most this many traits per companion. */
    public static final int MAX = 3;
    /** A run's HP fell under this share of its maximum: a close call (companion_performance.close_calls). */
    public static final double CLOSE_CALL = 0.20d;

    /** WARY: a death, or this many close calls, in the last {@link #WARY_WINDOW} runs there. */
    static final int WARY_CLOSE_CALLS = 2;
    static final int WARY_WINDOW = 5;
    /** WARY and SHAKEN go after this many clean runs in a row (there, for WARY). */
    static final int CLEAN_STREAK_TO_LOSE = 3;
    /** VETERAN: this many clears there and one within {@link #VETERAN_FRESH_DAYS}; kept from {@link #VETERAN_KEEP_CLEARS}. */
    static final int VETERAN_CLEARS = 10;
    static final int VETERAN_KEEP_CLEARS = 8;
    static final int VETERAN_FRESH_DAYS = 30;
    /** VETERAN goes once it has not run there in this many days. */
    static final int VETERAN_STALE_DAYS = 90;
    /** STEADY: this many runs in a row without a death; kept while the last this-many runs hold one death at most. */
    static final int STEADY_RUNS = 20;
    static final int STEADY_KEEP_DEATHS = 1;
    /** SHAKEN: this many deaths over the last {@link #SHAKEN_DAYS} days. */
    static final int SHAKEN_DEATHS = 3;
    static final int SHAKEN_DAYS = 7;
    /** HOMEBODY: benched this long ("most of a month"); kept until it has done this many runs in a week. */
    static final int HOMEBODY_DAYS = 18;
    static final int HOMEBODY_RUNS_TO_LOSE = 3;
    static final int HOMEBODY_RUN_DAYS = 7;
    /** TRADER: bench sales over the last this-many days, against the bar; kept down to half the bar. */
    static final int TRADER_DAYS = 30;

    static final long DAY_MS = 86_400_000L;

    public enum Kind {
        SHAKEN, WARY, VETERAN, STEADY, TRADER, HOMEBODY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) {
                    return kind;
                }
            }
            return null;
        }

        /** True for a trait of one quest or boss (WARY, VETERAN). */
        public boolean ofActivity() {
            return this == WARY || this == VETERAN;
        }
    }

    /** Why a companion is WARY of a place: it went down there, or it had close calls there. */
    public static final String DIED = "died";
    public static final String CLOSE = "close";

    /** One trait: its kind, its quest or boss for WARY and VETERAN (else null), and WARY's cause (else null). */
    public record Trait(Kind kind, String activity, String cause) {
        static final String PREFIX = "trait:";

        public static Trait of(Kind kind) {
            return new Trait(kind, null, null);
        }

        /** Its living_bond flag: "trait:steady", "trait:veteran:kpq", "trait:wary:zakum:died". */
        public String flag() {
            return PREFIX + kind.key() + (activity == null ? "" : ":" + activity) + (cause == null ? "" : ":" + cause);
        }

        /** The trait a flag names, or null when it is no trait's. */
        public static Trait parse(String flag) {
            if (flag == null || !flag.startsWith(PREFIX)) {
                return null;
            }
            String[] parts = flag.substring(PREFIX.length()).split(":", -1);
            Kind kind = Kind.fromKey(parts[0]);
            if (kind == null || kind.ofActivity() != parts.length >= 2 || parts.length > 3
                    || parts.length == 3 && kind != Kind.WARY) {
                return null;
            }
            String activity = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : null;
            String cause = parts.length == 3 && (DIED.equals(parts[2]) || CLOSE.equals(parts[2])) ? parts[2] : null;
            if (kind.ofActivity() && activity == null || parts.length == 3 && cause == null) {
                return null;
            }
            return new Trait(kind, activity, kind == Kind.WARY && cause == null ? DIED : cause);
        }

        /** The same trait whatever its cause: a WARY of Zakum is one trait whether it came from a death or not. */
        String identity() {
            return kind.key() + (activity == null ? "" : ":" + activity);
        }
    }

    /** One run of the companion's: its quest or boss, cleared or not, its deaths and close calls, when it was recorded. */
    public record Run(String activity, boolean cleared, int deaths, int closeCalls, long atMs) {
        boolean clean() {
            return deaths <= 0 && closeCalls <= 0;
        }
    }

    /**
     * What {@link #of} reads: the companion's runs with its owner, newest first; when its current bench spell began
     * (0: it is not benched); what its bench sales took over the last {@link #TRADER_DAYS} days; the TRADER bar; now.
     */
    public record History(List<Run> runs, long benchedSinceMs, long soldMeso, long traderMeso, long nowMs) {
    }

    private Traits() {
    }

    /** The traits held from {@code flags} (living_bond.flags), in their order; flags that are no trait's are skipped. */
    public static List<Trait> held(Collection<String> flags) {
        List<Trait> out = new ArrayList<>();
        for (String flag : flags == null ? List.<String>of() : flags) {
            Trait trait = Trait.parse(flag);
            if (trait != null) {
                out.add(trait);
            }
        }
        return out;
    }

    /**
     * The companion's traits now, at most {@link #MAX}: the ones it {@code held} that still pass their keep bar first
     * (so a new trait never pushes out an old one), then the ones it newly passes the gain bar for, each group in
     * {@link Kind} order (SHAKEN, WARY, VETERAN, STEADY, TRADER, HOMEBODY). SHAKEN and STEADY never come together.
     */
    public static List<Trait> of(History history, Collection<Trait> held) {
        Map<String, Trait> before = new LinkedHashMap<>();
        for (Trait trait : held == null ? List.<Trait>of() : held) {
            before.put(trait.identity(), trait);
        }
        List<Trait> kept = new ArrayList<>();
        List<Trait> gained = new ArrayList<>();
        for (Trait candidate : candidates(history, before.values())) {
            Trait was = before.get(candidate.identity());
            if (was != null) {
                if (keeps(history, candidate)) {
                    kept.add(candidate.kind() == Kind.WARY ? was : candidate); // a WARY keeps the cause it came from
                }
            } else if (gains(history, candidate)) {
                gained.add(candidate);
            }
        }
        Comparator<Trait> order = Comparator.comparingInt(trait -> trait.kind().ordinal());
        kept.sort(order);
        gained.sort(order);
        List<Trait> out = new ArrayList<>();
        for (Trait trait : concat(kept, gained)) {
            if (out.size() >= MAX) {
                break;
            }
            if (trait.kind() == Kind.STEADY && out.stream().anyMatch(t -> t.kind() == Kind.SHAKEN)
                    || trait.kind() == Kind.SHAKEN && out.stream().anyMatch(t -> t.kind() == Kind.STEADY)) {
                continue;
            }
            out.add(trait);
        }
        return out;
    }

    /** Every trait worth testing: one of each kind, a WARY and a VETERAN for each activity run or held. */
    private static List<Trait> candidates(History history, Collection<Trait> held) {
        Set<String> activities = new LinkedHashSet<>();
        for (Run run : history.runs()) { // newest first: the latest place first among equals
            activities.add(run.activity());
        }
        for (Trait trait : held) {
            if (trait.activity() != null) {
                activities.add(trait.activity());
            }
        }
        List<Trait> out = new ArrayList<>();
        out.add(Trait.of(Kind.SHAKEN));
        for (String activity : activities) {
            out.add(new Trait(Kind.WARY, activity, waryCause(history, activity)));
        }
        for (String activity : activities) {
            out.add(new Trait(Kind.VETERAN, activity, null));
        }
        out.add(Trait.of(Kind.STEADY));
        out.add(Trait.of(Kind.TRADER));
        out.add(Trait.of(Kind.HOMEBODY));
        return out;
    }

    static boolean gains(History history, Trait trait) {
        long now = history.nowMs();
        return switch (trait.kind()) {
            case WARY -> {
                List<Run> there = runsAt(history, trait.activity());
                List<Run> window = there.subList(0, Math.min(WARY_WINDOW, there.size()));
                int closeCalls = window.stream().mapToInt(run -> Math.max(0, run.closeCalls())).sum();
                boolean died = window.stream().anyMatch(run -> run.deaths() > 0);
                yield (died || closeCalls >= WARY_CLOSE_CALLS) && cleanStreak(there) < CLEAN_STREAK_TO_LOSE;
            }
            case VETERAN -> {
                List<Run> there = runsAt(history, trait.activity());
                long clears = there.stream().filter(Run::cleared).count();
                boolean fresh = there.stream().anyMatch(run -> run.cleared() && now - run.atMs() <= VETERAN_FRESH_DAYS * DAY_MS);
                yield clears >= VETERAN_CLEARS && fresh;
            }
            case STEADY -> history.runs().size() >= STEADY_RUNS
                    && history.runs().subList(0, STEADY_RUNS).stream().allMatch(run -> run.deaths() <= 0);
            case SHAKEN -> deathsSince(history, now - SHAKEN_DAYS * DAY_MS) >= SHAKEN_DEATHS
                    && deathlessStreak(history) < CLEAN_STREAK_TO_LOSE;
            case HOMEBODY -> history.benchedSinceMs() > 0L && now - history.benchedSinceMs() >= HOMEBODY_DAYS * DAY_MS;
            case TRADER -> history.traderMeso() > 0L && history.soldMeso() >= history.traderMeso();
        };
    }

    static boolean keeps(History history, Trait trait) {
        long now = history.nowMs();
        return switch (trait.kind()) {
            case WARY -> cleanStreak(runsAt(history, trait.activity())) < CLEAN_STREAK_TO_LOSE;
            case VETERAN -> {
                List<Run> there = runsAt(history, trait.activity());
                yield !there.isEmpty() && there.stream().filter(Run::cleared).count() >= VETERAN_KEEP_CLEARS
                        && now - there.getFirst().atMs() <= VETERAN_STALE_DAYS * DAY_MS;
            }
            case STEADY -> history.runs().subList(0, Math.min(STEADY_RUNS, history.runs().size())).stream()
                    .mapToInt(run -> Math.max(0, run.deaths())).sum() <= STEADY_KEEP_DEATHS;
            case SHAKEN -> deathlessStreak(history) < CLEAN_STREAK_TO_LOSE;
            case HOMEBODY -> history.benchedSinceMs() > 0L || history.runs().stream()
                    .filter(run -> now - run.atMs() <= HOMEBODY_RUN_DAYS * DAY_MS).count() < HOMEBODY_RUNS_TO_LOSE;
            case TRADER -> history.traderMeso() > 0L && history.soldMeso() * 2L >= history.traderMeso();
        };
    }

    /** {@link #DIED} when a run in WARY's window there had a death, else {@link #CLOSE}. */
    private static String waryCause(History history, String activity) {
        List<Run> there = runsAt(history, activity);
        return there.subList(0, Math.min(WARY_WINDOW, there.size())).stream().anyMatch(run -> run.deaths() > 0)
                ? DIED : CLOSE;
    }

    /** The runs of one activity, newest first. */
    private static List<Run> runsAt(History history, String activity) {
        return history.runs().stream().filter(run -> run.activity().equals(activity)).toList();
    }

    /** How many runs in a row, newest first, were clean (no death, no close call). */
    private static int cleanStreak(List<Run> runs) {
        int streak = 0;
        for (Run run : runs) {
            if (!run.clean()) {
                break;
            }
            streak++;
        }
        return streak;
    }

    /** How many of its latest runs in a row, wherever they were, had no death (SHAKEN's clean runs). */
    private static int deathlessStreak(History history) {
        int streak = 0;
        for (Run run : history.runs()) {
            if (run.deaths() > 0) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private static int deathsSince(History history, long sinceMs) {
        return history.runs().stream().filter(run -> run.atMs() >= sinceMs).mapToInt(run -> Math.max(0, run.deaths())).sum();
    }

    private static List<Trait> concat(List<Trait> a, List<Trait> b) {
        List<Trait> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
