package server.living.population.events;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.paper.PaperDesk;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The world events calendar (docs/WORLD_SYSTEMS_PLAN.md N4; population.WORLD_EVENTS_ENABLED plus a switch a kind).
 * {@code events.yaml} lists the events; each occurrence is a {@code living_world_event} row, so a restart neither repeats
 * one (the unique key, and PLANNED to RUNNING is claimed once) nor loses one (a RUNNING row is resumed until its end, a
 * PLANNED one whose start was missed by under {@link #LATE_MS} starts late in full). A week is planned in the hours
 * before the paper that comes out ahead of it: each event takes the busiest free hour ({@link HourPicker}) and is filed
 * to the paper's Notices; citizens in town mention it from the day before. A running event sets {@link WorldModifier}
 * effects and clears them when it ends.
 *
 * <p>Rides the 60 s social lane ({@link #minute}); running events step on the 20 s chatter tick ({@link #tick}).
 */
public final class WorldEvents {
    private static final Logger log = LoggerFactory.getLogger(WorldEvents.class);
    static final String PLANNED = "PLANNED";
    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String CANCELLED = "CANCELLED";
    static final String MISSED = "MISSED";
    /** A start missed by more than this (the server was down) is not made up. */
    static final long LATE_MS = 3 * 3_600_000L;
    /** A week is planned this long before the paper that announces it comes out. */
    static final long PLAN_AHEAD_MS = 6 * 3_600_000L;
    /** A world notice goes out this long before the start. */
    static final long WARN_MS = 10 * 60_000L;
    /** Citizens mention an event from this long before it. */
    static final long TALK_MS = 24 * 3_600_000L;
    static final long TALK_GAP_MS = 3_600_000L;
    static final int SAMPLE_DAYS = 28;
    private static final long WEEK_MS = 7 * 86_400_000L;

    /** A row of living_world_event. */
    public record Occurrence(long id, String event, int number, long startsAtMs, long endsAtMs, String state, int channel,
                             String site, String result) {
    }

    /** What the calendar keeps; tests use an in-memory one. A failed read is null, a failed write false. */
    interface Store {
        /** PLANNED and RUNNING rows, by start. */
        List<Occurrence> open();

        /** Rows starting in {@code [fromMs, toMs)}, whatever their state. */
        List<Occurrence> between(long fromMs, long toMs);

        Boolean exists(String eventId, int occurrence);

        /** A PLANNED row; false when the occurrence has one already. */
        boolean insert(String eventId, int occurrence, long startsAtMs, long endsAtMs, int channel, String site);

        /** PLANNED to RUNNING, once. */
        boolean claim(long id, long endsAtMs, int channel);

        /** An open row to its final state, once. */
        boolean finish(long id, String state, String result);

        Map<Long, Double> samples(int days);
    }

    /** One kind of event while it runs. Every call comes with the calendar's lock held. */
    interface Runner {
        /** It starts, or goes on after a restart. */
        void begin(long now, boolean resumed);

        /** Every 20 s; true when it is over before its time. */
        boolean step(long now);

        /** Its time is up (or {@link #step} said so): the result, with prizes and news. {@link #cleanUp} follows. */
        String end(long now);

        /** Everything it put into the world goes; also when it is cancelled or the server stops. */
        void cleanUp();

        /** It ended while the server was down: the result. */
        default String endedOffline(long now) {
            return "ended while the server was down";
        }
    }

    /** An event running now. */
    static final class Run {
        final Occurrence row;
        final EventCatalog.Entry entry;
        final long endsAtMs;
        final int channel;
        Runner runner;

        Run(Occurrence row, EventCatalog.Entry entry, long endsAtMs, int channel) {
            this.row = row;
            this.entry = entry;
            this.endsAtMs = endsAtMs;
            this.channel = channel;
        }

        /** Its effects' source. */
        String source() {
            return entry.id() + ":" + row.id();
        }
    }

    /** What {@link #talk} says: a chatter trigger and its slots, between two times, by citizens of {@code region} ("" all). */
    record Topic(String key, String trigger, String region, Map<String, String> slots, long fromMs, long untilMs) {
    }

    private final Population population;
    private final Store store;
    private final ZoneId zone;
    private final Random random;
    private volatile EventCatalog catalog;
    private volatile WorldModifier modifier = WorldModifier.NONE;
    private final Map<Long, Run> runs = new LinkedHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private final List<Topic> aftermath = new ArrayList<>();
    private volatile List<Topic> topics = List.of();
    private final Map<String, Long> saidAt = new ConcurrentHashMap<>();
    private final AtomicLong deferred = new AtomicLong();
    /** The world notice; tests read it. */
    Consumer<String> notice = WorldEvents::broadcast;

    public WorldEvents(Population population) {
        this(population, new WorldEventStore(), null, ZoneId.systemDefault(), new Random());
    }

    WorldEvents(Population population, Store store, EventCatalog catalog, ZoneId zone, Random random) {
        this.population = population;
        this.store = store;
        this.catalog = catalog;
        this.zone = zone;
        this.random = random;
    }

    public boolean on() {
        return population.config().WORLD_EVENTS_ENABLED;
    }

    private boolean on(EventCatalog.Kind kind) {
        PopulationConfig config = population.config();
        return on() && switch (kind) {
            case MARKET_DAY -> config.WORLD_EVENT_MARKET_DAY_ENABLED;
            case INVASION, FESTIVAL -> false;
        };
    }

    EventCatalog catalog() {
        EventCatalog current = catalog;
        if (current == null) {
            current = EventCatalog.load(java.nio.file.Path.of(population.config().CONTENT_DIR));
            catalog = current;
        }
        return current;
    }

    /** What the running events change (PresenceScheduler, Market, CitizenMiniGames, the drop game); memory only. */
    public WorldModifier modifier() {
        return modifier;
    }

    synchronized void set(WorldModifier.Effect effect) {
        modifier = modifier.with(effect);
    }

    synchronized void clear(String source) {
        modifier = modifier.without(source);
    }

    Population population() {
        return population;
    }

    Random random() {
        return random;
    }

    // ---- the minute ----------------------------------------------------------------------------------------------

    /** The social minute: rows to resume or start, weeks to plan, what citizens may mention. */
    public synchronized void minute(long now) {
        try {
            if (!on()) {
                stopAll("world events off");
                return;
            }
            List<Occurrence> open = store.open();
            if (open == null) {
                return; // storage is down: the running events go on, nothing is decided
            }
            for (Occurrence row : open) {
                EventCatalog.Entry entry = catalog().entry(row.event());
                if (entry != null && !runs.containsKey(row.id())) {
                    due(row, entry, now);
                }
            }
            int week = weekOf(now);
            plan(week, now);
            if (now >= paperMs(week) - PLAN_AHEAD_MS) {
                plan(week + 7, now);
            }
            refreshTopics(open, now);
        } catch (RuntimeException failure) {
            log.warn("world events minute failed", failure);
        }
    }

    private void due(Occurrence row, EventCatalog.Entry entry, long now) {
        if (RUNNING.equals(row.state())) { // a restart
            Run run = new Run(row, entry, row.endsAtMs(), row.channel());
            if (!on(entry.kind())) {
                store.finish(row.id(), CANCELLED, "its switch is off");
            } else if (now >= row.endsAtMs()) {
                String result = runner(run).endedOffline(now);
                store.finish(row.id(), DONE, result);
                log.info("world event {} {} was running when the server stopped: {}", row.event(), row.number(), result);
            } else {
                begin(run, true, now);
            }
            return;
        }
        if (now < row.startsAtMs()) {
            if (row.startsAtMs() - now <= WARN_MS && on(entry.kind())
                    && warned.putIfAbsent(row.event() + row.number(), true) == null) {
                notice.accept("[World Event] " + title(row, entry) + " starts in "
                        + Math.max(1L, (row.startsAtMs() - now + 59_999L) / 60_000L) + " minutes.");
            }
            return;
        }
        if (now >= row.startsAtMs() + LATE_MS) {
            if (store.finish(row.id(), MISSED, "")) {
                log.info("world event {} {} missed", row.event(), row.number());
            }
            return;
        }
        if (on(entry.kind())) {
            start(row, entry, now);
        }
    }

    /** Starts a planned row: claimed once, for its full time from now. True when it runs. */
    private boolean start(Occurrence row, EventCatalog.Entry entry, long now) {
        for (Run run : runs.values()) {
            if (run.entry.kind() == entry.kind()) {
                return false; // one of a kind at a time (a GM's): it starts when that one is over
            }
        }
        long endsAt = now + entry.durationMs();
        int channel = busiestChannel();
        if (!store.claim(row.id(), endsAt, channel)) {
            return false;
        }
        begin(new Run(row, entry, endsAt, channel), false, now);
        return runs.containsKey(row.id());
    }

    private void begin(Run run, boolean resumed, long now) {
        run.runner = runner(run);
        runs.put(run.row.id(), run);
        try {
            run.runner.begin(now, resumed);
            log.info("world event {} {} {} on channel {} until {} {}", run.entry.id(), run.row.number(),
                    resumed ? "resumes" : "begins", run.channel, Instant.ofEpochMilli(run.endsAtMs).atZone(zone),
                    run.row.site());
        } catch (RuntimeException failure) {
            log.warn("world event {} could not begin", run.entry.id(), failure);
            stop(run, CANCELLED, "failed to begin");
        }
    }

    private Runner runner(Run run) {
        return switch (run.entry.kind()) {
            case MARKET_DAY -> new MarketDay(this, run);
            case INVASION, FESTIVAL -> throw new IllegalStateException("not built");
        };
    }

    /** Every 20 s: the running events. */
    public synchronized void tick(long now) {
        for (Run run : new ArrayList<>(runs.values())) {
            try {
                if (!on(run.entry.kind())) {
                    stop(run, CANCELLED, "its switch is off");
                } else if (run.runner.step(now) || now >= run.endsAtMs) {
                    stop(run, DONE, run.runner.end(now));
                }
            } catch (RuntimeException failure) {
                log.warn("world event {} failed; it is called off", run.entry.id(), failure);
                stop(run, CANCELLED, "failed");
            }
        }
    }

    private void stop(Run run, String state, String result) {
        runs.remove(run.row.id());
        try {
            run.runner.cleanUp();
        } finally {
            store.finish(run.row.id(), state, result);
            log.info("world event {} {} {}: {}", run.entry.id(), run.row.number(), state, result);
        }
    }

    private void stopAll(String why) {
        for (Run run : new ArrayList<>(runs.values())) {
            stop(run, CANCELLED, why);
        }
        modifier = WorldModifier.NONE;
        aftermath.clear();
        topics = List.of();
    }

    /** The population stops: everything the events put into the world goes; their rows stay RUNNING to be resumed. */
    public synchronized void shutdown() {
        for (Run run : runs.values()) {
            try {
                run.runner.cleanUp();
            } catch (RuntimeException failure) {
                log.debug("world event {} clean-up failed", run.entry.id(), failure);
            }
        }
        runs.clear();
        modifier = WorldModifier.NONE;
    }

    // ---- planning ------------------------------------------------------------------------------------------------

    /** The Monday (days since 1970-01-01) of the week {@code ms} is in. */
    int weekOf(long ms) {
        return (int) Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    private long mondayMs(int weekOf) {
        return HourPicker.atMs(weekOf, 0, zone);
    }

    /** When that week's paper comes out. */
    private long paperMs(int weekOf) {
        PopulationConfig config = population.config();
        return HourPicker.atMs(weekOf, HourPicker.slot(day(config.PAPER_DAY), config.PAPER_HOUR), zone);
    }

    private static DayOfWeek day(String name) {
        return DayOfWeek.valueOf(name.trim().toUpperCase(Locale.ROOT));
    }

    /** Gives every event due in week {@code weekOf} that has no row its hour; with no hour left it gets none. */
    void plan(int weekOf, long now) {
        HourPicker.Load load = null;
        List<HourPicker.Taken> taken = null;
        for (EventCatalog.Entry entry : catalog().entries()) {
            Integer number = on(entry.kind()) ? entry.occurrence(weekOf) : null;
            if (number == null || !Boolean.FALSE.equals(store.exists(entry.id(), number))) {
                continue;
            }
            if (taken == null) {
                taken = taken(weekOf);
                Map<Long, Double> samples = taken == null ? null : store.samples(SAMPLE_DAYS);
                if (samples == null) {
                    return; // storage is down: next minute
                }
                load = HourPicker.load(samples, zone);
            }
            long at = HourPicker.pick(load, weekOf, entry.durationMs(), entry.slots(weekOf), entry.fallbackSlot(weekOf),
                    taken, now + WARN_MS, zone);
            if (at < 0L) {
                continue;
            }
            String site = site(entry, weekOf, at);
            if (store.insert(entry.id(), number, at, at + entry.durationMs(), 0, site)) {
                taken.add(new HourPicker.Taken(at, at + entry.durationMs()));
                log.info("world event {} {} planned for {} {}", entry.id(), number, Instant.ofEpochMilli(at).atZone(zone), site);
                announce(new Occurrence(0L, entry.id(), number, at, at + entry.durationMs(), PLANNED, 0, site, ""), entry, now);
            }
        }
    }

    /** What an event of that week may not run into: the tournament, and the week's events; null when storage is down. */
    private List<HourPicker.Taken> taken(int weekOf) {
        List<Occurrence> rows = store.between(mondayMs(weekOf), mondayMs(weekOf) + WEEK_MS);
        if (rows == null) {
            return null;
        }
        List<HourPicker.Taken> out = new ArrayList<>();
        for (Occurrence row : rows) {
            out.add(new HourPicker.Taken(row.startsAtMs(), row.endsAtMs()));
        }
        long tournament = HourPicker.atMs(weekOf, HourPicker.slot(day(YamlConfig.config.pvp.TOURNAMENT_DAY),
                YamlConfig.config.pvp.TOURNAMENT_HOUR), zone);
        out.add(new HourPicker.Taken(tournament, tournament + 2 * 3_600_000L));
        return out;
    }

    private String site(EventCatalog.Entry entry, int weekOf, long at) {
        return switch (entry.kind()) {
            case MARKET_DAY -> "";
            case INVASION -> {
                List<EventCatalog.Site> sites = catalog().sites();
                yield sites.isEmpty() ? "" : sites.get(Math.floorMod(Math.floorDiv(weekOf, 7), sites.size())).id();
            }
            case FESTIVAL -> {
                List<EventCatalog.FestivalTown> towns = catalog().festivals().towns();
                int season = (Instant.ofEpochMilli(at).atZone(zone).getMonthValue() - 1) / 3;
                yield towns.isEmpty() ? "" : towns.get(season % towns.size()).name();
            }
        };
    }

    /** A planned event in the paper's Notices, when an issue comes out between now and its start. */
    private void announce(Occurrence row, EventCatalog.Entry entry, long now) {
        int week = weekOf(now);
        long next = paperMs(week) >= now ? paperMs(week) : paperMs(week + 7);
        if (row.startsAtMs() > next) {
            PaperDesk.file("notices." + entry.id(), 300, slots(row, entry));
        }
    }

    // ---- words ---------------------------------------------------------------------------------------------------

    /** "Sunday at 8 pm". */
    String when(long ms) {
        ZonedDateTime at = Instant.ofEpochMilli(ms).atZone(zone);
        int hour = at.getHour() % 12 == 0 ? 12 : at.getHour() % 12;
        return at.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " at " + hour
                + (at.getHour() < 12 ? " am" : " pm");
    }

    private Map<String, String> slots(Occurrence row, EventCatalog.Entry entry) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("when", when(row.startsAtMs()));
        String place = place(row, entry);
        if (!place.isEmpty()) {
            out.put("place", place);
        }
        return out;
    }

    private String place(Occurrence row, EventCatalog.Entry entry) {
        return switch (entry.kind()) {
            case MARKET_DAY -> "";
            case INVASION -> {
                EventCatalog.Site site = catalog().site(row.site());
                yield site == null ? "" : site.place();
            }
            case FESTIVAL -> row.site();
        };
    }

    private String title(Occurrence row, EventCatalog.Entry entry) {
        return switch (entry.kind()) {
            case MARKET_DAY -> "Market Day in the Free Market";
            case INVASION -> "An invasion at " + place(row, entry);
            case FESTIVAL -> "The " + row.site() + " festival";
        };
    }

    // ---- citizens' talk --------------------------------------------------------------------------------------------

    private void refreshTopics(List<Occurrence> open, long now) {
        List<Topic> out = new ArrayList<>();
        for (Occurrence row : open) {
            EventCatalog.Entry entry = catalog().entry(row.event());
            if (entry == null || !on(entry.kind())) {
                continue;
            }
            Map<String, String> slots = slots(row, entry);
            boolean today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                    .equals(Instant.ofEpochMilli(row.startsAtMs()).atZone(zone).toLocalDate());
            String at = slots.get("when");
            slots.put("when", (today ? "today" : "tomorrow") + at.substring(at.indexOf(' ')));
            slots.replaceAll((key, value) -> value.toLowerCase(Locale.ROOT));
            Run run = runs.get(row.id());
            if (run != null) {
                out.add(new Topic("now:" + row.id(), "event.now." + entry.id(), "", slots, 0L, run.endsAtMs));
            } else if (PLANNED.equals(row.state())) {
                out.add(new Topic("soon:" + row.id(), "event.soon." + entry.id(), "", slots, row.startsAtMs() - TALK_MS,
                        row.startsAtMs()));
            }
        }
        aftermath.removeIf(topic -> now >= topic.untilMs());
        out.addAll(aftermath);
        topics = List.copyOf(out);
        saidAt.values().removeIf(said -> now - said >= TALK_GAP_MS);
    }

    /** Citizens of {@code region} speak of how an event went ({@code trigger}) until {@code untilMs}. */
    synchronized void aftermath(String trigger, String region, Map<String, String> slots, long untilMs) {
        aftermath.add(new Topic(trigger + ":" + untilMs, trigger, region, slots, 0L, untilMs));
    }

    /**
     * The chatter tick's idle line of a citizen in town with a player on the map: an event of the next day, one running
     * now or how one went, each at most once a town map an hour; true when it said one.
     */
    public boolean talk(CitizenSession session, long now) {
        List<Topic> current = topics;
        if (current.isEmpty() || !on() || !population.fields().isTown(session.mapId)) {
            return false;
        }
        Character agent = session.agent();
        if (agent == null || agent.getMap() == null || humansOn(agent.getMap()).isEmpty()) {
            return false;
        }
        for (Topic topic : current) {
            String key = session.mapId + ":" + topic.key();
            Long said = saidAt.get(key);
            if (now < topic.fromMs() || now >= topic.untilMs() || said != null && now - said < TALK_GAP_MS
                    || !topic.region().isEmpty() && !topic.region().equals(session.citizen.homeRegion())) {
                continue;
            }
            ChatterEngine.Event event = ChatterEngine.Event.of(topic.trigger(), session.mapId, session.channel).self(session);
            topic.slots().forEach(event::slot);
            if (population.chatter().speakTo(event)) {
                saidAt.put(key, now);
                return true;
            }
            return false; // its cooldowns or the map's budget: not now
        }
        return false;
    }

    /** A citizen says a line of {@code trigger} the moment needs (a wave, a thanks). */
    void say(CitizenSession session, String trigger, Map<String, String> slots) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session);
        slots.forEach(event::slot);
        String line = population.chatter().compose(event);
        if (line != null) {
            population.chatter().say(session, line);
        }
    }

    // ---- prizes ----------------------------------------------------------------------------------------------------

    /** Fame and one item of the fixed table to a player, or to a bot on its own tick (decision D13: never meso). */
    void award(Character chr, EventCatalog.Prizes prizes, String why) {
        if (chr == null || chr.getClient() == null) {
            return;
        }
        EventCatalog.Prize prize = prizes.items().isEmpty() ? null
                : prizes.items().get(random.nextInt(prizes.items().size()));
        Runnable give = () -> {
            if (prizes.fame() > 0) {
                chr.gainFame(prizes.fame());
            }
            if (prize != null) {
                if (InventoryManipulator.checkSpace(chr.getClient(), prize.item(), prize.count(), "")) {
                    InventoryManipulator.addById(chr.getClient(), prize.item(), (short) prize.count());
                } else if (chr.getMap() != null && !AgentPresence.isAgent(chr)) { // a full bag: at their feet
                    chr.getMap().spawnItemDrop(chr, chr, new Item(prize.item(), (short) 0, (short) prize.count()),
                            chr.getPosition(), true, true);
                }
            }
            chr.dropMessage(6, "[World Event] " + why + (prize == null ? "" : " You received "
                    + (prize.count() > 1 ? prize.count() + " " : "") + itemName(prize.item()) + "."));
        };
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(chr.getId());
        if (entry != null && AgentForeignWriteRuntime.foreign(entry)) {
            AgentForeignWriteRuntime.defer(entry, "world-event-" + deferred.incrementAndGet(), owner -> give.run());
        } else {
            give.run();
        }
    }

    static String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "a prize" : name;
    }

    // ---- the world -------------------------------------------------------------------------------------------------

    static List<Character> humansOn(MapleMap map) {
        List<Character> out = new ArrayList<>(2);
        for (Character chr : map.getAllPlayers()) {
            if (chr != null && !AgentPresence.isAgent(chr) && !chr.isHidden()) {
                out.add(chr);
            }
        }
        return out;
    }

    /** The character with this id online anywhere, or null. */
    Character online(int characterId) {
        World world = population.gameWorld();
        return world == null || world.getPlayerStorage() == null ? null : world.getPlayerStorage().getCharacterById(characterId);
    }

    /** The citizens' channel with the most players on it now; the first when nobody is on. */
    private int busiestChannel() {
        Map<Integer, Integer> humans = new HashMap<>();
        World world = population.gameWorld();
        if (world != null && world.getPlayerStorage() != null) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!AgentPresence.isAgent(chr) && chr.getClient() != null) {
                    humans.merge(chr.getClient().getChannel(), 1, Integer::sum);
                }
            }
        }
        return population.channels().stream().max(Comparator.comparingInt((Integer channel) -> humans.getOrDefault(channel, 0))
                .thenComparing(Comparator.reverseOrder())).orElse(1);
    }

    private static void broadcast(String text) {
        try {
            for (World world : Server.getInstance().getWorlds()) {
                Server.getInstance().broadcastMessage(world.getId(), PacketCreator.serverNotice(6, text));
            }
        } catch (RuntimeException failure) {
            log.debug("world event notice failed: {}", text, failure);
        }
    }

    /** A loaded, unhired citizen alive, in no party or event, that nothing holds: an event may send it somewhere. */
    boolean free(CitizenSession session) {
        Citizen citizen = session == null ? null : population.citizen(session.characterId);
        Character agent = session == null || !session.loaded() ? null : session.agent();
        return agent != null && citizen != null && !citizen.hired() && agent.isAlive() && agent.getMap() != null
                && agent.getEventInstance() == null && agent.getParty() == null && !population.presence().held(session);
    }

    // ---- GM (!worldevent) --------------------------------------------------------------------------------------------

    /** {@code !worldevent list}: the calendar, its rows this week and next, and the busiest hours it plans from. */
    public synchronized List<String> list(long now) {
        List<String> out = new ArrayList<>();
        out.add("World events " + (on() ? "on" : "off (population.WORLD_EVENTS_ENABLED)") + ", " + runs.size() + " running.");
        for (EventCatalog.Entry entry : catalog().entries()) {
            out.add(entry.id() + ": " + entry.cadence().name().toLowerCase(Locale.ROOT) + ", " + entry.minutes() + " min, "
                    + (on(entry.kind()) ? "on" : "off"));
        }
        List<Occurrence> rows = store.between(mondayMs(weekOf(now)), mondayMs(weekOf(now)) + 2 * WEEK_MS);
        for (Occurrence row : rows == null ? List.<Occurrence>of() : rows) {
            out.add(row.event() + " #" + row.number() + " " + Instant.ofEpochMilli(row.startsAtMs()).atZone(zone).toLocalDateTime()
                    + " " + row.state() + (row.site().isEmpty() ? "" : " " + row.site())
                    + (row.channel() > 0 ? " ch" + row.channel() : "") + (row.result().isEmpty() ? "" : ": " + row.result()));
        }
        Map<Long, Double> samples = store.samples(SAMPLE_DAYS);
        if (samples != null) {
            HourPicker.Load load = HourPicker.load(samples, zone);
            List<Integer> slots = new ArrayList<>();
            for (int slot = 0; slot < HourPicker.SLOTS; slot++) {
                slots.add(slot);
            }
            slots.sort(Comparator.comparingDouble(slot -> -load.at(slot)));
            StringBuilder busiest = new StringBuilder("Busiest hours (" + load.days() + " days sampled):");
            for (int slot : slots.subList(0, 5)) {
                busiest.append(' ').append(DayOfWeek.of(slot / 24 + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                        .append(' ').append(slot % 24).append(":00=")
                        .append(String.format(Locale.ROOT, "%.2f", load.at(slot)));
            }
            out.add(busiest.toString());
        }
        return out;
    }

    /** {@code !worldevent start <id> [site]}: an occurrence of its own now, numbered below every planned one. */
    public synchronized String start(String id, String site, long now) {
        EventCatalog.Entry entry = catalog().entry(id);
        if (entry == null) {
            return "No event " + id + ".";
        }
        if (!on(entry.kind())) {
            return id + " is off (population.WORLD_EVENTS_ENABLED and its own switch).";
        }
        String where = site == null ? site(entry, weekOf(now), now) : site;
        int number = -(int) (now / 1000L);
        if (!store.insert(entry.id(), number, now, now + entry.durationMs(), 0, where)) {
            return "It could not be written.";
        }
        List<Occurrence> open = store.open();
        Occurrence row = open == null ? null : open.stream().filter(one -> one.event().equals(entry.id())
                && one.number() == number).findFirst().orElse(null);
        if (row == null) {
            return "It could not be read back.";
        }
        if (!start(row, entry, now)) {
            store.finish(row.id(), CANCELLED, "could not start");
            return entry.id() + " could not start (one of its kind is running, or its place is unknown).";
        }
        return title(row, entry) + " started on channel " + runs.get(row.id()).channel + " for " + entry.minutes() + " minutes.";
    }

    /** {@code !worldevent stop <id>}: ends a running event with no result. */
    public synchronized String stop(String id) {
        for (Run run : new ArrayList<>(runs.values())) {
            if (run.entry.id().equalsIgnoreCase(id)) {
                stop(run, CANCELLED, "stopped by a GM");
                return run.entry.id() + " stopped.";
            }
        }
        return id + " is not running.";
    }

    /** Tests: the runs now. */
    synchronized List<Run> runs() {
        return new ArrayList<>(runs.values());
    }
}
