package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenStore.MemoryRow;
import server.living.population.CitizenStore.MemoryWrite;
import server.living.population.PersonMemory.Episode;
import server.living.population.PersonMemory.Greeting;
import server.living.population.PersonMemory.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * The citizens' memories of the people they meet in use (docs/CITIZEN_PERSON_MEMORY_PLAN.md): a cache of the
 * (citizen, player) pairs together this session or touched by an event. Events from any thread ({@link #record}) only
 * change the cache; an event for a pair not loaded yet waits for its row. The social tick, every minute, loads the
 * pairs it meets ({@link #seen}, one read the first time in a citizen's session, before the shared minutes move
 * {@code last_at}), greets a returning player, and writes what changed in one batch ({@link #flush}); a citizen
 * logging off takes its memories with it ({@link #release}). Hot paths read the cache only ({@link #peek}).
 */
public final class PersonMemories {
    private static final Logger log = LoggerFactory.getLogger(PersonMemories.class);
    /** Pairs held at once; clean pairs of citizens no longer online go first, then an event that finds no room is lost. */
    static final int CACHE_MAX = 4096;
    /** A pair not seen together this long, with nothing to write, leaves the cache (its row has it all). */
    static final long IDLE_MS = 30 * 60_000L;
    /** A reunion noticed out of earshot may still be greeted this long, while the visit lasts. */
    static final long REUNION_WINDOW_MS = 10 * 60_000L;
    static final long PRUNE_EVERY_MS = 3_600_000L;
    /** Earshot for a reunion greeting on map chat (the conversation's NEAR_PX). */
    static final int GREET_PX = 700;
    /** The grounding line's length (DialogueContextSnapshot.MAX_FACT_CHARS). */
    static final int RECALL_CHARS = 256;

    /** One pair: its memory once loaded, the changes waiting for the load, and its visit bookkeeping. */
    static final class Entry {
        final int citizenId;
        final int playerId;
        PersonMemory memory;
        /** The row holds a memory (memory_at set): a pair new to it counts against MEMORY_PEOPLE_MAX on its first write. */
        boolean stored;
        boolean dirty;
        /** When they were last together: the row's last_at at the load, then every minute together. */
        long lastTogetherMs;
        /** A reunion is waiting for its greeting until then (0: none). */
        long reunionUntilMs;
        /** Out of the cache ({@link #drop}): whoever still holds it takes the pair's new entry instead. */
        boolean gone;
        final List<UnaryOperator<PersonMemory>> pending = new ArrayList<>();

        Entry(int citizenId, int playerId) {
            this.citizenId = citizenId;
            this.playerId = playerId;
        }

        boolean loaded() {
            return memory != null;
        }
    }

    private final Population population;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    /** Player id -> when a citizen last greeted them on meeting again. */
    private final Map<Integer, Long> greetedAt = new ConcurrentHashMap<>();
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong greetings = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private volatile long lastPruneMs;
    private volatile int lastPruned;

    PersonMemories(Population population) {
        this.population = population;
        this.lastPruneMs = System.currentTimeMillis();
    }

    public boolean on() {
        return population.config().MEMORY_ENABLED;
    }

    private PopulationConfig config() {
        return population.config();
    }

    static long key(int citizenId, int playerId) {
        return (long) citizenId << 32 | (playerId & 0xffffffffL);
    }

    // ---- events (any thread, memory only) ----------------------------------------------------

    /** Something happened between this citizen and this player at {@code mapId} (0: no place); memory only. */
    public void record(int citizenId, int playerId, Kind kind, int mapId, String detail, int count) {
        if (!on() || citizenId <= 0 || playerId <= 0 || citizenId == playerId) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        PopulationConfig config = config();
        UnaryOperator<PersonMemory> change = memory -> memory.with(kind, mapId, detail, count, nowMs, config);
        for (int attempt = 0; attempt < 3; attempt++) {
            Entry entry = entry(citizenId, playerId);
            if (entry == null) {
                break;
            }
            synchronized (entry) {
                if (entry.gone) {
                    continue; // let go between the lookup and the lock: the next lookup makes a new one
                }
                if (entry.loaded()) {
                    entry.memory = change.apply(entry.memory);
                    entry.dirty = true;
                } else {
                    entry.pending.add(change);
                }
            }
            recorded.incrementAndGet();
            return;
        }
        lost.incrementAndGet();
        log.debug("person memory cache full: {} of citizen {} with {} not kept", kind, citizenId, playerId);
    }

    /** Takes the entry out of the cache; the caller holds its lock. */
    private void drop(Entry entry) {
        entry.gone = true;
        cache.remove(key(entry.citizenId, entry.playerId), entry);
    }

    /** The pair's entry, made when there is room; null when the cache is full of pairs in use. */
    private Entry entry(int citizenId, int playerId) {
        long key = key(citizenId, playerId);
        Entry entry = cache.get(key);
        if (entry != null) {
            return entry;
        }
        if (cache.size() >= CACHE_MAX) {
            evict(System.currentTimeMillis(), true);
            if (cache.size() >= CACHE_MAX) {
                return null;
            }
        }
        return cache.computeIfAbsent(key, k -> new Entry(citizenId, playerId));
    }

    /** What the citizen remembers of the player as it stands in the cache; null when not loaded (or memory is off). */
    public PersonMemory peek(int citizenId, int playerId) {
        if (!on()) {
            return null;
        }
        Entry entry = cache.get(key(citizenId, playerId));
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            return entry.memory;
        }
    }

    /** How warm the citizen feels toward the player now: the cache, else its row (one read); 0 when nothing. */
    public int warmth(int citizenId, int playerId) {
        if (!on()) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        PersonMemory memory = peek(citizenId, playerId);
        if (memory == null) {
            MemoryRow row = population.store().memory(citizenId, playerId);
            memory = row == null ? PersonMemory.none() : row.memory();
        }
        return memory.feltWarmth(nowMs, config());
    }

    // ---- the social tick ------------------------------------------------------------------

    /**
     * The minute's sighting of {@code human} on the citizen's map (SocialFabric, before the shared minutes move
     * {@code last_at}): loads the pair the first time, marks a reunion when they were apart at least
     * MEMORY_RETURN_AFTER_MINUTES, and greets them if one is waiting and the citizen can. Then they are together now.
     */
    void seen(CitizenSession session, Character human, long nowMs) {
        if (!on()) {
            return;
        }
        Entry entry = entry(session.characterId, human.getId());
        if (entry == null || !load(entry)) {
            return;
        }
        PersonMemory memory;
        synchronized (entry) {
            if (entry.gone) {
                return; // let go meanwhile: the next minute's sighting loads it again
            }
            long apart = entry.lastTogetherMs <= 0L ? 0L : nowMs - entry.lastTogetherMs;
            if (apart >= config().MEMORY_RETURN_AFTER_MINUTES * 60_000L && apart > 0L) {
                entry.reunionUntilMs = nowMs + REUNION_WINDOW_MS;
            }
            entry.lastTogetherMs = nowMs;
            memory = entry.reunionUntilMs > nowMs ? entry.memory : null;
        }
        if (memory != null && greet(session, human, memory, nowMs)) {
            synchronized (entry) {
                entry.reunionUntilMs = 0L;
            }
        }
    }

    /**
     * Loads the pair once: reads its row (without the entry's lock, so an event's thread never waits on the database)
     * and applies what waited for it. True when it is loaded; false when the read failed (tried again later).
     */
    private boolean load(Entry entry) {
        synchronized (entry) {
            if (entry.loaded()) {
                return true;
            }
        }
        MemoryRow row = population.store().memory(entry.citizenId, entry.playerId);
        if (row == null) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        synchronized (entry) {
            if (entry.loaded()) {
                return true; // another tick loaded it meanwhile
            }
            PersonMemory memory = row.memory().faded(nowMs, config());
            entry.stored = !row.memory().isEmpty();
            entry.dirty = memory != row.memory();
            for (UnaryOperator<PersonMemory> change : entry.pending) {
                memory = change.apply(memory);
                entry.dirty = true;
            }
            entry.pending.clear();
            entry.memory = memory;
            entry.lastTogetherMs = Math.max(entry.lastTogetherMs, row.lastTogetherAtMs());
        }
        return true;
    }

    /**
     * The reunion greeting, if this one is worth it (salience {@link PersonMemory#GREET_SALIENCE}), the citizen is free
     * and within earshot, and no citizen greeted them in the last MEMORY_GREET_GAP_MS. True when it was said (or will
     * never be: the memory is not worth a greeting), false to try again within the visit.
     */
    boolean greet(CitizenSession session, Character human, PersonMemory memory, long nowMs) {
        PopulationConfig config = config();
        if (memory.salience(nowMs, config) < PersonMemory.GREET_SALIENCE) {
            return true;
        }
        Character agent = session.agent();
        Long last = greetedAt.get(human.getId());
        if (agent == null || agent.getMap() == null || agent.getEventInstance() != null || !Conversation.free(session, nowMs)
                || last != null && nowMs - last < config.MEMORY_GREET_GAP_MS
                || agent.getPosition().distance(human.getPosition()) > GREET_PX) {
            return false;
        }
        Greeting greeting = memory.greeting(nowMs, config);
        ChatterEngine.Event event = ChatterEngine.Event.of(greeting.trigger(), session.mapId, session.channel).self(session)
                .subject(human);
        slots(event, greeting.episode(), nowMs);
        if (!population.chatter().speakTo(event)) {
            return false;
        }
        greetedAt.put(human.getId(), nowMs);
        greetings.incrementAndGet();
        session.journal.add("social", "ran into " + human.getName() + " again");
        log.info("citizen {} greeted {} again ({}; warmth {})", session.name(), human.getName(), greeting.trigger(),
                memory.feltWarmth(nowMs, config));
        return true;
    }

    /** The answer to a remembered player's "hi" ({@code memory.hello}); false when it barely knows them or said nothing. */
    boolean hello(CitizenSession session, Character human, long nowMs) {
        PersonMemory memory = peek(session.characterId, human.getId());
        String trigger = memory == null ? null : memory.hello(nowMs, config());
        if (trigger == null) {
            return false;
        }
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session).subject(human);
        slots(event, memory.strongest(nowMs, config()), nowMs);
        return population.chatter().speakTo(event);
    }

    /** {when}, {place}, {pq}, {item} and {what} of the episode a line brings up. */
    private void slots(ChatterEngine.Event event, Episode episode, long nowMs) {
        if (episode == null) {
            return;
        }
        event.slot("when", PersonMemory.when(nowMs - episode.atMs()));
        if (episode.mapId() > 0) {
            event.slot("place", population.fields().name(episode.mapId()).toLowerCase(Locale.ROOT));
        }
        if (!episode.detail().isEmpty()) {
            event.slot("pq", episode.detail().toUpperCase(Locale.ROOT)).slot("what", episode.detail()).item(episode.detail());
        }
    }

    /** The model's grounding line about the player, or null when it remembers nothing of them. */
    String recall(int citizenId, int playerId, long nowMs) {
        PersonMemory memory = peek(citizenId, playerId);
        if (memory == null || memory.isEmpty()) {
            return null;
        }
        return memory.recall(nowMs, config(), mapId -> population.fields().name(mapId).toLowerCase(Locale.ROOT), RECALL_CHARS);
    }

    /**
     * End of the social tick: loads the pairs events touched, writes every changed memory in one batch (a citizen that
     * starts remembering someone past MEMORY_PEOPLE_MAX forgets its least memorable first), lets idle pairs go, and once
     * an hour prunes the table.
     */
    void flush(long nowMs) {
        if (!on()) {
            return;
        }
        write(new ArrayList<>(cache.values()));
        evict(nowMs, false);
        if (nowMs - lastPruneMs >= PRUNE_EVERY_MS) {
            lastPruneMs = nowMs;
            prune(nowMs);
        }
    }

    /** A citizen logs off: its memories are written and leave the cache. */
    void release(int citizenId) {
        if (!on()) {
            return;
        }
        List<Entry> its = new ArrayList<>();
        for (Entry entry : cache.values()) {
            if (entry.citizenId == citizenId) {
                its.add(entry);
            }
        }
        write(its);
        for (Entry entry : its) {
            synchronized (entry) {
                if (!entry.dirty && entry.pending.isEmpty()) {
                    drop(entry);
                }
            }
        }
    }

    /** Server shutdown, before the citizens unload: everything changed is written. */
    void flushAll() {
        if (on()) {
            write(new ArrayList<>(cache.values()));
        }
    }

    /** A citizen retired: it forgets everyone. */
    void forgetCitizen(int citizenId) {
        for (Entry entry : new ArrayList<>(cache.values())) {
            if (entry.citizenId == citizenId) {
                synchronized (entry) {
                    drop(entry);
                }
            }
        }
        if (on()) {
            population.store().forgetMemories(citizenId, null);
        }
    }

    private void write(List<Entry> entries) {
        long nowMs = System.currentTimeMillis();
        List<MemoryWrite> writes = new ArrayList<>();
        List<Entry> writing = new ArrayList<>();
        Map<Integer, Set<Integer>> newPeople = new HashMap<>();
        Map<Integer, Set<Integer>> inBatch = new HashMap<>();
        for (Entry entry : entries) {
            boolean waiting;
            synchronized (entry) {
                waiting = !entry.loaded() && !entry.pending.isEmpty();
            }
            if (waiting && !load(entry)) {
                continue;
            }
            synchronized (entry) {
                if (!entry.dirty || entry.gone) {
                    continue;
                }
                entry.dirty = false;
                writes.add(new MemoryWrite(entry.citizenId, entry.playerId, entry.memory));
                writing.add(entry);
                inBatch.computeIfAbsent(entry.citizenId, id -> new HashSet<>()).add(entry.playerId);
                if (!entry.stored && !entry.memory.isEmpty()) {
                    newPeople.computeIfAbsent(entry.citizenId, id -> new HashSet<>()).add(entry.playerId);
                }
            }
        }
        newPeople.forEach((citizenId, playerIds) -> makeRoom(citizenId, playerIds, inBatch.get(citizenId), nowMs));
        if (writes.isEmpty()) {
            return;
        }
        if (population.store().saveMemories(writes)) {
            written.addAndGet(writes.size());
            for (int i = 0; i < writing.size(); i++) {
                Entry entry = writing.get(i);
                synchronized (entry) {
                    entry.stored = !writes.get(i).memory().isEmpty();
                }
            }
        } else {
            for (Entry entry : writing) {
                synchronized (entry) {
                    entry.dirty = true; // the next tick tries again
                }
            }
        }
    }

    /**
     * A citizen starts remembering {@code arriving}: past MEMORY_PEOPLE_MAX with them, it forgets the least memorable of
     * the others (salience now, the cache's copy where it has one) in its row and in the cache. Someone whose memory
     * goes out in this same batch ({@code inBatch}) is in its company now and is not forgotten for it.
     */
    private void makeRoom(int citizenId, Set<Integer> arriving, Set<Integer> inBatch, long nowMs) {
        List<MemoryRow> rows = population.store().memories(citizenId);
        if (rows == null) {
            return;
        }
        PopulationConfig config = config();
        Map<Integer, PersonMemory> others = new HashMap<>();
        for (MemoryRow row : rows) {
            if (!arriving.contains(row.playerId())) {
                others.put(row.playerId(), row.memory());
            }
        }
        for (Entry entry : cache.values()) {
            if (entry.citizenId == citizenId && !arriving.contains(entry.playerId) && others.containsKey(entry.playerId)) {
                PersonMemory cached = peek(citizenId, entry.playerId);
                if (cached != null) {
                    others.put(entry.playerId, cached);
                }
            }
        }
        int over = others.size() + arriving.size() - config.MEMORY_PEOPLE_MAX;
        if (over <= 0) {
            return;
        }
        List<Integer> leastFirst = new ArrayList<>(others.keySet());
        leastFirst.removeIf(playerId -> inBatch != null && inBatch.contains(playerId));
        leastFirst.sort(Comparator.comparingDouble(playerId -> others.get(playerId).salience(nowMs, config)));
        List<Integer> forget = leastFirst.subList(0, Math.min(over, leastFirst.size()));
        if (forget.isEmpty()) {
            return; // everyone it remembers is in its company this minute: the cap waits for the next newcomer
        }
        population.store().forgetMemories(citizenId, forget);
        for (int playerId : forget) {
            Entry entry = cache.get(key(citizenId, playerId));
            if (entry != null) {
                synchronized (entry) {
                    if (entry.loaded()) {
                        entry.memory = PersonMemory.none();
                        entry.stored = false;
                        entry.dirty = false;
                    }
                }
            }
        }
        log.info("citizen {} forgot {} of the people it remembered to remember {} more", citizenId, forget.size(), arriving.size());
    }

    /** Lets idle clean pairs go (all clean pairs of citizens no longer online when {@code full}). */
    private void evict(long nowMs, boolean full) {
        for (Entry entry : new ArrayList<>(cache.values())) {
            synchronized (entry) {
                if (entry.dirty || !entry.pending.isEmpty()) {
                    continue;
                }
                boolean online = population.presence().session(entry.citizenId) != null;
                if (!online || nowMs - entry.lastTogetherMs >= IDLE_MS || full && !entry.loaded()) {
                    drop(entry);
                }
            }
        }
        greetedAt.values().removeIf(at -> nowMs - at >= Math.max(config().MEMORY_GREET_GAP_MS, IDLE_MS));
    }

    private void prune(long nowMs) {
        PopulationConfig config = config();
        int forgotten = population.store().pruneMemories(nowMs - config.MEMORY_FORGET_DAYS * 86_400_000L, config.MEMORY_TOTAL_MAX);
        lastPruned = forgotten;
        if (forgotten > 0) {
            log.info("person memory prune: {} memories forgotten (unchanged for {} days, or past {} remembered pairs)",
                    forgotten, config.MEMORY_FORGET_DAYS, config.MEMORY_TOTAL_MAX);
        }
    }

    // ---- ops ----------------------------------------------------------------------------

    /** One line for !citizens memory. */
    public String report() {
        int dirty = 0;
        for (Entry entry : cache.values()) {
            dirty += entry.dirty || !entry.pending.isEmpty() ? 1 : 0;
        }
        return "memory: " + (on() ? "on" : "off") + " cached=" + cache.size() + " unwritten=" + dirty + " remembered="
                + population.store().rememberedPairs() + " recorded=" + recorded.get() + " written=" + written.get()
                + " greetings=" + greetings.get() + " lost=" + lost.get() + " lastPrune=" + lastPruned;
    }

    /** What this citizen remembers, one line a person, most memorable first (the cache's copy where it has one). */
    public List<String> describe(int citizenId) {
        long nowMs = System.currentTimeMillis();
        PopulationConfig config = config();
        Map<Integer, PersonMemory> people = new HashMap<>();
        List<MemoryRow> rows = population.store().memories(citizenId);
        if (rows != null) {
            rows.forEach(row -> people.put(row.playerId(), row.memory()));
        }
        for (Entry entry : cache.values()) {
            PersonMemory cached = entry.citizenId == citizenId ? peek(citizenId, entry.playerId) : null;
            if (cached != null && !cached.isEmpty()) {
                people.put(entry.playerId, cached);
            }
        }
        List<Integer> order = new ArrayList<>(people.keySet());
        order.sort(Comparator.comparingDouble((Integer playerId) -> people.get(playerId).salience(nowMs, config)).reversed());
        List<String> out = new ArrayList<>();
        for (int playerId : order) {
            PersonMemory memory = people.get(playerId);
            String name = Character.getNameById(playerId);
            out.add((name == null ? "#" + playerId : name) + ": warmth " + memory.feltWarmth(nowMs, config) + " (stored "
                    + memory.warmth() + "), " + memory.recall(nowMs, config,
                    mapId -> population.fields().name(mapId).toLowerCase(Locale.ROOT), 400));
        }
        return out;
    }
}
