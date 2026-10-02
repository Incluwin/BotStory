package server.knowledge;

import config.KnowledgeConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.knowledge.KnownWorld.Past;
import server.knowledge.KnownWorld.Recall;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Every bot's known world (docs/SERVER_KNOWLEDGE_PLAN.md 7.4): opened by its host (citizens, residents) or lazily for
 * companions, fed by the engine hooks (kills, map entries, loot, purchases), by overheard true answers and by gossip, and
 * written behind to {@code living_known_world} every five minutes. Off ({@link #on} false: {@code knowledge.PERSONAL} or
 * {@code ENABLED} off, not started, or no snapshot) every query is null and every event a no-op, so answers are the
 * level horizon's.
 *
 * <p>Events cost one map lookup and a scan of at most 64 ints, so they are safe on Netty and agent threads. Synthesis and
 * the DB load run on {@code LOW_PRIORITY}, the flush on {@code SAVE}; nothing here touches the DB on the caller's thread
 * except {@link #stop}'s last flush.
 */
public final class PersonalKnowledge {
    private static final Logger log = LoggerFactory.getLogger(PersonalKnowledge.class);
    static final long FLUSH_MS = 300_000;
    static final int FLUSH_ROWS = 200;
    /** A lazily opened (companion) world nothing touched for this long is closed at the next flush. */
    static final long IDLE_CLOSE_MS = 1_800_000;
    private static final int GOSSIP_TRIES = 3;
    private static final int GOSSIP_SPOTS = 4;
    private static final int DESCRIBE_SHOWN = 5;
    private static final int WORLD = 0;

    /**
     * How a bot's past is filled in at open.
     *
     * @param regionAt level -> knowledge region key ("victoria", "orbis"; null = anywhere)
     * @param knows    a persona's {@code knows:} names (not "prices", which the host turns into {@code prices})
     */
    public record Spec(Past past, long seed, int level, IntFunction<String> regionAt, IntList homeMaps, List<String> knows,
                       boolean veteran, boolean prices) {
    }

    /** Where worlds are stored ({@link KnownWorldStore}; a fake in tests). */
    interface Store {
        /**
         * The stored row, null when there is none (or the table is missing); throws when the read failed, so a world whose
         * row could not be read is never written over it.
         */
        String load(int characterId);

        /** One batch; false when it failed. */
        boolean save(List<KnownWorldStore.Row> rows);

        void delete(int characterId);
    }

    private static volatile PersonalKnowledge shared;

    private final Supplier<WorldKnowledge> knowledge;
    private final IntFunction<ContentAnswers.Rates> rates;
    private final KnowledgeConfig config;
    private final Store store;
    private final Executor io;
    private final LongSupplier clock;
    private final Map<Integer, Slot> slots = new ConcurrentHashMap<>();
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicLong heard = new AtomicLong();
    private final AtomicLong gossiped = new AtomicLong();
    private volatile boolean started;
    private volatile ScheduledFuture<?> timer;
    private volatile long lastFlushMs;
    private volatile int lastFlushRows;

    PersonalKnowledge(Supplier<WorldKnowledge> knowledge, IntFunction<ContentAnswers.Rates> rates, KnowledgeConfig config,
                      Store store, Executor io, LongSupplier clock, boolean started) {
        this.knowledge = knowledge;
        this.rates = rates;
        this.config = config;
        this.store = store;
        this.io = io;
        this.clock = clock;
        this.started = started;
    }

    /** The server's registry: config.yaml {@code knowledge:}, the published snapshot, live world rates, the DB store. */
    public static PersonalKnowledge shared() {
        PersonalKnowledge instance = shared;
        if (instance == null) {
            synchronized (PersonalKnowledge.class) {
                if (shared == null) {
                    shared = new PersonalKnowledge(WorldKnowledge::current, ContentAnswers.Rates::live, YamlConfig.config.knowledge,
                            new KnownWorldStore(), task -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                            task, 0L), System::currentTimeMillis, false);
                }
                instance = shared;
            }
        }
        return instance;
    }

    /** Tests and the probe: no store, work runs on the calling thread, and it needs no {@link #start}. */
    static PersonalKnowledge inMemory(Supplier<WorldKnowledge> knowledge, IntFunction<ContentAnswers.Rates> rates,
                                      KnowledgeConfig config) {
        return new PersonalKnowledge(knowledge, rates, config, null, Runnable::run, System::currentTimeMillis, true);
    }

    public boolean on() {
        return started && config.ENABLED && config.PERSONAL && knowledge.get().ready();
    }

    /** LivingWorld.start: the write-behind every five minutes on the SAVE lane. */
    public synchronized void start() {
        started = true;
        if (timer == null && store != null) {
            timer = TimerManager.getInstance().register(TimerManager.SchedulerLane.SAVE, this::flushAll, FLUSH_MS, FLUSH_MS);
        }
    }

    /** LivingWorld.stop, after the citizens logged out and while the DB pool is up: cancel the timer, write everything. */
    public synchronized void stop() {
        started = false;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        flushAll();
    }

    // ---- worlds ---------------------------------------------------------------------------

    /** The host opens a bot's world: there at once (not ready); its past and its stored row are filled in on LOW_PRIORITY. */
    public void open(int characterId, Spec spec) {
        if (!on() || spec == null) {
            return;
        }
        long now = clock.getAsLong();
        Slot slot = slots.compute(characterId, (id, existing) -> {
            Slot out = existing == null ? new Slot(new KnownWorld(id)) : existing;
            out.hosted = true;
            out.closing = false;
            out.touchedMs = now;
            return out;
        });
        io.execute(() -> fill(slot, spec));
    }

    /** The host gave the world up: written at the next flush, then dropped. */
    public void close(int characterId) {
        Slot slot = slots.get(characterId);
        if (slot != null) {
            slot.closing = true;
            slot.hosted = false;
        }
    }

    /** The character was deleted: its world is dropped and its row deleted (no DB work while personal knowledge is off). */
    public void forget(int characterId) {
        slots.remove(characterId);
        dirty.remove(characterId);
        if (store != null && started && config.ENABLED && config.PERSONAL) {
            io.execute(() -> store.delete(characterId));
        }
    }

    /** A ready world, else null; never opens one. */
    public KnownWorld known(int characterId) {
        if (!on()) {
            return null;
        }
        Slot slot = slots.get(characterId);
        return slot != null && slot.world.ready() ? slot.world : null;
    }

    /**
     * A companion's world: get or open one with an unknown past (the level prior fills in); veteran by level, refreshed
     * per call, unless a host opened it with its own past. Ready at once, so its first answer already speaks as the later
     * ones do; its stored row is merged in once it has been read.
     */
    public KnownWorld companion(int characterId, int level) {
        if (!on()) {
            return null;
        }
        Slot slot = slotFor(characterId);
        slot.touchedMs = clock.getAsLong();
        KnownWorld world = slot.world;
        if (!slot.hosted) {
            world.veteran(level >= config.VETERAN_LEVEL);
        }
        return world.ready() ? world : null;
    }

    // ---- events (hosts pass agents only) --------------------------------------------------

    public void visited(int characterId, int mapId) {
        if (!on()) {
            return;
        }
        String region = fieldRegion(mapId);
        if (region != null) {
            record(characterId, EntityKind.MAP, mapId, region);
        }
    }

    /** A kill: the mob +1 and the map +1. */
    public void killed(int characterId, int mobId, int mapId) {
        if (!on()) {
            return;
        }
        record(characterId, EntityKind.MOB, mobId, null);
        String region = fieldRegion(mapId);
        if (region != null) {
            record(characterId, EntityKind.MAP, mapId, region);
        }
    }

    /** Loot picked up or an item bought. */
    public void gained(int characterId, int itemId) {
        if (on() && itemId > 0) {
            record(characterId, EntityKind.ITEM, itemId, null);
        }
    }

    /** It overheard a true answer: {@link Entity#key()}s of MAP, MOB and ITEM. Only into worlds already open. */
    public void heard(int characterId, long[] entityKeys) {
        Slot slot = on() && entityKeys != null && entityKeys.length > 0 ? slots.get(characterId) : null;
        if (slot == null) {
            return;
        }
        if (learn(slot.world, entityKeys)) {
            heard.incrementAndGet();
        }
        dirty.add(characterId);
        slot.touchedMs = clock.getAsLong();
    }

    /**
     * The teller passes one first-hand fact the learner lacks (one or two entity keys): a training spot and its main mob,
     * a mob it fought and a drop it saw, or an item it has and a mob it fought that drops it. Never something it only heard
     * (rumours travel one hop). False when nothing was new to the learner or either world is not ready.
     */
    public boolean gossip(int tellerId, int learnerId, long seed) {
        KnownWorld teller = known(tellerId);
        KnownWorld learner = known(learnerId);
        if (teller == null || learner == null || tellerId == learnerId) {
            return false;
        }
        WorldKnowledge k = knowledge.get();
        ContentAnswers.Rates rate = rates.apply(WORLD);
        SplittableRandom rng = new SplittableRandom(seed);
        for (int attempt = 0; attempt < GOSSIP_TRIES; attempt++) {
            long[] fact = switch (rng.nextInt(3)) {
                case 0 -> spotFact(k, teller, rng);
                case 1 -> mobFact(k, teller, rate, rng);
                default -> itemFact(k, teller, rng);
            };
            if (fact.length == 0 || knowsAll(learner, fact)) {
                continue;
            }
            learn(learner, fact);
            dirty.add(learnerId);
            gossiped.incrementAndGet();
            return true;
        }
        return false;
    }

    // ---- ops --------------------------------------------------------------------------------

    /** {@code !knowledge status}. */
    public String status() {
        int ready = 0;
        int hosted = 0;
        for (Slot slot : slots.values()) {
            ready += slot.world.ready() ? 1 : 0;
            hosted += slot.hosted ? 1 : 0;
        }
        return "personal: " + (on() ? "on" : "off") + ", open " + slots.size() + " (ready " + ready + ", hosted " + hosted
                + "), dirty " + dirty.size() + ", heard " + heard.get() + ", gossip " + gossiped.get() + ", last flush "
                + (lastFlushMs == 0 ? "never" : (clock.getAsLong() - lastFlushMs) / 1000 + " s ago (" + lastFlushRows + " rows)");
    }

    /** {@code !knowledge known <name>}: the past, flags, counts per kind, the top spots and the last heard. */
    public List<String> describe(int characterId) {
        Slot slot = slots.get(characterId);
        if (slot == null) {
            return List.of("no known world open for " + characterId);
        }
        KnownWorld w = slot.world;
        WorldKnowledge k = knowledge.get();
        List<String> out = new ArrayList<>();
        out.add("past " + w.past() + (w.ready() ? "" : " (loading)") + ", veteran " + w.veteran() + ", prices " + w.prices()
                + ", hosted " + slot.hosted + ", regions " + w.regions());
        out.add(w.summary());
        StringBuilder spots = new StringBuilder("spots:");
        IntList all = w.spots();
        for (int i = 0; i < all.size() && i < DESCRIBE_SHOWN; i++) {
            spots.append(' ').append(k.names().say(EntityKind.MAP, all.getInt(i))).append(';');
        }
        out.add(spots.toString());
        StringBuilder last = new StringBuilder("last heard:");
        for (long key : w.lastHeard(DESCRIBE_SHOWN)) {
            EntityKind kind = EntityKind.values()[(int) (key >>> 32)];
            last.append(' ').append(kind.name().toLowerCase(java.util.Locale.ROOT)).append(' ')
                    .append(k.names().say(kind, (int) key)).append(';');
        }
        out.add(last.toString());
        return out;
    }

    // ---- write-behind ------------------------------------------------------------------------

    /**
     * The timer and {@link #stop}: batches of {@value #FLUSH_ROWS} until no loaded world is dirty (or a save fails), so a
     * busy server writes every world each round, not the same first ones.
     */
    void flushAll() {
        int rounds = dirty.size() / FLUSH_ROWS + 1;
        for (int round = 0; round < rounds; round++) {
            if (flush() < FLUSH_ROWS) {
                break; // all written, failed, or only worlds still loading are left
            }
        }
    }

    /**
     * Up to {@value #FLUSH_ROWS} dirty worlds in one batch; a failure keeps them dirty (one warn). A world whose stored row
     * could not be read is never written (that would replace the row); its read is tried again. Then closed worlds with
     * nothing left to write are dropped, and lazy ones idle for {@link #IDLE_CLOSE_MS} close. Returns the rows written
     * (-1 when the save failed).
     */
    int flush() {
        long now = clock.getAsLong();
        List<Integer> taken = new ArrayList<>();
        List<KnownWorldStore.Row> rows = new ArrayList<>();
        for (Iterator<Integer> ids = dirty.iterator(); ids.hasNext() && rows.size() < FLUSH_ROWS; ) {
            int id = ids.next();
            Slot slot = slots.get(id);
            if (slot == null) {
                ids.remove();
            } else if (slot.loaded) { // a world whose stored row is not merged yet would overwrite it
                ids.remove();
                taken.add(id);
                rows.add(new KnownWorldStore.Row(id, slot.world.encode(), now));
            } else if (slot.loadFailed) {
                slot.loadFailed = false; // read again, one flush later
                io.execute(() -> {
                    synchronized (slot) {
                        load(slot);
                    }
                });
            }
        }
        int written = rows.size();
        if (!rows.isEmpty() && store != null && !store.save(rows)) {
            dirty.addAll(taken);
            log.warn("[knowledge] personal knowledge flush failed for {} worlds; kept for the next one", rows.size());
            written = -1;
        }
        for (Map.Entry<Integer, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (!slot.hosted && !slot.closing && now - slot.touchedMs > IDLE_CLOSE_MS) {
                slot.closing = true;
            }
            if (slot.closing) {
                slots.computeIfPresent(entry.getKey(), (id, current) -> current.closing && !dirty.contains(id) ? null : current);
            }
        }
        lastFlushMs = now;
        lastFlushRows = Math.max(0, written);
        return written;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The stored row, then the past (outside the slot's lock), then ready; a failed synthesis leaves an empty past. */
    private void fill(Slot slot, Spec spec) {
        Recall recall = Recall.NONE;
        try {
            synchronized (slot) {
                load(slot);
            }
            if (spec.past() == Past.SYNTHESIZED) {
                recall = PastSynthesis.synthesize(knowledge.get(), spec, rates.apply(WORLD));
            }
        } catch (RuntimeException failure) {
            log.warn("[knowledge] opening the known world of {} failed", slot.world.characterId(), failure);
        }
        synchronized (slot) {
            slot.world.configure(spec.past(), recall, spec.veteran(), spec.prices(), this::fieldRegion);
        }
    }

    /**
     * The stored row, once per slot (caller holds the slot): a world reopened before its flush keeps its memory. A failed
     * read (the store logs it) leaves the slot unloaded, so no flush writes it, and marks it for another try.
     */
    private void load(Slot slot) {
        if (!slot.loaded) {
            String stored;
            try {
                stored = store == null ? null : store.load(slot.world.characterId());
            } catch (RuntimeException failure) {
                slot.loadFailed = true;
                return;
            }
            slot.world.merge(stored, this::fieldRegion);
            slot.loaded = true;
        }
    }

    /** The slot of an id, opened lazily with an unknown past (a companion, or any agent no host opened). */
    private Slot slotFor(int characterId) {
        Slot existing = slots.get(characterId);
        if (existing != null) {
            return existing;
        }
        boolean[] created = new boolean[1];
        Slot slot = slots.computeIfAbsent(characterId, id -> {
            created[0] = true;
            Slot fresh = new Slot(new KnownWorld(id));
            fresh.world.configure(Past.UNKNOWN, Recall.NONE, false, false, this::fieldRegion); // a host that opens it reconfigures
            fresh.touchedMs = clock.getAsLong();
            return fresh;
        });
        if (created[0]) {
            io.execute(() -> {
                try {
                    synchronized (slot) {
                        load(slot);
                    }
                } catch (RuntimeException failure) {
                    log.warn("[knowledge] loading the known world of {} failed", characterId, failure);
                }
            });
        }
        return slot;
    }

    private void record(int characterId, EntityKind kind, int id, String region) {
        Slot slot = slotFor(characterId);
        slot.world.lived(kind, id, 1, region);
        slot.touchedMs = clock.getAsLong();
        dirty.add(characterId);
    }

    /**
     * The knowledge region of a map a bot can be said to know: a field, a town or a PQ lobby (OPQ's is instanced), never
     * another instance or a PQ stage.
     */
    private String fieldRegion(int mapId) {
        WorldKnowledge k = knowledge.get();
        MapFact map = k.map(mapId).orElse(null);
        return map == null || map.forcedReturn() != 0 && k.names().pqAtLobby(mapId).isEmpty() ? null : map.region();
    }

    private boolean learn(KnownWorld world, long[] keys) {
        boolean fresh = false;
        for (long key : keys) {
            EntityKind kind = EntityKind.values()[(int) (key >>> 32)];
            int id = (int) key;
            fresh |= world.heard(kind, id, kind == EntityKind.MAP ? fieldRegion(id) : null);
        }
        return fresh;
    }

    private static boolean knowsAll(KnownWorld world, long[] keys) {
        for (long key : keys) {
            int id = (int) key;
            boolean known = switch (EntityKind.values()[(int) (key >>> 32)]) {
                case MAP -> world.knowsMap(id);
                case MOB -> world.knowsMob(id);
                case ITEM -> world.knowsItem(id);
                default -> true;
            };
            if (!known) {
                return false;
            }
        }
        return true;
    }

    /** One of its top training spots and that spot's main mob. */
    private static long[] spotFact(WorldKnowledge k, KnownWorld teller, SplittableRandom rng) {
        IntList spots = teller.spots();
        if (spots.isEmpty()) {
            return new long[0];
        }
        int spot = spots.getInt(rng.nextInt(Math.min(GOSSIP_SPOTS, spots.size())));
        MapFact map = k.map(spot).orElse(null);
        if (map == null || map.mobs().isEmpty()) {
            return new long[]{key(EntityKind.MAP, spot)};
        }
        return new long[]{key(EntityKind.MAP, spot), key(EntityKind.MOB, ContentAnswers.mainMob(map))};
    }

    /** A mob it fought (weighted by kills) and the best drop it saw from it or has itself. */
    private static long[] mobFact(WorldKnowledge k, KnownWorld teller, ContentAnswers.Rates rate, SplittableRandom rng) {
        List<int[]> mobs = teller.firstHand(EntityKind.MOB);
        if (mobs.isEmpty()) {
            return new long[0];
        }
        long total = 0;
        for (int[] mob : mobs) {
            total += Math.max(1, mob[1]);
        }
        long roll = (long) (rng.nextDouble() * total);
        int mobId = mobs.get(0)[0];
        for (int[] mob : mobs) {
            roll -= Math.max(1, mob[1]);
            if (roll < 0) {
                mobId = mob[0];
                break;
            }
        }
        int kills = teller.kills(mobId);
        for (DropSource drop : k.mobDrops(mobId)) {
            if (drop.questId() == 0 && (kills * drop.effectiveChance(rate.drop(), rate.bossDrop()) >= 1
                    || teller.firstHand(EntityKind.ITEM, drop.itemId()))) {
                return new long[]{key(EntityKind.MOB, mobId), key(EntityKind.ITEM, drop.itemId())};
            }
        }
        return new long[0];
    }

    /** An item it has and a mob it fought that drops it. */
    private static long[] itemFact(WorldKnowledge k, KnownWorld teller, SplittableRandom rng) {
        List<int[]> items = teller.firstHand(EntityKind.ITEM);
        if (items.isEmpty()) {
            return new long[0];
        }
        int itemId = items.get(rng.nextInt(items.size()))[0];
        for (DropSource drop : k.dropSources(itemId)) {
            if (!drop.global() && teller.firstHand(EntityKind.MOB, drop.mobId())) {
                return new long[]{key(EntityKind.ITEM, itemId), key(EntityKind.MOB, drop.mobId())};
            }
        }
        return new long[0];
    }

    private static long key(EntityKind kind, int id) {
        return Entity.simple(kind, id, "", "").key();
    }

    /** One open world and who holds it. */
    private static final class Slot {
        final KnownWorld world;
        volatile boolean hosted;
        volatile boolean closing;
        volatile boolean loaded;
        /** Its stored row could not be read: the next flush tries again. */
        volatile boolean loadFailed;
        volatile long touchedMs;

        Slot(KnownWorld world) {
            this.world = world;
        }
    }
}
