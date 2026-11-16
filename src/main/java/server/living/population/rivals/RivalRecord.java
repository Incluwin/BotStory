package server.living.population.rivals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3.4: a human's head-to-head record against their rival crew (docs/PVP_PLAN.md V3 "After"), counted from the
 * {@code pvp_match} rows of the challenges they fought on the player side (side 0).
 */
final class RivalRecord {
    private RivalRecord() {
    }

    /** One fighter of a booked match. */
    record Fighter(int characterId, int side, boolean human, boolean ko, long dealt, long taken) { }

    /** One booked match: who won (0 the player side, 1 the crew, -1 a draw) and who fought. */
    record Match(long endedAtMs, int winnerSide, List<Fighter> fighters) { }

    /** Wins, losses and draws, counted from the human's side. */
    record Tally(int wins, int losses, int draws) {
        static final Tally NONE = new Tally(0, 0, 0);

        int played() {
            return wins + losses + draws;
        }

        /** Who leads: the crew ("ahead"), the human ("behind"), or neither ("even"). */
        String standing() {
            return losses > wins ? "ahead" : wins > losses ? "behind" : "even";
        }

        /** The score with the leader first, as the crew says it: "3-1". */
        String score() {
            return Math.max(wins, losses) + "-" + Math.min(wins, losses);
        }

        /** The score from the human's side: "1-3" (and ", 1 draw" when there were any). */
        String yours() {
            return wins + "-" + losses + (draws == 0 ? "" : draws == 1 ? ", 1 draw" : ", " + draws + " draws");
        }

        Tally plus(int winnerSide) {
            return new Tally(wins + (winnerSide == 0 ? 1 : 0), losses + (winnerSide == 1 ? 1 : 0),
                    draws + (winnerSide < 0 ? 1 : 0));
        }
    }

    /** The human's whole record in these matches. */
    static Tally overall(List<Match> matches) {
        Tally tally = Tally.NONE;
        for (Match match : matches) {
            tally = tally.plus(match.winnerSide());
        }
        return tally;
    }

    /** The human's record against each crew member who fought on the other side, in first-met order. */
    static Map<Integer, Tally> perFoe(List<Match> matches) {
        Map<Integer, Tally> out = new LinkedHashMap<>();
        for (Match match : matches) {
            for (Fighter fighter : match.fighters()) {
                if (fighter.side() == 1) {
                    out.merge(fighter.characterId(), Tally.NONE.plus(match.winnerSide()),
                            (a, b) -> new Tally(a.wins() + b.wins(), a.losses() + b.losses(), a.draws() + b.draws()));
                }
            }
        }
        return out;
    }
}
