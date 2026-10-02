package server.agents.capabilities.partyquest.kpq;

import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.awt.Point;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Re-rolling a heavy stage-1 draw (round 2b): human parties leave a KPQ run in stage 1 when two or more of them drew
 * Cloto's 35, and start it again, since a fresh draw is usually quicker than the grind. A companion run with a human
 * event leader does the same: once every collector has its coupon number and before any pass exists, it decides once
 * whether to offer it, and its leader decides. Stock and all-Agent runs never re-roll.
 *
 * <p>The rule: with the pace data warm and the party's power read at stage 1, the expected saving of a fresh draw,
 * {@link AgentPqPaceSegment.Redraw#savingMs}, must reach {@code minSavingMs} (the clear-time estimate prices the same
 * policy, {@link #redraw}); otherwise the players' rule of thumb, two or more collectors on the highest answer. Never
 * when the leader already declined this run, after {@code maxStreak} re-rolls of this leader in a row (reset by a
 * clear, a normal failure or {@code streakResetMs}), once a pass exists, or outside a companion run.</p>
 *
 * @param enabled       false: no run is ever offered a re-roll, and the estimate prices stage 1 as drawn
 * @param restartMs     what starting over costs a quick human: the exit, Nella, the walk to Lakelis, Yes, the question
 * @param minSavingMs   the least expected saving worth offering (estimate rule)
 * @param maxStreak     re-rolls of one leader in a row before no more are offered
 * @param streakResetMs how long a re-roll counts toward the streak
 * @param answerMs      how long the leader has to answer before the companions grind it out, from the question or,
 *                      once its window is on screen, from the window
 * @param windowClearPx how far every live Ligator must be from the leader before the companion's window may open
 */
public record AgentKpqRerollPolicy(boolean enabled, long restartMs, long minSavingMs, int maxStreak,
                                   long streakResetMs, long answerMs, int windowClearPx) {
    private static final boolean REROLL_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_ENABLED");
    private static final long REROLL_RESTART_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_RESTART_MS");
    private static final long REROLL_MIN_SAVING_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_MIN_SAVING_MS");
    private static final int REROLL_MAX_STREAK = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_MAX_STREAK");
    private static final long REROLL_STREAK_RESET_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_STREAK_RESET_MS");
    private static final long REROLL_ANSWER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_ANSWER_MS");
    private static final int REROLL_WINDOW_CLEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy.REROLL_WINDOW_CLEAR_PX");

    static final AgentKpqRerollPolicy CONFIGURED = new AgentKpqRerollPolicy(REROLL_ENABLED, REROLL_RESTART_MS,
            REROLL_MIN_SAVING_MS, REROLL_MAX_STREAK, REROLL_STREAK_RESET_MS, REROLL_ANSWER_MS, REROLL_WINDOW_CLEAR_PX);

    /** What the asking companion says once its leader chose to run it again: the run's parting line. */
    static final String GO_AGAIN_LINE = "ok, out we go. see you at lakelis.";
    /** What it says once the leader chose to push through, or let the question pass. */
    static final String GRIND_IT_OUT_LINE = "alright, let's grind it out.";
    /** The question when the estimate wants a re-roll although fewer than two drew the highest answer. */
    static final String LOT_OF_COUPONS_LINE = "that's a lot of coupons. wanna bail and run it again? it'd be quicker.";

    /** One word that says yes or no to anything: it counts only while nothing else was just asked of the leader. */
    private static final Set<String> ONE_WORD_GO_AGAIN = Set.of("yes", "yeah", "yep", "yup", "ok", "okay", "sure");
    private static final Set<String> ONE_WORD_PUSH_THROUGH = Set.of("no", "nah", "nope");
    /** Words that can only answer this question. */
    private static final Set<String> GO_AGAIN_WORDS = Set.of("go again", "lets go again", "run it again",
            "lets run it again", "bail", "lets bail", "sure lets go again", "ok lets go again", "yeah lets go again");
    private static final Set<String> PUSH_THROUGH_WORDS = Set.of("push through", "lets push through",
            "nah lets push through", "keep going", "lets keep going", "grind it out", "lets grind it out");

    public AgentKpqRerollPolicy {
        if (restartMs < 0L || minSavingMs < 0L || maxStreak < 0 || streakResetMs < 0L || answerMs < 0L
                || windowClearPx < 0) {
            throw new IllegalArgumentException("re-roll times, the streak and the window's clearance must be >= 0");
        }
    }

    /** The policy agent-engine.yaml configures. */
    public static AgentKpqRerollPolicy configured() {
        return CONFIGURED;
    }

    /** The same policy switched off. */
    AgentKpqRerollPolicy off() {
        return new AgentKpqRerollPolicy(false, restartMs, minSavingMs, maxStreak, streakResetMs, answerMs,
                windowClearPx);
    }

    /**
     * The stage-1 quota's redraw the clear-time estimate prices: the same saving rule and streak bound as the runs use;
     * null while the policy is off, so the estimate prices stage 1 exactly as drawn.
     */
    AgentPqPaceSegment.Redraw redraw() {
        return enabled ? new AgentPqPaceSegment.Redraw(restartMs, minSavingMs, maxStreak) : null;
    }

    /** Which rule a decision went by, as the decision line names it. */
    enum Rule {
        /** The expected saving from the warm pace data and the party's power. */
        ESTIMATE("estimate"),
        /** The players' rule of thumb: two or more collectors on the highest answer. */
        THIRTY_FIVES("thirty-fives");

        private final String label;

        Rule(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * One run's stage-1 draw as the decision reads it.
     *
     * @param companionRun   a companion-adopted run with a human event leader
     * @param declined       the leader already declined this run's offer
     * @param streak         the leader's re-rolls in a row (AgentKpqRerollStreaks)
     * @param passExists     a stage-1 pass exists already
     * @param targets        every collector's coupon number
     * @param remainingKills the coupons the party still needs: the numbers less those already picked up (the decision
     *                       waits for the last number, and the others keep killing meanwhile); the saving prices these
     * @param secondsPerKill stage 1's theory per kill for this party; NaN while the pace data is cold or unread
     * @param freshMeanKills a fresh draw's mean kills for this party size; NaN with it
     */
    record Draw(boolean companionRun, boolean declined, int streak, boolean passExists, List<Integer> targets,
                int remainingKills, double secondsPerKill, double freshMeanKills) {
        Draw {
            targets = List.copyOf(targets);
        }

        /** A draw nobody has collected for yet: every number still to grind. */
        Draw(boolean companionRun, boolean declined, int streak, boolean passExists, List<Integer> targets,
             double secondsPerKill, double freshMeanKills) {
            this(companionRun, declined, streak, passExists, targets,
                    targets.stream().mapToInt(Integer::intValue).sum(), secondsPerKill, freshMeanKills);
        }

        int total() {
            return targets.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * The decision and why: {@code savingMs} is the estimate's expected saving (NaN under the rule of thumb).
     */
    record Decision(boolean proposed, Rule rule, double savingMs, String reason) {
    }

    /** Decides whether to offer a re-roll for this draw. Pure. */
    Decision decide(Draw draw) {
        int highest = highestAnswer();
        long onHighest = draw.targets().stream().filter(target -> target == highest).count();
        boolean estimate = Double.isFinite(draw.secondsPerKill()) && draw.secondsPerKill() > 0.0d
                && Double.isFinite(draw.freshMeanKills()) && draw.freshMeanKills() > 0.0d;
        Rule rule = estimate ? Rule.ESTIMATE : Rule.THIRTY_FIVES;
        AgentPqPaceSegment.Redraw redraw = new AgentPqPaceSegment.Redraw(restartMs, minSavingMs, maxStreak);
        double savingMs = estimate
                ? redraw.savingMs(draw.remainingKills(), draw.freshMeanKills(), draw.secondsPerKill()) : Double.NaN;
        boolean wanted = estimate
                ? redraw.redraws(draw.remainingKills(), draw.freshMeanKills(), draw.secondsPerKill())
                : onHighest >= 2L;
        String gate = !enabled ? "disabled"
                : !draw.companionRun() ? "not-a-companion-run"
                : draw.declined() ? "declined"
                : draw.streak() >= maxStreak ? "streak"
                : draw.passExists() ? "pass-exists"
                : null;
        if (gate != null) return new Decision(false, rule, savingMs, gate);
        String reason = estimate ? (wanted ? "saving-reaches-minimum" : "saving-under-minimum")
                : (wanted ? "two-or-more-" + highest + "s" : "fewer-than-two-" + highest + "s");
        return new Decision(wanted, rule, savingMs, reason);
    }

    /** Cloto's highest coupon answer (35). */
    static int highestAnswer() {
        return AgentKpqDefinition.couponTargets().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * The asking companion's words: about the 35s when two or more drew them, else about the coupons; lower-case
     * player chat, no counter but the natural "35s".
     */
    static String askLine(List<Integer> targets) {
        int highest = highestAnswer();
        long onHighest = targets.stream().filter(target -> target == highest).count();
        if (onHighest < 2L) return LOT_OF_COUPONS_LINE;
        String who = onHighest == 2L ? "two" : onHighest == 3L ? "three" : "all";
        return "ugh, " + who + " of us got " + highest + "s. that's a long grind, wanna bail and run it again?";
    }

    /** {@link #typedAnswer(String, boolean)} while the question is the only one put to the leader. */
    static Boolean typedAnswer(String message) {
        return typedAnswer(message, true);
    }

    /**
     * A typed answer to the question in party or map chat: true for "go again" ("go again", "let's bail"...), false
     * for "push through" ("push through", "keep going"...), null for anything else. The whole line must be the answer,
     * so chat about something else never counts. A one-word yes or no ("ok", "sure", "nah"...) answers anything, so it
     * counts only when {@code oneWordCounts}: no other companion line asked something of the leader just before.
     */
    static Boolean typedAnswer(String message, boolean oneWordCounts) {
        if (message == null) return null;
        String normalized = message.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z ]", " ")
                .trim().replaceAll("\\s+", " ");
        if (GO_AGAIN_WORDS.contains(normalized)) return Boolean.TRUE;
        if (PUSH_THROUGH_WORDS.contains(normalized)) return Boolean.FALSE;
        if (!oneWordCounts) return null;
        if (ONE_WORD_GO_AGAIN.contains(normalized)) return Boolean.TRUE;
        if (ONE_WORD_PUSH_THROUGH.contains(normalized)) return Boolean.FALSE;
        return null;
    }

    /**
     * Whether the leader can take the companion's window now, which holds his input until he answers it: on his feet
     * on a foothold, out of his combat stance (he neither hit nor was hit moments ago), and no live Ligator within
     * {@code windowClearPx} of him. Until then the question stands in chat alone. Pure.
     */
    boolean lull(Point leader, boolean grounded, boolean combatStance, List<Point> liveLigators) {
        if (leader == null || !grounded || combatStance) return false;
        for (Point ligator : liveLigators) {
            if (ligator != null && ligator.distance(leader) <= windowClearPx) return false;
        }
        return true;
    }
}
