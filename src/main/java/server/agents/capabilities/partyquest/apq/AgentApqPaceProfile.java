package server.agents.capabilities.partyquest.apq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;
import server.life.LifeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * APQ as the clear-time estimate sees it (APQ plan A6), from the party inside to Amos's clear, for its six:
 * <pre>
 * mirror + mirrortalk + ropes + platforms + pieces + handin + gates + boss + fang
 * </pre>
 * mirror is a Clear of the Mirror's Map.wz census (the gender rooms' mobs) and Magik Fierry B, everyone fighting;
 * mirrortalk is the leader's Glimmer Man, hammer, shard and Amos (fixed, his); ropes and platforms are Puzzles whose
 * checks the companions' deduction bounds (seven tries at most on stage 2, about seven on stage 3), each check the
 * leader's talk plus the companions' re-forming; pieces is a Quota of fifty kills of Last Man Standing's mobs (each
 * drops one piece), the leader counted at LEADER_PIECE_CREDIT since he also gathers what is brought; handin is his
 * talk; gates is the companions' run through the seven gates (fixed, its slow threshold the stage's four minutes);
 * boss is a Clear of Geist Balrog's three phases (Mob.wz HP) everyone fights; fang is the leader's talk. Geist Balrog's
 * last phase decides who is fragile. Its priors are guesses until a live run measures them.
 */
public final class AgentApqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "apq";

    public static final String MIRROR = "mirror";
    public static final String MIRROR_TALK = "mirrortalk";
    public static final String ROPES = "ropes";
    public static final String PLATFORMS = "platforms";
    public static final String PIECES = "pieces";
    public static final String HANDIN = "handin";
    public static final String GATES = "gates";
    public static final String BOSS = "boss";
    public static final String FANG = "fang";
    /** The terms the two combination stages share: one leader asking Amos, the companions re-forming. */
    public static final String CHECK_TERM = "puzzle.check";
    public static final String ROPE_MOVE_TERM = "ropes.move";
    public static final String PLATFORM_MOVE_TERM = "platforms.move";

    private static final double MIRROR_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.MIRROR_EFFICIENCY");
    private static final double MIRROR_TALK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.MIRROR_TALK_MS");
    private static final int ROPE_CHECKS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.ROPE_CHECKS");
    private static final int PLATFORM_CHECKS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.PLATFORM_CHECKS");
    private static final double CHECK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.CHECK_MS");
    private static final double ROPE_MOVE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.ROPE_MOVE_MS");
    private static final double PLATFORM_MOVE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.PLATFORM_MOVE_MS");
    private static final double PIECES_SEEK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.PIECES_SEEK_MS");
    private static final double PIECES_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.PIECES_EFFICIENCY");
    private static final double LEADER_PIECE_CREDIT = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.LEADER_PIECE_CREDIT");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.HANDIN_MS");
    private static final double GATES_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.GATES_MS");
    private static final double BOSS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.BOSS_EFFICIENCY");
    private static final double FANG_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.FANG_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqPaceProfile.PACE_EPOCH");

    private final List<Integer> mobIds;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentApqPaceProfile(Map<Integer, Integer> mirror, Map<Integer, Integer> hp) {
        Map<Integer, Integer> boss = new TreeMap<>();
        AgentApqDefinition.BALROG_PHASES.forEach(phase -> boss.put(phase, 1));
        List<Integer> mobs = new ArrayList<>(mirror.keySet());
        mobs.addAll(AgentApqDefinition.PASS_MOBS.stream().sorted().toList());
        mobs.addAll(AgentApqDefinition.BALROG_PHASES);
        this.mobIds = List.copyOf(mobs);
        int pieceMob = AgentApqDefinition.PASS_MOBS.stream().min(Integer::compare).orElseThrow();
        this.segments = List.of(
                new AgentPqPaceSegment.Clear(MIRROR, mirror, hp, AgentApqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(MIRROR_TALK, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Puzzle(ROPES, ROPE_CHECKS, 1.0d, CHECK_TERM, ROPE_MOVE_TERM),
                new AgentPqPaceSegment.Puzzle(PLATFORMS, PLATFORM_CHECKS, 1.0d, CHECK_TERM, PLATFORM_MOVE_TERM),
                new AgentPqPaceSegment.Quota(PIECES, pieceMob, hp.get(pieceMob),
                        Pmf.point(AgentApqDefinition.PIECES_FOR_GATES), LEADER_PIECE_CREDIT),
                new AgentPqPaceSegment.Fixed(HANDIN, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed(GATES, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Clear(BOSS, boss, hp, AgentApqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(FANG, AgentPqPaceSegment.Side.LEADER));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(MIRROR, MIRROR_EFFICIENCY);
        values.put(MIRROR_TALK, MIRROR_TALK_MS);
        values.put(CHECK_TERM, CHECK_MS);
        values.put(ROPE_MOVE_TERM, ROPE_MOVE_MS);
        values.put(PLATFORM_MOVE_TERM, PLATFORM_MOVE_MS);
        values.put(PIECES + ".seek", PIECES_SEEK_MS);
        values.put(PIECES + ".combat", PIECES_EFFICIENCY);
        values.put(HANDIN, HANDIN_MS);
        values.put(GATES, GATES_MS);
        values.put(BOSS, BOSS_EFFICIENCY);
        values.put(FANG, FANG_MS);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        Map<String, Double> slow = new LinkedHashMap<>();
        slow.put(GATES, (double) AgentApqDefinition.GATE_RUN_TIMER_MS);
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, slow);
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentApqPaceProfile load() {
        AgentApqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load APQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: the Mirror's rooms as Map.wz places them, every mob's HP from Mob.wz. */
    static AgentApqPaceProfile load(Census census) {
        Map<Integer, Integer> mirror = new TreeMap<>();
        census.mobCounts(AgentApqDefinition.MIRROR_MAP).forEach((mobId, count) -> {
            if (AgentApqDefinition.MIRROR_MOBS.contains(mobId) && count != null && count > 0) mirror.put(mobId, count);
        });
        if (mirror.isEmpty()) throw new IllegalStateException("Map.wz places no APQ room mob on the Mirror");
        mirror.put(AgentApqDefinition.FIERRY, 1);
        List<Integer> hpOf = new ArrayList<>(mirror.keySet());
        hpOf.addAll(AgentApqDefinition.PASS_MOBS);
        hpOf.addAll(AgentApqDefinition.BALROG_PHASES);
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : hpOf) {
            Integer mobHp = census.mobHp(mobId);
            if (mobHp == null || mobHp <= 0) throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            hp.put(mobId, mobHp);
        }
        return new AgentApqPaceProfile(mirror, hp);
    }

    /** Every member on the lowest live mob id it can hurt: the estimate knows no positions, so types fall in turn. */
    static Map<Integer, Integer> everyoneOnTheLowest(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        new TreeMap<>(damageByMember).forEach((memberId, damage) -> {
            if (damage != null && !damage.isEmpty()) {
                choice.put(memberId, damage.keySet().stream().min(Integer::compare).orElseThrow());
            }
        });
        return choice;
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
    }

    /** AmoriaPQ.js's 75 minutes. */
    @Override
    public long timerMs() {
        return AgentApqDefinition.EVENT_TIMER_MS;
    }

    @Override
    public int minSize() {
        return AgentApqDefinition.PARTY_SIZE;
    }

    @Override
    public int maxSize() {
        return AgentApqDefinition.PARTY_SIZE;
    }

    @Override
    public List<Integer> mobIds() {
        return mobIds;
    }

    @Override
    public int dangerMobId() {
        return AgentApqDefinition.BALROG_LAST_PHASE;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize != AgentApqDefinition.PARTY_SIZE) {
            throw new IllegalArgumentException("APQ takes six members, not " + partySize);
        }
        return segments;
    }

    @Override
    public Map<String, Double> priors() {
        return priors;
    }

    @Override
    public int epoch() {
        return PACE_EPOCH;
    }

    @Override
    public Thresholds thresholds() {
        return thresholds;
    }

    @Override
    public Set<String> terms() {
        return terms;
    }

    @Override
    public Set<String> leaderTerms() {
        return leaderTerms;
    }

    /** Where the profile reads its content: Map.wz life and Mob.wz HP, or a test's stub. */
    interface Census {
        /** Mob id to the number of its spawn points in the map's WZ life. */
        Map<Integer, Integer> mobCounts(int mapId);

        /** The mob's max HP; null when Mob.wz has none. */
        Integer mobHp(int mobId);

        Census WZ = new Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData(
                        "Map/Map" + mapId / 100_000_000 + "/" + String.format("%09d", mapId) + ".img");
                if (map == null) throw new IllegalStateException("Map.wz has no map " + mapId);
                Map<Integer, Integer> counts = new TreeMap<>();
                Data life = map.getChildByPath("life");
                if (life != null) {
                    for (Data entry : life) {
                        if ("m".equals(DataTool.getString(entry.getChildByPath("type"), ""))) {
                            counts.merge(Integer.parseInt(DataTool.getString(entry.getChildByPath("id"), "").trim()),
                                    1, Integer::sum);
                        }
                    }
                }
                return counts;
            }

            @Override
            public Integer mobHp(int mobId) {
                Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB)
                        .getData(String.format("%07d", mobId) + ".img");
                Data info = mob == null ? null : mob.getChildByPath("info");
                Data hp = info == null ? null : info.getChildByPath("maxHP");
                return hp == null ? null
                        : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(String.valueOf(hp.getData())));
            }
        };
    }
}
