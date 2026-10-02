package server.agents.capabilities.partyquest.pace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small quest for the pace tests, shaped like KPQ: an opening, a quota of mob A, a leader hand-in, a puzzle of four
 * combinations, then a race between the companions (move, the normals B and C, the boss, carry) and the leader's
 * climb, then talks. Every value is in round numbers so the tests can work the theory out by hand.
 */
final class PaceStubProfile implements AgentPqPaceProfile {
    static final int MOB_A = 101;
    static final int MOB_B = 102;
    static final int MOB_C = 103;
    static final int BOSS = 104;

    /** Each fighter on the live type it hurts most (ties to the lower id). */
    static final AgentPqPaceSegment.Assigner GREEDY = damage -> {
        Map<Integer, Integer> choice = new TreeMap<>();
        damage.forEach((member, byMob) -> choice.put(member, byMob.entrySet().stream()
                .max(Map.Entry.<Integer, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
                .orElseThrow().getKey()));
        return choice;
    };

    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final boolean warmed;
    private final long timerMs;
    private final Thresholds thresholds;

    PaceStubProfile(List<AgentPqPaceSegment> segments, Map<String, Double> priors, boolean warmed, long timerMs,
                    Thresholds thresholds) {
        this.segments = segments;
        this.priors = priors;
        this.warmed = warmed;
        this.timerMs = timerMs;
        this.thresholds = thresholds;
    }

    static PaceStubProfile standard() {
        return new PaceStubProfile(standardSegments(), standardPriors(), true, 1_800_000L, standardThresholds());
    }

    static List<AgentPqPaceSegment> standardSegments() {
        return List.of(
                new AgentPqPaceSegment.Fixed("open", AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Quota("grind", MOB_A, 1_000, Pmf.sumOfDraws(List.of(10, 20, 30), 2), 0.5d),
                new AgentPqPaceSegment.Fixed("handin", AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed("entry", AgentPqPaceSegment.Side.LEADER, "p.entry"),
                new AgentPqPaceSegment.Puzzle("checks", 4, 0.0d, "p.check", "p.move"),
                new AgentPqPaceSegment.Parallel("race", List.of(
                        List.of(new AgentPqPaceSegment.Fixed("move", AgentPqPaceSegment.Side.COMPANION),
                                new AgentPqPaceSegment.Clear("normals", Map.of(MOB_B, 2, MOB_C, 1),
                                        Map.of(MOB_B, 500, MOB_C, 1_000, BOSS, 4_000), GREEDY, false),
                                new AgentPqPaceSegment.Clear("boss", Map.of(BOSS, 1),
                                        Map.of(MOB_B, 500, MOB_C, 1_000, BOSS, 4_000), GREEDY, false),
                                new AgentPqPaceSegment.Fixed("carry", AgentPqPaceSegment.Side.COMPANION, "carry",
                                        true)),
                        List.of(new AgentPqPaceSegment.Fixed("climb", AgentPqPaceSegment.Side.LEADER)))),
                new AgentPqPaceSegment.Fixed("talks", AgentPqPaceSegment.Side.LEADER));
    }

    static Map<String, Double> standardPriors() {
        Map<String, Double> priors = new LinkedHashMap<>();
        priors.put("open", 10_000.0d);
        priors.put("grind.seek", 10_000.0d);
        priors.put("grind.combat", 0.5d);
        priors.put("handin", 20_000.0d);
        priors.put("p.entry", 15_000.0d);
        priors.put("p.check", 9_000.0d);
        priors.put("p.move", 4_000.0d);
        priors.put("move", 3_000.0d);
        priors.put("normals", 0.2d);
        priors.put("boss", 0.25d);
        priors.put("carry", 20_000.0d);
        priors.put("climb", 40_000.0d);
        priors.put("talks", 15_000.0d);
        return priors;
    }

    static Thresholds standardThresholds() {
        return new Thresholds(7.0d, 0.6d, Map.of("grind", 720_000.0d, "normals", 180_000.0d, "boss", 180_000.0d));
    }

    PaceStubProfile with(List<AgentPqPaceSegment> newSegments, Map<String, Double> newPriors) {
        return new PaceStubProfile(newSegments, newPriors, warmed, timerMs, thresholds);
    }

    PaceStubProfile cold() {
        return new PaceStubProfile(segments, priors, false, timerMs, thresholds);
    }

    PaceStubProfile withTimer(long newTimerMs) {
        return new PaceStubProfile(segments, priors, warmed, newTimerMs, thresholds);
    }

    /** A companion with these damage rates (mob A, B, C, boss), full hit chance, 800 ms attacks and 10 touches. */
    static AgentPqMemberPower companion(int id, double a, double b, double c, double boss) {
        return new AgentPqMemberPower(id, "c" + id, false, false, 25, 100,
                Map.of(MOB_A, a, MOB_B, b, MOB_C, c, BOSS, boss),
                Map.of(MOB_A, 1.0d, MOB_B, 1.0d, MOB_C, 1.0d, BOSS, 1.0d),
                Map.of(MOB_A, 800.0d, MOB_B, 800.0d, MOB_C, 800.0d, BOSS, 800.0d), 10.0d);
    }

    /** A player beside the leader with these damage rates (mob A, B, C, boss), otherwise as a companion. */
    static AgentPqMemberPower human(int id, double a, double b, double c, double boss) {
        AgentPqMemberPower like = companion(id, a, b, c, boss);
        return new AgentPqMemberPower(id, "h" + id, true, false, like.level(), like.jobId(), like.dpsByMob(),
                like.hitByMob(), like.attackMsByMob(), like.touchesToDown());
    }

    static AgentPqMemberPower humanLeader(int id) {
        return new AgentPqMemberPower(id, "leader", true, true, 26, 0, Map.of(), Map.of(), Map.of(), 10.0d);
    }

    @Override
    public String questKey() {
        return "stub";
    }

    @Override
    public long timerMs() {
        return timerMs;
    }

    @Override
    public int minSize() {
        return 2;
    }

    @Override
    public int maxSize() {
        return 4;
    }

    @Override
    public List<Integer> mobIds() {
        return List.of(MOB_A, MOB_B, MOB_C, BOSS);
    }

    @Override
    public int dangerMobId() {
        return BOSS;
    }

    @Override
    public boolean warmed() {
        return warmed;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        return segments;
    }

    @Override
    public Map<String, Double> priors() {
        return priors;
    }

    @Override
    public int epoch() {
        return 1;
    }

    @Override
    public Thresholds thresholds() {
        return thresholds;
    }
}
