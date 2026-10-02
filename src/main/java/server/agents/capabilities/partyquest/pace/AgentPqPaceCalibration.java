package server.agents.capabilities.partyquest.pace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The learned terms of one quest's clear-time model: the companion terms (seek and combat efficiency per quota, the
 * efficiency of each clear, the companions' fixed times), the leader terms for one leader, and the spread of the shared
 * efficiency multiplier. {@link #prior} is the profile's priors; {@link #fit} learns from recorded runs and is pure.
 *
 * <p>Fit rules: only complete spans of runs recorded under the profile's epoch count, newest {@code MAX_ROWS} first,
 * each weighted {@code 0.5^(ageRank / HALF_LIFE_RUNS)}. Every term is shrunk toward its prior with
 * {@code PRIOR_WEIGHT_RUNS} pseudo-runs, in log space and clamped to [EFFICIENCY_MIN, EFFICIENCY_MAX] for
 * efficiencies; a quota's seek, which a run faster than the zero-seek bound puts at 0, is shrunk linearly in
 * [0, SEEK_MAX_MS]. Leader terms are learned per leader with {@code LEADER_PRIOR_WEIGHT_RUNS} pseudo-runs toward the
 * quick-human prior, or toward the other leaders' pooled terms once {@code LEADER_POOL_MIN_LEADERS} distinct leaders
 * that are not excluded have runs; an excluded leader's runs teach no leader term (his companions' spans still count).
 * A puzzle's move term is never learned. The estimate itself is never fed back.</p>
 *
 * @param fitNotes counts of what the fit skipped or bounded, for the caller to log: {@code <key>.incomplete},
 *                 {@code <key>.nonPositive}, {@code <key>.seekAtZeroBound}, {@code <key>.seekAtMax},
 *                 {@code <key>.unclearableTheory}, {@code size<n>.unsupported}
 */
public record AgentPqPaceCalibration(String questKey, int epoch, Map<String, Double> companion,
                                    Map<String, Double> leader, double logSpread, int records, Source source,
                                    Map<String, Integer> fitNotes) {
    private static final double PRIOR_WEIGHT_RUNS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.PRIOR_WEIGHT_RUNS");
    private static final double PRIOR_LOG_SPREAD = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.PRIOR_LOG_SPREAD");
    private static final double LEADER_PRIOR_WEIGHT_RUNS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.LEADER_PRIOR_WEIGHT_RUNS");
    private static final int LEADER_POOL_MIN_LEADERS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.LEADER_POOL_MIN_LEADERS");
    private static final double HALF_LIFE_RUNS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.HALF_LIFE_RUNS");
    private static final double EFFICIENCY_MIN = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.EFFICIENCY_MIN");
    private static final double EFFICIENCY_MAX = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.EFFICIENCY_MAX");
    private static final double SEEK_MAX_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.SEEK_MAX_MS");
    private static final int MAX_ROWS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration.MAX_ROWS");

    /** PRIOR: nothing recorded in this epoch yet. RECORDS: at least one recorded run moved a term. */
    public enum Source {
        PRIOR,
        RECORDS
    }

    public AgentPqPaceCalibration {
        companion = Collections.unmodifiableMap(new TreeMap<>(companion));
        leader = Collections.unmodifiableMap(new TreeMap<>(leader));
        fitNotes = Collections.unmodifiableMap(new TreeMap<>(fitNotes == null ? Map.of() : fitNotes));
        if (source == null) source = Source.PRIOR;
    }

    /** The profile's priors, split by side, with the prior spread. */
    public static AgentPqPaceCalibration prior(AgentPqPaceProfile profile) {
        Set<String> leaderTerms = profile.leaderTerms();
        Map<String, Double> companion = new TreeMap<>();
        Map<String, Double> leader = new TreeMap<>();
        for (String term : profile.terms()) {
            Double value = profile.priors().get(term);
            if (value == null) throw new IllegalStateException("no prior for pace term " + term + " of " + profile.questKey());
            (leaderTerms.contains(term) ? leader : companion).put(term, value);
        }
        return new AgentPqPaceCalibration(profile.questKey(), profile.epoch(), companion, leader, PRIOR_LOG_SPREAD, 0,
                Source.PRIOR, Map.of());
    }

    /**
     * Learns the terms for one leader from recorded runs. Pure: no I/O, no clock; the same inputs give the same
     * calibration.
     *
     * @param leaderId        the leader whose leader terms are wanted
     * @param excludedLeaders leaders whose own spans are never learned (a scripted test driver)
     */
    public static AgentPqPaceCalibration fit(AgentPqPaceProfile profile, List<AgentPqPaceObservation> observations,
                                             int leaderId, Set<Integer> excludedLeaders) {
        AgentPqPaceCalibration prior = prior(profile);
        Set<Integer> excluded = excludedLeaders == null ? Set.of() : excludedLeaders;
        List<Run> runs = new ArrayList<>();
        Map<String, Integer> notes = new TreeMap<>();
        for (AgentPqPaceObservation observation : observations == null ? List.<AgentPqPaceObservation>of()
                : observations.stream().filter(observation -> observation != null
                        && observation.epoch() == profile.epoch()
                        && profile.questKey().equals(observation.pace().questKey()))
                .sorted(Comparator.comparingInt(AgentPqPaceObservation::ageRank)).limit(Math.max(0, MAX_ROWS))
                .toList()) {
            int size = observation.pace().members().size();
            List<AgentPqPaceSegment> segments;
            try {
                segments = size >= profile.minSize() && size <= profile.maxSize() ? profile.segments(size) : null;
            } catch (RuntimeException unsupported) {
                segments = null;
            }
            if (segments == null) {
                notes.merge("size" + size + ".unsupported", 1, Integer::sum);
                continue;
            }
            runs.add(new Run(observation.pace(), segments, Math.pow(0.5d, observation.ageRank() / HALF_LIFE_RUNS)));
        }
        if (runs.isEmpty()) {
            return new AgentPqPaceCalibration(prior.questKey(), prior.epoch(), prior.companion(), prior.leader(),
                    prior.logSpread(), 0, Source.PRIOR, notes);
        }
        Set<String> leaderTerms = profile.leaderTerms();
        Set<Run> contributed = new HashSet<>();

        // Companion fixed times first: a clear or quota span that folds one in subtracts its fitted value.
        Map<String, Double> companion = new TreeMap<>(prior.companion());
        Map<String, List<double[]>> fixedObservations = new TreeMap<>();
        for (Run run : runs) {
            for (Visit visit : visits(run)) {
                if (visit.segment() instanceof AgentPqPaceSegment.Fixed fixed && !leaderTerms.contains(fixed.term())
                        && usable(visit, notes)) {
                    double observed = visit.span().durationMs() - folded(visit, companion, prior.leader(), leaderTerms);
                    addObservation(fixedObservations, fixed.term(), observed, run, contributed, notes, fixed.key());
                }
            }
        }
        fixedObservations.forEach((term, values) -> companion.put(term,
                shrinkLog(prior.companion().get(term), PRIOR_WEIGHT_RUNS, values, 0.0d, Double.MAX_VALUE)));

        // Quotas: combat efficiency from attacks per kill, then seek given that efficiency.
        Map<String, List<double[]>> combatObservations = new TreeMap<>();
        Map<String, List<double[]>> seekObservations = new TreeMap<>();
        Map<String, List<double[]>> clearObservations = new TreeMap<>();
        for (Run run : runs) {
            for (Visit visit : visits(run)) {
                if (visit.segment() instanceof AgentPqPaceSegment.Quota quota) {
                    double efficiency = runCombatEfficiency(quota, run.pace());
                    if (efficiency > 0.0d) {
                        combatObservations.computeIfAbsent(quota.combatTerm(), key -> new ArrayList<>())
                                .add(new double[]{efficiency, run.weight()});
                        contributed.add(run);
                    }
                }
            }
        }
        combatObservations.forEach((term, values) -> companion.put(term,
                shrinkLog(prior.companion().get(term), PRIOR_WEIGHT_RUNS, values, EFFICIENCY_MIN, EFFICIENCY_MAX)));
        for (Run run : runs) {
            for (Visit visit : visits(run)) {
                if (visit.segment() instanceof AgentPqPaceSegment.Quota quota && usable(visit, notes)) {
                    double seconds = (visit.span().durationMs()
                            - folded(visit, companion, prior.leader(), leaderTerms)) / 1000.0d;
                    double kills = visit.span().work();
                    if (!(seconds > 0.0d) || !(kills > 0.0d)) {
                        notes.merge(quota.key() + ".nonPositive", 1, Integer::sum);
                        continue;
                    }
                    double target = kills / seconds;
                    double combat = companion.get(quota.combatTerm());
                    List<AgentPqMemberPower> members = run.pace().members();
                    if (!(AgentPqPaceModel.killsPerSecond(quota, members, 0.0d, combat) > 0.0d)) {
                        notes.merge(quota.key() + ".unclearableTheory", 1, Integer::sum);
                        continue;
                    }
                    double seek = solveSeekMs(quota, members, combat, target, SEEK_MAX_MS);
                    if (seek <= 0.0d) notes.merge(quota.key() + ".seekAtZeroBound", 1, Integer::sum);
                    if (seek >= SEEK_MAX_MS) notes.merge(quota.key() + ".seekAtMax", 1, Integer::sum);
                    seekObservations.computeIfAbsent(quota.seekTerm(), key -> new ArrayList<>())
                            .add(new double[]{seek, run.weight()});
                    contributed.add(run);
                } else if (visit.segment() instanceof AgentPqPaceSegment.Clear clear && usable(visit, notes)) {
                    double seconds = (visit.span().durationMs()
                            - folded(visit, companion, prior.leader(), leaderTerms)) / 1000.0d;
                    AgentPqPaceModel.Fill fill = AgentPqPaceModel.waterFill(clear, run.pace().members());
                    if (!fill.clearable()) {
                        notes.merge(clear.key() + ".unclearableTheory", 1, Integer::sum);
                    } else if (!(seconds > 0.0d)) {
                        notes.merge(clear.key() + ".nonPositive", 1, Integer::sum);
                    } else {
                        clearObservations.computeIfAbsent(clear.key(), key -> new ArrayList<>())
                                .add(new double[]{fill.seconds() / seconds, run.weight()});
                        contributed.add(run);
                    }
                }
            }
        }
        seekObservations.forEach((term, values) -> companion.put(term,
                shrinkLinear(prior.companion().get(term), PRIOR_WEIGHT_RUNS, values, 0.0d, SEEK_MAX_MS)));
        clearObservations.forEach((term, values) -> companion.put(term,
                shrinkLog(prior.companion().get(term), PRIOR_WEIGHT_RUNS, values, EFFICIENCY_MIN, EFFICIENCY_MAX)));

        // Leader terms: this leader's runs, toward the prior or the other leaders' pool.
        Map<Integer, Map<String, List<double[]>>> leaderObservations = new TreeMap<>();
        for (Run run : runs) {
            int runLeader = run.pace().leaderId();
            if (excluded.contains(runLeader)) continue;
            Map<String, List<double[]>> own = leaderObservations.computeIfAbsent(runLeader, key -> new TreeMap<>());
            for (Visit visit : visits(run)) {
                if (visit.segment() instanceof AgentPqPaceSegment.Fixed fixed && leaderTerms.contains(fixed.term())
                        && usable(visit, notes)) {
                    double observed = visit.span().durationMs() - folded(visit, companion, prior.leader(), leaderTerms);
                    addObservation(own, fixed.term(), observed, run, contributed, notes, fixed.key());
                } else if (visit.segment() instanceof AgentPqPaceSegment.Puzzle puzzle && usable(visit, notes)) {
                    int checks = (int) Math.round(visit.span().work());
                    if (checks < 2) continue;
                    double perCheck = visit.span().durationMs() / (double) (checks - 1)
                            - puzzle.leaderMoveShare() * prior.leader().get(puzzle.moveTerm());
                    addObservation(own, puzzle.checkTerm(), perCheck, run, contributed, notes, puzzle.key());
                }
            }
        }
        leaderObservations.values().removeIf(Map::isEmpty);
        Map<String, Double> base = new TreeMap<>(prior.leader());
        if (leaderObservations.size() >= LEADER_POOL_MIN_LEADERS) {
            Map<String, List<double[]>> pooled = new TreeMap<>();
            leaderObservations.forEach((otherLeader, terms) -> {
                if (otherLeader != leaderId) {
                    terms.forEach((term, values) -> pooled.computeIfAbsent(term, key -> new ArrayList<>()).addAll(values));
                }
            });
            pooled.forEach((term, values) -> base.put(term,
                    shrinkLog(prior.leader().get(term), LEADER_PRIOR_WEIGHT_RUNS, values, 0.0d, Double.MAX_VALUE)));
        }
        Map<String, Double> leader = new TreeMap<>(base);
        if (!excluded.contains(leaderId)) {
            leaderObservations.getOrDefault(leaderId, Map.of()).forEach((term, values) -> leader.put(term,
                    shrinkLog(base.get(term), LEADER_PRIOR_WEIGHT_RUNS, values, 0.0d, Double.MAX_VALUE)));
        }

        // Spread of the shared efficiency multiplier, from each run's kill-term residuals under the fitted terms.
        double weightedSquares = 0.0d;
        double weights = 0.0d;
        for (Run run : runs) {
            List<Double> residuals = new ArrayList<>();
            for (Visit visit : visits(run)) {
                if (!(visit.segment() instanceof AgentPqPaceSegment.Quota)
                        && !(visit.segment() instanceof AgentPqPaceSegment.Clear)) continue;
                if (visit.span() == null || !visit.span().complete()) continue;
                double seconds = (visit.span().durationMs() - folded(visit, companion, leader, leaderTerms)) / 1000.0d;
                double theory = theorySeconds(visit, run.pace(), companion);
                if (seconds > 0.0d && theory > 0.0d && Double.isFinite(theory)) residuals.add(Math.log(seconds / theory));
            }
            for (double residual : residuals) {
                weightedSquares += run.weight() / residuals.size() * residual * residual;
                weights += run.weight() / residuals.size();
            }
        }
        double spread = Math.sqrt((PRIOR_WEIGHT_RUNS * PRIOR_LOG_SPREAD * PRIOR_LOG_SPREAD + weightedSquares)
                / (PRIOR_WEIGHT_RUNS + weights));
        return new AgentPqPaceCalibration(profile.questKey(), profile.epoch(), companion, leader, spread,
                contributed.size(), contributed.isEmpty() ? Source.PRIOR : Source.RECORDS, notes);
    }

    /**
     * The seek (ms) at which the quota's members would have killed at {@code killsPerSecond}: the kill rate falls as
     * seek grows, so bisection on [0, maxMs]. A run faster than the zero-seek bound gives 0, slower than the max gives
     * the max.
     */
    static double solveSeekMs(AgentPqPaceSegment.Quota quota, List<AgentPqMemberPower> members, double combat,
                              double killsPerSecond, double maxMs) {
        if (AgentPqPaceModel.killsPerSecond(quota, members, 0.0d, combat) <= killsPerSecond) return 0.0d;
        if (AgentPqPaceModel.killsPerSecond(quota, members, maxMs, combat) >= killsPerSecond) return maxMs;
        double lo = 0.0d;
        double hi = maxMs;
        for (int step = 0; step < 100; step++) {
            double mid = (lo + hi) / 2.0d;
            if (AgentPqPaceModel.killsPerSecond(quota, members, mid, combat) > killsPerSecond) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2.0d;
    }

    /**
     * One run's combat efficiency on a quota: per companion that killed and attacked, the policy's attacks per kill
     * ({@code mobHp / (dps x cooldown)}) over the attacks it really needed per kill, weighted by its kills. 0 when no
     * companion has both.
     */
    static double runCombatEfficiency(AgentPqPaceSegment.Quota quota, AgentPqPace pace) {
        double weighted = 0.0d;
        double kills = 0.0d;
        for (AgentPqMemberPower member : pace.members()) {
            AgentPqPace.MemberRun run = pace.run(member.characterId());
            double dps = member.dps(quota.mobId());
            double attackMs = member.attackMs(quota.mobId());
            if (member.human() || run == null || run.stage1Kills() <= 0 || run.stage1Attacks() <= 0
                    || !(dps > 0.0d) || !(attackMs > 0.0d)) {
                continue;
            }
            double policyAttacksPerKill = quota.mobHp() / (dps * attackMs / 1000.0d);
            double measuredAttacksPerKill = run.stage1Attacks() / (double) run.stage1Kills();
            double efficiency = Math.max(EFFICIENCY_MIN, Math.min(EFFICIENCY_MAX,
                    policyAttacksPerKill / measuredAttacksPerKill));
            weighted += run.stage1Kills() * efficiency;
            kills += run.stage1Kills();
        }
        return kills > 0.0d ? weighted / kills : 0.0d;
    }

    /** A kill segment's theory seconds for a recorded run under these companion terms, at its realised work. */
    private static double theorySeconds(Visit visit, AgentPqPace pace, Map<String, Double> companion) {
        if (visit.segment() instanceof AgentPqPaceSegment.Quota quota) {
            double rate = AgentPqPaceModel.killsPerSecond(quota, pace.members(), companion.get(quota.seekTerm()),
                    companion.get(quota.combatTerm()));
            return rate > 0.0d ? visit.span().work() / rate : Double.POSITIVE_INFINITY;
        }
        AgentPqPaceSegment.Clear clear = (AgentPqPaceSegment.Clear) visit.segment();
        return AgentPqPaceModel.waterFill(clear, pace.members()).seconds() / companion.get(clear.key());
    }

    private static boolean usable(Visit visit, Map<String, Integer> notes) {
        if (visit.span() == null) return false;
        if (!visit.span().complete()) {
            if (visit.span().durationMs() != 0L || !(visit.segment() instanceof AgentPqPaceSegment.Fixed)) {
                notes.merge(visit.segment().key() + ".incomplete", 1, Integer::sum);
            }
            return false;
        }
        return true;
    }

    private static double folded(Visit visit, Map<String, Double> companion, Map<String, Double> leader,
                                 Set<String> leaderTerms) {
        double total = 0.0d;
        for (String term : visit.folded()) total += (leaderTerms.contains(term) ? leader : companion).get(term);
        return total;
    }

    private static void addObservation(Map<String, List<double[]>> observations, String term, double value, Run run,
                                       Set<Run> contributed, Map<String, Integer> notes, String segmentKey) {
        if (!(value > 0.0d)) {
            notes.merge(segmentKey + ".nonPositive", 1, Integer::sum);
            return;
        }
        observations.computeIfAbsent(term, key -> new ArrayList<>()).add(new double[]{value, run.weight()});
        contributed.add(run);
    }

    /** {@code exp((n0 log prior + sum w log x) / (n0 + sum w))}, clamped. */
    static double shrinkLog(double prior, double priorWeight, List<double[]> observations, double min, double max) {
        if (!(prior > 0.0d)) return shrinkLinear(prior, priorWeight, observations, min, max);
        double weight = priorWeight;
        double sum = priorWeight * Math.log(prior);
        for (double[] observation : observations) {
            if (!(observation[0] > 0.0d)) continue;
            sum += observation[1] * Math.log(observation[0]);
            weight += observation[1];
        }
        return Math.max(min, Math.min(max, Math.exp(sum / weight)));
    }

    /** {@code (n0 prior + sum w x) / (n0 + sum w)}, clamped. */
    static double shrinkLinear(double prior, double priorWeight, List<double[]> observations, double min, double max) {
        double weight = priorWeight;
        double sum = priorWeight * prior;
        for (double[] observation : observations) {
            sum += observation[1] * observation[0];
            weight += observation[1];
        }
        return Math.max(min, Math.min(max, sum / weight));
    }

    /** A recorded run with its profile segments and its half-life weight. */
    private record Run(AgentPqPace pace, List<AgentPqPaceSegment> segments, double weight) {
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** A leaf segment of a run, its span (or null), and the unseen fixed terms its span includes. */
    private record Visit(AgentPqPaceSegment segment, AgentPqPace.Span span, List<String> folded) {
    }

    private static List<Visit> visits(Run run) {
        List<Visit> visits = new ArrayList<>();
        walk(run.segments(), run.pace(), visits);
        return visits;
    }

    private static void walk(List<AgentPqPaceSegment> segments, AgentPqPace pace, List<Visit> visits) {
        List<String> pending = new ArrayList<>();
        for (AgentPqPaceSegment segment : segments) {
            if (segment instanceof AgentPqPaceSegment.Parallel parallel) {
                for (List<AgentPqPaceSegment> branch : parallel.branches()) walk(branch, pace, visits);
                pending.clear();
                continue;
            }
            AgentPqPace.Span span = pace.span(segment.key());
            if (segment instanceof AgentPqPaceSegment.Fixed fixed && span != null && !span.complete()
                    && span.durationMs() == 0L) {
                pending.add(fixed.term());
                visits.add(new Visit(segment, span, List.of()));
                continue;
            }
            visits.add(new Visit(segment, span, List.copyOf(pending)));
            pending.clear();
        }
    }
}
