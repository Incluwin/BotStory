package server.living.population;

import server.living.standing.Standing;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * When citizens ask a human along to a run (docs/WORLD_SYSTEMS_PLAN.md N3.6): a small bandit over the context of an
 * invitation, the hour (four bands, UTC), what the human is doing (training on a field, or in a town or the FM), their
 * standing rung with the citizen asking, and how long since they last ran this quest with citizens. Each context keeps
 * how often it was asked and how often the answer was yes; a context is asked while its optimistic accept rate, the
 * mean plus the UCB bonus {@code explore x sqrt(ln(1 + N) / (1 + n))} (the arithmetic of CitizenSpots, copied, not
 * shared: another wave extracts that class), reaches {@code floor}. A context never asked is always asked, and one
 * where people never say yes is tried less and less until it stops. In memory since boot: no schema change (N3).
 */
final class InviteBandit {
    /** What the human is doing as the invitation would go out. */
    enum Activity { FIELD, TOWN }

    /** How long since the human last ran this quest with citizens: never seen, within the hour, or longer. */
    enum SinceRan { NEVER, RECENT, LONG }

    static final long RECENT_MS = 60 * 60_000L;

    /** One invitation's context. */
    record Context(int hourBand, Activity activity, Standing.Rung rung, SinceRan sinceRan) {
        String key() {
            return hourBand + "|" + activity + "|" + rung + "|" + sinceRan;
        }

        static Context of(long nowMs, boolean town, Standing.Rung rung, Long lastRanMs) {
            int hour = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).getHour();
            SinceRan since = lastRanMs == null ? SinceRan.NEVER
                    : nowMs - lastRanMs < RECENT_MS ? SinceRan.RECENT : SinceRan.LONG;
            return new Context(hour / 6, town ? Activity.TOWN : Activity.FIELD, rung, since);
        }
    }

    /** {asked, yes} by context key. */
    private final Map<String, int[]> stats = new HashMap<>();
    private int total;

    /** True when {@code context} is worth asking now (see the class note). */
    synchronized boolean worthAsking(Context context, double explore, double floor) {
        int[] counts = stats.get(context.key());
        if (counts == null || counts[0] == 0) {
            return true;
        }
        return optimistic(counts[0], counts[1], total, explore) >= floor;
    }

    /** The answer to an invitation of {@code context}: yes, or no / no answer. */
    synchronized void answered(Context context, boolean yes) {
        int[] counts = stats.computeIfAbsent(context.key(), key -> new int[2]);
        counts[0]++;
        if (yes) {
            counts[1]++;
        }
        total++;
    }

    /** Mean accept rate plus the UCB bonus, as CitizenSpots scores a field: explore x sqrt(ln(1 + N) / (1 + n)). */
    static double optimistic(int asked, int yes, int total, double explore) {
        double mean = asked == 0 ? 0.0d : yes / (double) asked;
        return mean + explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + asked));
    }

    /** {asked, yes} for {@code context}, for status lines and tests. */
    synchronized int[] counts(Context context) {
        int[] counts = stats.get(context.key());
        return counts == null ? new int[2] : counts.clone();
    }

    /** One line for the status: contexts and answers since boot. */
    synchronized String status() {
        int yes = stats.values().stream().mapToInt(counts -> counts[1]).sum();
        return "invites " + total + " asked, " + yes + " yes, " + stats.size() + " contexts";
    }
}
