package server.agents.capabilities.partyquest.rjpq;

import server.agents.integration.NpcSighting;

import java.awt.Point;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * RJPQ companion play's pure rules and every line a companion says. Lines are lower case map chat with no digits and
 * no counters; the side's npc ("romeo"/"juliet") and captive names fill them.
 */
final class AgentRjpqCompanionPlay {
    private AgentRjpqCompanionPlay() {
    }

    // ---- lines (stage 1-3) ----

    static String booksStart() {
        return "the pass is hidden in one of these books. let's split up and check them.";
    }

    static String passFound() {
        return "found the pass! the way out is open.";
    }

    static String letterFound(AgentRjpqSide side) {
        return "found " + side.captiveName() + "'s letter in this book. no idea what to do with it.";
    }

    static String tunnelStart() {
        return "nothing in here comes back. let's clear it all.";
    }

    static String beakersStart(int beaker) {
        return "one liquid each, and they all go in the same beaker. starting with the " + beakerWord(beaker)
                + " one.";
    }

    static String beakerNext(int beaker) {
        return beakerWord(beaker) + " beaker next.";
    }

    /** The beaker by where it stands: beaker1 is on the left, beaker3 on the right (Map.wz 926100100). */
    static String beakerWord(int beaker) {
        return switch (beaker) {
            case 0 -> "left";
            case 1 -> "middle";
            default -> "right";
        };
    }

    // ---- lines (stage 4-5) ----

    static String keysStart() {
        return "the huroids drop card keys. one key opens each door.";
    }

    static String officeStandBack() {
        return "if you want yulete, go talk to him now. we'll stay back.";
    }

    // ---- rules ----

    /** A beaker as its reactor stands now: its index (0-2 left to right) and state. */
    record Beaker(int index, int state, Point position) {
    }

    /**
     * The beaker every companion feeds now: the uncounted one closest to full (the highest state), the leftmost on a
     * tie; -1 when all three are counted (state {@link AgentRjpqDefinition#BEAKER_COUNTED_STATE} or more).
     */
    static int targetBeaker(Collection<Beaker> beakers) {
        return beakers.stream()
                .filter(beaker -> beaker.state() < AgentRjpqDefinition.BEAKER_COUNTED_STATE)
                .max(Comparator.comparingInt(Beaker::state).thenComparing(Beaker::index, Comparator.reverseOrder()))
                .map(Beaker::index)
                .orElse(-1);
    }

    /**
     * The book a companion checks next: the nearest one it has not checked and no other companion has claimed; null
     * when none is left. A companion knows only the books its own run's companions checked, as players would.
     */
    static NpcSighting nextBook(List<NpcSighting> books, Set<Integer> checked, Set<Integer> claimedByOthers,
                                Point from) {
        if (books == null || from == null) {
            return null;
        }
        return books.stream()
                .filter(book -> book.position() != null)
                .filter(book -> !checked.contains(book.objectId()) && !claimedByOthers.contains(book.objectId()))
                .min(Comparator.comparingDouble((NpcSighting book) -> book.position().distanceSq(from))
                        .thenComparingInt(NpcSighting::objectId))
                .orElse(null);
    }

    /** Whether a floor drop at {@code at} lies on a beaker's item area (a liquid there is being poured, not lost). */
    static boolean inBeakerArea(Point at, Collection<Beaker> beakers) {
        return beakers.stream().anyMatch(beaker -> AgentRjpqDefinition.BEAKER_AREA.contains(beaker.position(), at));
    }
}
