package server.agents.capabilities.expedition.gpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.boss.AgentBossPriorityFight;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sharenian, the Guild Quest, for adopted companions (GPQ plan B4-B5), the boss run's fight for key gpq: the boss track
 * admits the owner's companions standing by Shuang when the guild is called in, each joins at her while the strategy
 * time lasts, and the run adopts it on any Sharenian map (boss-rules.json stageMaps). Each tick this hands the members
 * on each map to that map's play (AgentGpqStagePlays): the rocks, the Gatekeeper's statues, the Longinus spears, the
 * vassals' offerings, the waterway's clothes, the King's Corridor's sacrifice, and on Ergoth's throne the boss track's
 * priority fight under boss-rules.json gpq. Every leader-only step (the Gatekeeper, the fountain, Sharen III's ghost,
 * the Rubian's turn-in) stays the human leader's: companions get the stage ready and tell them when to talk. After the
 * clear it plays the Treasure Warehouse's boxes and the walk out by Nuris.
 */
public final class AgentGpqRun implements AgentBossFight {
    private static final Logger log = LoggerFactory.getLogger(AgentGpqRun.class);

    /** The boss key (boss-rules.json, AgentBossRunCatalog, living_world.COMPANION_BOSS_ENABLED_TYPES). */
    public static final String KEY = "gpq";
    /** Victoria Road: Excavation Site&lt;Camp&gt;, where Shuang registers and lets members join. */
    public static final int CAMP_MAP_ID = 101_030_104;
    public static final int SHUANG_NPC_ID = 9_040_000;
    /** Shuang's "Join your guild's Guild Quest" (npc/9040000.js selection 1). */
    public static final int JOIN_SELECTION = 1;

    /** The instance maps the run plays on. */
    public enum Stage {
        EXCAVATION(990_000_000),
        VALLEY(990_000_100),
        ENTRANCE(990_000_200),
        GATEKEEPER(990_000_300),
        ROAD(990_000_301),
        HALL(990_000_400),
        MIDDLE_PATH(990_000_401),
        GLORY(990_000_410),
        COURAGE(990_000_420),
        FAITH(990_000_430),
        PLEDGE(990_000_431),
        JUSTICE(990_000_440),
        FOUNTAIN(990_000_500),
        BANQUET(990_000_501),
        CELLAR(990_000_502),
        WATERWAY(990_000_600),
        GOLEM_MAZE(990_000_610),
        PANTS_END(990_000_611),
        SHOES_MAZE(990_000_620),
        GARGOYLE_MAZE(990_000_630),
        TOP_END(990_000_631),
        SLIME_MAZE(990_000_640),
        CROWN_END(990_000_641),
        GRAVE(990_000_700),
        CORRIDOR(990_000_800),
        THRONE(990_000_900);

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

        /** The Longinus spear stage's maps: the Hall of the Knight and its five rooms. */
        boolean spearStage() {
            return this == HALL || this == GLORY || this == COURAGE || this == FAITH || this == PLEDGE
                    || this == JUSTICE;
        }

        /** The waterway stage's maps: the hub, its rooms and their ends (the grave is its own). */
        boolean waterwayStage() {
            return this == WATERWAY || this == GOLEM_MAZE || this == PANTS_END || this == SHOES_MAZE
                    || this == GARGOYLE_MAZE || this == TOP_END || this == SLIME_MAZE || this == CROWN_END;
        }
    }

    /** One stage's play: what its members do this tick. */
    @FunctionalInterface
    public interface StagePlay {
        void tick(List<Character> members, EventInstanceManager event, AgentGpqRun run, long nowMs);
    }

    /** Ergoth's Throne, the battle map (AgentBossRunDefinition.battleMapId). */
    public static final int THRONE_MAP_ID = Stage.THRONE.mapId;
    /** Sharen III's Treasure Warehouse, where the clear warps the team for its 30 s of boxes. */
    public static final int WAREHOUSE_MAP_ID = 990_001_000;
    /** The Returning Path, where Nuris (9040001) sees the team out to the camp. */
    public static final int RETURNING_MAP_ID = 990_001_100;

    private final Map<Stage, StagePlay> plays;
    private final Map<Integer, Integer> ordinals = new ConcurrentHashMap<>();
    private final Set<Stage> unbuiltLogged = EnumSet.noneOf(Stage.class);
    /** Member id -> when it may act again (a portal it just took, a hit it just made). */
    private final Map<Integer, Long> busyUntil = new ConcurrentHashMap<>();
    /** Task -> the member that holds it ("rock:<oid>", "spear:<oid>", "altar:spear2", "offer:<area>"). */
    private final Map<String, Integer> claims = new ConcurrentHashMap<>();
    /** What a member said once this run ("strategy"). */
    private final Set<String> said = ConcurrentHashMap.newKeySet();
    /** Each member's job in its stage as the plays last set it (role / roleOf). */
    private final Map<Integer, String> roles = new ConcurrentHashMap<>();
    /** The stage each member was on at its last tick. */
    private final Map<Integer, Stage> stagesOf = new ConcurrentHashMap<>();
    /** Counters and markers the plays keep over the run. */
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    /** Stage 2: the spear plan of the last tick, shared by the stage's maps (AgentGpqSpearPlays.plan). */
    private volatile AgentGpqSpearPlays.Plan spearPlan;
    /** Stage 3: the vassals' offerings as the party works them out. */
    private final AgentGpqVassals vassals = new AgentGpqVassals();
    /** Ergoth's fight: the boss track's priority fight under boss-rules.json gpq, made on first use. */
    private final Supplier<AgentBossFight> throneFight;
    private AgentBossFight throne;
    /** After the clear: the Treasure Warehouse's boxes and the walk out; null plays nothing. */
    private final StagePlay afterClear;
    private volatile int leaderId;
    /** As a member enters a stage, the live run stops the walk its last stage's play gave it (CWKPQ C9). */
    private Consumer<Character> enteredStage = member -> {
    };

    AgentGpqRun(Map<Stage, StagePlay> plays, Supplier<AgentBossFight> throneFight, StagePlay afterClear) {
        this.plays = plays.isEmpty() ? Map.of() : new EnumMap<>(plays);
        this.throneFight = throneFight;
        this.afterClear = afterClear;
    }

    /** A fresh run for one boss run session, with every stage play and the walk out. */
    public static AgentGpqRun forCompanions() {
        AgentGpqPorts ports = AgentGpqPorts.LIVE;
        AgentGpqRun created = new AgentGpqRun(AgentGpqStagePlays.all(ports), AgentBossPriorityFight.forKey(KEY),
                (members, event, run, nowMs) -> AgentGpqThronePlays.afterClear(ports, members, event, run, nowMs));
        created.enteredStage = member -> {
            AgentRuntimeEntry entry = ports.entry(member.getId());
            if (entry != null) {
                ports.gateway().stop(entry);
            }
        };
        return created;
    }

    @Override
    public boolean playsAfterClear(int mapId) {
        return afterClear != null && (mapId == WAREHOUSE_MAP_ID || mapId == RETURNING_MAP_ID);
    }

    @Override
    public void tickAfterClear(List<Character> members, EventInstanceManager event, long nowMs) {
        observe(event);
        if (afterClear != null && !members.isEmpty()) {
            afterClear.tick(members, event, this, nowMs);
        }
    }

    private void observe(EventInstanceManager event) {
        if (event != null) {
            leaderId = event.getLeaderId();
        }
    }

    /** The instance leader (the human who registered the guild), as last seen. */
    int leaderId() {
        return leaderId;
    }

    /** Tests: what happens as a member enters a stage. */
    void onEnteredStage(Consumer<Character> hook) {
        this.enteredStage = hook;
    }

    @Override
    public void adopt(int characterId, int ordinal) {
        ordinals.put(characterId, ordinal);
    }

    /** The member's formation slot, by adoption order; -1 when it was not adopted by this run. */
    public int ordinal(int characterId) {
        return ordinals.getOrDefault(characterId, -1);
    }

    /** Every adopted member's id, in slot order. */
    List<Integer> adopted() {
        List<Integer> ids = new ArrayList<>(ordinals.keySet());
        ids.sort(Comparator.comparingInt(this::ordinal));
        return ids;
    }

    @Override
    public void tick(List<Character> fighters, EventInstanceManager event, long nowMs) {
        observe(event);
        Map<Stage, List<Character>> byStage = new EnumMap<>(Stage.class);
        for (Character fighter : fighters) {
            Stage stage = Stage.of(fighter.getMapId());
            if (stage != null) {
                byStage.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(fighter);
                if (stagesOf.put(fighter.getId(), stage) != stage) {
                    roles.remove(fighter.getId()); // its last stage's job no longer applies
                    enteredStage.accept(fighter);
                }
            }
        }
        for (Map.Entry<Stage, List<Character>> here : byStage.entrySet()) {
            StagePlay play = plays.get(here.getKey());
            if (play != null) {
                play.tick(here.getValue(), event, this, nowMs);
            } else if (unbuiltLogged.add(here.getKey())) {
                log.info("GPQ stage {} has no companion play yet: {} member(s) stand where they are",
                        here.getKey(), here.getValue().size());
            }
        }
    }

    @Override
    public void end(EventInstanceManager event) {
        ordinals.clear();
        busyUntil.clear();
        claims.clear();
        marks.clear();
        vassals.reset();
        AgentBossFight fight;
        synchronized (this) {
            fight = throne;
            throne = null;
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

    /**
     * Gives {@code task} to {@code memberId} unless another member holds it; true when it is {@code memberId}'s now.
     * A member holds as many tasks as the plays give it.
     */
    boolean claim(String task, int memberId) {
        Integer holder = claims.putIfAbsent(task, memberId);
        return holder == null || holder == memberId;
    }

    /** The member holding {@code task}, or 0. */
    int holder(String task) {
        return claims.getOrDefault(task, 0);
    }

    void release(String task) {
        claims.remove(task);
    }

    /** Lets go of every task {@code memberId} holds whose name starts with {@code prefix}. */
    void releaseAll(int memberId, String prefix) {
        claims.entrySet().removeIf(held -> held.getValue() == memberId && held.getKey().startsWith(prefix));
    }

    /** The tasks {@code memberId} holds whose name starts with {@code prefix}. */
    List<String> claimsOf(int memberId, String prefix) {
        List<String> held = new ArrayList<>();
        claims.forEach((task, holder) -> {
            if (holder == memberId && task.startsWith(prefix)) {
                held.add(task);
            }
        });
        held.sort(Comparator.naturalOrder());
        return held;
    }

    /**
     * Records the job a stage play gave {@code memberId} this tick, in words; true when it changed (the play logs the
     * change, and !gpqtest why shows the last one).
     */
    boolean role(int memberId, String role) {
        return !role.equals(roles.put(memberId, role));
    }

    /** The job a stage play last gave {@code memberId}, or null. */
    public String roleOf(int memberId) {
        return roles.get(memberId);
    }

    /** True the first time {@code what} is said this run. */
    boolean once(String what) {
        return said.add(what);
    }

    /** Forgets that {@code what} was said, so it may be said again (a phase that came round again). */
    void unsay(String what) {
        said.remove(what);
    }

    /** A value the plays keep over the run (a count, a time); 0 when unset. */
    long mark(String key) {
        return marks.getOrDefault(key, 0L);
    }

    void mark(String key, long value) {
        marks.put(key, value);
    }

    /** Counts {@code key} once more; the new count. */
    long bump(String key) {
        return marks.merge(key, 1L, Long::sum);
    }

    AgentGpqVassals vassals() {
        return vassals;
    }

    AgentGpqSpearPlays.Plan spearPlan() {
        return spearPlan;
    }

    void spearPlan(AgentGpqSpearPlays.Plan plan) {
        spearPlan = plan;
    }

    /** Ergoth's priority fight, one per run (it keeps per-member state). */
    synchronized AgentBossFight throne() {
        if (throne == null) {
            throne = throneFight.get();
        }
        return throne;
    }
}
