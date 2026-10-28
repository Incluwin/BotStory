package server.living.crew;

import server.living.crew.BanterCatalog.Stat;
import server.living.crew.CrewBanter.Speaker;
import server.living.crew.CrewTieStore.RunRow;
import server.living.population.records.ClearLog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The numbers rivals tease each other with (docs/BOT_CONCEPT_PLAN.md F6.4), each from a real record and never made up:
 * levels as they stand, each one's clears in a quest or boss (companion_performance, 3+ runs each), deaths and damage
 * share in the newest run both were in (within {@link #RECENT_RUN_MS}), and each one's fastest clear of a quest with
 * this owner (living_clear). A fact exists only when the caller is ahead; otherwise there is nothing to tease with
 * and the exchange is not said. On companion-ops (the run rows are a store read).
 */
final class RivalFacts {
    /** A record counts once a companion ran the quest this often (as LineupPlan.RECORD_RUNS ranks by it). */
    static final int RECORD_RUNS = 3;
    /** "That run" must be this recent; an older one is no longer worth bringing up. */
    static final long RECENT_RUN_MS = 3L * 60 * 60 * 1000;
    /** The two rows of one run were written this close together (CompanionRunRecorder writes them in one loop). */
    static final long SAME_RUN_MS = 10_000L;
    /** Rows read per pair: enough to find their newest shared run. */
    static final int ROWS = 40;
    /** A damage share or a best time must lead by this much to be worth saying. */
    static final double MIN_SHARE_LEAD = 0.02d;
    static final double MIN_TIME_LEAD = 0.05d;

    /** The slots a stat fills: {act} (null for levels), {lead} and {trail}, {gap} (null where the stat has none). */
    record Fact(String act, String lead, String trail, String gap) { }

    /** Where the records come from; tests bring their own. */
    interface Sources {
        /** Every companion_performance activity ("kpq", "zakum"...). */
        List<String> activities();

        /** The companion's {runs, clears} in the activity. */
        int[] record(String activity, int companionId);

        /** The two companions' newest rows under this owner, newest first; throws when the read fails. */
        List<RunRow> runs(int ownerId, int first, int second, int limit);

        /** Quest script -> the companion's fastest clear with this owner (ms). */
        Map<String, Long> bests(int ownerId, int companionId);

        /** What players call an activity ("kpq", "zakum"). */
        String activityLabel(String activity);

        /** What players call a quest's script ("kpq", "zakum"). */
        String questLabel(String script);
    }

    private final Sources sources;

    RivalFacts(Sources sources) {
        this.sources = sources;
    }

    /** The fact for {@code stat} with {@code caller} ahead of {@code answerer}, or null when there is none. */
    Fact fact(Stat stat, int ownerId, Speaker caller, Speaker answerer, long nowMs) {
        return switch (stat) {
            case LEVEL -> level(caller, answerer);
            case RECORD -> record(caller, answerer);
            case DEATHS, DAMAGE -> lastRun(stat, ownerId, caller, answerer, nowMs);
            case BEST_TIME -> bestTime(ownerId, caller, answerer);
        };
    }

    private static Fact level(Speaker caller, Speaker answerer) {
        int gap = caller.level() - answerer.level();
        return gap <= 0 ? null : new Fact(null, String.valueOf(caller.level()), String.valueOf(answerer.level()),
                String.valueOf(gap));
    }

    /** The activity where the caller's clear rate leads the answerer's most, both with RECORD_RUNS runs. */
    private Fact record(Speaker caller, Speaker answerer) {
        Fact best = null;
        double bestLead = 0d;
        for (String activity : sources.activities()) {
            int[] mine = sources.record(activity, caller.characterId());
            int[] theirs = sources.record(activity, answerer.characterId());
            if (mine == null || theirs == null || mine[0] < RECORD_RUNS || theirs[0] < RECORD_RUNS) {
                continue;
            }
            double lead = (double) mine[1] / mine[0] - (double) theirs[1] / theirs[0];
            if (lead > bestLead && mine[1] > theirs[1]) {
                bestLead = lead;
                best = new Fact(sources.activityLabel(activity), mine[1] + " of " + mine[0],
                        theirs[1] + " of " + theirs[0], null);
            }
        }
        return best;
    }

    /** Deaths or damage share in the newest recent run both were in: the caller died less, or did more of the damage. */
    private Fact lastRun(Stat stat, int ownerId, Speaker caller, Speaker answerer, long nowMs) {
        List<RunRow> rows;
        try {
            rows = sources.runs(ownerId, caller.characterId(), answerer.characterId(), ROWS);
        } catch (RuntimeException failure) {
            return null; // nothing read is nothing to say
        }
        for (RunRow mine : rows) {
            if (mine.companionId() != caller.characterId() || nowMs - mine.recordedAtMs() > RECENT_RUN_MS) {
                continue;
            }
            for (RunRow theirs : rows) {
                if (theirs.companionId() != answerer.characterId() || !theirs.activity().equals(mine.activity())
                        || Math.abs(theirs.recordedAtMs() - mine.recordedAtMs()) > SAME_RUN_MS
                        || theirs.durationS() != mine.durationS()) {
                    continue;
                }
                String act = sources.activityLabel(mine.activity());
                if (stat == Stat.DEATHS) {
                    return mine.deaths() < theirs.deaths() ? new Fact(act, String.valueOf(mine.deaths()),
                            String.valueOf(theirs.deaths()), String.valueOf(theirs.deaths() - mine.deaths())) : null;
                }
                Double ours = mine.damageShare();
                Double other = theirs.damageShare();
                return ours != null && other != null && ours - other >= MIN_SHARE_LEAD
                        ? new Fact(act, percent(ours), percent(other), null) : null;
            }
            return null; // the caller's newest recent run had no answerer in it: that is "that run"
        }
        return null;
    }

    /** The quest where the caller's fastest clear with this owner beats the answerer's by the most. */
    private Fact bestTime(int ownerId, Speaker caller, Speaker answerer) {
        Map<String, Long> mine = sources.bests(ownerId, caller.characterId());
        Map<String, Long> theirs = sources.bests(ownerId, answerer.characterId());
        Fact best = null;
        double bestLead = MIN_TIME_LEAD;
        for (Map.Entry<String, Long> quest : mine.entrySet()) {
            Long other = theirs.get(quest.getKey());
            if (other == null || other <= 0L || quest.getValue() <= 0L) {
                continue;
            }
            double lead = 1d - (double) quest.getValue() / other;
            if (lead >= bestLead) {
                bestLead = lead;
                best = new Fact(sources.questLabel(quest.getKey()), ClearLog.time(quest.getValue()), ClearLog.time(other),
                        null);
            }
        }
        return best;
    }

    static String percent(double share) {
        return Math.round(share * 100d) + "%";
    }

    /** What players call a quest from its label and aliases: the first alias, else the label in lower case. */
    static String spoken(String label, List<String> aliases) {
        if (aliases != null && !aliases.isEmpty() && !aliases.getFirst().isBlank()) {
            return aliases.getFirst().toLowerCase(Locale.ROOT);
        }
        return label == null ? "" : label.toLowerCase(Locale.ROOT);
    }
}
