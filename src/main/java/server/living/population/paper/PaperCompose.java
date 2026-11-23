package server.living.population.paper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds one week's issue: the stories filed to the desk over the 7 days before the issue time, plus the sections the
 * desk reads from tables that already exist (the tournament and ladder, the clear records, the price index, the
 * rival crews' news). Each section keeps its {@link #SECTION_MAX} heaviest stories. Pure: every read is a
 * {@link Sources} call, so tests compose from rows in memory.
 */
public final class PaperCompose {
    /** Stories printed per section at most (the Arena's champions always fit: there are three brackets). */
    static final int SECTION_MAX = 6;
    /** A price moves into the Market section at this many percent over the week, up or down. */
    static final int MARKET_MIN_PERCENT = 5;
    static final int MARKET_EACH_WAY = 3;
    /** Slots that hold a character's name: the stories that carry them name those characters. */
    static final Set<String> NAME_SLOTS = Set.of("name", "partner", "champion", "runner_up", "leader");
    /** A slot that holds several names, comma separated. */
    static final String NAMES_SLOT = "names";

    static final int W_CHAMPION = 100;
    static final int W_FIRST_CLEAR = 90;
    static final int W_RECORD = 80;
    static final int W_CLIMB = 60;
    static final int W_FASTEST = 45;
    static final int W_RIVAL = 40;
    static final int W_MARKET = 30;

    /** A bracket's tournament this week, finished: who won, who came second. */
    public record Champion(String bracket, String champion, String runnerUp) {
    }

    /** The week's biggest rating climb in one bracket. */
    public record Climb(String name, String bracket, int points) {
    }

    /** A clear in the window: quest script, how long (0 untimed), and the names in it. */
    public record ClearRow(String quest, long durationMs, long clearedAtMs, List<String> names) {
        public ClearRow {
            names = List.copyOf(names);
        }
    }

    /** One basket item's price on one day (economy_cpi_daily). */
    public record Price(LocalDate day, int itemId, long price) {
    }

    /** A rival crew beat a time (a rival.news clear row): crew name, quest script, its time. */
    public record RivalBeat(String crew, String quest, long durationMs) {
    }

    /** What composing reads. */
    public interface Sources {
        List<PaperItem> items(long fromMs, long toMs);

        List<Champion> champions(int weekOf);

        /** The biggest climb this week of each bracket's ladder; meaningful only while the week is the current one. */
        List<Climb> climbs(int weekOf);

        List<ClearRow> clears(long fromMs, long toMs);

        /** Each recorded quest's fastest timed clear before {@code beforeMs}. */
        Map<String, Long> fastestBefore(long beforeMs);

        /** Quests with any clear before {@code beforeMs}. */
        Set<String> clearedBefore(long beforeMs);

        List<Price> prices(LocalDate from, LocalDate to);

        List<RivalBeat> rivalBeats(long fromMs, long toMs);

        /** The records page's name for a quest script, null when the quest is no record. */
        String questLabel(String script);

        String itemName(int itemId);
    }

    private PaperCompose() {
    }

    /** The issue of week {@code weekOf}, issued at {@code issuedAtMs}, covering the 7 days before it. */
    public static PaperIssue compose(int weekOf, long issuedAtMs, LocalDate issueDay, Sources sources,
                                     PaperCatalog catalog) {
        long fromMs = issuedAtMs - 7L * 24 * 3_600_000L;
        Map<String, List<Draft>> drafts = new LinkedHashMap<>();
        arena(weekOf, sources, drafts);
        records(fromMs, issuedAtMs, sources, drafts);
        market(issueDay, sources, drafts);
        for (PaperItem item : sources.items(fromMs, issuedAtMs)) {
            add(drafts, item.key(), item.weight(), item.slots());
        }
        List<PaperIssue.Section> sections = new ArrayList<>();
        for (PaperCatalog.SectionInfo info : catalog.sections()) {
            List<Draft> mine = drafts.getOrDefault(info.id(), List.of());
            List<Draft> sorted = new ArrayList<>(mine);
            sorted.sort(Comparator.comparingInt(Draft::weight).reversed());
            List<PaperIssue.Story> stories = new ArrayList<>();
            for (Draft draft : sorted) {
                if (stories.size() >= SECTION_MAX) {
                    break;
                }
                PaperCatalog.Words words = catalog.words(weekOf, draft.key(), draft.slots());
                if (words != null) {
                    stories.add(new PaperIssue.Story(draft.key(), draft.weight(), words.headline(), words.body(),
                            words.gist(), namesOf(draft.slots())));
                }
            }
            if (!stories.isEmpty()) {
                sections.add(new PaperIssue.Section(info.id(), info.title(), stories));
            }
        }
        return new PaperIssue(weekOf, issuedAtMs, sections);
    }

    private record Draft(String key, int weight, Map<String, String> slots) {
    }

    private static void add(Map<String, List<Draft>> drafts, String key, int weight, Map<String, String> slots) {
        drafts.computeIfAbsent(PaperItem.section(key), section -> new ArrayList<>()).add(new Draft(key, weight, slots));
    }

    static List<String> namesOf(Map<String, String> slots) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> slot : new TreeMap<>(slots).entrySet()) {
            if (NAME_SLOTS.contains(slot.getKey()) && !slot.getValue().isBlank()) {
                out.add(slot.getValue());
            } else if (NAMES_SLOT.equals(slot.getKey())) {
                for (String one : slot.getValue().split(",")) {
                    if (!one.isBlank()) {
                        out.add(one.trim());
                    }
                }
            }
        }
        return out;
    }

    // ---- Arena -------------------------------------------------------------------------------------------------------

    private static void arena(int weekOf, Sources sources, Map<String, List<Draft>> drafts) {
        for (Champion champion : sources.champions(weekOf)) {
            if (champion.champion() == null || champion.champion().isBlank()) {
                continue;
            }
            Map<String, String> slots = new LinkedHashMap<>();
            slots.put("bracket", bracketLabel(champion.bracket()));
            slots.put("champion", champion.champion());
            if (champion.runnerUp() != null && !champion.runnerUp().isBlank()) {
                slots.put("runner_up", champion.runnerUp());
            }
            add(drafts, slots.containsKey("runner_up") ? "arena.champion" : "arena.champion.alone", W_CHAMPION, slots);
        }
        Climb best = null;
        for (Climb climb : sources.climbs(weekOf)) {
            if (climb.points() > 0 && (best == null || climb.points() > best.points())) {
                best = climb;
            }
        }
        if (best != null) {
            add(drafts, "arena.climb", W_CLIMB, Map.of("name", best.name(), "bracket", bracketLabel(best.bracket()),
                    "points", String.valueOf(best.points())));
        }
    }

    /** "Rookie" for ROOKIE: the coliseum's own bracket names. */
    static String bracketLabel(String bracket) {
        if (bracket == null || bracket.isEmpty()) {
            return "";
        }
        return bracket.charAt(0) + bracket.substring(1).toLowerCase(Locale.ROOT);
    }

    // ---- Records -----------------------------------------------------------------------------------------------------

    private static void records(long fromMs, long toMs, Sources sources, Map<String, List<Draft>> drafts) {
        List<ClearRow> clears = sources.clears(fromMs, toMs);
        if (!clears.isEmpty()) {
            Map<String, Long> before = sources.fastestBefore(fromMs);
            Set<String> clearedBefore = sources.clearedBefore(fromMs);
            Map<String, ClearRow> firsts = new LinkedHashMap<>();
            Map<String, ClearRow> fastest = new LinkedHashMap<>();
            for (ClearRow clear : clears) { // oldest first
                if (sources.questLabel(clear.quest()) == null) {
                    continue;
                }
                if (!clearedBefore.contains(clear.quest())) {
                    firsts.putIfAbsent(clear.quest(), clear);
                }
                ClearRow held = fastest.get(clear.quest());
                if (clear.durationMs() > 0 && (held == null || clear.durationMs() < held.durationMs())) {
                    fastest.put(clear.quest(), clear);
                }
            }
            for (ClearRow first : firsts.values()) {
                add(drafts, "records.first", W_FIRST_CLEAR, Map.of("quest", sources.questLabel(first.quest()),
                        NAMES_SLOT, names(first.names())));
            }
            for (ClearRow best : fastest.values()) {
                Long old = before.get(best.quest());
                boolean record = old == null || best.durationMs() < old;
                if (firsts.containsKey(best.quest()) && firsts.get(best.quest()) == best) {
                    continue; // the first clear's story says it
                }
                add(drafts, record ? "records.record" : "records.fastest", record ? W_RECORD : W_FASTEST,
                        Map.of("quest", sources.questLabel(best.quest()), "time", time(best.durationMs()),
                                NAMES_SLOT, names(best.names())));
            }
        }
        Set<String> told = new HashSet<>();
        for (RivalBeat beat : sources.rivalBeats(fromMs, toMs)) {
            String label = sources.questLabel(beat.quest());
            if (label != null && told.add(beat.crew() + "|" + beat.quest())) {
                add(drafts, "records.rival", W_RIVAL, Map.of("crew", beat.crew(), "quest", label,
                        "time", time(beat.durationMs())));
            }
        }
    }

    /** The first four names, comma separated ("and 2 more" past them). */
    static String names(List<String> names) {
        if (names.size() <= 4) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 4)) + " and " + (names.size() - 4) + " more";
    }

    /** "12:05" for 12 min 5 s; "1:02:03" past an hour. */
    static String time(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        long secs = seconds % 60L;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    // ---- Market ------------------------------------------------------------------------------------------------------

    /** Each basket item's price on the issue's eve against 7 days earlier: the biggest risers and fallers. */
    private static void market(LocalDate issueDay, Sources sources, Map<String, List<Draft>> drafts) {
        LocalDate to = issueDay.minusDays(1);
        LocalDate from = to.minusDays(7);
        Map<Integer, Price> first = new LinkedHashMap<>();
        Map<Integer, Price> last = new LinkedHashMap<>();
        for (Price price : sources.prices(from, to)) {
            if (price.price() <= 0) {
                continue;
            }
            first.merge(price.itemId(), price, (a, b) -> b.day().isBefore(a.day()) ? b : a);
            last.merge(price.itemId(), price, (a, b) -> b.day().isAfter(a.day()) ? b : a);
        }
        List<int[]> moves = new ArrayList<>(); // item, percent
        for (Map.Entry<Integer, Price> entry : first.entrySet()) {
            Price end = last.get(entry.getKey());
            if (end == null || !end.day().isAfter(entry.getValue().day())) {
                continue;
            }
            long start = entry.getValue().price();
            int percent = (int) Math.round((end.price() - start) * 100.0d / start);
            if (Math.abs(percent) >= MARKET_MIN_PERCENT) {
                moves.add(new int[] {entry.getKey(), percent});
            }
        }
        moves.sort(Comparator.comparingInt((int[] move) -> -move[1]).thenComparingInt(move -> move[0]));
        int risers = 0;
        for (int[] move : moves) {
            if (move[1] > 0 && risers < MARKET_EACH_WAY) {
                risers++;
                marketStory(drafts, sources, "market.riser", move);
            }
        }
        int fallers = 0;
        for (int i = moves.size() - 1; i >= 0; i--) {
            int[] move = moves.get(i);
            if (move[1] < 0 && fallers < MARKET_EACH_WAY) {
                fallers++;
                marketStory(drafts, sources, "market.faller", move);
            }
        }
    }

    private static void marketStory(Map<String, List<Draft>> drafts, Sources sources, String key, int[] move) {
        String name = sources.itemName(move[0]);
        if (name == null || name.isBlank()) {
            return;
        }
        add(drafts, key, W_MARKET + Math.min(30, Math.abs(move[1]) / 2), Map.of("item", name,
                "percent", String.valueOf(Math.abs(move[1]))));
    }
}
