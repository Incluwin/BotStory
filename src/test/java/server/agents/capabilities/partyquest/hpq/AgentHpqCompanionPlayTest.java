package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.hpq.AgentHpqCompanionPlay.AfterClear;
import static server.agents.capabilities.partyquest.hpq.AgentHpqCompanionPlay.DefenseStep;
import static server.agents.capabilities.partyquest.hpq.AgentHpqCompanionPlay.Entry;
import static server.agents.capabilities.partyquest.hpq.AgentHpqCompanionPlay.HandOver;
import static server.agents.capabilities.partyquest.hpq.AgentHpqCompanionPlay.Pad;

/** A companion run's HPQ rules and lines, each pinned on its own (plan step 2). */
class AgentHpqCompanionPlayTest {
    private static final int RED = 4_001_095;
    private static final int BROWN = 4_001_096;
    private static final int PURPLE = 4_001_098;
    private static final int GREEN = 4_001_099;

    // ---- seeds ----

    @Test
    void aPadIsNeededWhileItTakesASeedAndNoneIsOnItsWay() {
        List<Pad> pads = List.of(
                new Pad(RED, true, false),
                new Pad(BROWN, true, true),     // a seed went down in its box moments ago
                new Pad(PURPLE, false, false),  // already bloomed
                new Pad(GREEN, true, false));

        assertEquals(List.of(RED, GREEN), new ArrayList<>(AgentHpqCompanionPlay.neededSeeds(pads)));
    }

    @Test
    void aPadIsPendingOnlyWithinTheActivationWaitOfASeedInItsBox() {
        assertTrue(AgentHpqCompanionPlay.padPending(10_000L, 0L, 15_999L, 6_000L), "an Agent's own drop");
        assertFalse(AgentHpqCompanionPlay.padPending(10_000L, 0L, 16_000L, 6_000L), "the wait is over");
        assertTrue(AgentHpqCompanionPlay.padPending(0L, 12_000L, 17_999L, 6_000L), "a player's drop lying there");
        assertFalse(AgentHpqCompanionPlay.padPending(0L, 0L, 17_999L, 6_000L), "nothing went down");
    }

    @Test
    void anAgentPlantsTheFirstNeededSeedItCarriesAndOneWithOnlyDeadSeedsIsSeedless() {
        Set<Integer> needed = Set.of(GREEN);

        assertEquals(GREEN, AgentHpqCompanionPlay.carriedSeed(seed -> seed == RED || seed == GREEN, needed));
        assertEquals(0, AgentHpqCompanionPlay.carriedSeed(seed -> seed == RED || seed == BROWN, needed),
                "red and brown pads are done: those seeds are dead");
    }

    @Test
    void aSeedAPlayerCarriesIsHisUntilTheHintAndTheGraceHavePassed() {
        long since = 100_000L;
        // SEED_HOLD_HINT_MS 20 s, then HUMAN_SEED_GRACE_MS 30 s.
        assertFalse(AgentHpqCompanionPlay.seedHintDue(since, since + 19_999L, 20_000L));
        assertTrue(AgentHpqCompanionPlay.seedHintDue(since, since + 20_000L, 20_000L));
        assertTrue(AgentHpqCompanionPlay.humanSeedCovers(since, since + 49_999L, 20_000L, 30_000L));
        assertFalse(AgentHpqCompanionPlay.humanSeedCovers(since, since + 50_000L, 20_000L, 30_000L));
    }

    @Test
    void companionsFetchOnlyTheNeededColoursNobodyCarries() {
        List<Integer> toFetch = AgentHpqCompanionPlay.seedsToFetch(
                List.of(RED, BROWN, PURPLE, GREEN), Set.of(BROWN), Set.of(PURPLE));

        assertEquals(List.of(RED, GREEN), toFetch);
        assertEquals(RED, AgentHpqCompanionPlay.fetchSeed(toFetch, 0));
        assertEquals(GREEN, AgentHpqCompanionPlay.fetchSeed(toFetch, 1));
        assertEquals(RED, AgentHpqCompanionPlay.fetchSeed(toFetch, 2));
        assertEquals(0, AgentHpqCompanionPlay.fetchSeed(List.of(), 0), "nothing left to fetch");
    }

    @Test
    void theLeaderIsToldThePadByWhereItLies() {
        assertEquals("up top left", AgentHpqDefinition.padPositionName(new Point(-359, -692)));
        assertEquals("up top middle", AgentHpqDefinition.padPositionName(new Point(4, -690)));
        assertEquals("upper right", AgentHpqDefinition.padPositionName(new Point(182, -452)));
        assertEquals("upper left", AgentHpqDefinition.padPositionName(new Point(-535, -449)));
        assertEquals("middle", AgentHpqDefinition.padPositionName(new Point(0, -210)));
        assertEquals("left of the middle", AgentHpqDefinition.padPositionName(new Point(-350, -205)));
    }

    // ---- defense ----

    @Test
    void guardPostsSpreadAroundTheAnchorOnItsFloor() {
        Point anchor = new Point(-183, -188);

        List<Point> posts = AgentHpqCompanionPlay.guardPosts(anchor, 110, 60, Point::new);

        assertEquals(List.of(new Point(-183, -188), new Point(-293, -188), new Point(-73, -188),
                new Point(-403, -188), new Point(37, -188)), posts);
    }

    @Test
    void aPostOffTheBunnysFloorCollapsesOntoTheAnchor() {
        Point anchor = new Point(-183, -188);
        // Two spreads left the floor ends and the ground there is the spawn floor; one spread right has no ground.
        List<Point> posts = AgentHpqCompanionPlay.guardPosts(anchor, 110, 60, point -> {
            if (point.x == -403) return new Point(-403, 83);
            if (point.x == -73) return null;
            return new Point(point.x, point.y + 30);
        });

        assertEquals(new Point(-183, -158), posts.get(0));
        assertEquals(new Point(-293, -158), posts.get(1));
        assertEquals(anchor, posts.get(2), "no ground");
        assertEquals(anchor, posts.get(3), "271 px below the anchor");
        assertEquals(new Point(37, -158), posts.get(4), "30 px is within the step");
    }

    @Test
    void theAnchorIsTheLiveBunnyAndBeforeItIsSeenTheGroundUnderItsSpawn() {
        Point spawnGround = new Point(-183, -188);

        assertEquals(spawnGround, AgentHpqCompanionPlay.guardAnchor(null, null, spawnGround, 70));
        assertEquals(new Point(-180, -190),
                AgentHpqCompanionPlay.guardAnchor(new Point(-180, -190), null, spawnGround, 70));
        assertEquals(new Point(-180, -190), AgentHpqCompanionPlay.guardAnchor(
                new Point(-150, -190), new Point(-180, -190), spawnGround, 70), "a small shift keeps the posts");
        assertEquals(new Point(-60, -190), AgentHpqCompanionPlay.guardAnchor(
                new Point(-60, -190), new Point(-180, -190), spawnGround, 70), "a real move moves them");
        assertEquals(new Point(-180, -190), AgentHpqCompanionPlay.guardAnchor(
                null, new Point(-180, -190), spawnGround, 70), "the last place it stood");
    }

    @Test
    void aGuardWakesForAHostileNearItselfOrOneThreateningTheBunny() {
        Point guard = new Point(-403, -188);
        Point bunny = new Point(-183, -188);

        assertTrue(AgentHpqCompanionPlay.guardWakes(guard, bunny, List.of(new Point(-450, -188)), 220, 260));
        assertTrue(AgentHpqCompanionPlay.guardWakes(guard, bunny, List.of(new Point(60, -188)), 220, 260),
                "243 px from the Bunny");
        assertFalse(AgentHpqCompanionPlay.guardWakes(guard, bunny, List.of(new Point(300, -400)), 220, 260));
    }

    @Test
    void theClearIsCheckedBeforeTheBunnyLostAndTheBunnyLostBeforeTheDelivery() {
        assertEquals(DefenseStep.CLEARED,
                AgentHpqCompanionPlay.defenseStep(true, true, false, true, 10, 10),
                "Growlie had his cakes; the Bunny dies with the stage");
        assertEquals(DefenseStep.BUNNY_LOST, AgentHpqCompanionPlay.defenseStep(false, true, false, true, 10, 10));
        assertEquals(DefenseStep.DELIVER, AgentHpqCompanionPlay.defenseStep(false, true, false, false, 10, 10),
                "the leader is not on the stage");
        assertEquals(DefenseStep.GUARD, AgentHpqCompanionPlay.defenseStep(false, false, false, true, 3, 10),
                "not seen yet");
        assertEquals(DefenseStep.GUARD, AgentHpqCompanionPlay.defenseStep(false, true, true, true, 9, 10));
    }

    @Test
    void theBunnyHitLineIsSpacedAndCapped() {
        assertFalse(AgentHpqCompanionPlay.bunnyHurtLineDue(-1L, 900L, 0, 0L, 1_000L, 30_000L, 3), "first look");
        assertTrue(AgentHpqCompanionPlay.bunnyHurtLineDue(1_000L, 900L, 0, 0L, 1_000L, 30_000L, 3));
        assertFalse(AgentHpqCompanionPlay.bunnyHurtLineDue(900L, 900L, 0, 0L, 1_000L, 30_000L, 3), "no loss");
        assertFalse(AgentHpqCompanionPlay.bunnyHurtLineDue(900L, 800L, 1, 1_000L, 30_999L, 30_000L, 3));
        assertTrue(AgentHpqCompanionPlay.bunnyHurtLineDue(900L, 800L, 1, 1_000L, 31_000L, 30_000L, 3));
        assertFalse(AgentHpqCompanionPlay.bunnyHurtLineDue(900L, 800L, 3, 1_000L, 900_000L, 30_000L, 3), "cap");
    }

    // ---- cakes ----

    @Test
    void theCollectorStaysUntilItGoesDownOrLeavesItsCakes() {
        assertEquals(12, AgentHpqCompanionPlay.collector(List.of(11, 12, 13), 12, Set.of()));
        assertEquals(11, AgentHpqCompanionPlay.collector(List.of(11, 13), 12, Set.of()), "12 went down");
        assertEquals(13, AgentHpqCompanionPlay.collector(List.of(11, 12, 13), 11, Set.of(11, 12)));
        assertEquals(11, AgentHpqCompanionPlay.collector(List.of(11, 12), 0, Set.of()), "the first on the centre");
        assertEquals(0, AgentHpqCompanionPlay.collector(List.of(), 12, Set.of()));
    }

    @Test
    void withEveryStandingCompanionRetiredOneStillHoldsTheCollectorRole() {
        assertEquals(11, AgentHpqCompanionPlay.collector(List.of(11, 12), 11, Set.of(11, 12)), "the current one");
        assertEquals(11, AgentHpqCompanionPlay.collector(List.of(11, 12), 0, Set.of(11, 12)), "the centre one");
        assertEquals(12, AgentHpqCompanionPlay.collector(List.of(12, 13), 11, Set.of(12, 13)), "11 went down");
    }

    @Test
    void aCollectorIsAboutToGoDownOnlyLowOnHpWithNoHpPotionLeft() {
        assertTrue(AgentHpqCompanionPlay.dying(250, 1_000, 0, 25));
        assertFalse(AgentHpqCompanionPlay.dying(251, 1_000, 0, 25), "above the dying share");
        assertFalse(AgentHpqCompanionPlay.dying(250, 1_000, 12, 25), "a potion left: it drinks");
        assertFalse(AgentHpqCompanionPlay.dying(900, 1_000, 0, 25), "out of potions at full HP");
        assertFalse(AgentHpqCompanionPlay.dying(250, 1_000, -1, 25), "an unknown count is not none");
        assertFalse(AgentHpqCompanionPlay.dying(0, 1_000, 0, 25), "already down");
        assertTrue(AgentHpqCompanionPlay.lowOnHp(250, 1_000, 25));
        assertFalse(AgentHpqCompanionPlay.lowOnHp(251, 1_000, 25));
    }

    @Test
    void theHandOverStartsForABatchNearTheLeaderOrWhenTheCakesAreAllMade() {
        // collector, leader, leaderNear, bunnyCakes, naturalOnFloor -> due
        assertTrue(AgentHpqCompanionPlay.handOverDue(3, 0, true, 3, false, 3, 10), "a batch, leader near");
        assertFalse(AgentHpqCompanionPlay.handOverDue(3, 0, false, 3, false, 3, 10), "a batch, leader far");
        assertFalse(AgentHpqCompanionPlay.handOverDue(2, 0, true, 2, false, 3, 10), "short of a batch");
        assertTrue(AgentHpqCompanionPlay.handOverDue(4, 6, false, 10, true, 3, 10), "it and he make ten");
        assertTrue(AgentHpqCompanionPlay.handOverDue(1, 3, false, 10, false, 3, 10), "all made, none to collect");
        assertFalse(AgentHpqCompanionPlay.handOverDue(1, 3, false, 10, true, 3, 10), "one more lies there");
        assertFalse(AgentHpqCompanionPlay.handOverDue(0, 9, true, 10, false, 3, 10), "nothing to bring");
    }

    @Test
    void theStackGoesDownAtHisFeetBesideHimOrByGrowlie() {
        Point spot = new Point(429, 83);
        // collector, spot, onGround x2, landing, reachable, walking, timeout, reach
        assertEquals(HandOver.WALK, AgentHpqCompanionPlay.handOver(
                new Point(100, 83), spot, true, true, null, true, 5_000L, 30_000L, 60));
        assertEquals(HandOver.DROP_AT_FEET, AgentHpqCompanionPlay.handOver(
                new Point(400, 83), spot, true, true, new Point(400, 83), true, 5_000L, 30_000L, 60));
        assertEquals(HandOver.DROP_AT_FEET, AgentHpqCompanionPlay.handOver(
                new Point(400, 83), spot, true, true, null, true, 5_000L, 30_000L, 60), "no preview");
        assertEquals(HandOver.DROP_BESIDE, AgentHpqCompanionPlay.handOver(
                new Point(400, 83), spot, true, true, new Point(340, 80), true, 5_000L, 30_000L, 60));
        assertEquals(HandOver.WALK, AgentHpqCompanionPlay.handOver(
                new Point(400, 83), spot, true, true, new Point(400, -2), true, 5_000L, 30_000L, 60),
                "the map would put it on the platform over him");
        assertEquals(HandOver.WALK, AgentHpqCompanionPlay.handOver(
                new Point(400, 83), spot, false, true, null, true, 5_000L, 30_000L, 60), "in the air");
        assertEquals(HandOver.TO_GROWLIE, AgentHpqCompanionPlay.handOver(
                new Point(100, 83), spot, true, true, null, false, 0L, 30_000L, 60), "no route to him");
        assertEquals(HandOver.WALK, AgentHpqCompanionPlay.handOver(
                new Point(100, 83), spot, true, true, null, true, 30_000L, 30_000L, 60));
        assertEquals(HandOver.TO_GROWLIE, AgentHpqCompanionPlay.handOver(
                new Point(100, 83), spot, true, true, null, true, 30_001L, 30_000L, 60), "the walk ran out");
    }

    @Test
    void aStackLeftLyingIsPickedBackUpAtTheDueTimeAndAtMostTheMax() {
        long droppedAt = 50_000L;

        assertFalse(AgentHpqCompanionPlay.recollectDue(droppedAt, droppedAt + 149_999L, 150_000L, 0, 2));
        assertTrue(AgentHpqCompanionPlay.recollectDue(droppedAt, droppedAt + 150_000L, 150_000L, 0, 2));
        assertTrue(AgentHpqCompanionPlay.recollectDue(droppedAt, droppedAt + 150_000L, 150_000L, 1, 2));
        assertFalse(AgentHpqCompanionPlay.recollectDue(droppedAt, droppedAt + 150_000L, 150_000L, 2, 2), "max");
        assertFalse(AgentHpqCompanionPlay.recollectDue(droppedAt, droppedAt + 900_000L, 150_000L, 0, 0));
    }

    @Test
    void theRecollectNeverWaitsPastTheCakesExpiryMargin() {
        assertEquals(170_000L, AgentHpqDefinition.RICE_CAKE_RECOLLECT_LATEST_MS);
        assertEquals(150_000L, AgentHpqCompanionPlay.recollectAfterMs(150_000L, 170_000L));
        assertEquals(170_000L, AgentHpqCompanionPlay.recollectAfterMs(175_000L, 170_000L));
    }

    @Test
    void theNudgeIsDueOnceTheStackHasLainLongEnough() {
        assertFalse(AgentHpqCompanionPlay.nudgeDue(10_000L, 49_999L, 40_000L));
        assertTrue(AgentHpqCompanionPlay.nudgeDue(10_000L, 50_000L, 40_000L));
    }

    // ---- after the clear and entering ----

    @Test
    void onTheClearMapTheCompanionsFollowHimClaimOrWait() {
        int clear = AgentHpqDefinition.CLEAR_MAP;
        assertEquals(AfterClear.BONUS,
                AgentHpqCompanionPlay.afterClear(true, 500, true, AgentHpqDefinition.BONUS_MAP, false));
        assertEquals(AfterClear.CLAIM,
                AgentHpqCompanionPlay.afterClear(true, 500, true, AgentHpqDefinition.REWARD_EXIT_MAP, false));
        assertEquals(AfterClear.CLAIM, AgentHpqCompanionPlay.afterClear(true, 500, false, 100_000_200, false));
        assertEquals(AfterClear.CLAIM, AgentHpqCompanionPlay.afterClear(false, 0, false, 0, false), "offline");
        assertEquals(AfterClear.CLAIM, AgentHpqCompanionPlay.afterClear(true, 0, true, clear, false), "down");
        assertEquals(AfterClear.WAIT, AgentHpqCompanionPlay.afterClear(true, 500, true, clear, false));
        assertEquals(AfterClear.CLAIM_AFTER_WAIT, AgentHpqCompanionPlay.afterClear(true, 500, true, clear, true));
    }

    @Test
    void companionsClaimOnceTheLeaderIsOffTheClearMapAndPigTownOutOfTheEventOrTheWaitIsOver() {
        int clear = AgentHpqDefinition.CLEAR_MAP;
        int pigTown = AgentHpqDefinition.BONUS_MAP;
        assertFalse(AgentHpqCompanionPlay.companionsMayClaim(true, clear, false));
        assertFalse(AgentHpqCompanionPlay.companionsMayClaim(true, pigTown, false));
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(true, AgentHpqDefinition.REWARD_EXIT_MAP, false),
                "off the clear map and Pig Town");
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(false, clear, false), "out of the event");
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(true, clear, true), "the clear wait is over");
    }

    @Test
    void theEntryWaitsForEveryoneThenGoesOnWithThreeOrGivesUp() {
        assertEquals(Entry.ENTERED, AgentHpqCompanionPlay.entering(4, 4, 1_000L, 30_000L, 3));
        assertEquals(Entry.WAIT, AgentHpqCompanionPlay.entering(3, 4, 29_999L, 30_000L, 3));
        assertEquals(Entry.EJECT_ABSENT, AgentHpqCompanionPlay.entering(3, 4, 30_000L, 30_000L, 3));
        assertEquals(Entry.GIVE_UP, AgentHpqCompanionPlay.entering(2, 4, 30_000L, 30_000L, 3));
    }

    // ---- lines ----

    @Test
    void everyLineIsLowerCaseButNamesAndCarriesNoClientMarkup() {
        String leader = "Crewfriend";
        List<String> lines = new ArrayList<>(List.of(
                AgentHpqCompanionPlay.SEEDS_START, AgentHpqCompanionPlay.HALF_THE_FLOWERS,
                AgentHpqCompanionPlay.BUNNY_OUT, AgentHpqCompanionPlay.BUNNY_HIT,
                AgentHpqCompanionPlay.CAKES_RECOLLECTED, AgentHpqCompanionPlay.ALL_TEN,
                AgentHpqCompanionPlay.CLEARED, AgentHpqCompanionPlay.PIG_TOWN,
                AgentHpqCompanionPlay.WAIT_OUTSIDE, AgentHpqCompanionPlay.LEAVING_CAKES,
                AgentHpqCompanionPlay.cakesDroppedLine(leader, true),
                AgentHpqCompanionPlay.cakesDroppedLine(leader, false),
                AgentHpqCompanionPlay.cakesByGrowlieLine(leader),
                AgentHpqCompanionPlay.cakesStillLyingLine(leader),
                AgentHpqCompanionPlay.carryOnLine("HappyWolf", 3),
                AgentHpqCompanionPlay.carryOnLine("HappyWolf", 4),
                AgentHpqCompanionPlay.carryOnLine("HappyWolf", 5)));
        for (String position : List.of("up top left", "up top middle", "upper right", "upper left", "middle",
                "left of the middle")) {
            lines.add(AgentHpqCompanionPlay.seedPadLine(leader, position));
        }
        for (String line : lines) {
            String withoutNames = line.replace(leader, "").replace("HappyWolf", "");
            assertEquals(withoutNames.toLowerCase(java.util.Locale.ROOT), withoutNames, line);
            assertFalse(line.contains("#") || line.contains("[") || line.contains("<"), line);
            assertFalse(line.isBlank());
        }
    }

    @Test
    void theLinesSayWhatThePlanSays() {
        assertEquals("we'll get the seeds. stay near the middle and keep the monsters off the bunny when it comes out.",
                AgentHpqCompanionPlay.SEEDS_START);
        assertEquals("three flowers up, three to go.", AgentHpqCompanionPlay.HALF_THE_FLOWERS);
        assertEquals("Crewfriend, that seed goes on the pad upper right. drop just one.",
                AgentHpqCompanionPlay.seedPadLine("Crewfriend", "upper right"));
        assertEquals("the bunny's out! keep the monsters off it.", AgentHpqCompanionPlay.BUNNY_OUT);
        assertEquals("the bunny's getting hit, on it!", AgentHpqCompanionPlay.BUNNY_HIT);
        assertEquals("the rice cakes are at your feet, Crewfriend. pick them up and talk to growlie.",
                AgentHpqCompanionPlay.cakesDroppedLine("Crewfriend", true));
        assertEquals("the rice cakes are right next to you, Crewfriend. pick them up and talk to growlie.",
                AgentHpqCompanionPlay.cakesDroppedLine("Crewfriend", false));
        assertEquals("Crewfriend, couldn't get to you, left the cakes by growlie.",
                AgentHpqCompanionPlay.cakesByGrowlieLine("Crewfriend"));
        assertEquals("Crewfriend, the cakes are still lying there. growlie's down where we came in.",
                AgentHpqCompanionPlay.cakesStillLyingLine("Crewfriend"));
        assertEquals("picked the cakes back up so they don't vanish. here they are again.",
                AgentHpqCompanionPlay.CAKES_RECOLLECTED);
        assertEquals("that's all ten. growlie's down where we came in.", AgentHpqCompanionPlay.ALL_TEN);
        assertEquals("nice one! tory has your reward, and tommy can take us to pig town if you want more.",
                AgentHpqCompanionPlay.CLEARED);
        assertEquals("pig town! watch out for the iron hogs.", AgentHpqCompanionPlay.PIG_TOWN);
        assertEquals("we'll grab our rewards and wait for you outside.", AgentHpqCompanionPlay.WAIT_OUTSIDE);
        assertEquals("Ilse had to go. we'll keep going with four.", AgentHpqCompanionPlay.carryOnLine("Ilse", 4));
        assertEquals("almost out of pots, leaving the cakes here, grab them.", AgentHpqCompanionPlay.LEAVING_CAKES);
        assertNull(AgentHpqDefinition.padPositionName(null));
    }
}
