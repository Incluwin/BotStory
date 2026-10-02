package server.agents.capabilities.partyquest.kpq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
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
 * KPQ as the clear-time estimate sees it, from Lakelis's Yes to the stage-5 clear:
 * <pre>
 * yes + s1.open + s1.grind + s1.handin
 *   + s2.entry + s2.checks + s2.accept + s3.entry + s3.checks + s3.accept + s4.entry + s4.checks
 *   + max(s5.move + s5.descent + s5.normals + s5.boss + s5.loot + s5.carry, s5.climb) + s5.talks
 * </pre>
 * Stage 1's kills are the sum of the non-leaders' Cloto answers (one coupon per Ligator), hunted by every member, the
 * human leader at STAGE1_HUMAN_CREDIT of his rate; a puzzle's checks are uniform over its combinations, tried in
 * {@link AgentKpqCombinationOrder}'s order; stage 4 has no accept of its own, its exit being the leader's climb in
 * stage 5. In a party of three the leader holds a puzzle spot, and his share of the formation changes is replayed from
 * the combination order and {@link AgentKpqPuzzleParticipantOrder}'s rank ratios. Stage 5's normals and King Slime are
 * the Map.wz census of 103000804, read when the profile warms, fought by every member but the human leader (he waits
 * by Cloto; another human fights beside the companions) and assigned as {@link AgentKpqStageFiveTargetPolicy} assigns
 * them.
 *
 * <p>The companion priors are the 2026-10-01 timed run (session kpq-8353c615); every leader prior is an assumed quick
 * human, never measured. Content facts are not tunables: mob counts and HP come from the game data, answers and
 * combinations from the existing KPQ classes, the timer from the catalog.</p>
 */
public final class AgentKpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "kpq";

    public static final String YES = "yes";
    public static final String S1_OPEN = "s1.open";
    public static final String S1_GRIND = "s1.grind";
    public static final String S1_HANDIN = "s1.handin";
    public static final String S5_RACE = "s5.race";
    public static final String S5_MOVE = "s5.move";
    public static final String S5_DESCENT = "s5.descent";
    public static final String S5_NORMALS = "s5.normals";
    public static final String S5_BOSS = "s5.boss";
    public static final String S5_LOOT = "s5.loot";
    public static final String S5_CARRY = "s5.carry";
    public static final String S5_CLIMB = "s5.climb";
    public static final String S5_TALKS = "s5.talks";
    public static final String PUZZLE_ENTRY_TERM = "puzzle.entry";
    public static final String PUZZLE_CHECK_TERM = "puzzle.check";
    public static final String PUZZLE_MOVE_TERM = "puzzle.move";
    public static final String PUZZLE_ACCEPT_TERM = "puzzle.accept";

    private static final double STAGE1_COMBAT_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE1_COMBAT_EFFICIENCY");
    private static final double STAGE1_SEEK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE1_SEEK_MS");
    private static final double STAGE1_HUMAN_CREDIT = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE1_HUMAN_CREDIT");
    private static final double STAGE5_NORMALS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE5_NORMALS_EFFICIENCY");
    private static final double STAGE5_BOSS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE5_BOSS_EFFICIENCY");
    private static final double YES_TO_STAGE1_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.YES_TO_STAGE1_MS");
    private static final double STAGE1_OPENING_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE1_OPENING_MS");
    private static final double STAGE4_COMPANION_MOVE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE4_COMPANION_MOVE_MS");
    private static final double STAGE5_DESCENT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE5_DESCENT_MS");
    private static final double STAGE5_CARRY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.STAGE5_CARRY_MS");
    private static final double HUMAN_STAGE1_HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_STAGE1_HANDIN_MS");
    private static final double HUMAN_PUZZLE_ENTRY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_PUZZLE_ENTRY_MS");
    private static final double HUMAN_PUZZLE_CHECK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_PUZZLE_CHECK_MS");
    private static final double HUMAN_PUZZLE_MOVE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_PUZZLE_MOVE_MS");
    private static final double HUMAN_STAGE_ACCEPT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_STAGE_ACCEPT_MS");
    private static final double HUMAN_STAGE4_CLIMB_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_STAGE4_CLIMB_MS");
    private static final double HUMAN_FINAL_TALKS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.HUMAN_FINAL_TALKS_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.WEAK_HIT_CHANCE");
    private static final double SLOW_STAGE1_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.SLOW_STAGE1_MS");
    private static final double SLOW_NORMALS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.SLOW_NORMALS_MS");
    private static final double SLOW_BOSS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.SLOW_BOSS_MS");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile.PACE_EPOCH");
    /** King Slime's loot delay is the coordinator's own, so the estimate waits exactly as long as the companions. */
    private static final double KING_SLIME_LOOT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KING_SLIME_LOOT_DELAY_MS");

    private final AgentPartyQuestDefinition definition;
    private final List<Integer> mobIds;
    private final int bossId;
    private final Map<Integer, List<AgentPqPaceSegment>> segmentsBySize;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentKpqPaceProfile(AgentPartyQuestDefinition definition, List<Integer> mobIds, int bossId,
                                Map<Integer, List<AgentPqPaceSegment>> segmentsBySize, Map<String, Double> priors,
                                Thresholds thresholds) {
        this.definition = definition;
        this.mobIds = List.copyOf(mobIds);
        this.bossId = bossId;
        this.segmentsBySize = Collections.unmodifiableMap(new TreeMap<>(segmentsBySize));
        this.priors = Collections.unmodifiableMap(new TreeMap<>(priors));
        this.thresholds = thresholds;
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later on
     * a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentKpqPaceProfile load() {
        AgentKpqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load KPQ mob " + mobId);
            }
        }
        return profile;
    }

    static AgentKpqPaceProfile load(Census census) {
        Map<Integer, Integer> stageOne = census.mobCounts(AgentKpqDefinition.STAGE_1_MAP);
        if (stageOne.size() != 1) {
            throw new IllegalStateException("KPQ stage 1 should hold one mob type, Map.wz lists " + stageOne);
        }
        int quotaMob = stageOne.keySet().iterator().next();
        MobFacts quotaFacts = requireFacts(census, quotaMob);

        Map<Integer, Integer> normals = new TreeMap<>();
        Map<Integer, Integer> bosses = new TreeMap<>();
        Map<Integer, Integer> hp = new TreeMap<>();
        census.mobCounts(AgentKpqDefinition.STAGE_5_MAP).forEach((mobId, count) -> {
            MobFacts facts = requireFacts(census, mobId);
            hp.put(mobId, facts.hp());
            (facts.boss() ? bosses : normals).put(mobId, count);
        });
        if (normals.isEmpty() || bosses.size() != 1) {
            throw new IllegalStateException("KPQ stage 5 should hold normal mobs and one boss, Map.wz lists normals "
                    + normals + " and bosses " + bosses);
        }
        int bossId = bosses.keySet().iterator().next();

        AgentPartyQuestDefinition definition = AgentPartyQuestCatalog.require(QUEST_KEY);
        double[] rankWeights = AgentKpqPuzzleParticipantOrder.humanRoleWeights();
        Map<Integer, List<AgentPqPaceSegment>> segments = new TreeMap<>();
        for (int size = definition.minimumPartySize(); size <= definition.maximumPartySize(); size++) {
            segments.put(size, segments(size, quotaMob, quotaFacts.hp(), normals, bosses, hp, rankWeights));
        }
        List<Integer> mobIds = new ArrayList<>();
        mobIds.add(quotaMob);
        mobIds.addAll(normals.keySet());
        mobIds.add(bossId);
        return new AgentKpqPaceProfile(definition, mobIds, bossId, segments, priorValues(),
                new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of(
                        S1_GRIND, SLOW_STAGE1_MS, S5_NORMALS, SLOW_NORMALS_MS, S5_BOSS, SLOW_BOSS_MS)));
    }

    private static List<AgentPqPaceSegment> segments(int size, int quotaMob, int quotaHp, Map<Integer, Integer> normals,
                                                     Map<Integer, Integer> bosses, Map<Integer, Integer> hp,
                                                     double[] rankWeights) {
        List<AgentPqPaceSegment> segments = new ArrayList<>();
        segments.add(new AgentPqPaceSegment.Fixed(YES, AgentPqPaceSegment.Side.COMPANION));
        segments.add(new AgentPqPaceSegment.Fixed(S1_OPEN, AgentPqPaceSegment.Side.COMPANION));
        // Every member but the leader answers one Cloto question; each coupon is one Ligator.
        segments.add(new AgentPqPaceSegment.Quota(S1_GRIND, quotaMob, quotaHp,
                Pmf.sumOfDraws(AgentKpqDefinition.couponTargets(), size - 1), STAGE1_HUMAN_CREDIT));
        segments.add(new AgentPqPaceSegment.Fixed(S1_HANDIN, AgentPqPaceSegment.Side.LEADER));
        for (int stage = 2; stage <= 4; stage++) {
            List<List<Integer>> order = AgentKpqCombinationOrder.forPositionCount(
                    AgentKpqDefinition.combinationStage(stage).positions().size());
            // The leader holds a spot only when the party has no more members than the puzzle has spots.
            double leaderShare = size <= order.getFirst().size() ? leaderMoveShare(order, rankWeights) : 0.0d;
            segments.add(new AgentPqPaceSegment.Fixed(entryKey(stage), AgentPqPaceSegment.Side.LEADER,
                    PUZZLE_ENTRY_TERM));
            segments.add(new AgentPqPaceSegment.Puzzle(checksKey(stage), order.size(), leaderShare, PUZZLE_CHECK_TERM,
                    PUZZLE_MOVE_TERM));
            if (stage < 4) {
                segments.add(new AgentPqPaceSegment.Fixed(acceptKey(stage), AgentPqPaceSegment.Side.LEADER,
                        PUZZLE_ACCEPT_TERM));
            }
        }
        segments.add(new AgentPqPaceSegment.Parallel(S5_RACE, List.of(
                List.of(new AgentPqPaceSegment.Fixed(S5_MOVE, AgentPqPaceSegment.Side.COMPANION),
                        new AgentPqPaceSegment.Fixed(S5_DESCENT, AgentPqPaceSegment.Side.COMPANION),
                        new AgentPqPaceSegment.Clear(S5_NORMALS, normals, hp, AgentKpqStageFiveTargetPolicy::assign,
                                false),
                        new AgentPqPaceSegment.Clear(S5_BOSS, bosses, hp, AgentKpqStageFiveTargetPolicy::assign, false),
                        new AgentPqPaceSegment.Fixed(S5_LOOT, AgentPqPaceSegment.Side.COMPANION),
                        // The carry ends with the passes at the leader's feet: where he waits shapes it.
                        new AgentPqPaceSegment.Fixed(S5_CARRY, AgentPqPaceSegment.Side.COMPANION, S5_CARRY, true)),
                List.of(new AgentPqPaceSegment.Fixed(S5_CLIMB, AgentPqPaceSegment.Side.LEADER)))));
        segments.add(new AgentPqPaceSegment.Fixed(S5_TALKS, AgentPqPaceSegment.Side.LEADER));
        return List.copyOf(segments);
    }

    private static Map<String, Double> priorValues() {
        Map<String, Double> priors = new LinkedHashMap<>();
        priors.put(YES, YES_TO_STAGE1_MS);
        priors.put(S1_OPEN, STAGE1_OPENING_MS);
        priors.put(S1_GRIND + ".seek", STAGE1_SEEK_MS);
        priors.put(S1_GRIND + ".combat", STAGE1_COMBAT_EFFICIENCY);
        priors.put(S1_HANDIN, HUMAN_STAGE1_HANDIN_MS);
        priors.put(PUZZLE_ENTRY_TERM, HUMAN_PUZZLE_ENTRY_MS);
        priors.put(PUZZLE_CHECK_TERM, HUMAN_PUZZLE_CHECK_MS);
        priors.put(PUZZLE_MOVE_TERM, HUMAN_PUZZLE_MOVE_MS);
        priors.put(PUZZLE_ACCEPT_TERM, HUMAN_STAGE_ACCEPT_MS);
        priors.put(S5_MOVE, STAGE4_COMPANION_MOVE_MS);
        priors.put(S5_DESCENT, STAGE5_DESCENT_MS);
        priors.put(S5_NORMALS, STAGE5_NORMALS_EFFICIENCY);
        priors.put(S5_BOSS, STAGE5_BOSS_EFFICIENCY);
        priors.put(S5_LOOT, KING_SLIME_LOOT_DELAY_MS);
        priors.put(S5_CARRY, STAGE5_CARRY_MS);
        priors.put(S5_CLIMB, HUMAN_STAGE4_CLIMB_MS);
        priors.put(S5_TALKS, HUMAN_FINAL_TALKS_MS);
        return priors;
    }

    public static String entryKey(int stage) {
        return "s" + stage + ".entry";
    }

    public static String checksKey(int stage) {
        return "s" + stage + ".checks";
    }

    public static String acceptKey(int stage) {
        return "s" + stage + ".accept";
    }

    /**
     * How often each puzzle rank moves over the whole order: rank r starts on the first combination's r-th spot (as
     * the coordinator pre-positions), and at each next combination the ranks off it take its open spots in order (as
     * {@code AgentKpqCoordinator.assignFormation} does).
     */
    static int[] movesPerRank(List<List<Integer>> order) {
        List<Integer> first = order.getFirst();
        int[] spot = new int[first.size()];
        for (int rank = 0; rank < spot.length; rank++) spot[rank] = first.get(rank);
        int[] moves = new int[spot.length];
        for (List<Integer> combination : order.subList(1, order.size())) {
            List<Integer> held = new ArrayList<>();
            for (int position : spot) {
                if (combination.contains(position)) held.add(position);
            }
            List<Integer> open = combination.stream().filter(position -> !held.contains(position)).toList();
            int next = 0;
            for (int rank = 0; rank < spot.length; rank++) {
                if (!combination.contains(spot[rank])) {
                    spot[rank] = open.get(next++);
                    moves[rank]++;
                }
            }
        }
        return moves;
    }

    /**
     * The leader's expected share of formation changes: his rank is drawn with the configured ratios, and each rank's
     * moves are over the order's changes (exactly one member moves per change).
     */
    static double leaderMoveShare(List<List<Integer>> order, double[] rankWeights) {
        if (order.size() < 2) return 0.0d;
        int[] moves = movesPerRank(order);
        double total = 0.0d;
        for (int rank = 0; rank < moves.length && rank < rankWeights.length; rank++) total += rankWeights[rank];
        if (!(total > 0.0d)) return 0.0d;
        double share = 0.0d;
        for (int rank = 0; rank < moves.length && rank < rankWeights.length; rank++) {
            share += rankWeights[rank] / total * moves[rank] / (order.size() - 1);
        }
        return share;
    }

    private static MobFacts requireFacts(Census census, int mobId) {
        MobFacts facts = census.mob(mobId);
        if (facts == null || facts.hp() <= 0) throw new IllegalStateException("Mob.wz has no HP for KPQ mob " + mobId);
        return facts;
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
    }

    @Override
    public long timerMs() {
        return definition.eventTimerMs();
    }

    @Override
    public int minSize() {
        return definition.minimumPartySize();
    }

    @Override
    public int maxSize() {
        return definition.maximumPartySize();
    }

    @Override
    public List<Integer> mobIds() {
        return mobIds;
    }

    @Override
    public int dangerMobId() {
        return bossId;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        List<AgentPqPaceSegment> segments = segmentsBySize.get(partySize);
        if (segments == null) throw new IllegalArgumentException("KPQ takes 3-4 members, not " + partySize);
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

    /** A mob's HP and whether it is a boss. */
    record MobFacts(int hp, boolean boss) {
    }

    /** Where the profile reads its content: Map.wz life and Mob.wz stats, or a test's stub. */
    interface Census {
        /** Mob id to the number of its spawn points in the map's WZ life. */
        Map<Integer, Integer> mobCounts(int mapId);

        MobFacts mob(int mobId);

        Census WZ = new Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData(mapImage(mapId));
                if (map == null) throw new IllegalStateException("Map.wz has no map " + mapId);
                Map<Integer, Integer> counts = new TreeMap<>();
                Data life = map.getChildByPath("life");
                if (life == null) return counts;
                for (Data entry : life) {
                    if ("m".equals(DataTool.getString(entry.getChildByPath("type"), ""))) {
                        counts.merge(Integer.parseInt(DataTool.getString(entry.getChildByPath("id"), "").trim()), 1,
                                Integer::sum);
                    }
                }
                return counts;
            }

            /** Mob.wz info as LifeFactory reads it (maxHP and boss of the mob itself, boss falling back to its link). */
            @Override
            public MobFacts mob(int mobId) {
                Data info = mobInfo(mobId);
                if (info == null) return null;
                Data hp = info.getChildByPath("maxHP");
                long maxHp = hp == null ? 1L : Long.parseLong(String.valueOf(hp.getData()));
                int link = DataTool.getIntConvert("link", info, 0);
                Data linked = link == 0 ? null : mobInfo(link);
                boolean linkedBoss = linked != null && DataTool.getIntConvert("boss", linked, 0) > 0;
                return new MobFacts((int) Math.min(Integer.MAX_VALUE, maxHp),
                        DataTool.getIntConvert("boss", info, linkedBoss ? 1 : 0) > 0);
            }
        };

        private static String mapImage(int mapId) {
            return "Map/Map" + mapId / 100_000_000 + "/" + String.format("%09d", mapId) + ".img";
        }

        private static Data mobInfo(int mobId) {
            Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d", mobId) + ".img");
            return mob == null ? null : mob.getChildByPath("info");
        }
    }
}
