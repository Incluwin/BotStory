package server.agents.capabilities.partyquest.hpq;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

/**
 * A companion run's HPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: which
 * seeds are still needed and who fetches them, where the guards stand around the live Moon Bunny, when and where the
 * cake collector hands its stack over, and when the companions may claim. Lines are lower-case and name NPCs in lower
 * case; only a player's name keeps its capitals.
 */
final class AgentHpqCompanionPlay {
    /** C1, as the party arrives on Primrose Hill. */
    static final String SEEDS_START =
            "we'll get the seeds. stay near the middle and keep the monsters off the bunny when it comes out.";
    /** C2, once half the moonflowers are up. */
    static final String HALF_THE_FLOWERS = "three flowers up, three to go.";
    /** C4, when the Moon Bunny appears. */
    static final String BUNNY_OUT = "the bunny's out! keep the monsters off it.";
    /** C5, when the Moon Bunny loses HP (bounded by BUNNY_HURT_LINE_EVERY_MS and BUNNY_HURT_LINE_MAX). */
    static final String BUNNY_HIT = "the bunny's getting hit, on it!";
    /** C8, after a stack the leader left lying was picked back up and dropped for him again. */
    static final String CAKES_RECOLLECTED = "picked the cakes back up so they don't vanish. here they are again.";
    /** C9, when the leader holds all ten and no hand-over told him where to take them. */
    static final String ALL_TEN = "that's all ten. growlie's down where we came in.";
    /** C10, on the clear map. */
    static final String CLEARED = "nice one! tory has your reward, and tommy can take us to pig town if you want more.";
    /** C11, in Pig Town. */
    static final String PIG_TOWN = "pig town! watch out for the iron hogs.";
    /** C12, when the leader has stayed on the clear map past COMPANION_CLEAR_WAIT_MS. */
    static final String WAIT_OUTSIDE = "we'll grab our rewards and wait for you outside.";
    /** C14, the collector about to go down (low on HP, no HP potion left) leaves its stack where it stands. */
    static final String LEAVING_CAKES = "almost out of pots, leaving the cakes here, grab them.";

    private static final List<String> COUNT_WORDS = List.of("none", "one", "two", "three", "four", "five", "six");
    /** The guard posts around the anchor, in spreads: the centre (the collector's) first. */
    private static final int[] POST_SPREADS = {0, -1, 1, -2, 2};

    private AgentHpqCompanionPlay() {
    }

    /** C3: the leader has held a needed seed for SEED_HOLD_HINT_MS. */
    static String seedPadLine(String leader, String padPosition) {
        return leader + ", that seed goes on the pad " + padPosition + ". drop just one.";
    }

    /** C6: the stack lies at the leader's feet, or (the map put it a step off his spot) right next to him. */
    static String cakesDroppedLine(String leader, boolean atHisFeet) {
        return "the rice cakes are " + (atHisFeet ? "at your feet" : "right next to you") + ", " + leader
                + ". pick them up and talk to growlie.";
    }

    /** C6b: the leader could not be reached (or the walk ran out of time), so the stack lies by Growlie. */
    static String cakesByGrowlieLine(String leader) {
        return leader + ", couldn't get to you, left the cakes by growlie.";
    }

    /** C7: a hand-over has lain CAKE_PICKUP_NUDGE_MS without the leader picking it up. */
    static String cakesStillLyingLine(String leader) {
        return leader + ", the cakes are still lying there. growlie's down where we came in.";
    }

    /** C13: a member left and the run carries on with the rest. */
    static String carryOnLine(String member, int remaining) {
        String count = remaining >= 0 && remaining < COUNT_WORDS.size()
                ? COUNT_WORDS.get(remaining) : Integer.toString(remaining);
        return member + " had to go. we'll keep going with " + count + ".";
    }

    // ---- seeds ----

    /** One moonflower pad as seen this tick: its seed, whether it still takes one, and whether one is on its way. */
    record Pad(int seedItemId, boolean active, boolean pending) {
    }

    /**
     * A pad is pending while a seed of its colour went down in its box within the activation wait: an Agent's own
     * drop ({@code plantedAtMs}) or a player's that still lies there ({@code latestDropAtMs}); 0 is none.
     */
    static boolean padPending(long plantedAtMs, long latestDropAtMs, long nowMs, long activationWaitMs) {
        return (plantedAtMs > 0L && nowMs - plantedAtMs < activationWaitMs)
                || (latestDropAtMs > 0L && nowMs - latestDropAtMs < activationWaitMs);
    }

    /** The seeds still needed, in pad order: a pad that takes a seed and has none on its way. */
    static Set<Integer> neededSeeds(Collection<Pad> pads) {
        Set<Integer> needed = new LinkedHashSet<>();
        for (Pad pad : pads) {
            if (pad.active() && !pad.pending()) needed.add(pad.seedItemId());
        }
        return needed;
    }

    /**
     * The seed an Agent plants: the first needed one it carries. 0 when it carries none, or only dead seeds (colours
     * no pad needs any more), which leaves it seedless.
     */
    static int carriedSeed(IntPredicate carries, Collection<Integer> needed) {
        for (int seed : needed) {
            if (carries.test(seed)) return seed;
        }
        return 0;
    }

    /**
     * A needed seed a human carries is his to plant until SEED_HOLD_HINT_MS (when C3 tells the leader which pad) plus
     * HUMAN_SEED_GRACE_MS have passed; after that the companions fetch that colour themselves.
     */
    static boolean humanSeedCovers(long heldSinceMs, long nowMs, long holdHintMs, long graceMs) {
        return nowMs - heldSinceMs < holdHintMs + graceMs;
    }

    static boolean seedHintDue(long heldSinceMs, long nowMs, long holdHintMs) {
        return nowMs - heldSinceMs >= holdHintMs;
    }

    /** The needed seeds nobody carries for a pad yet, in pad order: what the seedless companions fetch. */
    static List<Integer> seedsToFetch(Collection<Integer> needed, Collection<Integer> carriedByAgents,
                                      Collection<Integer> coveredByHumans) {
        List<Integer> toFetch = new ArrayList<>();
        for (int seed : needed) {
            if (!carriedByAgents.contains(seed) && !coveredByHumans.contains(seed)) toFetch.add(seed);
        }
        return toFetch;
    }

    /** The colour the {@code ordinal}-th seedless companion fetches; 0 when nothing is left to fetch. */
    static int fetchSeed(List<Integer> toFetch, int ordinal) {
        return toFetch.isEmpty() ? 0 : toFetch.get(Math.floorMod(ordinal, toFetch.size()));
    }

    // ---- defense ----

    /**
     * Where the guards stand around: the live Moon Bunny (it lands on the floor under its authored spawn, about
     * (-183,-188), not at (-183,-433)), kept while it moves less than {@code moveTolerancePx}; before it is seen, the
     * ground under its spawn point. Never the authored upper posts.
     */
    static Point guardAnchor(Point bunny, Point previousAnchor, Point groundedSpawn, int moveTolerancePx) {
        if (bunny != null) {
            boolean moved = previousAnchor == null
                    || Math.abs(bunny.x - previousAnchor.x) > moveTolerancePx
                    || Math.abs(bunny.y - previousAnchor.y) > moveTolerancePx;
            return new Point(moved ? bunny : previousAnchor);
        }
        return previousAnchor != null ? new Point(previousAnchor) : groundedSpawn == null ? null : new Point(groundedSpawn);
    }

    /**
     * Five posts at the anchor's x plus 0, -s, +s, -2s and +2s, each on the ground there ({@code groundAt}); one
     * whose ground lies more than {@code maxDyPx} above or below the anchor (off the Bunny's floor) is the anchor.
     */
    static List<Point> guardPosts(Point anchor, int spreadPx, int maxDyPx, UnaryOperator<Point> groundAt) {
        List<Point> posts = new ArrayList<>();
        for (int spread : POST_SPREADS) {
            Point ground = groundAt.apply(new Point(anchor.x + spread * spreadPx, anchor.y));
            posts.add(ground == null || Math.abs(ground.y - anchor.y) > maxDyPx ? new Point(anchor) : new Point(ground));
        }
        return posts;
    }

    /** A hostile this close to the Moon Bunny threatens it. */
    static boolean threatens(Point hostile, Point bunny, int threatRadiusPx) {
        return hostile != null && bunny != null && hostile.distanceSq(bunny) <= (long) threatRadiusPx * threatRadiusPx;
    }

    /** A guard wakes for a hostile near itself, or for one threatening the Bunny. */
    static boolean guardWakes(Point guard, Point bunny, Collection<Point> hostiles, int wakeRadiusPx,
                              int threatRadiusPx) {
        for (Point hostile : hostiles) {
            if (hostile == null) continue;
            if (guard != null && hostile.distanceSq(guard) <= (long) wakeRadiusPx * wakeRadiusPx) return true;
            if (threatens(hostile, bunny, threatRadiusPx)) return true;
        }
        return false;
    }

    /** What the defense does this tick, checked in this order. */
    enum DefenseStep { CLEARED, BUNNY_LOST, DELIVER, GUARD }

    /**
     * The clear comes first: the Bunny may be gone once Growlie has his cakes. Then the Bunny lost (seen, no longer
     * alive, the leader still on the stage). Then the leader holding every cake, which starts the delivery.
     */
    static DefenseStep defenseStep(boolean cleared, boolean bunnySeen, boolean bunnyAlive, boolean leaderOnStage,
                                   int leaderCakes, int requiredCakes) {
        if (cleared) return DefenseStep.CLEARED;
        if (bunnySeen && !bunnyAlive && leaderOnStage) return DefenseStep.BUNNY_LOST;
        if (leaderCakes >= requiredCakes) return DefenseStep.DELIVER;
        return DefenseStep.GUARD;
    }

    /** C5 is due: the Bunny lost HP since the last look, under the line cap and past the line spacing. */
    static boolean bunnyHurtLineDue(long lastHp, long hp, int linesSaid, long lastLineAtMs, long nowMs, long everyMs,
                                    int maxLines) {
        return lastHp >= 0L && hp < lastHp && linesSaid < maxLines
                && (linesSaid == 0 || nowMs - lastLineAtMs >= everyMs);
    }

    // ---- cakes ----

    /**
     * The cake collector: the current one while it stands and is not retired (about to go down), else the first such
     * companion standing (the one on the centre post). With every standing companion retired one still holds the
     * role (the current one, else the first), so the run never goes without a collector; 0 only when none stands.
     */
    static int collector(List<Integer> standingCompanions, int currentCollectorId, Set<Integer> retired) {
        boolean currentStands = currentCollectorId > 0 && standingCompanions.contains(currentCollectorId);
        if (currentStands && !retired.contains(currentCollectorId)) return currentCollectorId;
        for (int id : standingCompanions) {
            if (!retired.contains(id)) return id;
        }
        if (currentStands) return currentCollectorId;
        return standingCompanions.isEmpty() ? 0 : standingCompanions.getFirst();
    }

    /** Still standing, at or below the dying share of its max HP. */
    static boolean lowOnHp(int hp, int maxHp, int dyingPercent) {
        // As a percentage of its max HP: hp / maxHp <= dyingPercent / 100.
        return hp > 0 && maxHp > 0 && (long) hp * 100L <= (long) maxHp * dyingPercent;
    }

    /**
     * About to go down (KPQ's aboutToGoDown): low on HP with no HP potion left to drink. One with a potion drinks it,
     * and one out of potions at full HP is in no danger; a count that cannot be told (-1) is not none.
     */
    static boolean dying(int hp, int maxHp, int hpPotions, int dyingPercent) {
        return lowOnHp(hp, maxHp, dyingPercent) && hpPotions == 0;
    }

    /**
     * The collector sets out with its stack: it holds a batch and the leader is near; or what it and the leader hold
     * makes all the cakes; or the Bunny has made them all and none is left on the floor to collect.
     */
    static boolean handOverDue(int collectorCakes, int leaderCakes, boolean leaderNear, int bunnyCakes,
                               boolean naturalCakeOnFloor, int batch, int requiredCakes) {
        if (collectorCakes <= 0) return false;
        return (collectorCakes >= batch && leaderNear)
                || collectorCakes + leaderCakes >= requiredCakes
                || (bunnyCakes >= requiredCakes && !naturalCakeOnFloor);
    }

    /** What the collector does with its stack this tick. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, TO_GROWLIE }

    /**
     * Walk to the leader's spot until within {@code reachPx} of it (both on a foothold, as KPQ's at-feet rule), then
     * drop where the map's own placement ({@code landing}, KPQ's predictDropLanding preview) puts the stack: at his
     * feet, or beside him on his floor; one that would land over or under him walks on. A leader it has no route to,
     * or a walk past {@code timeoutMs}, takes the stack to Growlie instead.
     */
    static HandOver handOver(Point collector, Point leaderSpot, boolean collectorOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.TO_GROWLIE;
        if (!atFeet(collector, leaderSpot, collectorOnGround, leaderOnGround, reachPx)) return HandOver.WALK;
        if (landing == null || landsAt(landing, leaderSpot, reachPx)) return HandOver.DROP_AT_FEET;
        if (Math.abs(landing.y - leaderSpot.y) <= reachPx) return HandOver.DROP_BESIDE;
        return HandOver.WALK;
    }

    /** Both on a foothold, within {@code px} of each other on both axes (AgentKpqPassHandOver.atFeet). */
    static boolean atFeet(Point dropper, Point spot, boolean dropperOnGround, boolean spotOnGround, int px) {
        return dropper != null && spot != null && dropperOnGround && spotOnGround
                && Math.abs(dropper.x - spot.x) <= px && Math.abs(dropper.y - spot.y) <= px;
    }

    /** A drop the map lands within {@code px} of this spot on both axes (AgentKpqPassHandOver.landsAt). */
    static boolean landsAt(Point landing, Point spot, int px) {
        return landing != null && spot != null
                && Math.abs(landing.x - spot.x) <= px && Math.abs(landing.y - spot.y) <= px;
    }

    /** The configured re-collect delay, never past the latest a stack can be picked back up before it expires. */
    static long recollectAfterMs(long configuredMs, long latestMs) {
        return Math.min(configuredMs, latestMs);
    }

    /** A stack left lying is picked back up from {@code recollectAfterMs} after its drop, at most {@code max} times. */
    static boolean recollectDue(long droppedAtMs, long nowMs, long recollectAfterMs, int recollectsDone,
                                int maxRecollects) {
        return recollectsDone < maxRecollects && nowMs - droppedAtMs >= recollectAfterMs;
    }

    /** C7 is due: the stack has lain {@code nudgeMs} since its drop. */
    static boolean nudgeDue(long droppedAtMs, long nowMs, long nudgeMs) {
        return nowMs - droppedAtMs >= nudgeMs;
    }

    // ---- after the clear ----

    /** What the companions do on the clear map. */
    enum AfterClear { WAIT, BONUS, CLAIM, CLAIM_AFTER_WAIT }

    /**
     * The leader went to Pig Town: follow. He is on the reward exit map, out of the event, offline or down: claim.
     * He has stayed past the clear wait: say so and claim. Otherwise wait for him.
     */
    static AfterClear afterClear(boolean leaderOnline, int leaderHp, boolean leaderInEvent, int leaderMapId,
                                 boolean clearWaitPassed) {
        if (!leaderOnline || leaderHp <= 0 || !leaderInEvent) return AfterClear.CLAIM;
        if (leaderMapId == AgentHpqDefinition.BONUS_MAP) return AfterClear.BONUS;
        if (leaderMapId == AgentHpqDefinition.REWARD_EXIT_MAP) return AfterClear.CLAIM;
        return clearWaitPassed ? AfterClear.CLAIM_AFTER_WAIT : AfterClear.WAIT;
    }

    /**
     * Companions claim (which takes them out to Henesys) only once the leader is off the clear map and Pig Town, or out
     * of the event, or the clear wait has passed: claiming earlier would leave him there, and Tommy's warp would take
     * them to Pig Town no more.
     */
    static boolean companionsMayClaim(boolean leaderInEvent, int leaderMapId, boolean clearWaitPassed) {
        return clearWaitPassed || !leaderInEvent
                || (leaderMapId != AgentHpqDefinition.CLEAR_MAP && leaderMapId != AgentHpqDefinition.BONUS_MAP);
    }

    // ---- entering ----

    /** What the entry does: wait, go on with everyone, go on without the absent, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    /** Everyone inside goes on; after the entry wait at least {@code minimumParty} inside go on without the rest. */
    static Entry entering(int inside, int members, long waitedMs, long entryWaitMs, int minimumParty) {
        if (inside >= members) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return inside >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }
}
