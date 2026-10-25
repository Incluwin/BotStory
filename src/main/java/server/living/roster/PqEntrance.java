package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A party quest's entrance as companion runs go in by it (P6b HPQ step 5): Lakelis's for KPQ, Tory's for HPQ. Each has
 * its quest's definition (the event, the recruit map, the timer), its entry NPC, the seats a lineup takes, the monsters
 * a companion is weighed against without a clear time, the voice at the desk, and the Agent layer's own admission
 * words, session registry and pace profile, never another quest's. Potions are weighed by the quest's own standard
 * (AgentSupplyReadiness.shortfall(partyQuest(key)): PARTY_QUEST_STANDARDS, else the default), so an entrance has no
 * readiness of its own beyond its key.
 * A key without an entrance has none: {@link #of} throws, and the desk leaves its NPC stock.
 */
public final class PqEntrance {
    /** Who a lineup seats: at least {@code min}, at most {@code max}, and her own choice fills {@code preferred}. */
    public record SeatRules(int min, int max, int preferred) { }

    /** A run's session while its quest's registry holds it: its id and the event leader it has now. */
    public record LiveSession(String sessionId, int eventLeaderId) { }

    /**
     * The job branches (Job.getJobNiche: 1 warrior, 2 magician, 3 bowman, 4 thief, 5 pirate) her own lineup choice
     * prefers between everyone who goes in, humans too, and how much longer than the quickest lineup one that has them
     * may take and still be her choice. A preference, never a rule: a lineup without them goes in all the same, and the
     * leader's pick is never changed for it. Only OPQ's entrance has one (Wonky blesses a party with all five).
     */
    public record BranchPreference(Set<Integer> branches, long worthMs) {
        public BranchPreference {
            branches = Set.copyOf(branches);
        }

        /** How many of the branches nobody in {@code lineup} has. */
        public int missing(Collection<LineupPlan.Seat> lineup) {
            Set<Integer> missing = new HashSet<>(branches);
            for (LineupPlan.Seat seat : lineup) {
                missing.remove(seat.jobId() / 100 % 10);
            }
            return missing.size();
        }

        /** True when someone in {@code lineup} has each of the branches. */
        public boolean metBy(Collection<LineupPlan.Seat> lineup) {
            return missing(lineup) == 0;
        }
    }

    /** The quest's own Agent-layer admission of a lineup, its leader as operator and event leader. */
    interface Admit {
        PqEntryPort.Admitted admit(Character leader, List<Character> members, long seed, long nowMs);
    }

    private static final Map<String, PqEntrance> ENTRANCES = Collections.unmodifiableMap(entrances());
    /** Every entrance's key, KPQ's first. */
    private static final List<String> KEYS = List.copyOf(ENTRANCES.keySet());
    /** The entrances that keep their entry NPC clear of followers, by the map she stands on. */
    private static final Map<Integer, PqEntrance> KEEPING_CLEAR = Collections.unmodifiableMap(keepingClear());
    /** Each entrance's key, by the map its entry NPC stands on (its recruit map). */
    private static final Map<Integer, String> KEY_BY_RECRUIT_MAP = Collections.unmodifiableMap(keysByRecruitMap());

    private final String key;
    private final AgentPartyQuestDefinition definition;
    private final int entryNpcId;
    private final SeatRules seats;
    private final int[] dpsMobIds;
    private final DeskVoice voice;
    private final BiFunction<Character, Boolean, String> memberProblem;
    private final BiFunction<Integer, Collection<Integer>, String> registrationBlocker;
    private final Supplier<? extends AgentPqPaceProfile> paceLoader;
    private final IntFunction<? extends AgentCompanionRunSession> forOperator;
    private final IntFunction<? extends AgentCompanionRunSession> forMember;
    private final boolean admitsOnIo;
    private final BooleanSupplier backgroundRuns;
    private final Beside beside;
    private final KeepClear keepClear;
    private final Admit admit;
    private final BranchPreference branchPreference;
    /**
     * Where the entry NPC kept clear of followers stands, by the map instance she was found on: looked for once per map
     * (MapleMap.getNPCById copies every object on it under its lock, and she never moves), not on every follow tick.
     */
    private final Map<MapleMap, Point> keptClearSpots = Collections.synchronizedMap(new WeakHashMap<>());

    /** Where the {@code index}th extra waits from the entry NPC: {@code dx + step * index} along, grounded or not. */
    record Beside(int dx, int step, boolean grounded) { }

    /** Where an owner stands at the entry NPC: within {@code dx} of her, from {@code above} over her to {@code below}. */
    record KeepClear(int dx, int above, int below) { }

    PqEntrance(String key, int entryNpcId, SeatRules seats, int[] dpsMobIds, DeskVoice voice,
               BiFunction<Character, Boolean, String> memberProblem,
               BiFunction<Integer, Collection<Integer>, String> registrationBlocker,
               Supplier<? extends AgentPqPaceProfile> paceLoader,
               IntFunction<? extends AgentCompanionRunSession> forOperator,
               IntFunction<? extends AgentCompanionRunSession> forMember, boolean admitsOnIo,
               BooleanSupplier backgroundRuns, Beside beside, KeepClear keepClear, Admit admit) {
        this(key, entryNpcId, seats, dpsMobIds, voice, memberProblem, registrationBlocker, paceLoader, forOperator,
                forMember, admitsOnIo, backgroundRuns, beside, keepClear, admit, null);
    }

    /** An entrance whose own lineup choice prefers {@code branchPreference}'s job branches (null: none). */
    PqEntrance(String key, int entryNpcId, SeatRules seats, int[] dpsMobIds, DeskVoice voice,
               BiFunction<Character, Boolean, String> memberProblem,
               BiFunction<Integer, Collection<Integer>, String> registrationBlocker,
               Supplier<? extends AgentPqPaceProfile> paceLoader,
               IntFunction<? extends AgentCompanionRunSession> forOperator,
               IntFunction<? extends AgentCompanionRunSession> forMember, boolean admitsOnIo,
               BooleanSupplier backgroundRuns, Beside beside, KeepClear keepClear, Admit admit,
               BranchPreference branchPreference) {
        this.key = key;
        this.definition = AgentPartyQuestCatalog.require(key);
        this.entryNpcId = entryNpcId;
        this.seats = seats;
        this.dpsMobIds = dpsMobIds.clone();
        this.voice = voice;
        this.memberProblem = memberProblem;
        this.registrationBlocker = registrationBlocker;
        this.paceLoader = paceLoader;
        this.forOperator = forOperator;
        this.forMember = forMember;
        this.admitsOnIo = admitsOnIo;
        this.backgroundRuns = backgroundRuns;
        this.beside = beside;
        this.keepClear = keepClear;
        this.admit = admit;
        this.branchPreference = branchPreference;
    }

    /** Every entrance by its key, each quest's built in its own file (KpqEntrance, HpqEntrance) in its own slot. */
    private static Map<String, PqEntrance> entrances() {
        Map<String, PqEntrance> entrances = new LinkedHashMap<>();
        register(entrances, KpqEntrance.create());

        register(entrances, HpqEntrance.create());

        register(entrances, LpqEntrance.create());

        register(entrances, EpqEntrance.create());

        // slot: lmpq
        register(entrances, LmpqEntrance.create());

        // slot: opq
        register(entrances, OpqEntrance.create());

        // slot: ppq
        register(entrances, PpqEntrance.create());

        // slot: rjpq
        register(entrances, RjpqEntrance.create(AgentRjpqSide.ZENUMIST));
        register(entrances, RjpqEntrance.create(AgentRjpqSide.ALCADNO));
        // slot: apq
        register(entrances, ApqEntrance.create());
        return entrances;
    }

    /**
     * Adds {@code entrance} under its key, read as {@link #find} reads keys; a key registered twice, or an entrance
     * without its quest's admission, throws.
     */
    static void register(Map<String, PqEntrance> entrances, PqEntrance entrance) {
        String key = entrance.key.trim().toLowerCase(Locale.ROOT);
        if (entrance.admit == null) {
            throw new IllegalStateException("no admission for the companion party-quest entrance " + key);
        }
        if (entrances.putIfAbsent(key, entrance) != null) {
            throw new IllegalStateException("two companion party-quest entrances for " + key);
        }
    }

    private static Map<Integer, PqEntrance> keepingClear() {
        Map<Integer, PqEntrance> keeping = new LinkedHashMap<>();
        for (PqEntrance entrance : ENTRANCES.values()) {
            if (entrance.keepClear != null) {
                keeping.put(entrance.definition.recruitMapId(), entrance);
            }
        }
        return keeping;
    }

    private static Map<Integer, String> keysByRecruitMap() {
        Map<Integer, String> keys = new LinkedHashMap<>();
        for (PqEntrance entrance : ENTRANCES.values()) {
            keys.putIfAbsent(entrance.definition.recruitMapId(), entrance.key);
        }
        return keys;
    }

    private static LiveSession live(AgentCompanionRunSession session) {
        return session == null ? null : new LiveSession(session.sessionId(), session.eventLeaderId());
    }

    private static String sessionId(AgentCompanionRunSession session) {
        return session == null ? null : session.sessionId();
    }

    /** The entrance of {@code key} (any case, spaces around it ignored); throws when there is none. */
    public static PqEntrance of(String key) {
        PqEntrance entrance = find(key);
        if (entrance == null) {
            throw new IllegalArgumentException("no companion party-quest entrance for " + key);
        }
        return entrance;
    }

    /** The entrance of {@code key}, or null when there is none. */
    static PqEntrance find(String key) {
        return key == null ? null : ENTRANCES.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** Every entrance's key, KPQ's first. */
    public static List<String> keys() {
        return KEYS;
    }

    /**
     * The key of the entrance whose entry NPC stands on {@code mapId} (its recruit map: kpq on Kerning City, hpq on
     * Henesys Park, and each later entrance once registered), or null on any other map. Read-only, for the companion
     * restock duty's per-quest potion standard: the desk and the companions at it agree on the quest.
     */
    public static String keyAt(int mapId) {
        return KEY_BY_RECRUIT_MAP.get(mapId);
    }

    /** The entrance whose entry NPC on {@code mapId} is kept clear of followers (Tory's, in Henesys Park); else null. */
    static PqEntrance keepingClearOn(int mapId) {
        return KEEPING_CLEAR.get(mapId);
    }

    /** The seats of {@code key}'s lineups; throws when it has no entrance. */
    public static SeatRules seats(String key) {
        return of(key).seats;
    }

    public String key() {
        return key;
    }

    public AgentPartyQuestDefinition definition() {
        return definition;
    }

    public int entryNpcId() {
        return entryNpcId;
    }

    public SeatRules seatRules() {
        return seats;
    }

    /** The job branches her own lineup choice prefers (OPQ's five); null for every other quest. */
    public BranchPreference branchPreference() {
        return branchPreference;
    }

    /** The monsters a companion that can go is weighed against without a clear time (its damage, averaged). */
    public int[] dpsMobIds() {
        return dpsMobIds.clone();
    }

    public DeskVoice voice() {
        return voice;
    }

    /** Why the member cannot go in, in its quest's admission words; "" when it can. */
    public String memberProblem(Character member, boolean eventLeader) {
        return memberProblem.apply(member, eventLeader);
    }

    /** What already holds the operator or one of these characters in the quest's registry; "" when nothing does. */
    public String registrationBlocker(int operatorId, Collection<Integer> characterIds) {
        return registrationBlocker.apply(operatorId, characterIds);
    }

    /** Admits the lineup by the quest's own Agent-layer admission, {@code leader} as operator and event leader. */
    PqEntryPort.Admitted admit(Character leader, List<Character> members, long seed, long nowMs) {
        return admit.admit(leader, members, seed, nowMs);
    }

    /** Builds the quest's pace profile (on companion-ops, at boot). */
    public Supplier<? extends AgentPqPaceProfile> paceLoader() {
        return paceLoader;
    }

    /**
     * The run's session while the quest's registry holds it for {@code operatorId}, its operator (a lineup's leader),
     * as {@code sessionId}; else null.
     */
    public LiveSession session(int operatorId, String sessionId) {
        LiveSession session = live(forOperator.apply(operatorId));
        return session != null && session.sessionId().equals(sessionId) ? session : null;
    }

    /** True while the quest's registry holds {@code agentId} in the session {@code sessionId}. */
    public boolean holds(int agentId, String sessionId) {
        String held = sessionId(forMember.apply(agentId));
        return held != null && held.equals(sessionId);
    }

    /**
     * True when the lineup is admitted on the leader's I/O thread with its start, in one step (KPQ, as before); false
     * when admission runs on companion-ops and only the start comes back to that thread (HPQ).
     */
    public boolean admitsOnIo() {
        return admitsOnIo;
    }

    /**
     * True while the background population runs this quest's lobbies (which count only their own runs): the desk
     * leaves the entry NPC stock meanwhile.
     */
    public boolean backgroundRuns() {
        return backgroundRuns.getAsBoolean();
    }

    /**
     * Where the {@code index}th extra of a lineup waits on {@code map}: beside Lakelis where she stands, or on the
     * ground below a point past the end of Tory's ledge; null when the entry NPC is not on that map.
     */
    public Point beside(MapleMap map, int index) {
        NPC npc = map == null ? null : map.getNPCById(entryNpcId);
        return npc == null || npc.getPosition() == null ? null : beside(map, npc.getPosition(), index);
    }

    /**
     * Where the entry NPC kept clear of followers (Tory) stands on {@code map} while an owner standing at
     * {@code standing} is at her (on her ledge); null anywhere else on it, at an entrance that keeps none (Lakelis's:
     * her companions follow beside the owner, as before), or when she is not on that map. Meanwhile the owner's
     * companions wait at the extras' spots ({@link #beside(MapleMap, Point, int)}), so they never crowd her or hide her
     * name. The map is searched for her once (again only while she is not found there): an owner anywhere else on it
     * costs a lookup and a comparison, and the spot is read-only.
     */
    Point keptClearAt(MapleMap map, Point standing) {
        Point at = keepClear == null || map == null || standing == null ? null : keptClearSpot(map);
        if (at == null || Math.abs(standing.x - at.x) > keepClear.dx() || standing.y < at.y - keepClear.above()
                || standing.y > at.y + keepClear.below()) {
            return null;
        }
        return at;
    }

    private Point keptClearSpot(MapleMap map) {
        Point known = keptClearSpots.get(map);
        if (known != null) {
            return known;
        }
        NPC npc = map.getNPCById(entryNpcId);
        Point at = npc == null ? null : npc.getPosition();
        if (at == null) {
            return null;
        }
        known = new Point(at);
        keptClearSpots.put(map, known);
        return known;
    }

    /** Where the {@code index}th extra waits from the entry NPC standing at {@code npcAt} on {@code map}. */
    Point beside(MapleMap map, Point npcAt, int index) {
        Point point = new Point(npcAt.x + beside.dx() + beside.step() * index, npcAt.y);
        Point ground = beside.grounded() ? map.getPointBelow(point) : null;
        return ground != null ? new Point(ground) : point;
    }
}
