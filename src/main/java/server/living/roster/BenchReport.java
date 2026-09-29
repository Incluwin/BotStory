package server.living.roster;

import constants.game.ExpTable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The call-back report (docs/BOT_MANAGEMENT_PLAN.md flow 1 step 5, P5 "call one back and read its report"): what a
 * benched or away companion did for its owner, tallied as the ledger credits it and its live slices harvest and end,
 * and said once, in one short whisper, when its owner calls it back ("farmed 143 stolen fences and 12k meso at ant
 * tunnel i. died once."). Taking the report forgets the tally, so a second call tells nothing new twice; a seat taken
 * without a word (a login's restore) takes it too. Only what reached the haul counts as farmed (never a trainee's own
 * pickups), and nothing here moves an item or a meso. Kept in memory: a restart starts every tally afresh.
 * Thread-safe: the ledger, the harvest and the call run on companion-ops, a stall's sales may come from elsewhere.
 */
final class BenchReport {
    /** One companion's time away so far. */
    private static final class Tally {
        long exp;
        /** The level its first EXP credit was at (0: none yet), for "went up a level". */
        int startLevel;
        long meso;
        long sold;
        int deaths;
        /** The field it last worked at (0: none), for "at ant tunnel i". */
        int campMapId;
        final Map<Integer, Integer> items = new LinkedHashMap<>();

        boolean worthTelling(int levelNow) {
            return meso > 0L || sold > 0L || deaths > 0 || !items.isEmpty() || expPhrase(levelNow) != null;
        }

        /** "went up 2 levels", "+14% exp", or null below one percent of a level. */
        String expPhrase(int levelNow) {
            if (exp <= 0L || startLevel <= 0) {
                return null;
            }
            int levels = levelNow - startLevel;
            if (levels >= 1) {
                return levels == 1 ? "went up a level" : "went up " + levels + " levels";
            }
            long needed = Math.max(1L, ExpTable.getExpNeededForLevel(startLevel));
            long percent = exp * 100L / needed;
            return percent >= 1L ? "+" + percent + "% exp" : null;
        }
    }

    private final Map<String, Tally> tallies = new ConcurrentHashMap<>();

    private static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }

    private void add(int ownerId, String personaId, java.util.function.Consumer<Tally> change) {
        if (personaId == null) {
            return;
        }
        tallies.compute(key(ownerId, personaId), (key, tally) -> {
            Tally out = tally == null ? new Tally() : tally;
            change.accept(out);
            return out;
        });
    }

    /** EXP credited at {@code level} (banked by the ledger, or gained on a live slice) at the field {@code campMapId}. */
    void exp(int ownerId, String personaId, long exp, int level, int campMapId) {
        if (exp <= 0L) {
            return;
        }
        add(ownerId, personaId, tally -> {
            tally.exp += exp;
            if (level > 0 && (tally.startLevel <= 0 || level < tally.startLevel)) {
                tally.startLevel = level;
            }
            where(tally, campMapId);
        });
    }

    /** Meso and an item stack that reached the haul from the field {@code campMapId} (itemId 0: meso alone). */
    void haul(int ownerId, String personaId, int campMapId, long meso, int itemId, int quantity) {
        if (meso <= 0L && (itemId <= 0 || quantity <= 0)) {
            return;
        }
        add(ownerId, personaId, tally -> {
            tally.meso += Math.max(0L, meso);
            if (itemId > 0 && quantity > 0) {
                tally.items.merge(itemId, quantity, Integer::sum);
            }
            where(tally, campMapId);
        });
    }

    /** Meso the haul sold for (the ledger's SELL and STALL, or a live stall's sales). */
    void sold(int ownerId, String personaId, long meso) {
        if (meso > 0L) {
            add(ownerId, personaId, tally -> tally.sold += meso);
        }
    }

    /** Deaths on a live slice at {@code campMapId}. */
    void deaths(int ownerId, String personaId, int deaths, int campMapId) {
        if (deaths > 0) {
            add(ownerId, personaId, tally -> {
                tally.deaths += deaths;
                where(tally, campMapId);
            });
        }
    }

    private static void where(Tally tally, int campMapId) {
        if (campMapId > 0) {
            tally.campMapId = campMapId;
        }
    }

    /**
     * The report of the companion's time away, once: the line to whisper (null when nothing worth telling happened),
     * and the tally is forgotten either way. {@code levelNow} is its level now (0: unknown).
     */
    String take(int ownerId, String personaId, int levelNow, IntFunction<String> placeNames, IntFunction<String> itemNames) {
        Tally tally = personaId == null ? null : tallies.remove(key(ownerId, personaId));
        if (tally == null || !tally.worthTelling(levelNow)) {
            return null;
        }
        String at = tally.campMapId > 0 ? " at " + lower(placeNames.apply(tally.campMapId)) : "";
        StringBuilder line = new StringBuilder();
        String farmed = farmed(tally, itemNames);
        String exp = tally.expPhrase(levelNow);
        if (farmed != null) {
            line.append("farmed ").append(farmed).append(at).append('.');
            if (exp != null) {
                line.append(' ').append(exp).append(" too.");
            }
        } else if (exp != null) {
            line.append("trained").append(at).append(", ").append(exp).append('.');
        }
        if (tally.sold > 0L) {
            line.append(line.isEmpty() ? "" : " ").append("sold the haul for ").append(meso(tally.sold)).append(" meso.");
        }
        if (tally.deaths > 0) {
            if (line.isEmpty()) {
                line.append("rough hunt").append(at).append('.');
            }
            line.append(" died ").append(tally.deaths == 1 ? "once" : tally.deaths == 2 ? "twice" : tally.deaths + " times")
                    .append('.');
        }
        return line.toString();
    }

    /** "143 stolen fences and 12k meso", "143 stolen fences, 20 other items and 12k meso", "12k meso"; null for nothing. */
    private static String farmed(Tally tally, IntFunction<String> itemNames) {
        int top = 0;
        int topCount = 0;
        int others = 0;
        for (Map.Entry<Integer, Integer> item : tally.items.entrySet()) {
            if (item.getValue() > topCount) {
                others += topCount;
                top = item.getKey();
                topCount = item.getValue();
            } else {
                others += item.getValue();
            }
        }
        String items = topCount <= 0 ? "" : topCount + " " + plural(lower(itemNames.apply(top)), topCount)
                + (others > 0 ? (tally.meso > 0L ? ", " : " and ") + others + (others == 1 ? " other item" : " other items") : "");
        String meso = tally.meso > 0L ? meso(tally.meso) + " meso" : "";
        if (items.isEmpty() && meso.isEmpty()) {
            return null;
        }
        return items.isEmpty() ? meso : meso.isEmpty() ? items : items + " and " + meso;
    }

    /** "stolen fences", "snail shells", "leathers"; one stays as it is. */
    static String plural(String name, int count) {
        if (count == 1 || name.isEmpty() || name.endsWith("s")) {
            return name;
        }
        if (name.endsWith("y") && name.length() > 1 && "aeiou".indexOf(name.charAt(name.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }

    /** "850", "3.1k", "12k". */
    static String meso(long value) {
        if (value < 1_000L) {
            return String.valueOf(value);
        }
        return value < 10_000L ? String.format(Locale.ROOT, "%.1fk", value / 1_000.0d) : Math.round(value / 1_000.0d) + "k";
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
