package server.living.population.records;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.integration.AgentPresence;
import server.living.population.Citizen;
import server.living.population.Population;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clear records (docs/BOT_CONCEPT_PLAN.md F7.1): every cleared event instance of a quest {@code rivals.yaml} lists, by
 * anyone (players, their companions, citizens), becomes a living_clear row with its members, start and duration
 * (EventInstanceManager.clearPQ through LivingWorldHooks.eventCleared: a party quest's end or a boss's death). Measuring
 * only, behind population.CLEAR_RECORDS_ENABLED (on by default). In memory it keeps what the records page shows - each
 * quest's first and fastest clear, each player's best, each rival crew's best - loaded once from the table and kept
 * current by every clear. The rival crew hears of each clear after it is booked ({@link Listener}).
 */
public final class ClearLog {
    private static final Logger log = LoggerFactory.getLogger(ClearLog.class);
    /** Below this a clear has no believable time (a start never seen): it is booked untimed. */
    private static final long MIN_DURATION_MS = 1_000L;
    private static final int RECENT = 20;

    /** What the log asks of the world; tests bring their own. */
    public interface Ports {
        /** {@link Clear#AS_PLAYER}, {@link Clear#AS_COMPANION}, {@link Clear#AS_CITIZEN} or {@link Clear#AS_BOT}. */
        char kindOf(Character member);

        /** Runs {@code work} off the caller's thread (the event script's), in order. */
        void later(String what, Runnable work);
    }

    /** Who hears of a booked clear (the rival crews): the crew's and each player's best before it. */
    public interface Listener {
        int creditFor(Set<Integer> memberIds);

        void cleared(Clear clear, long crewBestBefore, Map<Integer, Long> playerBestsBefore);
    }

    private final Population population;
    private final ClearStore store;
    private final Ports ports;
    private volatile QuestLabels labels;
    private volatile Listener listener;
    private final Map<String, Clear> firsts = new HashMap<>();
    private final Map<String, Clear> fastest = new HashMap<>();
    private final Map<Integer, Map<String, Long>> playerBests = new HashMap<>();
    private final Map<Integer, Map<String, Long>> crewBests = new HashMap<>();
    private final Deque<Clear> recent = new ArrayDeque<>();
    private boolean loaded;

    public ClearLog(Population population) {
        this(population, new ClearStore(), null, null);
    }

    ClearLog(Population population, ClearStore store, Ports ports, QuestLabels labels) {
        this.population = population;
        this.store = store;
        this.ports = ports == null ? new LivePorts(population) : ports;
        this.labels = labels;
    }

    public boolean on() {
        return population.config().CLEAR_RECORDS_ENABLED;
    }

    public void listen(Listener crews) {
        this.listener = crews;
    }

    public QuestLabels labels() {
        QuestLabels current = labels;
        if (current == null) {
            current = QuestLabels.load(Path.of(population.config().CONTENT_DIR));
            labels = current;
        }
        return current;
    }

    /** Content reload (!citizens reload): the labels are read again. */
    public void reload() {
        labels = null;
    }

    // ---- recording ---------------------------------------------------------------------------

    /**
     * Event instance {@code instance} of {@code script} was cleared by {@code players} (on the script's thread, once an
     * instance). {@code startedAtMs} is its first event timer, 0 when unknown. Reads memory only here; the row is
     * written, the caches moved and the crews told on the living executor.
     */
    public void cleared(List<Character> players, String script, String instance, long startedAtMs, long nowMs) {
        if (!on() || players == null || players.isEmpty() || !labels().recorded(script)) {
            return;
        }
        List<Clear.Member> members = new ArrayList<>();
        int channel = 0;
        int mapId = 0;
        for (Character chr : players) {
            if (chr == null) {
                continue;
            }
            if (mapId == 0) {
                mapId = chr.getMapId();
                channel = chr.getClient() == null ? 0 : chr.getClient().getChannel();
            }
            members.add(new Clear.Member(chr.getId(), ports.kindOf(chr), chr.getName()));
        }
        if (members.isEmpty()) {
            return;
        }
        long duration = startedAtMs > 0L && nowMs - startedAtMs >= MIN_DURATION_MS ? nowMs - startedAtMs : 0L;
        Clear unbooked = new Clear(0, script, instance, channel, mapId, duration > 0L ? startedAtMs : 0L, nowMs, duration,
                Clear.kindOf(members), 0, members);
        ports.later("records.clear " + script, () -> book(unbooked));
    }

    /** On the living executor: the crew it counts for, the row, the caches, then the crews hear of it. */
    void book(Clear unbooked) {
        try {
            load();
            Listener crews = listener;
            Set<Integer> ids = new java.util.HashSet<>();
            unbooked.members().forEach(member -> ids.add(member.id()));
            int crewId = crews == null ? 0 : crews.creditFor(ids);
            Clear clear = new Clear(0, unbooked.quest(), unbooked.instance(), unbooked.channel(), unbooked.mapId(),
                    unbooked.startedAtMs(), unbooked.clearedAtMs(), unbooked.durationMs(), unbooked.kind(), crewId,
                    unbooked.members());
            clear = clear.withId(store.insert(clear));
            long crewBefore;
            Map<Integer, Long> playersBefore = new LinkedHashMap<>();
            synchronized (this) {
                crewBefore = best(crewBests, crewId, clear.quest());
                for (Clear.Member member : clear.members()) {
                    if (member.human()) {
                        playersBefore.put(member.id(), best(playerBests, member.id(), clear.quest()));
                    }
                }
                merge(clear);
                recent.addFirst(clear);
                while (recent.size() > RECENT) {
                    recent.removeLast();
                }
            }
            log.info("clear booked: quest={} kind={} time={} crew={} members={}", clear.quest(), clear.kind(),
                    clear.timed() ? time(clear.durationMs()) : "-", crewId, names(clear, 8));
            if (crews != null) {
                crews.cleared(clear, crewBefore, playersBefore);
            }
        } catch (RuntimeException failure) {
            log.warn("clear of {} could not be booked", unbooked.quest(), failure);
        }
    }

    /** The table's records into memory, once (at the first clear or the first look). */
    synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (Clear clear : store.records()) {
            merge(clear);
        }
        store.playerBests().forEach((id, bests) -> bests.forEach((quest, ms) -> keepBest(playerBests, id, quest, ms)));
        store.crewBests().forEach((id, bests) -> bests.forEach((quest, ms) -> keepBest(crewBests, id, quest, ms)));
        log.info("clear records: {} quests with a first clear, {} players and {} crews with a best", firsts.size(),
                playerBests.size(), crewBests.size());
    }

    private void merge(Clear clear) {
        Clear first = firsts.get(clear.quest());
        if (first == null || clear.clearedAtMs() < first.clearedAtMs()
                || clear.clearedAtMs() == first.clearedAtMs() && clear.id() < first.id()) {
            firsts.put(clear.quest(), clear);
        }
        if (clear.timed()) {
            Clear best = fastest.get(clear.quest());
            if (best == null || clear.durationMs() < best.durationMs()
                    || clear.durationMs() == best.durationMs() && clear.clearedAtMs() < best.clearedAtMs()) {
                fastest.put(clear.quest(), clear);
            }
            for (Clear.Member member : clear.members()) {
                if (member.human()) {
                    keepBest(playerBests, member.id(), clear.quest(), clear.durationMs());
                }
            }
            if (clear.crewId() > 0) {
                keepBest(crewBests, clear.crewId(), clear.quest(), clear.durationMs());
            }
        }
    }

    private static void keepBest(Map<Integer, Map<String, Long>> bests, int id, String quest, long ms) {
        if (ms > 0L) {
            bests.computeIfAbsent(id, key -> new HashMap<>()).merge(quest, ms, Math::min);
        }
    }

    private static long best(Map<Integer, Map<String, Long>> bests, int id, String quest) {
        Map<String, Long> one = id <= 0 ? null : bests.get(id);
        Long ms = one == null ? null : one.get(quest);
        return ms == null ? 0L : ms;
    }

    // ---- views -------------------------------------------------------------------------------

    public synchronized Clear first(String quest) {
        return firsts.get(quest);
    }

    public synchronized Clear fastest(String quest) {
        return fastest.get(quest);
    }

    /** The player's fastest timed clear of the quest in ms; 0 for none. */
    public synchronized long playerBest(int characterId, String quest) {
        return best(playerBests, characterId, quest);
    }

    /** The crew's fastest timed clear of the quest in ms; 0 for none. */
    public synchronized long crewBest(int crewId, String quest) {
        return best(crewBests, crewId, quest);
    }

    /** Quest -> the crew's best, for !rivals. */
    public synchronized Map<String, Long> crewBests(int crewId) {
        Map<String, Long> one = crewBests.get(crewId);
        return one == null ? Map.of() : Map.copyOf(one);
    }

    /** The listed quests that have a clear, in rivals.yaml order. */
    public synchronized List<String> questsWithRecords() {
        List<String> out = new ArrayList<>();
        for (QuestLabels.Quest quest : labels().all()) {
            if (firsts.containsKey(quest.script())) {
                out.add(quest.script());
            }
        }
        return out;
    }

    public synchronized List<Clear> recent() {
        return new ArrayList<>(recent);
    }

    /** "14:20" (m:ss), "1:02:05" past an hour. */
    public static String time(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds % 60L)
                : String.format("%d:%02d", minutes, seconds % 60L);
    }

    /** "Ann, Bo, Cy +3": up to {@code max} names, players first. */
    public static String names(Clear clear, int max) {
        List<Clear.Member> sorted = new ArrayList<>(clear.members());
        sorted.sort((a, b) -> Boolean.compare(b.human(), a.human()));
        List<String> names = new ArrayList<>();
        for (Clear.Member member : sorted.subList(0, Math.min(max, sorted.size()))) {
            names.add(member.name());
        }
        int more = clear.members().size() - names.size();
        return String.join(", ", names) + (more > 0 ? " +" + more : "");
    }

    // ---- the server ---------------------------------------------------------------------------

    private static final class LivePorts implements Ports {
        private final Population population;

        LivePorts(Population population) {
            this.population = population;
        }

        @Override
        public char kindOf(Character member) {
            if (!AgentPresence.isAgent(member)) {
                return Clear.AS_PLAYER;
            }
            Citizen citizen = population.citizen(member.getId());
            if (citizen != null) {
                return citizen.hired() ? Clear.AS_COMPANION : Clear.AS_CITIZEN;
            }
            return population.world().residents().isPersonaCharacter(member.getId()) ? Clear.AS_COMPANION : Clear.AS_BOT;
        }

        @Override
        public void later(String what, Runnable work) {
            population.world().executor().submit(what, work);
        }
    }
}
