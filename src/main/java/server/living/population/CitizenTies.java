package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.records.Clear;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What citizens are to each other (docs/WORLD_SYSTEMS_PLAN.md N2.1, population.CITIZEN_TIES_ENABLED): FRIEND or RIVAL
 * and how strongly, one tie a pair (living_tie, a < b). A tie grows only from pairs the server already holds: the
 * members of one party at the minute tick, circle mates on one map, the citizens of a booked clear, the two sides of a
 * coliseum match; never from a scan of all pairs. Its strength fades like warmth (MEMORY_WARMTH_HALF_LIFE_DAYS), and a
 * citizen holds at most TIES_PER_CITIZEN_MAX: a new tie stronger than its weakest pushes that one out, a weaker one is
 * not made. Seen as one signed number, friendship up and rivalry down: a friend who keeps losing to you drifts through
 * nothing into a rival. Every tie is kept in memory (a few thousand rows at most); what changed is written once a
 * minute in one batch ({@link #flush}).
 */
public final class CitizenTies {
    private static final Logger log = LoggerFactory.getLogger(CitizenTies.class);
    /** Felt this strongly or more, a tie counts: it calls first, refuses, and is talked about. */
    public static final int KNOWN = 10;
    static final int MAX_STRENGTH = 100;
    /** Shared minutes: one in a party is worth {@link #PARTY_UNITS}, one with a circle mate on one map one unit. */
    static final int PARTY_UNITS = 3;
    static final int UNITS_PER_POINT = 30;
    /** A party quest or expedition cleared together. */
    static final int CLEAR_POINTS = 6;
    /** One decided coliseum match between two citizens, toward rivalry. */
    static final int MATCH_POINTS = 4;
    /** A failed citizen run, toward rivalry (a nudge: see {@link #runEnded}). */
    static final int FAIL_POINTS = 2;
    static final long PRUNE_EVERY_MS = 3_600_000L;
    /** Shared minutes not added to for this long are dropped (the pair is no longer together). */
    static final long UNITS_IDLE_MS = 30 * 60_000L;

    public enum Kind {
        FRIEND, RIVAL;

        static Kind parse(String name) {
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** One tie as stored: the pair (a < b), its kind, its strength when it last changed, and when. */
    public record Tie(int a, int b, Kind kind, int strength, long updatedAtMs) {
        /** Its strength now, faded since it last changed. */
        public double felt(long nowMs, double halfLifeDays) {
            return strength * PersonMemory.fade(nowMs - updatedAtMs, halfLifeDays);
        }

        double signed(long nowMs, double halfLifeDays) {
            return kind == Kind.FRIEND ? felt(nowMs, halfLifeDays) : -felt(nowMs, halfLifeDays);
        }

        public int other(int id) {
            return id == a ? b : a;
        }
    }

    private final Population population;
    private final TieStore store;
    private final Map<Long, Tie> ties = new HashMap<>();
    private final Map<Integer, Set<Long>> byCitizen = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final Map<Long, int[]> gone = new HashMap<>();
    /** Pair -> {shared units toward the next point, epoch minute last added}. */
    private final Map<Long, long[]> units = new HashMap<>();
    private boolean loaded;
    private long lastPruneMs;
    private long grown;
    private long pushedOut;
    private long written;

    CitizenTies(Population population) {
        this(population, new TieStore());
    }

    CitizenTies(Population population, TieStore store) {
        this.population = population;
        this.store = store;
    }

    public boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.CITIZEN_TIES_ENABLED;
    }

    private double halfLife() {
        return population.config().MEMORY_WARMTH_HALF_LIFE_DAYS;
    }

    static long key(int x, int y) {
        int a = Math.min(x, y);
        int b = Math.max(x, y);
        return (long) a << 32 | (b & 0xffffffffL);
    }

    /** The stored ties, read the first time they are needed; false while the read fails. */
    private boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        List<Tie> stored = store.all();
        if (stored == null) {
            return false;
        }
        for (Tie tie : stored) {
            put(tie);
        }
        loaded = true; // the first flush prunes what faded while the server was down
        return true;
    }

    private void put(Tie tie) {
        long key = key(tie.a(), tie.b());
        ties.put(key, tie);
        byCitizen.computeIfAbsent(tie.a(), id -> new HashSet<>()).add(key);
        byCitizen.computeIfAbsent(tie.b(), id -> new HashSet<>()).add(key);
        gone.remove(key);
    }

    private void remove(long key) {
        Tie tie = ties.remove(key);
        if (tie == null) {
            return;
        }
        drop(tie.a(), key);
        drop(tie.b(), key);
        dirty.remove(key);
        gone.put(key, new int[] {tie.a(), tie.b()});
    }

    private void drop(int id, long key) {
        Set<Long> keys = byCitizen.get(id);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                byCitizen.remove(id);
            }
        }
    }

    // ---- growth ------------------------------------------------------------------------------

    /**
     * Moves the pair's tie by {@code delta}: toward friendship when positive, rivalry when negative. A tie that fades
     * to nothing goes; a new one is made only where both have room (see the class comment).
     */
    synchronized void grow(int x, int y, double delta, long nowMs) {
        if (!on() || x <= 0 || y <= 0 || x == y || delta == 0d || !ensureLoaded()) {
            return;
        }
        long key = key(x, y);
        Tie old = ties.get(key);
        double next = Math.max(-MAX_STRENGTH, Math.min(MAX_STRENGTH, (old == null ? 0d : old.signed(nowMs, halfLife())) + delta));
        int strength = (int) Math.round(Math.abs(next));
        if (strength < 1) {
            remove(key);
            return;
        }
        if (old == null && !(room(x, strength, nowMs, false) && room(y, strength, nowMs, false)
                && room(x, strength, nowMs, true) && room(y, strength, nowMs, true))) {
            return;
        }
        put(new Tie(Math.min(x, y), Math.max(x, y), next > 0 ? Kind.FRIEND : Kind.RIVAL, strength, nowMs));
        dirty.add(key);
        grown++;
    }

    /** Room for one more tie of {@code strength} on {@code id}: under the cap, or its weakest is weaker (pushed out on {@code make}). */
    private boolean room(int id, int strength, long nowMs, boolean make) {
        Set<Long> keys = byCitizen.get(id);
        if (keys == null || keys.size() < Math.max(1, population.config().TIES_PER_CITIZEN_MAX)) {
            return true;
        }
        Tie weakest = null;
        for (long key : keys) {
            Tie tie = ties.get(key);
            if (tie != null && (weakest == null || tie.felt(nowMs, halfLife()) < weakest.felt(nowMs, halfLife()))) {
                weakest = tie;
            }
        }
        if (weakest == null || weakest.felt(nowMs, halfLife()) >= strength) {
            return false;
        }
        if (make) {
            remove(key(weakest.a(), weakest.b()));
            pushedOut++;
        }
        return true;
    }

    /**
     * The minute tick (SocialFabric): each pair of loaded citizens in one party shares {@link #PARTY_UNITS} units, each
     * pair of circle mates on one map and channel one; every {@link #UNITS_PER_POINT} units the pair's friendship grows
     * by a point. Only the groups at hand are paired: a party is at most six, a circle at most five.
     */
    synchronized void minute(long nowMs) {
        if (!on() || !ensureLoaded()) {
            return;
        }
        Map<String, List<Integer>> parties = new HashMap<>();
        Map<String, List<Integer>> circles = new HashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || agent.getMap() == null || session.activity == CitizenSession.Activity.AFK) {
                continue;
            }
            if (agent.getParty() != null) {
                parties.computeIfAbsent("p" + agent.getParty().getId(), k -> new ArrayList<>()).add(session.characterId);
            } else if (session.citizen.circleId() != 0) {
                circles.computeIfAbsent(session.citizen.circleId() + ":" + session.channel + ":" + agent.getMapId(),
                        k -> new ArrayList<>()).add(session.characterId);
            }
        }
        together(new ArrayList<>(parties.values()), new ArrayList<>(circles.values()), nowMs);
    }

    /** One minute of these parties and circle groups together (citizen ids). */
    synchronized void together(List<List<Integer>> parties, List<List<Integer>> circles, long nowMs) {
        if (!on() || !ensureLoaded()) {
            return;
        }
        long minute = nowMs / 60_000L;
        parties.forEach(group -> together(group, PARTY_UNITS, minute, nowMs));
        circles.forEach(group -> together(group, 1, minute, nowMs));
    }

    private void together(List<Integer> group, int add, long minute, long nowMs) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                long key = key(group.get(i), group.get(j));
                long[] shared = units.computeIfAbsent(key, k -> new long[2]);
                if (shared[1] == minute) {
                    continue; // already counted this minute
                }
                shared[0] += add;
                shared[1] = minute;
                if (shared[0] >= UNITS_PER_POINT) {
                    shared[0] -= UNITS_PER_POINT;
                    grow(group.get(i), group.get(j), 1d, nowMs);
                }
            }
        }
    }

    /** A booked clear (ClearLog.onBooked): its citizens grow closer, pair by pair. */
    void cleared(Clear clear) {
        if (!on() || clear == null) {
            return;
        }
        List<Integer> citizens = new ArrayList<>();
        for (Clear.Member member : clear.members()) {
            if (member.kind() == Clear.AS_CITIZEN) {
                citizens.add(member.id());
            }
        }
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < citizens.size(); i++) {
            for (int j = i + 1; j < citizens.size(); j++) {
                grow(citizens.get(i), citizens.get(j), CLEAR_POINTS, nowMs);
            }
        }
    }

    /**
     * A citizen run ended (CitizenPartyQuests). A clear is ignored here: it reaches {@link #cleared} through
     * ClearLog.onBooked, so it is never counted twice. A failed run only nudges each pair toward rivalry by
     * {@link #FAIL_POINTS}: a friendship loses that much but never drops under one point (one bad run does not make
     * friends into rivals), and a pair with no tie starts one far under {@link #KNOWN}.
     */
    void runEnded(java.util.Collection<Integer> citizenIds, boolean cleared) {
        if (!on() || cleared || citizenIds == null) {
            return;
        }
        List<Integer> ids = new ArrayList<>(citizenIds);
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                failed(ids.get(i), ids.get(j), nowMs);
            }
        }
    }

    private synchronized void failed(int x, int y, long nowMs) {
        if (!ensureLoaded()) {
            return;
        }
        Tie tie = ties.get(key(x, y));
        double signed = tie == null ? 0d : tie.signed(nowMs, halfLife());
        double delta = signed > 0d ? -Math.min(FAIL_POINTS, Math.max(0d, signed - 1d)) : -FAIL_POINTS;
        if (delta != 0d) {
            grow(x, y, delta, nowMs);
        }
    }

    /** A decided coliseum match between two citizens (a draw is none): the two edge toward rivalry. */
    public void matched(int winnerId, int loserId) {
        if (on() && population.isCitizen(winnerId) && population.isCitizen(loserId)) {
            grow(winnerId, loserId, -MATCH_POINTS, System.currentTimeMillis());
        }
    }

    /** A citizen retired: its ties go. */
    synchronized void forget(int citizenId) {
        if (!on()) {
            return;
        }
        Set<Long> keys = byCitizen.get(citizenId);
        if (keys != null) {
            for (long key : new ArrayList<>(keys)) {
                remove(key);
            }
        }
        store.forget(citizenId);
        gone.values().removeIf(pair -> pair[0] == citizenId || pair[1] == citizenId);
    }

    /** End of the minute: every changed tie written in one batch; once an hour faded ties go and idle shared minutes drop. */
    synchronized void flush(long nowMs) {
        if (!on() || !loaded) {
            return;
        }
        if (nowMs - lastPruneMs >= PRUNE_EVERY_MS) {
            lastPruneMs = nowMs;
            for (Tie tie : new ArrayList<>(ties.values())) {
                if (tie.felt(nowMs, halfLife()) < 1.0d) {
                    remove(key(tie.a(), tie.b()));
                }
            }
            long minute = nowMs / 60_000L;
            units.values().removeIf(shared -> (minute - shared[1]) * 60_000L >= UNITS_IDLE_MS);
        }
        List<Tie> changed = new ArrayList<>();
        for (long key : dirty) {
            Tie tie = ties.get(key);
            if (tie != null) {
                changed.add(tie);
            }
        }
        List<int[]> removed = new ArrayList<>(gone.values());
        if (changed.isEmpty() && removed.isEmpty()) {
            return;
        }
        if (store.save(changed, removed)) {
            dirty.clear();
            gone.clear();
            written += changed.size() + removed.size();
        }
    }

    // ---- reading -----------------------------------------------------------------------------

    /** The kind of tie between the two when it is felt at least {@link #KNOWN}; null for none. */
    public synchronized Kind between(int x, int y) {
        if (!on() || x == y || !ensureLoaded()) {
            return null;
        }
        Tie tie = ties.get(key(x, y));
        return tie != null && tie.felt(System.currentTimeMillis(), halfLife()) >= KNOWN ? tie.kind() : null;
    }

    /** The citizen's ties, strongest first, as they are felt now. */
    public synchronized List<Tie> of(int citizenId) {
        if (!on() || !ensureLoaded()) {
            return List.of();
        }
        long nowMs = System.currentTimeMillis();
        List<Tie> out = new ArrayList<>();
        for (long key : byCitizen.getOrDefault(citizenId, Set.of())) {
            Tie tie = ties.get(key);
            if (tie != null) {
                out.add(tie);
            }
        }
        out.sort(Comparator.comparingDouble((Tie tie) -> tie.felt(nowMs, halfLife())).reversed());
        return out;
    }

    /**
     * Who a party's leader calls from {@code pool} (CitizenPartyQuests' mate call): never a rival; its strongest friend
     * when one is there; else anyone left, at random. Null when everyone there is a rival (or the pool is empty). Off,
     * the plain random pick.
     */
    public CitizenSession call(CitizenSession leader, List<CitizenSession> pool, Random random) {
        if (pool.isEmpty()) {
            return null;
        }
        if (!on()) {
            return pool.get(random.nextInt(pool.size()));
        }
        long nowMs = System.currentTimeMillis();
        List<CitizenSession> open = new ArrayList<>();
        CitizenSession friend = null;
        double best = 0d;
        synchronized (this) {
            if (!ensureLoaded()) {
                return pool.get(random.nextInt(pool.size()));
            }
            for (CitizenSession session : pool) {
                Tie tie = ties.get(key(leader.characterId, session.characterId));
                double felt = tie == null ? 0d : tie.felt(nowMs, halfLife());
                if (tie != null && felt >= KNOWN && tie.kind() == Kind.RIVAL) {
                    continue;
                }
                open.add(session);
                if (tie != null && felt >= KNOWN && felt > best) {
                    friend = session;
                    best = felt;
                }
            }
        }
        if (friend != null) {
            return friend;
        }
        return open.isEmpty() ? null : open.get(random.nextInt(open.size()));
    }

    /** True when {@code session} is the leader's friend (felt at least {@link #KNOWN}): called like a circle mate. */
    public boolean friends(CitizenSession leader, CitizenSession session) {
        return between(leader.characterId, session.characterId) == Kind.FRIEND;
    }

    /** One line for ops. */
    public synchronized String report() {
        return "ties: " + (on() ? "on" : "off") + " held=" + ties.size() + " unwritten=" + (dirty.size() + gone.size())
                + " grown=" + grown + " pushedOut=" + pushedOut + " written=" + written;
    }

    /** !ties: the citizen's ties, one line each, strongest first. */
    public List<String> describe(int citizenId) {
        long nowMs = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (Tie tie : of(citizenId)) {
            int other = tie.other(citizenId);
            Citizen citizen = population.citizen(other);
            String name = citizen == null ? Character.getNameById(other) : citizen.name();
            out.add((name == null ? "#" + other : name) + ": " + tie.kind() + " " + Math.round(tie.felt(nowMs, halfLife()))
                    + " (stored " + tie.strength() + ", " + PersonMemory.when(nowMs - tie.updatedAtMs()) + ")");
        }
        return out;
    }
}
