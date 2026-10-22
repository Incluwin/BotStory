package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.boss.AgentBossPriorityFight;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.Reactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * The Crimsonwood Keep run for adopted companions (CWKPQ plan Part B), the boss run's fight for key cwkpq: the boss
 * track signs members up at Jack and adopts them on any stage map (boss-rules.json stageMaps), and each tick this
 * hands the members on each stage map to that stage's play (AgentCwkpqStagePlays). The stage plays are built phase by
 * phase (C4, built: hallway, storage chamber and wit; C5: agility; C6: unity and the class rooms; C7: the Council
 * Hall); a stage with no play yet leaves its members standing where they are, logged once per run, so the run stays
 * switched off until all are built.
 * The run's clear is the Battle Statue's third talk (eim.clearPQ); after it the run still plays the Council Hall, the
 * Secret Chamber and the Armory (C8, AgentCwkpqArmoryPlay): the members walk on, wait until someone has started the
 * Armory's minute, and break their share of its boxes.
 */
public final class AgentCwkpqRun implements AgentBossFight {
    private static final Logger log = LoggerFactory.getLogger(AgentCwkpqRun.class);

    /** The instance maps the run plays on, in order. */
    public enum Stage {
        HALLWAY(610_030_100),
        STORAGE(610_030_200),
        AGILITY(610_030_300),
        WIT(610_030_400),
        UNITY(610_030_500),
        WARRIOR_ROOM(610_030_510),
        MAGICIAN_ROOM(610_030_521),
        THIEF_ROOM(610_030_530),
        BOWMAN_ROOM(610_030_540),
        PIRATE_ROOM(610_030_550),
        COUNCIL(610_030_600);

        private final int mapId;

        Stage(int mapId) {
            this.mapId = mapId;
        }

        public int mapId() {
            return mapId;
        }

        /** The stage on {@code mapId}, or null off the run's maps. */
        public static Stage of(int mapId) {
            for (Stage stage : values()) {
                if (stage.mapId == mapId) {
                    return stage;
                }
            }
            return null;
        }
    }

    /** One stage's play: what its members do this tick. */
    @FunctionalInterface
    public interface StagePlay {
        void tick(List<Character> members, EventInstanceManager event, AgentCwkpqRun run, long nowMs);
    }

    /** The Council Hall, the battle map (AgentBossRunDefinition.battleMapId). */
    public static final int COUNCIL_HALL_MAP_ID = Stage.COUNCIL.mapId;

    private final Map<Stage, StagePlay> plays;
    private final Map<Integer, Integer> ordinals = new ConcurrentHashMap<>();
    private final Set<Stage> unbuiltLogged = EnumSet.noneOf(Stage.class);
    /** Member id -> when it may act again (a portal it just took, a cast it just made). */
    private final Map<Integer, Long> busyUntil = new ConcurrentHashMap<>();
    /** Member id -> the stage-4 spot (reactor object id) it works on. */
    private final Map<Integer, Integer> spots = new ConcurrentHashMap<>();
    /** Stage-4 spot -> when its plain test hit was made. */
    private final Map<Integer, Long> testedAt = new ConcurrentHashMap<>();
    /** Stage-4 spots a plain hit did not move: real sigils. */
    private final Set<Integer> knownReal = ConcurrentHashMap.newKeySet();
    /** What a member said once this run ("hallway-hint"). */
    private final Set<String> said = ConcurrentHashMap.newKeySet();
    /** Each member's job in its stage as the plays last set it (role / roleOf). */
    private final Map<Integer, String> roles = new ConcurrentHashMap<>();
    /** The stage each member was on at its last tick. */
    private final Map<Integer, Stage> stagesOf = new ConcurrentHashMap<>();
    /** Stage 2-3 sigils seen turned, by anyone ("AGILITY:WARRIOR"): one comes back later and must not be turned again. */
    private final Set<String> turnedSigils = ConcurrentHashMap.newKeySet();
    /** Counters the plays keep over the run (a member's talks to a master that gave nothing). */
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    /** The Council Hall's fight: the boss track's priority fight under boss-rules.json cwkpq, made on first use. */
    private final Supplier<AgentBossFight> councilFight;
    private AgentBossFight council;
    /** After the clear (C8): the walk on through the Secret Chamber and the Armory's boxes; null plays nothing. */
    private final StagePlay afterClear;
    /** Armory box (reactor object id) -> the member whose share it is. */
    private final Map<Integer, Integer> boxOwners = new ConcurrentHashMap<>();
    /** The members the boxes were last shared among, in share order. */
    private List<Integer> sharedAmong = List.of();
    /** Member id -> the Armory box it is breaking. */
    private final Map<Integer, Integer> working = new ConcurrentHashMap<>();
    /** C8: when the run reached each step, for the clear-time records. */
    private final AgentCwkpqPaceMarks marks = new AgentCwkpqPaceMarks();
    private Supplier<AgentPqPaceProfile> paceProfile =
            () -> AgentPqPaceProfiles.cached(AgentCwkpqPaceProfile.QUEST_KEY);
    private IntPredicate isAgent = id -> AgentRuntimeRegistry.findByAgentCharacterId(id) != null;
    private volatile int leaderId;

    AgentCwkpqRun(Map<Stage, StagePlay> plays) {
        this(plays, AgentBossPriorityFight.forKey("cwkpq"), null);
    }

    AgentCwkpqRun(Map<Stage, StagePlay> plays, Supplier<AgentBossFight> councilFight) {
        this(plays, councilFight, null);
    }

    AgentCwkpqRun(Map<Stage, StagePlay> plays, Supplier<AgentBossFight> councilFight, StagePlay afterClear) {
        this.plays = plays.isEmpty() ? Map.of() : new EnumMap<>(plays);
        this.councilFight = councilFight;
        this.afterClear = afterClear;
    }

    /** A fresh run for one boss run session, with every stage play built so far and the walk on to the Armory. */
    public static AgentCwkpqRun forCompanions() {
        AgentCwkpqPorts ports = AgentCwkpqPorts.LIVE;
        return new AgentCwkpqRun(AgentCwkpqStagePlays.all(ports), AgentBossPriorityFight.forKey("cwkpq"),
                (members, event, run, nowMs) -> AgentCwkpqArmoryPlay.tick(ports, members, event, run, nowMs));
    }

    /** The Council Hall, the Secret Chamber and the Armory: the walk on and the boxes, once the keep is cleared. */
    @Override
    public boolean playsAfterClear(int mapId) {
        return afterClear != null && AgentCwkpqArmoryPlay.playsOn(mapId);
    }

    @Override
    public void tickAfterClear(List<Character> members, EventInstanceManager event, long nowMs) {
        observe(event, nowMs);
        if (afterClear != null && !members.isEmpty()) {
            afterClear.tick(members, event, this, nowMs);
        }
    }

    /**
     * C8: how the run went, one span per segment of the CWKPQ pace profile, for the clear-time ledger; null while the
     * profile is cold or the members' power could not be read. Its session id carries the start, since a boss run's
     * id restarts with the server.
     */
    @Override
    public AgentPqPace pace(long sessionId, long startedAtMs, long endedAtMs) {
        return marks.pace(paceProfile.get(), AgentCwkpqPaceProfile.QUEST_KEY + "-" + startedAtMs + "-" + sessionId,
                leaderId, startedAtMs, endedAtMs);
    }

    /** Notes the steps the event shows now (every tick, before and after the clear). */
    private void observe(EventInstanceManager event, long nowMs) {
        if (event == null) {
            return;
        }
        leaderId = event.getLeaderId();
        marks.observe(event, paceProfile.get(), isAgent, nowMs);
    }

    /** Tests: the pace profile and which characters are Agents. */
    void measuredWith(Supplier<AgentPqPaceProfile> profile, IntPredicate agents) {
        this.paceProfile = profile;
        this.isAgent = agents;
    }

    AgentCwkpqPaceMarks marks() {
        return marks;
    }

    /** The maps before the battle map (boss-rules.json stageMaps for cwkpq), in order. */
    public static List<Integer> stageMapsBeforeCouncil() {
        List<Integer> maps = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (stage != Stage.COUNCIL) {
                maps.add(stage.mapId);
            }
        }
        return maps;
    }

    @Override
    public void adopt(int characterId, int ordinal) {
        ordinals.put(characterId, ordinal);
    }

    /** The member's formation slot, by adoption order; -1 when it was not adopted by this run. */
    public int ordinal(int characterId) {
        return ordinals.getOrDefault(characterId, -1);
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        observe(event, nowMs);
        Map<Stage, List<Character>> byStage = new EnumMap<>(Stage.class);
        for (Character fighter : fighters) {
            Stage stage = Stage.of(fighter.getMapId());
            if (stage != null) {
                byStage.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(fighter);
                if (stagesOf.put(fighter.getId(), stage) != stage) {
                    roles.remove(fighter.getId()); // its last stage's job no longer applies
                }
            }
        }
        for (Map.Entry<Stage, List<Character>> here : byStage.entrySet()) {
            StagePlay play = plays.get(here.getKey());
            if (play != null) {
                play.tick(here.getValue(), event, this, nowMs);
            } else if (unbuiltLogged.add(here.getKey())) {
                log.info("CWKPQ stage {} has no companion play yet: {} member(s) stand where they are",
                        here.getKey(), here.getValue().size());
            }
        }
    }

    @Override
    public void end(EventInstanceManager event) {
        marks.clear();
        ordinals.clear();
        busyUntil.clear();
        spots.clear();
        testedAt.clear();
        knownReal.clear();
        counts.clear();
        boxOwners.clear();
        working.clear();
        synchronized (this) {
            sharedAmong = List.of();
        }
        AgentBossFight fight;
        synchronized (this) {
            fight = council;
            council = null;
        }
        if (fight != null) {
            fight.end(event);
        }
    }

    // ---- what the stage plays remember over the run ----

    /** True when {@code memberId} may act at {@code nowMs}. */
    boolean ready(int memberId, long nowMs) {
        return nowMs >= busyUntil.getOrDefault(memberId, 0L);
    }

    void busy(int memberId, long untilMs) {
        busyUntil.merge(memberId, untilMs, Math::max);
    }

    /** The stage-4 spot {@code memberId} works on, or null. */
    Integer spotOf(int memberId) {
        return spots.get(memberId);
    }

    /** Gives {@code memberId} the stage-4 spot {@code spot}, or none for null. */
    void assign(int memberId, Integer spot) {
        if (spot == null) {
            spots.remove(memberId);
        } else {
            spots.put(memberId, spot);
        }
    }

    /** True when a member other than {@code memberId} works on {@code spot}. */
    boolean spotTaken(int spot, int memberId) {
        return spots.entrySet().stream().anyMatch(held -> held.getValue() == spot && held.getKey() != memberId);
    }

    Long testedAt(int spot) {
        return testedAt.get(spot);
    }

    void tested(int spot, long nowMs) {
        testedAt.put(spot, nowMs);
    }

    boolean knownReal(int spot) {
        return knownReal.contains(spot);
    }

    void markReal(int spot) {
        knownReal.add(spot);
    }

    /**
     * Records the job a stage play gave {@code memberId} this tick, in words; true when it changed (the play logs the
     * change, and !cwkpqtest why shows the last one).
     */
    boolean role(int memberId, String role) {
        return !role.equals(roles.put(memberId, role));
    }

    /** The job a stage play last gave {@code memberId}, or null. */
    public String roleOf(int memberId) {
        return roles.get(memberId);
    }

    /** Records that {@code archetype}'s sigil on {@code stage} was turned. */
    void sigilTurned(Stage stage, AgentCwkpqSigils.Archetype archetype) {
        turnedSigils.add(stage + ":" + archetype);
    }

    /** True once {@code archetype}'s sigil on {@code stage} was seen turned. */
    boolean wasTurned(Stage stage, AgentCwkpqSigils.Archetype archetype) {
        return turnedSigils.contains(stage + ":" + archetype);
    }

    /** True the first time {@code what} is said this run. */
    boolean once(String what) {
        return said.add(what);
    }

    /** How many times {@code key} was counted this run. */
    int count(String key) {
        return counts.getOrDefault(key, 0);
    }

    /** Counts {@code key} once more; the new count. */
    int bump(String key) {
        return counts.merge(key, 1, Integer::sum);
    }

    /**
     * Shares the live Armory boxes among {@code sharers} (humans first, then companions), each a run of neighbouring
     * boxes along the shelves (AgentCwkpqArmoryPlay.path); kept while the same members share, cut again over the boxes
     * still standing when one comes or goes. True when this call cut the shares.
     */
    synchronized boolean shareBoxes(List<Integer> sharers, Collection<Reactor> liveBoxes) {
        if (sharers.isEmpty() || sharers.equals(sharedAmong)) {
            return false;
        }
        sharedAmong = List.copyOf(sharers);
        boxOwners.clear();
        List<Reactor> path = AgentCwkpqArmoryPlay.path(liveBoxes);
        for (int index = 0; index < path.size(); index++) {
            boxOwners.put(path.get(index).getObjectId(), sharers.get(index * sharers.size() / path.size()));
        }
        return true;
    }

    /** The member whose share the box is, or null. */
    Integer boxOwner(int boxObjectId) {
        return boxOwners.get(boxObjectId);
    }

    /** The Armory box {@code memberId} is breaking, or null. */
    Integer boxOf(int memberId) {
        return working.get(memberId);
    }

    void workOn(int memberId, Integer boxObjectId) {
        if (boxObjectId == null) {
            working.remove(memberId);
        } else {
            working.put(memberId, boxObjectId);
        }
    }

    /** The Council Hall's priority fight, one per run (it keeps per-member state). */
    synchronized AgentBossFight council() {
        if (council == null) {
            council = councilFight.get();
        }
        return council;
    }
}
