package server.agents.capabilities.combat.learning;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Learned value of each action (skill id, 0 = basic attack) in each situation
 * ({@link AgentCombatLearningContext}). A value is the discounted damage that followed taking the
 * action there: the action's own damage plus what the situation it led to is worth. A cell can
 * start from a prior (the damage formula's estimate) that counts as a few pseudo-visits, so one
 * lucky roll cannot overturn it; after that, updates are the running mean over prior and visits,
 * with a floored step size so later play can still move a value when the situation changes.
 */
public final class AgentCombatPolicyTable {
    /**
     * Visits and learned value of one (situation, action) pair; {@code seeded} = started from a
     * prior. {@code meanReward} and {@code meanSeconds} are what the choice itself delivered per
     * cast and how long it took - the evidence behind the value, for reports and for anything an
     * Agent says about its skills.
     */
    public record Cell(int visits, double value, boolean seeded, double meanReward, double meanSeconds) {
    }

    private final Map<String, Map<Integer, Cell>> rows = new TreeMap<>();
    /** Realized vs expected damage per "skill" and per "situation|skill": {realized, expected, casts}. */
    private final Map<String, double[]> calibration = new LinkedHashMap<>();
    /** Every closed decision so far: {damage credited, seconds, decisions}; the rate the Agent actually sustains. */
    private final double[] observed = new double[3];

    public synchronized Cell cell(String context, int action) {
        Map<Integer, Cell> row = rows.get(context);
        return row == null ? null : row.get(action);
    }

    public synchronized int visits(String context, int action) {
        Cell cell = cell(context, action);
        return cell == null ? 0 : cell.visits();
    }

    /** Starts an unseen pair at {@code prior}; an existing cell keeps what it has learned. */
    public synchronized void seed(String context, int action, double prior) {
        rows.computeIfAbsent(context, ignored -> new LinkedHashMap<>())
                .putIfAbsent(action, new Cell(0, prior, true, 0.0d, 0.0d));
    }

    /**
     * Moves the value toward {@code target}; a seeded prior weighs as {@code priorWeight} visits.
     * {@code reward} and {@code seconds} are this visit's own damage and duration.
     */
    public synchronized void update(String context, int action, double target, double alphaFloor, double priorWeight,
                                    double reward, double seconds) {
        observed[0] += reward;
        observed[1] += seconds;
        observed[2] += 1;
        Map<Integer, Cell> row = rows.computeIfAbsent(context, ignored -> new LinkedHashMap<>());
        Cell cell = row.get(action);
        if (cell == null) {
            row.put(action, new Cell(1, target, false, reward, seconds));
            return;
        }
        int visits = cell.visits() + 1;
        double alpha = Math.max(1.0d / (visits + (cell.seeded() ? priorWeight : 0.0d)), alphaFloor);
        row.put(action, new Cell(visits, cell.value() + alpha * (target - cell.value()), cell.seeded(),
                cell.meanReward() + (reward - cell.meanReward()) / visits,
                cell.meanSeconds() + (seconds - cell.meanSeconds()) / visits));
    }

    /**
     * The damage to credit a cast with: the formula's expected damage for exactly this cast,
     * scaled by how realized damage has compared with expected for this skill in this situation.
     * The damage roll carries no information about the choice, so taking the expectation removes
     * its noise; the calibration keeps what the formula does not know (server caps, immunities,
     * edits it does not model). Each level shrinks toward the one above it by {@code weight}
     * pseudo-casts: situation toward skill, skill toward 1.
     */
    public synchronized double calibratedReward(String context, int skillId, double realized, double expected,
                                                double weight) {
        if (expected <= 0) {
            return realized;
        }
        double[] skill = calibration.computeIfAbsent(Integer.toString(skillId), ignored -> new double[3]);
        double[] situation = calibration.computeIfAbsent(context + "|" + skillId, ignored -> new double[3]);
        for (double[] acc : new double[][] {skill, situation}) {
            acc[0] += Math.max(0, realized);
            acc[1] += expected;
            acc[2] += 1;
        }
        double skillRatio = shrunkRatio(skill, 1.0d, weight);
        return expected * shrunkRatio(situation, skillRatio, weight);
    }

    /** Damage per second over every decision learned from, or NaN before {@code minDecisions} of them. */
    public synchronized double observedRate(int minDecisions) {
        return observed[2] < Math.max(1, minDecisions) || observed[1] <= 0 ? Double.NaN : observed[0] / observed[1];
    }

    /** Realized/expected over {@code acc}, pulled toward {@code toward} as if {@code weight} casts had read exactly that. */
    static double shrunkRatio(double[] acc, double toward, double weight) {
        double meanExpected = acc[2] <= 0 ? 0 : acc[1] / acc[2];
        double pseudo = weight * meanExpected;
        return acc[1] + pseudo <= 0 ? toward : (acc[0] + pseudo * toward) / (acc[1] + pseudo);
    }

    /** Highest value among {@code actions} visited at least {@code minVisits} times (0 admits priors), or NaN. */
    public synchronized double bestValue(String context, Collection<Integer> actions, int minVisits) {
        Integer best = bestAction(context, actions, minVisits);
        return best == null ? Double.NaN : cell(context, best).value();
    }

    /** Action with the highest value among {@code actions} visited at least {@code minVisits} times; null if none. */
    public synchronized Integer bestAction(String context, Collection<Integer> actions, int minVisits) {
        Map<Integer, Cell> row = rows.get(context);
        if (row == null) {
            return null;
        }
        Integer best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int action : actions) {
            Cell cell = row.get(action);
            if (cell != null && cell.visits() >= minVisits && cell.value() > bestValue) {
                best = action;
                bestValue = cell.value();
            }
        }
        return best;
    }

    public synchronized Map<String, Map<Integer, Cell>> snapshot() {
        Map<String, Map<Integer, Cell>> copy = new TreeMap<>();
        rows.forEach((context, row) -> copy.put(context, new LinkedHashMap<>(row)));
        return copy;
    }

    public static AgentCombatPolicyTable of(Map<String, Map<Integer, Cell>> rows) {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        if (rows != null) {
            rows.forEach((context, row) -> table.rows.put(context, new LinkedHashMap<>(row)));
        }
        return table;
    }
}
