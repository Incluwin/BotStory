package server.living.population.events;

import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code events.yaml} (docs/WORLD_SYSTEMS_PLAN.md N4): the calendar's events and what each kind needs. */
public final class EventCatalog {
    public static final String FILE = "events.yaml";
    static final EventCatalog NONE = new EventCatalog(List.of(), List.of(), new Market(1, 1.0d, 1.0d),
            new Festivals(1.0d, List.of()), new Prizes(0, List.of()), new Prizes(0, List.of()));

    public enum Kind { MARKET_DAY, INVASION, FESTIVAL }

    enum Cadence { WEEKLY, FORTNIGHTLY, SEASONAL }

    /**
     * One event of the calendar. {@code minutes}: how long it runs. {@code fallback}: its slot of the week while there
     * are too few player samples. {@code dates}: a SEASONAL event's days of the year, its hour within
     * {@code hours} (empty: any hour).
     */
    public record Entry(String id, Kind kind, Cadence cadence, int minutes, int fallback, List<MonthDay> dates,
                        Set<Integer> hours) {
        public long durationMs() {
            return minutes * 60_000L;
        }

        /** The occurrence due in the week of Monday {@code weekOf} (its unique number), or null when none is. */
        Integer occurrence(int weekOf) {
            return switch (cadence) {
                case WEEKLY -> weekOf;
                case FORTNIGHTLY -> Math.floorMod(Math.floorDiv(weekOf, 7), 2) == 0 ? weekOf : null;
                case SEASONAL -> {
                    LocalDate day = date(weekOf);
                    yield day == null ? null : (int) day.toEpochDay();
                }
            };
        }

        /** The slots it may start at in that week; null for any. */
        Set<Integer> slots(int weekOf) {
            LocalDate day = cadence == Cadence.SEASONAL ? date(weekOf) : null;
            if (day == null && hours.isEmpty()) {
                return null;
            }
            Set<Integer> out = new LinkedHashSet<>();
            for (DayOfWeek one : DayOfWeek.values()) {
                for (int hour = 0; hour < 24; hour++) {
                    if ((day == null || day.getDayOfWeek() == one) && (hours.isEmpty() || hours.contains(hour))) {
                        out.add(HourPicker.slot(one, hour));
                    }
                }
            }
            return out;
        }

        /** Its fallback slot that week: a seasonal event's is on its date. */
        int fallbackSlot(int weekOf) {
            LocalDate day = cadence == Cadence.SEASONAL ? date(weekOf) : null;
            return day == null ? fallback : HourPicker.slot(day.getDayOfWeek(), fallback % 24);
        }

        private LocalDate date(int weekOf) {
            for (int i = 0; i < 7; i++) {
                LocalDate day = LocalDate.ofEpochDay(weekOf + i);
                if (dates.contains(MonthDay.from(day))) {
                    return day;
                }
            }
            return null;
        }
    }

    /** A wave: how many of which monster. */
    record Wave(int mob, int count) {
    }

    /**
     * Where an invasion comes: the field {@code map} outside {@code town}'s gate, {@code place} as people say it. Citizens
     * of {@code region} of {@code levelMin..levelMax} come. The last monster is {@code boss} with {@code bossHp} times its
     * HP (an ordinary monster, so the boss rules stay out of it).
     */
    record Site(String id, String region, int map, String town, String place, int levelMin, int levelMax, List<Wave> waves,
                int boss, int bossHp) {
    }

    record Market(int stalls, double ask, double weight) {
    }

    /** A season's town: its map and the jump-quest course of its meet. */
    record FestivalTown(String name, int map, String region, List<String> courses) {
    }

    record Festivals(double weight, List<FestivalTown> towns) {
    }

    record Prize(int item, int count) {
    }

    /** What everyone who earned it gets: fame, and one of the items (decision D13: never meso). */
    record Prizes(int fame, List<Prize> items) {
    }

    private final List<Entry> entries;
    private final List<Site> sites;
    private final Market market;
    private final Festivals festivals;
    private final Prizes invasion;
    private final Prizes festival;

    private EventCatalog(List<Entry> entries, List<Site> sites, Market market, Festivals festivals,
                         Prizes invasion, Prizes festival) {
        this.entries = List.copyOf(entries);
        this.sites = List.copyOf(sites);
        this.market = market;
        this.festivals = festivals;
        this.invasion = invasion;
        this.festival = festival;
    }

    /** {@code events.yaml} in {@code contentDir}; no events when it is missing. */
    public static EventCatalog load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        if (!Files.exists(file)) {
            return NONE;
        }
        Node root = ContentLoader.read(file);
        List<Entry> entries = new ArrayList<>();
        for (Node node : root.get("events").items()) {
            List<MonthDay> dates = new ArrayList<>();
            for (String date : node.has("dates") ? node.get("dates").strings() : List.<String>of()) {
                dates.add(MonthDay.parse("--" + date));
            }
            Set<Integer> hours = new LinkedHashSet<>();
            if (node.has("hours")) {
                List<Integer> range = node.get("hours").integers();
                for (int hour = range.get(0); hour <= range.get(1); hour++) {
                    hours.add(hour);
                }
            }
            Node fallback = node.get("fallback");
            int slot = HourPicker.slot(DayOfWeek.valueOf(fallback.get("day").string("SUNDAY")), fallback.get("hour").integer());
            entries.add(new Entry(node.get("id").string(), kind(node.get("kind").string()),
                    Cadence.valueOf(node.get("cadence").string().toUpperCase(Locale.ROOT)), node.get("minutes").integer(),
                    slot, List.copyOf(dates), Set.copyOf(hours)));
        }
        List<Site> sites = new ArrayList<>();
        for (Node node : root.get("invasions").items()) {
            List<Wave> waves = new ArrayList<>();
            for (Node wave : node.get("waves").items()) {
                waves.add(new Wave(wave.get("mob").integer(), wave.get("count").integer()));
            }
            List<Integer> levels = node.get("levels").integers();
            sites.add(new Site(node.get("id").string(), node.get("region").string(), node.get("map").integer(),
                    node.get("town").string(), node.get("place").string(), levels.get(0), levels.get(1),
                    List.copyOf(waves), node.get("boss").get("mob").integer(), node.get("boss").get("hp").integer()));
        }
        Node day = root.get("market");
        Market market = new Market(day.get("stalls").integer(), day.get("ask").decimal(1.0d), day.get("weight").decimal(1.0d));
        List<FestivalTown> towns = new ArrayList<>();
        for (Node node : root.get("festivals").get("towns").items()) {
            towns.add(new FestivalTown(node.get("name").string(), node.get("map").integer(), node.get("region").string(),
                    node.get("courses").strings()));
        }
        Festivals festivals = new Festivals(root.get("festivals").get("weight").decimal(1.0d), List.copyOf(towns));
        if (market.stalls() < 1 || market.ask() <= 0.0d || market.ask() > 1.0d) {
            throw new ContentException(FILE + ": market stalls must be at least 1 and ask over 0 up to 1");
        }
        return new EventCatalog(entries, sites, market, festivals, prizes(root.get("prizes").get("invasion")),
                prizes(root.get("prizes").get("festival")));
    }

    private static Prizes prizes(Node node) {
        List<Prize> items = new ArrayList<>();
        for (Node item : node.get("items").items()) {
            items.add(new Prize(item.get("item").integer(), item.get("count").integer(1)));
        }
        return new Prizes(node.get("fame").integer(0), List.copyOf(items));
    }

    private static Kind kind(String name) {
        return Kind.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(String id) {
        return entries.stream().filter(entry -> entry.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    List<Site> sites() {
        return sites;
    }

    Site site(String id) {
        return sites.stream().filter(site -> site.id().equals(id)).findFirst().orElse(null);
    }

    Market market() {
        return market;
    }

    Festivals festivals() {
        return festivals;
    }

    FestivalTown festivalTown(String name) {
        return festivals.towns().stream().filter(town -> town.name().equals(name)).findFirst().orElse(null);
    }

    Prizes invasionPrizes() {
        return invasion;
    }

    Prizes festivalPrizes() {
        return festival;
    }
}
