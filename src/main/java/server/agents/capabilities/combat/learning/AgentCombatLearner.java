package server.agents.capabilities.combat.learning;

import client.Character;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lets Agents learn which skill to use from what their casts actually did, instead of from
 * hand-written rotations.
 *
 * <p>Every attack and every summon/debuff cast is one decision in a situation
 * ({@link AgentCombatLearningContext}). When the next decision is taken, the previous one is
 * scored with semi-Markov Q-learning: its own measured damage plus the discounted value of the
 * situation it led to, discounted by how long it really took. That is what lets a finisher that
 * spends the Combo stack, a debuff that deals nothing now, or a summon that pays off later be
 * judged on what follows them and not only on their own hit.</p>
 *
 * <p>Learning starts from the damage formula, not from nothing: every option begins at the
 * estimated-DPS scorer's number (its own expected damage plus carrying on at a sustainable rate
 * after it: its own measured rate once known), weighted as a few pseudo-visits, and options the
 * estimate puts far below that rate are not explored at all. What the learner has to find from play is where that myopic
 * estimate is wrong: finishers that reset the stack, casts that land for nothing, summons and
 * debuffs whose worth only shows later.</p>
 *
 * <p>Modes: {@code LEARN} explores (every choice tried a few times per situation, then
 * epsilon-greedy) and updates the table; {@code FROZEN} plays the table greedily without
 * updating (the arena's evaluation); {@code LIVE} exploits an arena-adopted policy file where it
 * has enough evidence and otherwise leaves the decision to the estimated-DPS scorer;
 * {@code OFF} stays out of the way entirely (the arena's prior baseline and forced trials). An Agent nobody can see
 * (AgentCombatLearnGuard: a bench grinder alone at its camp) runs {@code LEARN} on its job's candidate table, which live
 * play never reads until the arena's {@code policy=candidate} check adopts it.</p>
 */
public final class AgentCombatLearner {
    private static final double GAMMA_PER_SECOND = config.AgentTuning.doubleValue("server.agents.capabilities.combat.learning.AgentCombatLearner.GAMMA_PER_SECOND");
    private static final double ALPHA_FLOOR = config.AgentTuning.doubleValue("server.agents.capabilities.combat.learning.AgentCombatLearner.ALPHA_FLOOR");
    private static final int MIN_TRIES = config.AgentTuning.intValue("server.agents.capabilities.combat.learning.AgentCombatLearner.MIN_TRIES");
    private static final int LIVE_MIN_EVIDENCE = config.AgentTuning.intValue("server.agents.capabilities.combat.learning.AgentCombatLearner.LIVE_MIN_EVIDENCE");
    private static final long PLAN_FRESH_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.learning.AgentCombatLearner.PLAN_FRESH_MS");
    private static final long MAX_WINDOW_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.learning.AgentCombatLearner.MAX_WINDOW_MS");
    private static final double PRIOR_WEIGHT = config.AgentTuning.doubleValue("server.agents.capabilities.combat.learning.AgentCombatLearner.PRIOR_WEIGHT");
    private static final double PRUNE_RATIO = config.AgentTuning.doubleValue("server.agents.capabilities.combat.learning.AgentCombatLearner.PRUNE_RATIO");
    private static final long SPECIAL_CAST_FLOOR_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.learning.AgentCombatLearner.SPECIAL_CAST_FLOOR_MS");
    private static final double CALIBRATION_WEIGHT = config.AgentTuning.doubleValue("server.agents.capabilities.combat.learning.AgentCombatLearner.CALIBRATION_WEIGHT");
    private static final int RATE_MIN_DECISIONS = config.AgentTuning.intValue("server.agents.capabilities.combat.learning.AgentCombatLearner.RATE_MIN_DECISIONS");

    public enum Mode { LIVE, LEARN, FROZEN, OFF }

    /** A learner's say on one special move: cast it, skip it, or leave it to the prior rules. */
    public enum SpecialVerdict { CAST, SKIP, DEFER }

    private static volatile boolean liveEnabled = true;
    /** Arena override of the hand-written priors per character: true relaxes them, false pins them on. */
    private static final Map<Integer, Boolean> PRIORS_OVERRIDE = new ConcurrentHashMap<>();

    private AgentCombatLearner() {
    }

    public static void setLiveEnabled(boolean value) {
        liveEnabled = value;
    }

    public static double gammaPerSecond() {
        return GAMMA_PER_SECOND;
    }

    /** Puts one Agent in a mode; the arena calls this around each trial. */
    public static void begin(AgentRuntimeEntry entry, Mode mode, AgentCombatPolicyTable table,
                             double epsilon, boolean relaxPriors) {
        state(entry).begin(mode, table, epsilon);
        PRIORS_OVERRIDE.put(entry.bot().getId(), relaxPriors);
    }

    public static void end(AgentRuntimeEntry entry) {
        state(entry).end();
        PRIORS_OVERRIDE.remove(entry.bot().getId());
    }

    /** Drops the open decision without learning from it (the arena resets the fight between episodes). */
    public static void discardOpen(AgentRuntimeEntry entry) {
        state(entry).clearOpen();
    }

    public static long decisions(AgentRuntimeEntry entry) {
        return state(entry).decisions();
    }

    /**
     * Whether the hand-written priors (hold Combo to a full stack, Crash only on a buffed target)
     * are lifted for this Agent: in the arena when a run asks the learner to rediscover them, and
     * live when the job's adopted policy was learned without them.
     */
    public static boolean priorsRelaxed(Character bot) {
        if (bot == null) {
            return false;
        }
        Boolean override = PRIORS_OVERRIDE.get(bot.getId());
        if (override != null) {
            return override;
        }
        if (!liveEnabled || bot.getJob() == null) {
            return false;
        }
        return AgentCombatPolicyRepository.defaultRepository().find(bot.getJob().getId())
                .map(loaded -> loaded.policy().priorsRelaxed()
                        && loaded.policy().scenarios().values().stream().anyMatch(AgentCombatPolicy.Scenario::adopted))
                .orElse(false);
    }

    /** The learner's pick among this tick's planned attacks, or null to let the rotation profile/scorer decide. */
    public static AgentAttackPlan choose(AgentRuntimeEntry entry, Character bot, Monster target,
                                         List<AgentAttackPlan> candidates) {
        if (entry == null || bot == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        AgentCombatLearningState state = state(entry);
        Mode mode = resolve(state, bot);
        if (mode == Mode.OFF) {
            return null;
        }
        int reach = reach(candidates);
        AgentCombatPolicyTable table = tableFor(state, mode, bot, target, reach);
        if (table == null) {
            return null;
        }
        String context = AgentCombatLearningContext.of(entry, bot, target, reach);
        Estimates estimates = estimate(bot, candidates, table);
        List<Integer> actions = estimates.worthTrying();
        if (mode != Mode.LIVE) {
            // The adopted live table is shared and read-only; only the arena's own table takes priors.
            estimates.priors().forEach((action, prior) -> table.seed(context, action, prior));
        }
        state.plan(context, actions, reach, estimates.bestDps(), System.currentTimeMillis());
        Integer pick = switch (mode) {
            case LEARN -> explore(table, context, actions, state.epsilon(), MIN_TRIES, ThreadLocalRandom.current());
            case FROZEN -> table.bestAction(context, actions, 0);
            default -> table.bestAction(context, actions, LIVE_MIN_EVIDENCE);
        };
        if (pick == null) {
            return null;
        }
        for (AgentAttackPlan candidate : candidates) {
            if (candidate.skillId == pick) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether a summon/debuff should take the next attack window, weighed against the attacks just planned. */
    public static SpecialVerdict special(AgentRuntimeEntry entry, Character bot, Monster target, int skillId) {
        if (entry == null || bot == null) {
            return SpecialVerdict.DEFER;
        }
        AgentCombatLearningState state = state(entry);
        Mode mode = resolve(state, bot);
        if (mode == Mode.OFF) {
            return SpecialVerdict.DEFER;
        }
        long now = System.currentTimeMillis();
        AgentCombatLearningState.Plan plan = state.plan();
        boolean fresh = plan != null && now - plan.atMs() <= PLAN_FRESH_MS;
        AgentCombatPolicyTable table = tableFor(state, mode, bot, target, fresh ? plan.reach() : 1);
        if (table == null || !fresh) {
            // Nothing to weigh it against yet: live keeps the prior rules, the arena waits for a plan.
            return mode == Mode.LIVE ? SpecialVerdict.DEFER : SpecialVerdict.SKIP;
        }
        String context = AgentCombatLearningContext.of(entry, bot, target, plan.reach());
        List<Integer> attacks = plan.actions();
        if (mode != Mode.LIVE) {
            // Nothing lands on the cast itself; the prior is "carry on at the best rate after spending the cast".
            table.seed(context, skillId, prior(0, castSeconds(bot, skillId), plan.bestDps()));
        }
        SpecialVerdict verdict;
        switch (mode) {
            case LEARN -> {
                List<Integer> options = new ArrayList<>(attacks);
                options.add(skillId);
                Integer pick = explore(table, context, options, state.epsilon(), MIN_TRIES, ThreadLocalRandom.current());
                verdict = pick != null && pick == skillId ? SpecialVerdict.CAST : SpecialVerdict.SKIP;
            }
            case FROZEN -> {
                AgentCombatPolicyTable.Cell cell = table.cell(context, skillId);
                double bestAttack = table.bestValue(context, attacks, 0);
                verdict = cell != null && !Double.isNaN(bestAttack) && cell.value() > bestAttack
                        ? SpecialVerdict.CAST : SpecialVerdict.SKIP;
            }
            default -> {
                AgentCombatPolicyTable.Cell cell = table.cell(context, skillId);
                double bestAttack = table.bestValue(context, attacks, LIVE_MIN_EVIDENCE);
                if (cell == null || cell.visits() < LIVE_MIN_EVIDENCE || Double.isNaN(bestAttack)) {
                    return SpecialVerdict.DEFER;
                }
                verdict = cell.value() > bestAttack ? SpecialVerdict.CAST : SpecialVerdict.SKIP;
            }
        }
        if (verdict == SpecialVerdict.CAST) {
            state.special(context, now);
        }
        return verdict;
    }

    /** Whether this Agent is learning right now; the attack path only pays for an expected-damage estimate then. */
    public static boolean learning(AgentRuntimeEntry entry) {
        return entry != null && state(entry).mode() == Mode.LEARN;
    }

    /**
     * An attack landed for {@code damage}, where the formula expected {@code expected} for exactly
     * this cast (0 when unknown): close the previous decision and open this one.
     */
    public static void onAttackCommitted(AgentRuntimeEntry entry, int skillId, long damage, double expected) {
        if (entry == null) {
            return;
        }
        AgentCombatLearningState state = state(entry);
        if (state.mode() != Mode.LEARN) {
            return;
        }
        long now = System.currentTimeMillis();
        AgentCombatLearningState.Plan plan = state.plan();
        if (plan == null || now - plan.atMs() > PLAN_FRESH_MS) {
            state.clearOpen();
            return;
        }
        learn(state, plan.context(), plan.actions(), now);
        double reward = state.table() == null ? Math.max(0, damage)
                : state.table().calibratedReward(plan.context(), skillId, Math.max(0, damage), expected, CALIBRATION_WEIGHT);
        state.open(new AgentCombatLearningState.Open(plan.context(), skillId, now, reward));
    }

    /** A summon/debuff the learner allowed was cast; it deals nothing now, what follows decides its worth. */
    public static void onSpecialCast(AgentRuntimeEntry entry, int skillId) {
        if (entry == null) {
            return;
        }
        AgentCombatLearningState state = state(entry);
        if (state.mode() != Mode.LEARN) {
            return;
        }
        long now = System.currentTimeMillis();
        String context = state.specialContext(now, PLAN_FRESH_MS);
        AgentCombatLearningState.Plan plan = state.plan();
        if (context == null || plan == null) {
            state.clearOpen();
            return;
        }
        learn(state, context, plan.actions(), now);
        state.open(new AgentCombatLearningState.Open(context, skillId, now, 0));
    }

    /**
     * Damage the Agent caused without a cast of its own (a summon attacking) is credited to
     * whatever decision is running when it lands; the "summon up" situation carries the rest.
     */
    public static void onPassiveDamage(AgentRuntimeEntry entry, long damage) {
        if (entry == null || damage <= 0) {
            return;
        }
        AgentCombatLearningState state = state(entry);
        if (state.mode() == Mode.LEARN) {
            state.addToOpen(damage);
        }
    }

    private static void learn(AgentCombatLearningState state, String nextContext, List<Integer> nextActions, long now) {
        AgentCombatLearningState.Open open = state.open();
        AgentCombatPolicyTable table = state.table();
        if (open == null || table == null) {
            return;
        }
        long windowMs = now - open.atMs();
        state.clearOpen();
        if (windowMs <= 0 || windowMs > MAX_WINDOW_MS) {
            return;  // the fight was interrupted (reset, walked off); not a reading of the skill
        }
        double next = table.bestValue(nextContext, nextActions, 0);
        if (Double.isNaN(next)) {
            AgentCombatPolicyTable.Cell own = table.cell(open.context(), open.action());
            next = own == null ? Double.NaN : own.value();
        }
        table.update(open.context(), open.action(),
                target(open.reward(), windowMs / 1000.0d, GAMMA_PER_SECOND, next), ALPHA_FLOOR, PRIOR_WEIGHT,
                open.reward(), windowMs / 1000.0d);
    }

    /**
     * Semi-Markov Q-learning target: the action's damage plus the next situation's value,
     * discounted by the seconds the action took. With no estimate of what follows, assume the
     * same return keeps coming at this rate.
     */
    static double target(double reward, double seconds, double gammaPerSecond, double nextValue) {
        double discount = Math.pow(gammaPerSecond, Math.max(0.0d, seconds));
        if (Double.isNaN(nextValue)) {
            return discount < 1.0d ? reward / (1.0d - discount) : reward;
        }
        return reward + discount * nextValue;
    }

    /** Least-tried first until every option has {@code minTries} visits here, then epsilon-greedy. */
    static Integer explore(AgentCombatPolicyTable table, String context, List<Integer> actions,
                           double epsilon, int minTries, Random random) {
        if (actions.isEmpty()) {
            return null;
        }
        int fewest = Integer.MAX_VALUE;
        for (int action : actions) {
            fewest = Math.min(fewest, table.visits(context, action));
        }
        if (fewest < Math.max(1, minTries)) {
            List<Integer> leastTried = new ArrayList<>();
            for (int action : actions) {
                if (table.visits(context, action) == fewest) {
                    leastTried.add(action);
                }
            }
            return leastTried.get(random.nextInt(leastTried.size()));
        }
        if (random.nextDouble() < epsilon) {
            return actions.get(random.nextInt(actions.size()));
        }
        return table.bestAction(context, actions, 0);
    }

    /**
     * The mode this decision is taken in. A mode the arena set ({@link #begin}) stands until it {@link #end}s. Otherwise
     * an Agent the installed {@link AgentCombatLearnGuard} rule says nobody can see learns ({@code LEARN}) into its job's
     * candidate table ({@link AgentCombatCandidates}), and the moment the rule says otherwise it is back in the live
     * mode, the decision it had open dropped.
     */
    static Mode resolve(AgentCombatLearningState state, Character bot) {
        if (state.held()) {
            return state.mode();
        }
        AgentCombatLearnGuard.Rule rule = AgentCombatLearnGuard.rule();
        if (rule != null && bot.getJob() != null && rule.learnUnseen(bot)) {
            state.learnUnseen(AgentCombatCandidates.defaultCandidates().table(bot.getJob().getId()), rule.epsilon());
        } else {
            state.seen();
        }
        return state.mode();
    }

    private static AgentCombatPolicyTable tableFor(AgentCombatLearningState state, Mode mode, Character bot,
                                                   Monster target, int reach) {
        if (mode != Mode.LIVE) {
            return state.table();
        }
        if (!liveEnabled || bot.getJob() == null) {
            return null;
        }
        String scenario = AgentCombatPolicy.scenarioFor(target != null && target.isBoss(), reach);
        return AgentCombatPolicyRepository.defaultRepository().find(bot.getJob().getId())
                .filter(loaded -> {
                    AgentCombatPolicy.Scenario learned = loaded.policy().scenarios().get(scenario);
                    return learned != null && learned.adopted();
                })
                .map(loaded -> loaded.tables().get(scenario))
                .orElse(null);
    }

    /**
     * The scorer's view of this tick's candidates: priors for the ones worth trying, and the rate
     * the Agent can keep up after any of them ({@code bestDps}), which every prior carries on at.
     */
    record Estimates(Map<Integer, Double> priors, double bestDps) {
        List<Integer> worthTrying() {
            return new ArrayList<>(priors.keySet());
        }
    }

    private static Estimates estimate(Character bot, List<AgentAttackPlan> candidates, AgentCombatPolicyTable table) {
        Map<Integer, double[]> damageAndSeconds = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> spendsResource = new java.util.HashSet<>();
        for (AgentAttackPlan candidate : candidates) {
            if (damageAndSeconds.containsKey(candidate.skillId)) {
                continue;
            }
            double damage = AgentAttackPlanScoringPolicy.scoreAttackPlan(bot, candidate).rawDamage();
            damageAndSeconds.put(candidate.skillId, new double[] {damage, Math.max(1, candidate.cooldownMs) / 1000.0d});
            if (constants.game.GameConstants.isFinisherSkill(candidate.skillId)
                    || server.agents.capabilities.combat.AgentCombatSkillClassifier.isEnergyAttackSkill(candidate.skillId)
                    || server.agents.capabilities.combat.AgentAranCombo.spendsCombo(candidate.skillId)) {
                spendsResource.add(candidate.skillId);
            }
        }
        return estimates(damageAndSeconds, spendsResource, table.observedRate(RATE_MIN_DECISIONS),
                GAMMA_PER_SECOND, PRUNE_RATIO);
    }

    /**
     * Prior for each option: its estimated damage plus the discounted value of carrying on at the
     * sustainable rate once it is done, so a slow option pays for its time and one that lands
     * nothing costs exactly its cast. The sustainable rate is what the Agent has actually been
     * dealing ({@code observedRate}) once known, before that the best estimate among skills that
     * spend nothing - a finisher's or energy attack's number cannot be repeated back to back.
     * Options estimated below {@code pruneRatio} of that rate are left out; resource spenders are
     * kept, since their worth depends on the stack or charge they spend.
     */
    static Estimates estimates(Map<Integer, double[]> damageAndSeconds, java.util.Set<Integer> spendsResource,
                               double observedRate, double gammaPerSecond, double pruneRatio) {
        double sustainable = 0;
        for (Map.Entry<Integer, double[]> e : damageAndSeconds.entrySet()) {
            if (!spendsResource.contains(e.getKey())) {
                sustainable = Math.max(sustainable, e.getValue()[0] / e.getValue()[1]);
            }
        }
        double carryOnRate = Double.isNaN(observedRate) || observedRate <= 0 ? sustainable : observedRate;
        Map<Integer, Double> priors = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> e : damageAndSeconds.entrySet()) {
            double dps = e.getValue()[0] / e.getValue()[1];
            if (sustainable > 0 && dps < pruneRatio * sustainable && !spendsResource.contains(e.getKey())) {
                continue;
            }
            priors.put(e.getKey(), prior(e.getValue()[0], e.getValue()[1], carryOnRate, gammaPerSecond));
        }
        return new Estimates(priors, carryOnRate);
    }

    private static double prior(double damage, double seconds, double bestDps) {
        return prior(damage, seconds, bestDps, GAMMA_PER_SECOND);
    }

    static double prior(double damage, double seconds, double bestDps, double gammaPerSecond) {
        double carryOn = bestDps / -Math.log(gammaPerSecond);
        return damage + Math.pow(gammaPerSecond, seconds) * carryOn;
    }

    /** The real lock a summon/debuff cast puts on the next attack, as the special-move tick applies it. */
    private static double castSeconds(Character bot, int skillId) {
        long ms = server.agents.capabilities.combat.AgentCombatSpecialMoveTickRuntime.castLockMs(bot, skillId);
        return Math.max(ms, SPECIAL_CAST_FLOOR_MS) / 1000.0d;
    }

    private static int reach(List<AgentAttackPlan> candidates) {
        int reach = 1;
        for (AgentAttackPlan candidate : candidates) {
            if (candidate.targets != null) {
                reach = Math.max(reach, candidate.targets.size());
            }
        }
        return reach;
    }

    private static AgentCombatLearningState state(AgentRuntimeEntry entry) {
        return entry.capabilityStates().require(AgentCombatLearningState.STATE_KEY);
    }
}
