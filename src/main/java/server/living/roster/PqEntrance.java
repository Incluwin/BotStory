package server.living.roster;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile;
import server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A party quest's entrance as companion runs go in by it (P6b HPQ step 5): Lakelis's for KPQ, Tory's for HPQ. Each has
 * its quest's definition (the event, the recruit map, the timer), its entry NPC, the seats a lineup takes, the monsters
 * a companion is weighed against without a clear time, the voice at the desk, and the Agent layer's own admission
 * words, session registry and pace profile, never another quest's. Potions are weighed alike at every entrance
 * (AgentSupplyReadiness.shortfall(PARTY_QUEST), the 2026-10-02 decision), so an entrance has no readiness of its own.
 * A key without an entrance has none: {@link #of} throws, and the desk leaves its NPC stock.
 */
public final class PqEntrance {
    /** Who a lineup seats: at least {@code min}, at most {@code max}, and her own choice fills {@code preferred}. */
    public record SeatRules(int min, int max, int preferred) { }

    /** A run's session while its quest's registry holds it: its id and the event leader it has now. */
    public record LiveSession(String sessionId, int eventLeaderId) { }

    /** Extras wait beside Lakelis: 40 px on from her, 30 px apart, where she stands. */
    private static final Beside BESIDE_LAKELIS = new Beside(40, 30, false);
    /**
     * Extras wait off Tory's ledge (Henesys Park: x 1357-1523 at y 364), on the park floor below past its end, so they
     * never crowd her or hide her name (P6b HPQ step 0).
     */
    private static final Beside BELOW_TORY = new Beside(120, 30, true);
    /**
     * An owner stands at Tory while on her ledge: within 100 px of her (the ledge runs from 77 px left of her to 89 px
     * right), from 100 px above her (a jump on it) to 40 px below (its floor; the step below it is 132 px down). Their
     * companions wait at the extras' spots meanwhile (P6b HPQ step 0: followers crowding her ledge hid her).
     */
    private static final KeepClear ON_TORYS_LEDGE = new KeepClear(100, 100, 40);

    private static final Map<String, PqEntrance> ENTRANCES = Collections.unmodifiableMap(entrances());
    /** Every entrance's key, KPQ's first. */
    private static final List<String> KEYS = List.copyOf(ENTRANCES.keySet());
    /** The entrances that keep their entry NPC clear of followers, by the map she stands on. */
    private static final Map<Integer, PqEntrance> KEEPING_CLEAR = Collections.unmodifiableMap(keepingClear());

    private final String key;
    private final AgentPartyQuestDefinition definition;
    private final int entryNpcId;
    private final SeatRules seats;
    private final int[] dpsMobIds;
    private final DeskVoice voice;
    private final BiFunction<Character, Boolean, String> memberProblem;
    private final BiFunction<Integer, Collection<Integer>, String> registrationBlocker;
    private final Supplier<? extends AgentPqPaceProfile> paceLoader;
    private final IntFunction<LiveSession> sessionOfOperator;
    private final IntFunction<String> sessionIdOfMember;
    private final boolean admitsOnIo;
    private final BooleanSupplier backgroundRuns;
    private final Beside beside;
    private final KeepClear keepClear;

    /** Where the {@code index}th extra waits from the entry NPC: {@code dx + step * index} along, grounded or not. */
    private record Beside(int dx, int step, boolean grounded) { }

    /** Where an owner stands at the entry NPC: within {@code dx} of her, from {@code above} over her to {@code below}. */
    private record KeepClear(int dx, int above, int below) { }

    private PqEntrance(String key, int entryNpcId, SeatRules seats, int[] dpsMobIds, DeskVoice voice,
                       BiFunction<Character, Boolean, String> memberProblem,
                       BiFunction<Integer, Collection<Integer>, String> registrationBlocker,
                       Supplier<? extends AgentPqPaceProfile> paceLoader, IntFunction<LiveSession> sessionOfOperator,
                       IntFunction<String> sessionIdOfMember, boolean admitsOnIo, BooleanSupplier backgroundRuns,
                       Beside beside, KeepClear keepClear) {
        this.key = key;
        this.definition = AgentPartyQuestCatalog.require(key);
        this.entryNpcId = entryNpcId;
        this.seats = seats;
        this.dpsMobIds = dpsMobIds.clone();
        this.voice = voice;
        this.memberProblem = memberProblem;
        this.registrationBlocker = registrationBlocker;
        this.paceLoader = paceLoader;
        this.sessionOfOperator = sessionOfOperator;
        this.sessionIdOfMember = sessionIdOfMember;
        this.admitsOnIo = admitsOnIo;
        this.backgroundRuns = backgroundRuns;
        this.beside = beside;
        this.keepClear = keepClear;
    }

    private static Map<String, PqEntrance> entrances() {
        Map<String, PqEntrance> entrances = new LinkedHashMap<>();
        // KPQ: stage 1's Ligator, stage 5's Jr. Necki and King Slime; admitted and started in one I/O step, as before
        entrances.put("kpq", new PqEntrance("kpq", 9_020_000, new SeatRules(3, 4, 4),
                new int[] {9_300_001, 9_300_000, 9_300_003}, LakelisLines.VOICE,
                AgentKpqAdmissionService::memberProblem, AgentKpqSessionRegistry::registrationBlocker,
                AgentKpqPaceProfile::load, operatorId -> live(AgentKpqSessionRegistry.forOperator(operatorId)),
                memberId -> sessionId(AgentKpqSessionRegistry.forMember(memberId)), true, () -> false,
                BESIDE_LAKELIS, null));
        // HPQ: the hill's three monsters' stat proxies; its admission runs on companion-ops, never on an I/O thread
        entrances.put("hpq", new PqEntrance("hpq", 1_012_112, new SeatRules(3, 6, 4),
                new int[] {9_300_062, 9_300_063, 9_300_064}, ToryLines.VOICE,
                (member, eventLeader) -> AgentHpqAdmissionService.memberProblem(member),
                AgentHpqSessionRegistry::registrationBlocker, AgentHpqPaceProfile::load,
                operatorId -> live(AgentHpqSessionRegistry.forOperator(operatorId)),
                memberId -> sessionId(AgentHpqSessionRegistry.forMember(memberId)), false,
                AgentHpqPopulationRuntime::backgroundEnabled, BELOW_TORY, ON_TORYS_LEDGE));
        return entrances;
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

    private static LiveSession live(AgentKpqSession session) {
        return session == null ? null : new LiveSession(session.sessionId(), session.eventLeaderId());
    }

    private static LiveSession live(AgentHpqSession session) {
        return session == null ? null : new LiveSession(session.sessionId(), session.eventLeaderId());
    }

    private static String sessionId(AgentKpqSession session) {
        return session == null ? null : session.sessionId();
    }

    private static String sessionId(AgentHpqSession session) {
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

    /** Builds the quest's pace profile (on companion-ops, at boot). */
    public Supplier<? extends AgentPqPaceProfile> paceLoader() {
        return paceLoader;
    }

    /**
     * The run's session while the quest's registry holds it for {@code operatorId}, its operator (a lineup's leader),
     * as {@code sessionId}; else null.
     */
    public LiveSession session(int operatorId, String sessionId) {
        LiveSession session = sessionOfOperator.apply(operatorId);
        return session != null && session.sessionId().equals(sessionId) ? session : null;
    }

    /** True while the quest's registry holds {@code agentId} in the session {@code sessionId}. */
    public boolean holds(int agentId, String sessionId) {
        String held = sessionIdOfMember.apply(agentId);
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
     * Where the {@code index}th of an owner's companions waits while the owner stands at {@code standing} on
     * {@code map}: at an entry NPC kept clear of followers (Tory, the owner on her ledge), the {@code index}th
     * extra's spot ({@link #beside}), so they never crowd her or hide her name. Null anywhere else, at an entrance that
     * keeps none (Lakelis's: beside the owner, as before), or when she is not on that map.
     */
    public Point waitingSpot(MapleMap map, Point standing, int index) {
        NPC npc = keepClear == null || map == null || standing == null ? null : map.getNPCById(entryNpcId);
        Point at = npc == null ? null : npc.getPosition();
        if (at == null || Math.abs(standing.x - at.x) > keepClear.dx() || standing.y < at.y - keepClear.above()
                || standing.y > at.y + keepClear.below()) {
            return null;
        }
        return beside(map, at, index);
    }

    private Point beside(MapleMap map, Point npcAt, int index) {
        Point point = new Point(npcAt.x + beside.dx() + beside.step() * index, npcAt.y);
        Point ground = beside.grounded() ? map.getPointBelow(point) : null;
        return ground != null ? new Point(ground) : point;
    }
}
