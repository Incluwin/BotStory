package server.agents.capabilities.partyquest.pace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The clear-time estimate of a lineup: the profile's segments, priced with each member's power and the calibrated
 * terms, as an exact distribution on a GRID_MS grid. Every kill term (quota, clear) shares one lognormal efficiency
 * multiplier of spread {@code calibration.logSpread()}, since a party that fights slowly is slow everywhere; it is
 * integrated with a 7-point Gauss-Hermite rule. A quota's kill count and a puzzle's check count are drawn exactly from
 * the profile; a quota with a redraw is the exact mixture of the draws kept and the restarts thrown-away draws cost
 * ({@link QuotaMixture}). Pure and in memory: no game data, I/O or locks, so it may run on a leader's I/O thread.
 */
public final class AgentPqPaceEstimator {
    private static final long GRID_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator.GRID_MS");
    private static final long HORIZON_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator.HORIZON_MS");
    /** Gauss-Hermite nodes and weights (7 points), rescaled for a standard normal: sqrt(2) x_k and w_k / sqrt(pi). */
    private static final double[] NORMAL_NODES = {
            -2.651961356835233 * Math.sqrt(2.0d), -1.6735516287674714 * Math.sqrt(2.0d),
            -0.8162878828589647 * Math.sqrt(2.0d), 0.0d, 0.8162878828589647 * Math.sqrt(2.0d),
            1.6735516287674714 * Math.sqrt(2.0d), 2.651961356835233 * Math.sqrt(2.0d)};
    private static final double[] NORMAL_WEIGHTS = normalized(new double[]{
            0.0009717812450995192, 0.05451558281912703, 0.4256072526101278, 0.8102646175568073,
            0.4256072526101278, 0.05451558281912703, 0.0009717812450995192});

    private AgentPqPaceEstimator() {
    }

    /**
     * @return the estimate, or null when the profile is cold, the calibration is missing, or the party size is outside
     * the profile's
     */
    public static AgentPqPaceEstimate estimate(AgentPqPaceProfile profile, List<AgentPqMemberPower> members,
                                               AgentPqPaceCalibration calibration) {
        if (profile == null || !profile.warmed() || calibration == null || members == null) return null;
        int size = members.size();
        if (size < profile.minSize() || size > profile.maxSize()) return null;
        List<AgentPqPaceSegment> segments = profile.segments(size);
        AgentPqPaceModel.Terms terms = new AgentPqPaceModel.Terms(profile, calibration);
        long horizonBins = Math.max(1L, HORIZON_MS / GRID_MS);

        Map<String, Double> killSeconds = new LinkedHashMap<>();
        Map<String, Double> quotaRates = new LinkedHashMap<>();
        Map<String, AgentPqPaceModel.Fill> fills = new LinkedHashMap<>();
        List<AgentPqPaceEstimate.Weakness> weaknesses = new ArrayList<>();
        boolean clearable = true;
        for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments)) {
            if (segment instanceof AgentPqPaceSegment.Quota quota) {
                double rate = AgentPqPaceModel.killsPerSecond(quota, members, terms.value(quota.seekTerm()),
                        terms.value(quota.combatTerm()));
                quotaRates.put(quota.key(), rate);
                if (!(rate > 0.0d)) {
                    clearable = false;
                    weaknesses.add(new AgentPqPaceEstimate.Weakness(0, quota.mobId(), quota.key(),
                            AgentPqPaceEstimate.Kind.UNHITTABLE));
                }
            } else if (segment instanceof AgentPqPaceSegment.Clear clear) {
                AgentPqPaceModel.Fill fill = AgentPqPaceModel.waterFill(clear, members);
                fills.put(clear.key(), fill);
                killSeconds.put(clear.key(), fill.seconds() / terms.value(clear.key()));
                if (!fill.clearable()) {
                    clearable = false;
                    for (int mobId : fill.unhittable()) {
                        weaknesses.add(new AgentPqPaceEstimate.Weakness(0, mobId, clear.key(),
                                AgentPqPaceEstimate.Kind.UNHITTABLE));
                    }
                }
            }
        }

        Map<String, Double> segmentMeanMs = new TreeMap<>();
        double horizonS = horizonBins * GRID_MS / 1000.0d;
        double p10;
        double p50;
        double p90;
        double meanS;
        double overTimer;
        if (clearable) {
            Evaluation evaluation = new Evaluation(terms, quotaRates, killSeconds, horizonBins);
            double[] mixture = new double[(int) horizonBins + 1];
            double mean = 0.0d;
            for (int node = 0; node < NORMAL_NODES.length; node++) {
                double multiplier = Math.exp(calibration.logSpread() * NORMAL_NODES[node]);
                Timeline total = evaluation.sequence(segments, multiplier, NORMAL_WEIGHTS[node], segmentMeanMs);
                double[] bins = total.absolute(horizonBins);
                for (int index = 0; index < bins.length; index++) mixture[index] += NORMAL_WEIGHTS[node] * bins[index];
                mean += NORMAL_WEIGHTS[node] * Math.min(total.meanMs(), horizonBins * (double) GRID_MS);
            }
            p10 = quantile(mixture, 0.1d) * GRID_MS / 1000.0d;
            p50 = quantile(mixture, 0.5d) * GRID_MS / 1000.0d;
            p90 = quantile(mixture, 0.9d) * GRID_MS / 1000.0d;
            meanS = mean / 1000.0d;
            overTimer = 0.0d;
            if (profile.timerMs() > 0L) {
                for (int index = 0; index < mixture.length; index++) {
                    if ((long) index * GRID_MS > profile.timerMs()) overTimer += mixture[index];
                }
            }
            overTimer = Math.max(0.0d, Math.min(1.0d, overTimer));
        } else {
            p10 = horizonS;
            p50 = horizonS;
            p90 = horizonS;
            meanS = horizonS;
            overTimer = 1.0d;
        }
        Map<String, Double> segmentMeanS = new TreeMap<>();
        segmentMeanMs.forEach((key, ms) -> segmentMeanS.put(key, Math.min(horizonS, ms / 1000.0d)));
        if (!clearable) {
            for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments)) {
                segmentMeanS.putIfAbsent(segment.key(), horizonS);
            }
        }

        weaknesses.addAll(memberWeaknesses(profile, segments, members));
        AgentPqPaceProfile.Thresholds thresholds = profile.thresholds();
        for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments)) {
            Double limit = thresholds.slowMs().get(segment.key());
            Double mean = segmentMeanS.get(segment.key());
            if (!clearable || limit == null || mean == null || !(mean * 1000.0d > limit)) continue;
            int mobId = segment instanceof AgentPqPaceSegment.Quota quota ? quota.mobId()
                    : segment instanceof AgentPqPaceSegment.Clear ? fills.get(segment.key()).lastMobId() : 0;
            weaknesses.add(new AgentPqPaceEstimate.Weakness(0, mobId, segment.key(), AgentPqPaceEstimate.Kind.SLOW));
        }

        Map<Integer, Double> partyDps = new TreeMap<>();
        for (int mobId : profile.mobIds()) {
            double total = 0.0d;
            for (AgentPqMemberPower member : members) {
                if (!member.human()) total += member.dps(mobId);
            }
            partyDps.put(mobId, total);
        }
        return new AgentPqPaceEstimate(clearable, p10, p50, p90, meanS, overTimer, segmentMeanS, partyDps, weaknesses,
                calibration.source(), calibration.records());
    }

    /**
     * One segment's theory time for a recorded run, at the efficiency multiplier 1 and the run's realised work: a quota's
     * kills over the members' kill rate, a clear's water-fill over its efficiency, a puzzle's checks after the first at
     * the per-check time, a fixed segment's value, a parallel's slowest branch at each branch's expected draws. A run's
     * report sets it beside the measured span, and their ratio is the implied efficiency multiplier.
     *
     * @param work the span's work as {@link AgentPqPace} defines it (ignored for clears and fixed segments)
     * @return seconds; infinite when the members cannot do it
     */
    public static double theorySeconds(AgentPqPaceProfile profile, List<AgentPqMemberPower> members,
                                       AgentPqPaceCalibration calibration, String segmentKey, double work) {
        AgentPqPaceModel.Terms terms = new AgentPqPaceModel.Terms(profile, calibration);
        for (AgentPqPaceSegment segment : profile.segments(members.size())) {
            Double seconds = theorySeconds(segment, segmentKey, work, members, terms);
            if (seconds != null) return seconds;
        }
        throw new IllegalArgumentException("no segment " + segmentKey + " in " + profile.questKey());
    }

    private static Double theorySeconds(AgentPqPaceSegment segment, String key, double work,
                                        List<AgentPqMemberPower> members, AgentPqPaceModel.Terms terms) {
        if (segment instanceof AgentPqPaceSegment.Parallel parallel) {
            double slowest = 0.0d;
            for (List<AgentPqPaceSegment> branch : parallel.branches()) {
                double branchSeconds = 0.0d;
                for (AgentPqPaceSegment inner : branch) {
                    Double found = theorySeconds(inner, key, work, members, terms);
                    if (found != null && !parallel.key().equals(key)) return found;
                    branchSeconds += expectedSeconds(inner, members, terms);
                }
                slowest = Math.max(slowest, branchSeconds);
            }
            return parallel.key().equals(key) ? slowest : null;
        }
        if (!segment.key().equals(key)) return null;
        return switch (segment) {
            case AgentPqPaceSegment.Quota quota -> {
                double rate = AgentPqPaceModel.killsPerSecond(quota, members, terms.value(quota.seekTerm()),
                        terms.value(quota.combatTerm()));
                yield rate > 0.0d ? work / rate : Double.POSITIVE_INFINITY;
            }
            case AgentPqPaceSegment.Clear clear ->
                    AgentPqPaceModel.waterFill(clear, members).seconds() / terms.value(clear.key());
            case AgentPqPaceSegment.Puzzle puzzle -> Math.max(0.0d, work - 1.0d) * (terms.value(puzzle.checkTerm())
                    + puzzle.leaderMoveShare() * terms.value(puzzle.moveTerm())) / 1000.0d;
            case AgentPqPaceSegment.Fixed fixed -> terms.value(fixed.term()) / 1000.0d;
            case AgentPqPaceSegment.Parallel ignored -> null;
        };
    }

    /** A leaf's expected theory seconds at the multiplier 1 (a quota at its mean kills, a puzzle at its mean checks). */
    private static double expectedSeconds(AgentPqPaceSegment segment, List<AgentPqMemberPower> members,
                                          AgentPqPaceModel.Terms terms) {
        return switch (segment) {
            case AgentPqPaceSegment.Quota quota -> {
                double rate = AgentPqPaceModel.killsPerSecond(quota, members, terms.value(quota.seekTerm()),
                        terms.value(quota.combatTerm()));
                yield !(rate > 0.0d) ? Double.POSITIVE_INFINITY
                        : quota.redraw() == null ? quota.kills().mean() / rate : QuotaMixture.of(quota, rate).meanS(rate);
            }
            case AgentPqPaceSegment.Puzzle puzzle -> theorySeconds(puzzle, puzzle.key(), puzzle.checks().mean(), members,
                    terms);
            case AgentPqPaceSegment.Parallel parallel -> theorySeconds(parallel, parallel.key(), 0.0d, members, terms);
            default -> theorySeconds(segment, segment.key(), 0.0d, members, terms);
        };
    }

    /**
     * MISSES and FRAGILE, in member order, for every member but the human leader (he waits out the fights a profile
     * parks him from; a human beside him fights, so whether he lands his hits counts too).
     */
    private static List<AgentPqPaceEstimate.Weakness> memberWeaknesses(AgentPqPaceProfile profile,
                                                                     List<AgentPqPaceSegment> segments,
                                                                     List<AgentPqMemberPower> members) {
        AgentPqPaceProfile.Thresholds thresholds = profile.thresholds();
        List<AgentPqPaceEstimate.Weakness> weaknesses = new ArrayList<>();
        String dangerSegment = "";
        for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments)) {
            if (segment instanceof AgentPqPaceSegment.Clear clear && clear.counts().containsKey(profile.dangerMobId())
                    && dangerSegment.isEmpty()) {
                dangerSegment = clear.key();
            }
        }
        for (AgentPqMemberPower member : members) {
            if (member.humanLeader()) continue;
            for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments)) {
                List<Integer> mobs = segment instanceof AgentPqPaceSegment.Quota quota ? List.of(quota.mobId())
                        : segment instanceof AgentPqPaceSegment.Clear clear ? List.copyOf(clear.counts().keySet())
                        : List.of();
                for (int mobId : mobs) {
                    if (member.hit(mobId) < thresholds.weakHitChance()) {
                        weaknesses.add(new AgentPqPaceEstimate.Weakness(member.characterId(), mobId, segment.key(),
                                AgentPqPaceEstimate.Kind.MISSES));
                    }
                }
            }
            if (profile.dangerMobId() > 0 && member.touchesToDown() < thresholds.fragileTouches()) {
                weaknesses.add(new AgentPqPaceEstimate.Weakness(member.characterId(), profile.dangerMobId(),
                        dangerSegment, AgentPqPaceEstimate.Kind.FRAGILE));
            }
        }
        return weaknesses;
    }

    /** The lower quantile of a binned distribution, in bins. */
    private static int quantile(double[] mass, double q) {
        double cumulative = 0.0d;
        for (int index = 0; index < mass.length; index++) {
            cumulative += mass[index];
            if (cumulative >= q - 1e-12) return index;
        }
        return mass.length - 1;
    }

    private static double[] normalized(double[] weights) {
        double total = 0.0d;
        for (double weight : weights) total += weight;
        double[] normalized = new double[weights.length];
        for (int index = 0; index < weights.length; index++) normalized[index] = weights[index] / total;
        return normalized;
    }

    /**
     * A quota's outcomes under its redraw: per outcome the restarts it took (their fixed time, ms), the kills it kept
     * and its chance. A draw is thrown away ({@link AgentPqPaceSegment.Redraw#redraws}) at the theory's kill rate
     * {@code rate}, as the party decides, and at most {@code maxRedraws} times in a row: attempt j is reached with the
     * chance q^j that every earlier draw was thrown away, and its draw is kept unless it is thrown away again, the last
     * attempt's always. Exact over the kill distribution; the chances sum to 1.
     */
    record QuotaMixture(double[] fixedMs, double[] kills, double[] p) {
        static QuotaMixture of(AgentPqPaceSegment.Quota quota, double rate) {
            AgentPqPaceSegment.Redraw redraw = quota.redraw();
            Pmf drawn = quota.kills();
            double[] mass = drawn.p();
            double mean = drawn.mean();
            boolean[] thrown = new boolean[mass.length];
            double thrownChance = 0.0d;
            for (int index = 0; index < mass.length; index++) {
                thrown[index] = redraw.redraws(drawn.offset() + index, mean, 1.0d / rate);
                if (thrown[index]) thrownChance += mass[index];
            }
            List<double[]> outcomes = new ArrayList<>();
            double reach = 1.0d;
            for (int attempt = 0; attempt <= redraw.maxRedraws(); attempt++) {
                boolean last = attempt == redraw.maxRedraws();
                for (int index = 0; index < mass.length; index++) {
                    if (!last && thrown[index]) continue;
                    outcomes.add(new double[]{(double) attempt * redraw.restartMs(), drawn.offset() + index,
                            reach * mass[index]});
                }
                reach *= thrownChance;
                if (reach == 0.0d) break;
            }
            double[] fixedMs = new double[outcomes.size()];
            double[] kills = new double[outcomes.size()];
            double[] p = new double[outcomes.size()];
            for (int index = 0; index < outcomes.size(); index++) {
                fixedMs[index] = outcomes.get(index)[0];
                kills[index] = outcomes.get(index)[1];
                p[index] = outcomes.get(index)[2];
            }
            return new QuotaMixture(fixedMs, kills, p);
        }

        /** The mean seconds at the multiplier 1: the restarts plus the kept kills at {@code rate}. */
        double meanS(double rate) {
            double mean = 0.0d;
            for (int index = 0; index < p.length; index++) {
                mean += p[index] * (fixedMs[index] / 1000.0d + kills[index] / rate);
            }
            return mean;
        }
    }

    /** Prices segments at one value of the shared efficiency multiplier. */
    private record Evaluation(AgentPqPaceModel.Terms terms, Map<String, Double> quotaRates,
                              Map<String, Double> killSeconds, long horizonBins) {
        Timeline sequence(List<AgentPqPaceSegment> segments, double multiplier, double weight,
                          Map<String, Double> segmentMeanMs) {
            Timeline total = Timeline.point(0.0d);
            for (AgentPqPaceSegment segment : segments) {
                Timeline piece = segment(segment, multiplier, weight, segmentMeanMs);
                segmentMeanMs.merge(segment.key(), weight * piece.meanMs(), Double::sum);
                total = total.plus(piece);
            }
            return total;
        }

        private Timeline segment(AgentPqPaceSegment segment, double multiplier, double weight,
                                 Map<String, Double> segmentMeanMs) {
            return switch (segment) {
                case AgentPqPaceSegment.Fixed fixed -> Timeline.point(terms.value(fixed.term()));
                case AgentPqPaceSegment.Clear clear -> Timeline.point(multiplier * killSeconds.get(clear.key()) * 1000.0d);
                case AgentPqPaceSegment.Quota quota -> {
                    double rate = quotaRates.get(quota.key());
                    if (quota.redraw() != null) {
                        QuotaMixture mixture = QuotaMixture.of(quota, rate);
                        double[] times = new double[mixture.p().length];
                        for (int index = 0; index < times.length; index++) {
                            times[index] = mixture.fixedMs()[index]
                                    + multiplier * mixture.kills()[index] / rate * 1000.0d;
                        }
                        yield Timeline.outcomes(times, mixture.p(), horizonBins);
                    }
                    Pmf kills = quota.kills();
                    double[] times = new double[kills.p().length];
                    for (int index = 0; index < times.length; index++) {
                        times[index] = multiplier * (kills.offset() + index) / rate * 1000.0d;
                    }
                    yield Timeline.outcomes(times, kills.p(), horizonBins);
                }
                case AgentPqPaceSegment.Puzzle puzzle -> {
                    Pmf checks = puzzle.checks();
                    double perCheck = terms.value(puzzle.checkTerm())
                            + puzzle.leaderMoveShare() * terms.value(puzzle.moveTerm());
                    double[] times = new double[checks.p().length];
                    for (int index = 0; index < times.length; index++) {
                        times[index] = (checks.offset() + index - 1) * perCheck;
                    }
                    yield Timeline.outcomes(times, checks.p(), horizonBins);
                }
                case AgentPqPaceSegment.Parallel parallel -> {
                    List<Timeline> branches = new ArrayList<>();
                    for (List<AgentPqPaceSegment> branch : parallel.branches()) {
                        branches.add(sequence(branch, multiplier, weight, segmentMeanMs));
                    }
                    yield Timeline.max(branches, horizonBins);
                }
            };
        }
    }

    /**
     * A time distribution: a continuous {@code shiftMs} plus {@code mass} over GRID_MS bins from 0, and its exact mean.
     * Point segments only move the shift, so the many fixed times of a run add without rounding; drawn segments are
     * rounded to the grid once per outcome, but their mean is kept unrounded (except past a parallel of drawn branches,
     * whose maximum is taken on the grid).
     */
    private record Timeline(double shiftMs, double[] mass, double meanMs) {
        static Timeline point(double ms) {
            return new Timeline(ms, new double[]{1.0d}, ms);
        }

        /** Each outcome on its nearest bin; outcomes past the horizon share the bin just past it. */
        static Timeline outcomes(double[] timesMs, double[] probabilities, long capBins) {
            long[] bins = new long[timesMs.length];
            long lowest = Long.MAX_VALUE;
            long highest = Long.MIN_VALUE;
            double mean = 0.0d;
            for (int index = 0; index < timesMs.length; index++) {
                bins[index] = Math.max(0L, Math.min(capBins + 1L, Math.round(timesMs[index] / GRID_MS)));
                lowest = Math.min(lowest, bins[index]);
                highest = Math.max(highest, bins[index]);
                mean += probabilities[index] * Math.min(timesMs[index], (capBins + 1L) * (double) GRID_MS);
            }
            double[] mass = new double[(int) (highest - lowest + 1)];
            for (int index = 0; index < timesMs.length; index++) mass[(int) (bins[index] - lowest)] += probabilities[index];
            return new Timeline(lowest * (double) GRID_MS, mass, mean);
        }

        boolean isPoint() {
            return mass.length == 1;
        }

        Timeline plus(Timeline other) {
            double mean = meanMs + other.meanMs;
            if (other.isPoint()) return new Timeline(shiftMs + other.shiftMs, mass, mean);
            if (isPoint()) return new Timeline(shiftMs + other.shiftMs, other.mass, mean);
            double[] sum = new double[mass.length + other.mass.length - 1];
            for (int left = 0; left < mass.length; left++) {
                if (mass[left] == 0.0d) continue;
                for (int right = 0; right < other.mass.length; right++) sum[left + right] += mass[left] * other.mass[right];
            }
            return new Timeline(shiftMs + other.shiftMs, sum, mean);
        }

        /** The distribution on absolute bins 0..cap, the tail beyond the cap folded into its last bin. */
        double[] absolute(long cap) {
            double[] bins = new double[(int) cap + 1];
            long start = Math.round(shiftMs / GRID_MS);
            for (int index = 0; index < mass.length; index++) {
                long bin = Math.max(0L, Math.min(cap, start + index));
                bins[(int) bin] += mass[index];
            }
            return bins;
        }

        /** The slowest branch: exact for point branches, else the product of the branches' distribution functions. */
        static Timeline max(List<Timeline> branches, long cap) {
            if (branches.stream().allMatch(Timeline::isPoint)) {
                return point(branches.stream().mapToDouble(Timeline::shiftMs).max().orElse(0.0d));
            }
            double[] cdf = new double[(int) cap + 1];
            java.util.Arrays.fill(cdf, 1.0d);
            for (Timeline branch : branches) {
                double[] bins = branch.absolute(cap);
                double cumulative = 0.0d;
                for (int index = 0; index < bins.length; index++) {
                    cumulative += bins[index];
                    cdf[index] *= Math.min(1.0d, cumulative);
                }
            }
            double[] mass = new double[cdf.length];
            double previous = 0.0d;
            for (int index = 0; index < cdf.length; index++) {
                mass[index] = Math.max(0.0d, cdf[index] - previous);
                previous = cdf[index];
            }
            double total = 0.0d;
            for (double value : mass) total += value;
            double mean = 0.0d;
            for (int index = 0; index < mass.length; index++) {
                mass[index] /= total;
                mean += index * (double) GRID_MS * mass[index];
            }
            return new Timeline(0.0d, mass, mean);
        }
    }
}
