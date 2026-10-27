package server.living.population;

import config.PopulationConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * What a citizen remembers of one human (docs/CITIZEN_PERSON_MEMORY_PLAN.md): how it feels about them, when they met,
 * and a few things that happened between them, each with when and where. Weights fade with age the way people forget:
 * an episode's weight halves every {@code MEMORY_HALF_LIFE_DAYS} and it is gone under {@link #FORGET_WEIGHT}, so a chat
 * lasts about ten days and a cleared run about a month; warmth halves every {@code MEMORY_WARMTH_HALF_LIFE_DAYS}. Under
 * {@link #DETAIL_WEIGHT} an episode is only a feeling: it counts, but it is no longer brought up. Stored in the pair's
 * living_acquaintance row ({@link CitizenStore#memory}); pure, so the citizen's day never waits on it.
 */
public record PersonMemory(int warmth, long memoryAtMs, long firstMetAtMs, List<Episode> episodes) {
    /** Warmth at or above this: someone it likes (known for the invite decision, never ignored on purpose). */
    public static final int WARM = 15;
    public static final int FOND = 40;
    /** Warmth at or under this: wary of them (a cool greeting). */
    public static final int COOL = -10;
    /** Warmth at or under this: a grudge (decision D1: a reserved or easygoing citizen turns down their invite). */
    public static final int COLD = -30;
    public static final int MAX_WARMTH = 100;
    static final double FORGET_WEIGHT = 1.0d;
    static final double DETAIL_WEIGHT = 3.0d;
    /** Episodes older than this come out vague ("long time no see") rather than by name. */
    static final long VAGUE_AFTER_MS = 14L * 86_400_000L;
    static final int DETAIL_CHARS = 32;
    /** Party minutes a stint warms by one point, at most {@link #PARTY_WARMTH_MAX} a stint. */
    static final int PARTY_MINUTES_PER_POINT = 10;
    static final int PARTY_WARMTH_MAX = 8;
    /** A reunion is worth a greeting from this salience up: two chats, a trade, a party stint (not one "hi"). */
    static final double GREET_SALIENCE = 5.0d;
    /** A "hi" gets a remembered hello from this salience up. */
    static final double HELLO_SALIENCE = 3.0d;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;
    /** No two runs of a party quest end this close together: inside it, it is the same run told twice. */
    private static final long RUN_FOLD_MS = 10 * 60_000L;

    /** What a reunion greeting or a hello brings up: its chatter trigger and the episode it names (null: none). */
    public record Greeting(String trigger, Episode episode) { }

    /** What happened, how much it weighs at first, how much it warms, and how close repeats fold into one. */
    public enum Kind {
        /** They talked to it. */
        TALK(2, 2, 2 * HOUR_MS),
        /** They thanked it (for an answer). */
        THANKED(3, 4, 2 * HOUR_MS),
        /** It answered their question; the detail is what about. */
        HELPED(3, 2, 2 * HOUR_MS),
        /** A party stint together; the count is minutes. */
        PARTY(2, 0, 3 * HOUR_MS),
        /** A party quest cleared together; the detail is the quest ("kpq"). One run told twice folds. */
        PQ_CLEAR(10, 12, RUN_FOLD_MS),
        /** A party quest failed with them still there. */
        PQ_FAIL(6, 4, RUN_FOLD_MS),
        /**
         * They left its party quest run before the end, and it failed. A let-down from nothing is a grudge
         * ({@link #COLD}) for one warmth half-life (two weeks); from a friend it is only cool.
         */
        PQ_LEFT(8, -60, RUN_FOLD_MS),
        /** It bought something at their shop; the detail is the item. */
        BOUGHT(5, 3, HOUR_MS),
        /** They bought something at its stall. */
        SOLD(5, 5, HOUR_MS),
        FAMED(5, 8, 0L),
        /** A let-down like {@link #PQ_LEFT}. */
        DEFAMED(7, -60, 0L),
        /**
         * They handed it what its request on the needs board asked for, in person; the detail is the item. Warms once a
         * (UTC) day whatever they brought (the fold ignores the detail), more than HELPED.
         */
        FULFILLED(6, 8, DAY_MS),
        /** The same, left at a board while it was away: it warms less. */
        FULFILLED_BOARD(4, 4, DAY_MS);

        final double weight;
        final int warmth;
        final long foldMs;

        Kind(double weight, int warmth, long foldMs) {
            this.weight = weight;
            this.warmth = warmth;
            this.foldMs = foldMs;
        }

        static Kind parse(String name) {
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** One thing that happened: when (its latest repeat), where (0: no place), how many times or minutes, and what. */
    public record Episode(Kind kind, long atMs, int mapId, int count, String detail) {
        public Episode {
            detail = detail == null ? "" : detail;
        }

        /** Its first weight: a party stint weighs more the longer it was. */
        double baseWeight() {
            return kind == Kind.PARTY ? kind.weight + Math.min(6, count / PARTY_MINUTES_PER_POINT) : kind.weight;
        }

        double weight(long nowMs, PopulationConfig config) {
            return baseWeight() * fade(nowMs - atMs, config.MEMORY_HALF_LIFE_DAYS);
        }
    }

    public PersonMemory {
        episodes = List.copyOf(episodes);
        warmth = Math.max(-MAX_WARMTH, Math.min(MAX_WARMTH, warmth));
    }

    public static PersonMemory none() {
        return new PersonMemory(0, 0L, 0L, List.of());
    }

    public boolean isEmpty() {
        return episodes.isEmpty() && warmth == 0;
    }

    /** {@code 0.5^(ageMs / halfLifeDays)}; 1 for no age, 0 for a half-life of 0 days. */
    static double fade(long ageMs, double halfLifeDays) {
        if (ageMs <= 0L) {
            return 1.0d;
        }
        return halfLifeDays <= 0d ? 0d : Math.pow(0.5d, ageMs / (halfLifeDays * DAY_MS));
    }

    /** How warm it feels now: the stored warmth faded since it last changed. */
    public double warmthAt(long nowMs, PopulationConfig config) {
        return warmth * fade(nowMs - memoryAtMs, config.MEMORY_WARMTH_HALF_LIFE_DAYS);
    }

    public int feltWarmth(long nowMs, PopulationConfig config) {
        return (int) Math.round(warmthAt(nowMs, config));
    }

    /** How memorable they are: the warmth either way plus every episode's weight now. */
    public double salience(long nowMs, PopulationConfig config) {
        double total = Math.abs(warmthAt(nowMs, config));
        for (Episode episode : episodes) {
            total += episode.weight(nowMs, config);
        }
        return total;
    }

    /**
     * This memory with one more thing that happened {@code count} times (minutes, for a party stint) at {@code nowMs}: a
     * repeat inside its kind's fold window (same detail, same map for a party) adds to the last one; the warmth moves by
     * the kind's warmth (a party stint by a point every {@link #PARTY_MINUTES_PER_POINT} minutes, at most
     * {@link #PARTY_WARMTH_MAX}); forgotten episodes drop and at most {@code MEMORY_EPISODES_MAX} stay, the heaviest.
     */
    public PersonMemory with(Kind kind, int mapId, String detail, int count, long nowMs, PopulationConfig config) {
        String what = clean(detail);
        int times = Math.max(1, count);
        List<Episode> next = new ArrayList<>(episodes);
        int folded = -1;
        for (int i = next.size() - 1; i >= 0; i--) {
            Episode episode = next.get(i);
            boolean needs = kind == Kind.FULFILLED || kind == Kind.FULFILLED_BOARD;
            boolean sameDetail = needs || episode.detail().equalsIgnoreCase(what);
            boolean inWindow = needs ? nowMs / DAY_MS == episode.atMs() / DAY_MS // once a (UTC) day, however often
                    : nowMs - episode.atMs() <= kind.foldMs;
            if (episode.kind() == kind && sameDetail && inWindow
                    && (kind != Kind.PARTY || episode.mapId() == mapId)) {
                folded = i;
                break;
            }
        }
        int before = folded < 0 ? 0 : next.get(folded).count();
        if (folded < 0) {
            next.add(new Episode(kind, nowMs, mapId, times, what));
        } else {
            Episode old = next.remove(folded);
            next.add(new Episode(kind, nowMs, mapId == 0 ? old.mapId() : mapId, old.count() + times,
                    what.isEmpty() ? old.detail() : what));
        }
        int delta;
        if (kind == Kind.PARTY) {
            delta = partyPoints(before + times) - partyPoints(before);
        } else if (folded < 0) {
            delta = kind.warmth;
        } else if (kind == Kind.TALK || kind == Kind.THANKED || kind == Kind.HELPED) {
            delta = before < 3 ? kind.warmth / 2 : 0; // a chat warms; the tenth line of the same chat does not
        } else {
            delta = 0; // the same run or the same trade told twice
        }
        int warmed = (int) Math.round(warmthAt(nowMs, config) + delta);
        return new PersonMemory(warmed, nowMs, firstMetAtMs > 0L ? firstMetAtMs : nowMs, kept(next, nowMs, config));
    }

    /**
     * This memory feeling at least {@code floor} warm at {@code nowMs} (a warmer one keeps its warmth and its clock), the
     * episodes as they are: what a hire let go keeps from its friendship (docs/BOT_CONCEPT_PLAN.md C1.3).
     */
    public PersonMemory warmedTo(int floor, long nowMs, PopulationConfig config) {
        if (feltWarmth(nowMs, config) >= floor) {
            return this;
        }
        return new PersonMemory(floor, nowMs, firstMetAtMs > 0L ? firstMetAtMs : nowMs, kept(episodes, nowMs, config));
    }

    private static int partyPoints(int minutes) {
        return Math.min(PARTY_WARMTH_MAX, minutes / PARTY_MINUTES_PER_POINT);
    }

    /** As it is remembered now: forgotten episodes dropped; nothing at all once no episode is left and the warmth faded out. */
    public PersonMemory faded(long nowMs, PopulationConfig config) {
        List<Episode> kept = kept(episodes, nowMs, config);
        if (kept.isEmpty() && Math.abs(warmthAt(nowMs, config)) < 1.0d) {
            return none();
        }
        return kept.size() == episodes.size() ? this : new PersonMemory(warmth, memoryAtMs, firstMetAtMs, kept);
    }

    private static List<Episode> kept(List<Episode> episodes, long nowMs, PopulationConfig config) {
        List<Episode> kept = new ArrayList<>();
        for (Episode episode : episodes) {
            if (episode.weight(nowMs, config) >= FORGET_WEIGHT) {
                kept.add(episode);
            }
        }
        int max = Math.max(1, config.MEMORY_EPISODES_MAX);
        if (kept.size() > max) {
            List<Episode> heaviest = new ArrayList<>(kept);
            heaviest.sort(Comparator.comparingDouble((Episode episode) -> episode.weight(nowMs, config)).reversed()
                    .thenComparing(Comparator.comparingLong(Episode::atMs).reversed()));
            List<Episode> keep = heaviest.subList(0, max);
            kept.removeIf(episode -> !keep.contains(episode));
        }
        return kept;
    }

    /** The heaviest episode it still remembers in detail, or null. */
    public Episode strongest(long nowMs, PopulationConfig config) {
        Episode best = null;
        double bestWeight = DETAIL_WEIGHT;
        for (Episode episode : episodes) {
            double weight = episode.weight(nowMs, config);
            if (weight >= bestWeight) {
                best = episode;
                bestWeight = weight;
            }
        }
        return best;
    }

    /**
     * The reunion greeting (a {@code memory.greet.*} trigger): cool when it is wary of them (naming the let-down while
     * it still remembers it); else the strongest episode it remembers in detail from the last two weeks; else warm, or
     * vague ("hey... Ann right?"). A let-down outweighed by better times since is not brought up.
     */
    public Greeting greeting(long nowMs, PopulationConfig config) {
        double felt = warmthAt(nowMs, config);
        Episode strongest = strongest(nowMs, config);
        if (felt <= COOL) {
            boolean letDown = strongest != null && (strongest.kind() == Kind.PQ_LEFT || strongest.kind() == Kind.DEFAMED);
            return new Greeting("memory.greet.cool", letDown ? strongest : null);
        }
        if (strongest != null && nowMs - strongest.atMs() < VAGUE_AFTER_MS) {
            String trigger = switch (strongest.kind()) {
                case PQ_CLEAR -> "memory.greet.pq";
                case PQ_FAIL -> "memory.greet.pqfail";
                case PARTY -> "memory.greet.party";
                case BOUGHT -> "memory.greet.bought";
                case SOLD -> "memory.greet.sold";
                case FAMED -> "memory.greet.famed";
                case HELPED -> "memory.greet.helped";
                case FULFILLED, FULFILLED_BOARD -> "memory.greet.fulfilled";
                case TALK, THANKED -> "memory.greet.talk";
                case PQ_LEFT, DEFAMED -> null;
            };
            if (trigger != null) {
                return new Greeting(trigger, strongest);
            }
        }
        return new Greeting(felt >= WARM ? "memory.greet.warm" : "memory.greet.vague", null);
    }

    /** The answer to their "hi": a remembered hello, cool when it is wary of them; null for someone it barely knows. */
    public String hello(long nowMs, PopulationConfig config) {
        if (salience(nowMs, config) < HELLO_SALIENCE) {
            return null;
        }
        return warmthAt(nowMs, config) <= COOL ? "memory.hello.cool" : "memory.hello";
    }

    // ---- words ---------------------------------------------------------------------------

    /** How long ago, as a person would put it: earlier, yesterday, the other day, last week, a while back. */
    public static String when(long agoMs) {
        if (agoMs < 6 * HOUR_MS) {
            return "earlier";
        }
        if (agoMs < 30 * HOUR_MS) {
            return "yesterday";
        }
        if (agoMs < 6 * DAY_MS) {
            return "the other day";
        }
        return agoMs < 14 * DAY_MS ? "last week" : "a while back";
    }

    /** How it feels about them, for the model: fond of them ... annoyed with them. */
    public String feeling(long nowMs, PopulationConfig config) {
        double felt = warmthAt(nowMs, config);
        if (felt >= FOND) {
            return "i'm fond of them";
        }
        if (felt >= WARM) {
            return "i like them";
        }
        if (felt <= COLD) {
            return "i'm annoyed with them";
        }
        return felt <= COOL ? "i'm a bit wary of them" : "i don't know them well yet";
    }

    /**
     * The grounding line (at most {@code maxChars}): when they met, what happened newest first in recall words and
     * places, and the feeling. {@code placeName} names a map id ("kerning city"); null or blank leaves the place out.
     */
    public String recall(long nowMs, PopulationConfig config, IntFunction<String> placeName, int maxChars) {
        StringBuilder out = new StringBuilder();
        if (firstMetAtMs > 0L) {
            out.append("met ").append(nowMs - firstMetAtMs < 6 * HOUR_MS ? "today" : when(nowMs - firstMetAtMs));
        }
        List<Episode> newest = new ArrayList<>(episodes);
        newest.sort(Comparator.comparingLong(Episode::atMs).reversed());
        String feeling = feeling(nowMs, config);
        for (Episode episode : newest) {
            String place = episode.mapId() <= 0 || placeName == null ? null : placeName.apply(episode.mapId());
            String part = phrase(episode) + (place == null || place.isBlank() ? "" : " at " + place) + " "
                    + when(nowMs - episode.atMs());
            if (out.length() + part.length() + feeling.length() + 4 > maxChars) {
                break;
            }
            out.append(out.length() == 0 ? "" : "; ").append(part);
        }
        return out.append(out.length() == 0 ? "" : "; ").append(feeling).toString();
    }

    /** One episode in a few first-person words ("cleared KPQ together"). */
    static String phrase(Episode episode) {
        String what = episode.detail();
        String quest = what.isEmpty() ? "a party quest" : what.toUpperCase(Locale.ROOT);
        return switch (episode.kind()) {
            case TALK -> episode.count() > 2 ? "chatted a while" : "chatted";
            case THANKED -> "they thanked me";
            case HELPED -> what.isEmpty() ? "answered a question of theirs" : "told them about " + what;
            case PARTY -> "partied together " + episode.count() + " min";
            case PQ_CLEAR -> "cleared " + quest + " together";
            case PQ_FAIL -> "failed " + quest + " together";
            case PQ_LEFT -> "they left our " + quest + " run halfway";
            case BOUGHT -> "bought " + (what.isEmpty() ? "something" : what) + " at their shop";
            case SOLD -> "they bought " + (what.isEmpty() ? "something" : what) + " at my stall";
            case FAMED -> "they famed me";
            case DEFAMED -> "they defamed me";
            case FULFILLED -> "they brought me " + (what.isEmpty() ? "what i needed" : what);
            case FULFILLED_BOARD -> "they left " + (what.isEmpty() ? "what i needed" : what) + " for me at the board";
        };
    }

    /**
     * A detail as stored: printable ASCII without the codec's separator or a chatter slot's braces, at most
     * {@link #DETAIL_CHARS}.
     */
    static String clean(String detail) {
        if (detail == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : detail.trim().toCharArray()) {
            if (c >= 0x20 && c <= 0x7e && c != '|' && c != '{' && c != '}') {
                out.append(c);
            }
            if (out.length() >= DETAIL_CHARS) {
                break;
            }
        }
        return out.toString().trim();
    }

    // ---- codec (living_acquaintance.episodes) -------------------------------------------------

    /** One line an episode: {@code KIND|epochMinute|mapId|count|detail}. */
    public static String encode(List<Episode> episodes) {
        StringBuilder out = new StringBuilder();
        for (Episode episode : episodes) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(episode.kind().name()).append('|').append(episode.atMs() / 60_000L).append('|')
                    .append(episode.mapId()).append('|').append(episode.count()).append('|').append(clean(episode.detail()));
        }
        return out.toString();
    }

    /** The stored episodes; a line that does not parse is left out. */
    public static List<Episode> decode(String stored) {
        List<Episode> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String line : stored.split("\n")) {
            String[] parts = line.split("\\|", 5);
            Kind kind = parts.length == 5 ? Kind.parse(parts[0]) : null;
            if (kind == null) {
                continue;
            }
            try {
                out.add(new Episode(kind, Long.parseLong(parts[1]) * 60_000L, Integer.parseInt(parts[2]),
                        Math.max(1, Integer.parseInt(parts[3])), clean(parts[4])));
            } catch (NumberFormatException ignored) {
                // a damaged line is a forgotten episode
            }
        }
        return out;
    }
}
