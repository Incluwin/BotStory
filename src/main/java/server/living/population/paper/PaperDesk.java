package server.living.population.paper;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The town paper's desk (docs/WORLD_SYSTEMS_PLAN.md N1). Anything that happens and is worth a line files a story with
 * {@link #file}; every week at {@code PAPER_DAY} {@code PAPER_HOUR}:00 server time (Sunday 00:00, four hours after the
 * Saturday tournament) the desk composes the issue of the 7 days before it from those stories and the tables it reads
 * itself ({@link PaperCompose}) and keeps it as the week's one living_paper row. It rides the 60 s social lane
 * ({@link #minute}); a server down at the issue time issues on its first minute back within {@link #LATE_MS}, and a
 * restart in the issue hour finds the row there and composes nothing (the unique week_of makes a race harmless too).
 *
 * <p>The filing API is {@link #file(String, int, Map)}, stable for the pieces that report news after N1: a key
 * {@code "<section>.<story>"} that {@code paper.yaml} has words for, a weight against the week's other stories
 * (champions 100, a first clear 90, a wedding 70, a level 70 milestone 35), and the slots its templates fill. It is
 * cheap and never throws: the row is written on the desk's executor, and nothing is filed while the paper is off.</p>
 */
public final class PaperDesk {
    private static final Logger log = LoggerFactory.getLogger(PaperDesk.class);
    /** living_event_log kind of a filed story (character_id NULL; the detail is {@link PaperItem}'s JSON). */
    public static final String ITEM_EVENT = "paper.item";
    /**
     * living_event_log kind of a player's "seen this issue" marker (detail: the week_of): written when the letters tell
     * them the paper is out or when they read it at a board. A letters.* kind, so the retention sweep keeps it.
     */
    public static final String READ_EVENT = "letters.paper";
    /** An issue missed by more than this (the server was down all day) is skipped for the week. */
    static final long LATE_MS = 24L * 3_600_000L;
    /** Citizens talk about an issue's headlines for this long after it comes out. */
    static final long TALK_MS = 24L * 3_600_000L;
    static final long WEEK_MS = 7L * 24 * 3_600_000L;
    private static final long CACHE_MS = 10L * 60_000L;
    private static final int DETAIL_MAX = 512;

    /** What the desk keeps; tests use an in-memory one. */
    public interface Store {
        /** A filed story's row; false when the write failed. */
        boolean file(String detail);

        /** Week {@code weekOf}'s issue: empty when there is none, null when the read failed. */
        Optional<Stored> issue(int weekOf);

        /** Writes the week's issue unless it is there already; true only when this call wrote it. */
        boolean insert(int weekOf, long issuedAtMs, String body);

        /** The newest issue at or before week {@code weekOf}; empty when none (or the read failed). */
        Optional<Stored> latestAtOrBefore(int weekOf);

        /** The week of the issue a player last saw (their newest {@link #READ_EVENT}); -1 for none, null on failure. */
        Integer lastSeen(int characterId);

        void seen(int characterId, int weekOf);

        /** A character's name from the characters table; null when there is none. */
        String name(int characterId);
    }

    public record Stored(int weekOf, long issuedAtMs, String body) {
    }

    private static volatile PaperDesk installed;

    private final Store store;
    private final PaperCompose.Sources sources;
    private final Supplier<PaperCatalog> catalog;
    private final Supplier<PopulationConfig> config;
    private final Executor files;
    private final ZoneId zone;
    private final Set<Integer> issued = ConcurrentHashMap.newKeySet();
    private volatile PaperIssue latest;
    private volatile long latestReadAtMs = Long.MIN_VALUE;

    public PaperDesk(Store store, PaperCompose.Sources sources, Supplier<PaperCatalog> catalog,
                     Supplier<PopulationConfig> config, Executor files, ZoneId zone) {
        this.store = store;
        this.sources = sources;
        this.catalog = catalog;
        this.config = config;
        this.files = files;
        this.zone = zone;
    }

    /**
     * The live desk: JDBC storage, {@code paper.yaml} from the population content folder (read once), quest labels from
     * the records, item names from the WZ, and story rows written on {@code files} (companion-ops).
     */
    public static PaperDesk live(Supplier<PopulationConfig> config, server.living.population.records.ClearLog clears,
                                 Executor files) {
        java.util.concurrent.atomic.AtomicReference<PaperCatalog> words = new java.util.concurrent.atomic.AtomicReference<>();
        Supplier<PaperCatalog> catalog = () -> words.updateAndGet(loaded -> loaded != null ? loaded
                : PaperCatalog.load(java.nio.file.Path.of(config.get().CONTENT_DIR)));
        PaperStore store = new PaperStore(script -> clears.labels().recorded(script) ? clears.labels().label(script) : null,
                itemId -> server.ItemInformationProvider.getInstance().getName(itemId));
        return new PaperDesk(store, store, catalog, config, files, ZoneId.systemDefault());
    }

    /** The desk the hooks file to (Population.start); null before and after. */
    public static void install(PaperDesk desk) {
        installed = desk;
    }

    public static PaperDesk get() {
        return installed;
    }

    public PaperCatalog catalog() {
        return catalog.get();
    }

    public boolean on() {
        PopulationConfig current = config.get();
        return current != null && current.PAPER_ENABLED;
    }

    // ---- filing ------------------------------------------------------------------------------------------------------

    /**
     * Files a story for this week's paper: {@code key} is {@code "<section>.<story>"} ({@code town.wedding}), {@code
     * weight} 1..1000 against the week's other stories, {@code slots} what its templates fill ({@code name},
     * {@code partner}, {@code champion}, {@code runner_up}, {@code leader} and a comma-separated {@code names} are the
     * characters the story names). Nothing happens while the paper is off or no desk is installed.
     */
    public static void file(String key, int weight, Map<String, String> slots) {
        PaperDesk desk = installed;
        if (desk != null) {
            desk.fileNow(key, weight, slots);
        }
    }

    /** As {@link #file}, with the name of character {@code characterId} looked up for slot {@code nameSlot}. */
    public static void fileNaming(String key, int weight, Map<String, String> slots, String nameSlot, int characterId) {
        PaperDesk desk = installed;
        if (desk == null || !desk.on()) {
            return;
        }
        desk.files.execute(() -> {
            String name = desk.store.name(characterId);
            if (name != null && !name.isBlank()) {
                Map<String, String> named = new java.util.LinkedHashMap<>(slots);
                named.put(nameSlot, name);
                desk.write(key, weight, named);
            }
        });
    }

    void fileNow(String key, int weight, Map<String, String> slots) {
        if (!on() || key == null || PaperItem.section(key).isEmpty() || slots == null) {
            return;
        }
        files.execute(() -> write(key, weight, slots));
    }

    private void write(String key, int weight, Map<String, String> slots) {
        String detail = new PaperItem(0L, key, Math.max(1, Math.min(1000, weight)), slots).detail();
        if (detail.length() > DETAIL_MAX) {
            log.debug("paper: story {} not filed, {} characters", key, detail.length());
            return;
        }
        try {
            store.file(detail);
        } catch (RuntimeException failure) {
            log.debug("paper: story {} not filed", key, failure);
        }
    }

    // ---- the issue ---------------------------------------------------------------------------------------------------

    /** The social minute: in the issue window, the week's issue is composed and kept once. */
    public void minute(long nowMs) {
        if (!on()) {
            return;
        }
        int week = weekOf(nowMs);
        long at = issueAtMs(week);
        if (nowMs < at || nowMs >= at + LATE_MS || issued.contains(week)) {
            return;
        }
        issue(week);
    }

    /**
     * Composes and keeps week {@code weekOf}'s issue unless it exists: the issue now kept, or empty when storage is
     * down. A second call (a restart, a GM's {@code !paper issue}) returns the stored one and writes nothing.
     */
    public Optional<PaperIssue> issue(int weekOf) {
        Optional<Stored> existing = store.issue(weekOf);
        if (existing == null) {
            return Optional.empty(); // the read failed: try again next minute
        }
        if (existing.isPresent()) {
            issued.add(weekOf);
            return Optional.ofNullable(read(existing.get()));
        }
        PaperIssue composed = compose(weekOf);
        if (store.insert(weekOf, composed.issuedAtMs(), composed.toJson())) {
            log.info("paper: week {} issued, {} sections, {} stories", weekOf, composed.sections().size(),
                    composed.sections().stream().mapToInt(section -> section.stories().size()).sum());
        }
        issued.add(weekOf);
        latestReadAtMs = Long.MIN_VALUE;
        Optional<Stored> kept = store.issue(weekOf);
        return kept == null || kept.isEmpty() ? Optional.of(composed) : Optional.ofNullable(read(kept.get()));
    }

    /** Week {@code weekOf}'s issue as it would read now (the GM's {@code !paper}): composed, not kept. */
    public PaperIssue compose(int weekOf) {
        long at = issueAtMs(weekOf);
        return PaperCompose.compose(weekOf, at, Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), sources,
                catalog.get());
    }

    /** The newest issue out by {@code nowMs}; empty when there is none. Cached for ten minutes. */
    public Optional<PaperIssue> latest(long nowMs) {
        long readAt = latestReadAtMs;
        if (readAt == Long.MIN_VALUE || nowMs - readAt >= CACHE_MS || nowMs < readAt) {
            Optional<Stored> row = store.latestAtOrBefore(weekOf(nowMs));
            latest = row.map(this::read).filter(issue -> issue.issuedAtMs() <= nowMs).orElse(null);
            latestReadAtMs = nowMs;
        }
        return Optional.ofNullable(latest);
    }

    /** The issue before {@code issue}; empty when there is none. */
    public Optional<PaperIssue> before(PaperIssue issue) {
        return store.latestAtOrBefore(issue.weekOf() - 7).map(this::read);
    }

    /** The newest issue while its first day lasts (citizens talk about it); empty otherwise. */
    public Optional<PaperIssue> fresh(long nowMs) {
        return latest(nowMs).filter(issue -> nowMs - issue.issuedAtMs() < TALK_MS);
    }

    /** The newest issue if it is under a week old and {@code characterId} has not seen it; empty otherwise. */
    public Optional<PaperIssue> unseen(int characterId, long nowMs) {
        Optional<PaperIssue> issue = latest(nowMs).filter(one -> nowMs - one.issuedAtMs() < WEEK_MS);
        if (issue.isEmpty()) {
            return issue;
        }
        Integer seen = store.lastSeen(characterId);
        return seen == null || seen >= issue.get().weekOf() ? Optional.empty() : issue;
    }

    /** {@code characterId} saw week {@code weekOf}'s issue (a letter told them, or they read it). */
    public void seen(int characterId, int weekOf) {
        Integer last = store.lastSeen(characterId);
        if (last != null && last < weekOf) {
            store.seen(characterId, weekOf);
        }
    }

    private PaperIssue read(Stored row) {
        PaperIssue issue = PaperIssue.fromJson(row.weekOf(), row.issuedAtMs(), row.body());
        if (issue == null) {
            log.warn("paper: week {} issue body unreadable", row.weekOf());
        }
        return issue;
    }

    // ---- the calendar ------------------------------------------------------------------------------------------------

    /** The week {@code ms} falls in: its Monday, days since 1970-01-01 in the server's zone (as the tournament's). */
    public int weekOf(long ms) {
        return weekOf(Instant.ofEpochMilli(ms).atZone(zone));
    }

    static int weekOf(ZonedDateTime at) {
        return (int) at.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** When week {@code weekOf}'s issue comes out: its PAPER_DAY at PAPER_HOUR:00 in the server's zone. */
    public long issueAtMs(int weekOf) {
        PopulationConfig current = config.get();
        DayOfWeek day = DayOfWeek.valueOf(current.PAPER_DAY.trim().toUpperCase(Locale.ROOT));
        return LocalDate.ofEpochDay(weekOf).plusDays(day.getValue() - 1L).atTime(current.PAPER_HOUR, 0)
                .atZone(zone).toInstant().toEpochMilli();
    }

    /** The newest week whose issue time has passed at {@code nowMs}. */
    public int lastDueWeek(long nowMs) {
        int week = weekOf(nowMs);
        return nowMs >= issueAtMs(week) ? week : week - 7;
    }
}
