package server.agents.capabilities.partyquest.rjpq;

import client.inventory.Item;
import server.agents.integration.NpcSighting;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.function.UnaryOperator;

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

    // ---- lines (stage 6-7 and the claims) ----

    private static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth");
    private static final List<String> COLUMNS = List.of("far left", "second from the left", "second from the right",
            "far right");

    static String roomsStart(String leader) {
        return "one room each. we'll leave one for you, " + leader + ".";
    }

    static String leaderRow(String leader, int row, int column) {
        return leader + ", your " + ORDINALS.get(row) + " row should be the " + COLUMNS.get(column) + " one.";
    }

    static String bossCall() {
        return "everyone ready? i'll talk to yulete.";
    }

    static String watchCaptive(AgentRjpqSide side) {
        return "watch " + side.captiveName() + "! keep them off " + pronoun(side) + ".";
    }

    static String captiveHit(AgentRjpqSide side) {
        return side.captiveName() + "'s getting hit, on it!";
    }

    static String cleared(AgentRjpqSide side) {
        return "we did it! " + side.captiveName() + " is safe. " + side.npcName() + " has our reward.";
    }

    static String bagFull() {
        return "my bag's full, one sec.";
    }

    /** The captive as "her" (Juliet, held on the Zenumist side) or "him" (Romeo, on the Alcadno side). */
    static String pronoun(AgentRjpqSide side) {
        return "juliet".equals(side.captiveName()) ? "her" : "him";
    }

    // ---- rules ----

    private static final List<Integer> POST_SPREADS = List.of(0, -1, 1);

    /**
     * Three guard posts around the captive: her x plus 0, -s and +s, each on the ground there; a post whose ground lies
     * more than {@code maxDyPx} above or below her (off her ledge) is her own spot.
     */
    static List<Point> guardPosts(Point anchor, int spreadPx, int maxDyPx, UnaryOperator<Point> groundAt) {
        List<Point> posts = new ArrayList<>();
        for (int spread : POST_SPREADS) {
            Point raw = new Point(anchor.x + spread * spreadPx, anchor.y);
            Point ground = groundAt.apply(raw);
            Point post = ground == null ? raw : ground;
            posts.add(Math.abs(post.y - anchor.y) > maxDyPx ? new Point(anchor) : new Point(post));
        }
        return posts;
    }

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

    /**
     * The item a companion drops to free a slot: the least valuable one it may part with, never one of the run's own
     * (letters, liquid, keys, files, marbles); the first of equals; null when there is none.
     */
    static Item cheapest(Collection<Item> items, IntToLongFunction value, IntPredicate mayDrop) {
        Item cheapest = null;
        long cheapestValue = Long.MAX_VALUE;
        for (Item item : items) {
            int itemId = item.getItemId();
            if (AgentRjpqDefinition.EXCLUSIVE_ITEM_IDS.contains(itemId) || itemId == AgentRjpqSide.ZENUMIST.marbleItemId()
                    || itemId == AgentRjpqSide.ALCADNO.marbleItemId() || !mayDrop.test(itemId)) {
                continue;
            }
            long itemValue = value.applyAsLong(itemId);
            if (itemValue < cheapestValue) {
                cheapest = item;
                cheapestValue = itemValue;
            }
        }
        return cheapest;
    }

    /** Whether a floor drop at {@code at} lies on a beaker's item area (a liquid there is being poured, not lost). */
    static boolean inBeakerArea(Point at, Collection<Beaker> beakers) {
        return beakers.stream().anyMatch(beaker -> AgentRjpqDefinition.BEAKER_AREA.contains(beaker.position(), at));
    }
}
