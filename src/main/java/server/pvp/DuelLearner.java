package server.pvp;

import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The duel learner (docs/PVP_PLAN.md V2.3, docs/WORLD_SYSTEMS_PLAN.md I1.2): semi-Markov Q-learning over what a bot does
 * at each think, as the combat learner does over skills. The situation is {@link Situation}; an action is a skill id (an
 * attack) or one of the codes below; the reward is the damage dealt less the damage taken, each as a share of its
 * victim's max HP (the plan's H_ref, per victim, so a class's own HP counts), plus 1 for a win and -1 for a loss. Every
 * situation starts from what the prior brain would do there ({@link DuelBrain}), a little above the rest.
 */
final class DuelLearner {
    static final int APPROACH = -1;
    static final int RETREAT = -2;
    static final int JUMP = -3;
    /** Onto the next level above or below (a climb, a drop), to break line or meet a foe there. */
    static final int LEVEL = -4;
    static final int BUFF = -5;
    static final int DRINK = -6;
    static final int WAIT = -7;

    static final double GAMMA_PER_SECOND = 0.95d;
    static final double ALPHA_FLOOR = 0.02d;
    /** How many visits the starting value weighs, and how far the prior's choice starts above the rest. */
    static final double PRIOR_WEIGHT = 10d;
    static final double PRIOR_EDGE = 0.1d;
    static final double WIN = 1d;
    /** A think this long after the last one closes no decision (the fight was held up). */
    static final long MAX_WINDOW_MS = 10_000L;
    /** A live duel follows the table only where the action was tried this often there; else the prior brain. */
    static final int LIVE_MIN_VISITS = 30;

    enum Mode { LEARN, FROZEN, LIVE }

    private DuelLearner() {
    }

    /** What a fighter sees as it thinks; every field is coarse, as a person reads a fight. */
    record Situation(int distancePx, int reachPx, int foeAbovePx, double ownHp, double foeHp, double ownMp,
                     boolean foeRanged, boolean foeSwinging, boolean tonicReady) {
        String key() {
            String distance = distancePx < 80 ? "close" : distancePx <= reachPx ? "reach"
                    : distancePx <= 2 * reachPx ? "near" : "far";
            String level = Math.abs(foeAbovePx) <= DuelBrain.LEVEL_TOLERANCE_PX ? "level" : foeAbovePx > 0 ? "above" : "below";
            return distance + "|" + level + "|hp" + third(ownHp) + "|foe" + third(foeHp) + (ownMp < 0.2d ? "|dry" : "")
                    + (foeRanged ? "|ranged" : "|melee") + (foeSwinging ? "|swinging" : "") + (tonicReady ? "|tonic" : "");
        }

        private static int third(double share) {
            return share > 0.6d ? 2 : share > 0.3d ? 1 : 0;
        }
    }

    /** One fighter's learner in one duel. The Agent's thread decides; the duel's thread ends it. */
    static final class Seat {
        private final Mode mode;
        private final AgentCombatPolicyTable table;
        private final double epsilon;
        private final Random random;
        private String openKey;
        private int openAction;
        private long openAtMs;
        private double openScore;
        private long decisions;

        Seat(Mode mode, AgentCombatPolicyTable table, double epsilon) {
            this(mode, table, epsilon, null);
        }

        Seat(Mode mode, AgentCombatPolicyTable table, double epsilon, Random random) {
            this.mode = mode;
            this.table = table;
            this.epsilon = epsilon;
            this.random = random;
        }

        /**
         * The action to take at {@code now} among {@code legal} (which holds {@code prior}, what the prior brain would
         * do): {@code score} is the damage dealt less taken so far, in HP shares; it closes the last decision.
         */
        synchronized int choose(Situation situation, List<Integer> legal, int prior, double score, long now) {
            String key = situation.key();
            if (mode != Mode.LEARN) {
                // the measuring bouts play exactly what a live duel would
                Integer best = table.bestAction(key, legal, LIVE_MIN_VISITS);
                return best == null || table.visits(key, prior) < LIVE_MIN_VISITS ? prior : best;
            }
            for (int action : legal) {
                table.seed(key, action, action == prior ? PRIOR_EDGE : 0d);
            }
            close(score, now, table.bestValue(key, legal, 0));
            Random draw = random == null ? ThreadLocalRandom.current() : random;
            int pick = draw.nextDouble() < epsilon ? legal.get(draw.nextInt(legal.size())) : table.bestAction(key, legal, 0);
            openKey = key;
            openAction = pick;
            openAtMs = now;
            openScore = score;
            decisions++;
            return pick;
        }

        /** The duel is over: {@code outcome} is 1 for a win, -1 for a loss, 0 for a draw; nothing follows. */
        synchronized void end(double score, int outcome, long now) {
            if (mode == Mode.LEARN && openKey != null) {
                update(score - openScore + outcome * WIN, Math.max(1L, now - openAtMs), 0d);
                openKey = null;
            }
        }

        private void close(double score, long now, double next) {
            if (openKey != null && now > openAtMs && now - openAtMs <= MAX_WINDOW_MS) {
                update(score - openScore, now - openAtMs, next);
            }
            openKey = null;
        }

        private void update(double reward, long windowMs, double next) {
            double seconds = windowMs / 1000d;
            double target = reward + Math.pow(GAMMA_PER_SECOND, seconds) * (Double.isNaN(next) ? 0d : next);
            table.update(openKey, openAction, target, ALPHA_FLOOR, PRIOR_WEIGHT, reward, seconds);
        }

        synchronized long decisions() {
            return decisions;
        }
    }
}
