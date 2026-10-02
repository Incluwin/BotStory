package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Recorded runs for the clear-time record tests: a short quest with a leader term ({@link Profile}: the companions'
 * minute of opening, 100 Ligators of 1000 HP, the leader's hand-in, then King Slime's 5000 HP), runs of it as a
 * session reports them ({@link #pace}), and the companion runs they end ({@link #run}).
 */
final class PqPaceTestRuns {
    static final int LIGATOR = 9_300_001;
    static final int KING_SLIME = 9_300_003;
    static final long START = 1_790_000_000_000L;

    private PqPaceTestRuns() {
    }

    /** Three or four members, half an hour; {@code epoch} is its pace epoch. */
    record Profile(int epoch) implements AgentPqPaceProfile {
        @Override
        public String questKey() {
            return "kpq";
        }

        @Override
        public long timerMs() {
            return 1_800_000L;
        }

        @Override
        public int minSize() {
            return 3;
        }

        @Override
        public int maxSize() {
            return 4;
        }

        @Override
        public List<Integer> mobIds() {
            return List.of(LIGATOR, KING_SLIME);
        }

        @Override
        public int dangerMobId() {
            return KING_SLIME;
        }

        @Override
        public boolean warmed() {
            return true;
        }

        @Override
        public List<AgentPqPaceSegment> segments(int partySize) {
            if (partySize < 3 || partySize > 4) {
                throw new IllegalArgumentException("3-4 members, not " + partySize);
            }
            AgentPqPaceSegment.Assigner allOnTheBoss = damage -> {
                Map<Integer, Integer> choice = new TreeMap<>();
                damage.keySet().forEach(member -> choice.put(member, KING_SLIME));
                return choice;
            };
            return List.of(new AgentPqPaceSegment.Fixed("s1.open", AgentPqPaceSegment.Side.COMPANION),
                    new AgentPqPaceSegment.Quota("s1.grind", LIGATOR, 1_000, Pmf.point(100), 0.5d),
                    new AgentPqPaceSegment.Fixed("s1.handin", AgentPqPaceSegment.Side.LEADER),
                    new AgentPqPaceSegment.Clear("s5.boss", Map.of(KING_SLIME, 1), Map.of(KING_SLIME, 5_000),
                            allOnTheBoss, false));
        }

        @Override
        public Map<String, Double> priors() {
            return Map.of("s1.open", 60_000.0d, "s1.grind.seek", 0.0d, "s1.grind.combat", 1.0d, "s1.handin", 25_000.0d,
                    "s5.boss", 1.0d);
        }

        @Override
        public Thresholds thresholds() {
            return new Thresholds(7.0d, 0.6d, Map.of());
        }
    }

    /** A member's power: {@code dps} against both mobs, every hit landing, an 800 ms swing. */
    static AgentPqMemberPower power(int id, boolean human, boolean leader, double dps) {
        return new AgentPqMemberPower(id, "m" + id, human, leader, 25, human ? 230 : 310,
                Map.of(LIGATOR, dps, KING_SLIME, dps), Map.of(LIGATOR, 1.0d, KING_SLIME, 1.0d),
                Map.of(LIGATOR, 800.0d, KING_SLIME, 800.0d), human ? Double.MAX_VALUE : 12.5d);
    }

    /**
     * A run led by {@code leaderId} (a human) with these companions (500 damage a second each), as its session
     * reports it: a {@code openS} opening, 100 Ligators in 120 s, a {@code handinS} hand-in and King Slime in 20 s,
     * cleared or not; every span complete, each member's stage-1 swings and kills, the coupons drawn.
     */
    static AgentPqPace pace(String sessionId, int leaderId, List<Integer> companions, long openS, long handinS,
                            boolean cleared) {
        List<AgentPqMemberPower> members = new ArrayList<>();
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        members.add(power(leaderId, true, true, 0.0d));
        runs.add(new AgentPqPace.MemberRun(leaderId, 0, false, 0, 0));
        for (int id : companions) {
            members.add(power(id, false, false, 500.0d));
            runs.add(new AgentPqPace.MemberRun(id, id % 2, false, 160, 33));
        }
        long open = START + 1_000L;
        long grind = open + openS * 1_000L;
        long handin = grind + 120_000L;
        long boss = handin + handinS * 1_000L;
        long end = boss + 20_000L;
        List<AgentPqPace.Span> spans = List.of(new AgentPqPace.Span("s1.open", open, grind, 0.0d, true),
                new AgentPqPace.Span("s1.grind", grind, handin, 100.0d, true),
                new AgentPqPace.Span("s1.handin", handin, boss, 0.0d, true),
                new AgentPqPace.Span("s5.boss", boss, end, 5_000.0d, cleared));
        Map<String, Integer> drawn = new LinkedHashMap<>();
        drawn.put("s1.grind", 100);
        drawn.put("s2.checks", 3);
        return new AgentPqPace("kpq", sessionId, leaderId, START, cleared ? end : 0L, spans, members, runs, drawn);
    }

    /** {@link #pace} with a 60 s opening and a 25 s hand-in. */
    static AgentPqPace pace(String sessionId, int leaderId, List<Integer> companions, boolean cleared) {
        return pace(sessionId, leaderId, companions, 60L, 25L, cleared);
    }

    /** An estimate shown at a Yes: these quantiles, backed by {@code records} recorded runs, and segment means. */
    static AgentPqPaceEstimate estimate(double p10S, double p50S, double p90S, int records,
                                        Map<String, Double> segmentMeanS) {
        return new AgentPqPaceEstimate(true, p10S, p50S, p90S, p50S, 0.01d, segmentMeanS, Map.of(), List.of(),
                records == 0 ? AgentPqPaceCalibration.Source.PRIOR : AgentPqPaceCalibration.Source.RECORDS, records);
    }

    /** The companion run a human {@code leaderId} took these companions (owned by the leader) into, started. */
    static CompanionRun run(String sessionId, int leaderId, List<Integer> companions, AgentPqPaceEstimate estimate) {
        List<LineupPlan.Seat> lineup = new ArrayList<>();
        lineup.add(new LineupPlan.Seat(leaderId, "Leader", 25, 230, "Cleric", true, 0, null, 103000000, true, 0L, 0, 0));
        for (int id : companions) {
            lineup.add(new LineupPlan.Seat(id, "C" + id, 25, 310, "Hunter", false, leaderId, "p" + id, 103000000, true,
                    0L, 0, 0));
        }
        LineupPlan.ClearTime clearTime = estimate == null ? null
                : new LineupPlan.ClearTime(estimate, 1_800_000L, estimate.p50S(), estimate.p50S(), estimate.p50S(),
                estimate.p50S(), 0.0d);
        LineupPlan plan = new LineupPlan("kpq", leaderId, lineup, List.of(), List.of(), List.of(), null, false, null,
                clearTime);
        CompanionRun run = new CompanionRun(sessionId, plan, START);
        run.started = true;
        return run;
    }

    static Set<Integer> ids(int... ids) {
        Set<Integer> out = new java.util.LinkedHashSet<>();
        for (int id : ids) {
            out.add(id);
        }
        return out;
    }
}
