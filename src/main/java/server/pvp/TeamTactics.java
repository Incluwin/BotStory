package server.pvp;

import java.util.List;

/**
 * Team fights (docs/PVP_PLAN.md V6): which foe a side's bots go for. One side picks one target together (focus fire,
 * which is why the sides are always equal in size), its bots join a player's target when the player is hitting one
 * (they assist it, as teammates would), and a target too far from a bot is left for the foe at hand. Pure, so it is
 * tested apart from the duel.
 */
public final class TeamTactics {
    /** A player's landed hit this recent sets the side's target: the bots on its side assist it. */
    static final long ASSIST_MS = 4_000L;
    /** A called target farther than this from a bot is left for the nearest foe (it fights who is at hand). */
    static final int FOCUS_RANGE_PX = 650;
    /** A new call needs a foe at least this much lower in HP share than the one called. */
    static final double SWITCH_MARGIN = 0.25d;
    /** A call stands at least this long, so a side does not flicker between two foes. */
    static final long MIN_CALL_MS = 2_500L;
    /** A foe this many px farther from the side counts as one whole HP share healthier when picking one. */
    static final double DISTANCE_PER_SHARE_PX = 1_200d;

    private TeamTactics() {
    }

    /** A standing foe as the side sees it: its HP share (0..1) and where it stands. */
    public record Foe(int id, double hpShare, int x, int y) {
        double distanceTo(int fromX, int fromY) {
            return Math.hypot(x - fromX, y - fromY);
        }
    }

    /** What a side goes for and why: {@code assist} when it is the player's target. 0 when no foe stands. */
    public record Call(int targetId, boolean assist) {
        static final Call NONE = new Call(0, false);
    }

    /**
     * The side's target at {@code now}. {@code current} was called at {@code calledAtMs}; {@code playerTarget} is the
     * foe a player on the side last landed a hit on, at {@code playerHitAtMs} (0 when none). The side stands around
     * ({@code centreX}, {@code centreY}).
     */
    public static Call call(List<Foe> foes, int current, long calledAtMs, int playerTarget, long playerHitAtMs,
                            int centreX, int centreY, long now) {
        if (foes.isEmpty()) {
            return Call.NONE;
        }
        Foe called = find(foes, current);
        Foe playerFoe = find(foes, playerTarget);
        if (playerFoe != null && now - playerHitAtMs <= ASSIST_MS) {
            return new Call(playerFoe.id(), true);
        }
        if (called != null && now - calledAtMs < MIN_CALL_MS) {
            return new Call(called.id(), false);
        }
        Foe best = null;
        double bestScore = Double.MAX_VALUE;
        for (Foe foe : foes) {
            double score = foe.hpShare() + foe.distanceTo(centreX, centreY) / DISTANCE_PER_SHARE_PX;
            if (score < bestScore) {
                bestScore = score;
                best = foe;
            }
        }
        if (called != null && best.hpShare() > called.hpShare() - SWITCH_MARGIN) {
            return new Call(called.id(), false);
        }
        return new Call(best.id(), false);
    }

    /**
     * Which foe one bot at ({@code x}, {@code y}) goes for: the side's {@code call} while it is within
     * {@link #FOCUS_RANGE_PX}, else the nearest foe. 0 when no foe stands.
     */
    public static int targetFor(List<Foe> foes, Call call, int x, int y) {
        Foe called = find(foes, call.targetId());
        if (called != null && called.distanceTo(x, y) <= FOCUS_RANGE_PX) {
            return called.id();
        }
        Foe nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Foe foe : foes) {
            double distance = foe.distanceTo(x, y);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = foe;
            }
        }
        return nearest == null ? 0 : nearest.id();
    }

    private static Foe find(List<Foe> foes, int id) {
        if (id <= 0) {
            return null;
        }
        for (Foe foe : foes) {
            if (foe.id() == id) {
                return foe;
            }
        }
        return null;
    }
}
