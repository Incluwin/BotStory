package server.agents.capabilities.partyquest.lpq;

import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * LPQ as the clear-time estimate sees a companion run, from the Red Sign's Yes to the Violet Balloon's clear:
 * <pre>
 * yes + s1..s5 (collect or rooms, then handin) + s6.climb + s7.fight + s7.handin + s8.entry + s8.checks
 *     + s9.ratz + s9.boss + s9.key
 * </pre>
 * The companions collect each stage's passes up to the leader's feet (leader-bounded: where he waits shapes it); the
 * leader picks them up and talks the balloon through (handin, one term for every stage), climbs the stage-6 maze,
 * asks the Blue Balloon at every stage-8 formation (a puzzle over the 126 five-of-nine combinations, in the stock
 * order), and brings Alishar's key to the Violet Balloon. Alishar is a census clear the companions fight while the
 * leader stays out of his reach (S9: as KPQ parks its leader, he does not fight). The segments are the same for five
 * and six; only the puzzle's share of formation changes that move the leader differs (he holds a box only at five).
 * Every prior is an agent-engine.yaml tunable but Alishar's HP, which is content, read from LifeFactory. A host
 * registers it by {@link #QUEST_KEY} with {@link #load} (on companion-ops).
 */
public final class AgentLpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "lpq";

    public static final String YES = "yes";
    public static final String HANDIN = "handin";
    public static final String S6_CLIMB = "s6.climb";
    public static final String S7_FIGHT = "s7.fight";
    public static final String S8_ENTRY = "s8.entry";
    public static final String S8_CHECKS = "s8.checks";
    public static final String PUZZLE_CHECK = "puzzle.check";
    public static final String PUZZLE_MOVE = "puzzle.move";
    public static final String S9_RATZ = "s9.ratz";
    public static final String S9_BOSS = "s9.boss";
    public static final String S9_KEY = "s9.key";

    /**
     * The tower's mobs a member's damage is weighed against, as Mob.wz names them: Shadow Eye (9300008), Dark Eye
     * (9300014), Rombard (9300010) and Alishar (9300012, 125,000 HP), who hits hardest, so his touch decides who is
     * fragile.
     */
    private static final List<Integer> MOB_IDS = List.of(AgentLpqDefinition.STAGE_4_MAGIC_MOB,
            AgentLpqDefinition.STAGE_4_PHYSICAL_MOB, AgentLpqDefinition.ROMBARD, AgentLpqDefinition.ALISHAR);

    private static final double YES_TO_STAGE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.YES_TO_STAGE_MS");
    private static final double S1_COLLECT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S1_COLLECT_MS");
    private static final double S2_COLLECT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S2_COLLECT_MS");
    private static final double S3_COLLECT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S3_COLLECT_MS");
    private static final double S4_ROOMS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S4_ROOMS_MS");
    private static final double S5_ROOMS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S5_ROOMS_MS");
    private static final double S7_FIGHT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S7_FIGHT_MS");
    private static final double S9_RATZ_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S9_RATZ_MS");
    private static final double S9_BOSS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.S9_BOSS_EFFICIENCY");
    private static final double HUMAN_HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_HANDIN_MS");
    private static final double HUMAN_CLIMB_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_CLIMB_MS");
    private static final double HUMAN_PUZZLE_ENTRY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_PUZZLE_ENTRY_MS");
    private static final double HUMAN_PUZZLE_CHECK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_PUZZLE_CHECK_MS");
    private static final double HUMAN_PUZZLE_MOVE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_PUZZLE_MOVE_MS");
    private static final double HUMAN_KEY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.HUMAN_KEY_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile.PACE_EPOCH");

    private final AgentPartyQuestDefinition definition;
    private final Map<Integer, List<AgentPqPaceSegment>> segmentsBySize;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentLpqPaceProfile(AgentPartyQuestDefinition definition, int alisharHp, Map<String, Double> priors) {
        this.definition = definition;
        Map<Integer, List<AgentPqPaceSegment>> bySize = new TreeMap<>();
        for (int size = definition.minimumPartySize(); size <= definition.maximumPartySize(); size++) {
            bySize.put(size, segments(size, alisharHp));
        }
        this.segmentsBySize = Collections.unmodifiableMap(bySize);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(priors));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of());
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Reads Alishar's max HP from LifeFactory, then loads the profile's mobs there too, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentLpqPaceProfile load() {
        Monster alishar = LifeFactory.getMonster(AgentLpqDefinition.ALISHAR);
        if (alishar == null) {
            throw new IllegalStateException("LifeFactory cannot load Alishar " + AgentLpqDefinition.ALISHAR);
        }
        AgentLpqPaceProfile profile = load(alishar.getMaxHp());
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load LPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile for an Alishar of this max HP (Mob.wz maxHP, as LifeFactory reads it). */
    static AgentLpqPaceProfile load(long alisharHp) {
        if (alisharHp <= 0L || alisharHp > Integer.MAX_VALUE) {
            throw new IllegalStateException("Alishar has no usable HP (" + alisharHp + "): stage 9 cannot be priced");
        }
        Map<String, Double> priors = new LinkedHashMap<>();
        priors.put(YES, YES_TO_STAGE_MS);
        priors.put(collectKey(1), S1_COLLECT_MS);
        priors.put(collectKey(2), S2_COLLECT_MS);
        priors.put(collectKey(3), S3_COLLECT_MS);
        priors.put(collectKey(4), S4_ROOMS_MS);
        priors.put(collectKey(5), S5_ROOMS_MS);
        priors.put(S7_FIGHT, S7_FIGHT_MS);
        priors.put(HANDIN, HUMAN_HANDIN_MS);
        priors.put(S6_CLIMB, HUMAN_CLIMB_MS);
        priors.put(S8_ENTRY, HUMAN_PUZZLE_ENTRY_MS);
        priors.put(PUZZLE_CHECK, HUMAN_PUZZLE_CHECK_MS);
        priors.put(PUZZLE_MOVE, HUMAN_PUZZLE_MOVE_MS);
        priors.put(S9_RATZ, S9_RATZ_MS);
        priors.put(S9_BOSS, S9_BOSS_EFFICIENCY);
        priors.put(S9_KEY, HUMAN_KEY_MS);
        return new AgentLpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY), (int) alisharHp, priors);
    }

    /** The companions' part of a pass stage: s1-s3.collect, s4.rooms, s5.rooms, s7.fight. */
    public static String collectKey(int stage) {
        return switch (stage) {
            case 1, 2, 3 -> "s" + stage + ".collect";
            case 4, 5 -> "s" + stage + ".rooms";
            case 7 -> S7_FIGHT;
            default -> throw new IllegalArgumentException("LPQ stage " + stage + " has no passes to collect");
        };
    }

    /** The leader's part of a pass stage: its passes picked up and its balloon talked through. */
    public static String handinKey(int stage) {
        if (stage < 1 || stage > 7 || stage == 6) {
            throw new IllegalArgumentException("LPQ stage " + stage + " has no pass hand-in");
        }
        return "s" + stage + ".handin";
    }

    private static List<AgentPqPaceSegment> segments(int size, int alisharHp) {
        List<AgentPqPaceSegment> segments = new ArrayList<>();
        segments.add(new AgentPqPaceSegment.Fixed(YES, AgentPqPaceSegment.Side.COMPANION, YES, false));
        for (int stage : List.of(1, 2, 3, 4, 5)) passStage(segments, stage);
        segments.add(new AgentPqPaceSegment.Fixed(S6_CLIMB, AgentPqPaceSegment.Side.LEADER, S6_CLIMB, false));
        passStage(segments, 7);
        segments.add(new AgentPqPaceSegment.Fixed(S8_ENTRY, AgentPqPaceSegment.Side.LEADER, S8_ENTRY, false));
        segments.add(new AgentPqPaceSegment.Puzzle(S8_CHECKS, AgentLpqCombinationOrder.fiveOfNine().size(),
                leaderMoveShare(size), PUZZLE_CHECK, PUZZLE_MOVE));
        segments.add(new AgentPqPaceSegment.Fixed(S9_RATZ, AgentPqPaceSegment.Side.COMPANION, S9_RATZ, false));
        // Everyone but the leader fights Alishar; S9 keeps the leader out of his reach.
        segments.add(new AgentPqPaceSegment.Clear(S9_BOSS, Map.of(AgentLpqDefinition.ALISHAR, 1),
                Map.of(AgentLpqDefinition.ALISHAR, alisharHp), AgentLpqPaceProfile::allOnAlishar, false));
        segments.add(new AgentPqPaceSegment.Fixed(S9_KEY, AgentPqPaceSegment.Side.LEADER, S9_KEY, false));
        return List.copyOf(segments);
    }

    /** A pass stage: the companions bring the passes to his feet, then his hand-in. */
    private static void passStage(List<AgentPqPaceSegment> segments, int stage) {
        String collect = collectKey(stage);
        // The collection ends with the passes at the leader's feet: where he waits shapes it.
        segments.add(new AgentPqPaceSegment.Fixed(collect, AgentPqPaceSegment.Side.COMPANION, collect, true));
        segments.add(new AgentPqPaceSegment.Fixed(handinKey(stage), AgentPqPaceSegment.Side.LEADER, HANDIN, false));
    }

    private static Map<Integer, Integer> allOnAlishar(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> assignment = new TreeMap<>();
        for (Integer member : damageByMember.keySet()) assignment.put(member, AgentLpqDefinition.ALISHAR);
        return assignment;
    }

    /**
     * The share of stage 8's formation changes that move the leader himself. At six he never stands on a box: 0. At
     * five he stands on the fifth, last of the five (AgentLpqCoordinator's stage-8 participants), and each next
     * combination of the stock order keeps every member whose box it still has and gives the freed boxes to the others
     * in order (AgentLpqSession.stage8Assignments): the share is how often his own box leaves the next combination.
     */
    static double leaderMoveShare(int size) {
        if (size >= 6) return 0.0d;
        return leaderMoveShare(AgentLpqCombinationOrder.fiveOfNine());
    }

    /** As above, replayed over {@code order}; the leader is the last of its first combination's five. */
    static double leaderMoveShare(List<List<Integer>> order) {
        if (order.size() < 2) return 0.0d;
        List<Integer> first = order.getFirst();
        int members = first.size();
        int leader = members - 1;
        Map<Integer, Integer> boxByMember = new TreeMap<>();
        for (int member = 0; member < members; member++) boxByMember.put(member, first.get(member));
        int moves = 0;
        for (List<Integer> combination : order.subList(1, order.size())) {
            boxByMember.entrySet().removeIf(entry -> !combination.contains(entry.getValue()));
            if (!boxByMember.containsKey(leader)) moves++;
            List<Integer> used = new ArrayList<>(boxByMember.values());
            List<Integer> freeBoxes = combination.stream().filter(box -> !used.contains(box)).toList();
            int next = 0;
            for (int member = 0; member < members; member++) {
                if (!boxByMember.containsKey(member) && next < freeBoxes.size()) {
                    boxByMember.put(member, freeBoxes.get(next++));
                }
            }
        }
        return (double) moves / (order.size() - 1);
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
        return MOB_IDS;
    }

    @Override
    public int dangerMobId() {
        return AgentLpqDefinition.ALISHAR;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        List<AgentPqPaceSegment> segments = segmentsBySize.get(partySize);
        if (segments == null) {
            throw new IllegalArgumentException("LPQ takes " + minSize() + "-" + maxSize() + " members, not "
                    + partySize);
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
}
