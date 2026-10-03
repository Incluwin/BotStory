package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.life.Monster;
import server.maps.MapItem;

import java.io.IOException;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHpqCoordinatorPolicyTest {
    @Test
    void hpqCoordinatorDoesNotUseTheFullMapPartyQuestLootVacuum() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "server", "agents",
                "capabilities", "partyquest", "hpq", "AgentHpqCoordinator.java"));

        assertFalse(source.contains("HPQ.lootNearby("));
        assertTrue(source.contains("AgentGrindLootStateRuntime.setObjectiveLootTarget"));
    }

    @Test
    void naturalSeedTargetsExcludePlantedPlayerDropsAndUnrelatedItems() {
        MapItem naturalSeed = drop(4_001_095, false, false);
        MapItem plantedSeed = drop(4_001_096, true, false);
        MapItem pickedSeed = drop(4_001_097, false, true);
        MapItem riceCake = drop(AgentHpqDefinition.RICE_CAKE, false, false);

        assertEquals(List.of(naturalSeed), AgentHpqCoordinator.naturalSeedDrops(
                List.of(naturalSeed, plantedSeed, pickedSeed, riceCake)));
    }

    @Test
    void onlySuccessfulTestRunsRemainVisibleForTheNextRun() {
        assertTrue(AgentHpqTestService.holdAfterTerminal(AgentHpqSession.Phase.COMPLETED));
        assertFalse(AgentHpqTestService.holdAfterTerminal(AgentHpqSession.Phase.FAILED));
    }

    @Test
    void completedObservationKeepsItsPartyTogether() {
        assertTrue(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.TEST_OBSERVATION, true, false));
        assertFalse(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.TEST_OBSERVATION, false, false));
        assertFalse(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.PRODUCTION, true, false));
        assertFalse(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.PRODUCTION, false, false));
        // A companion run's party is its owner's: kept whatever the outcome.
        assertTrue(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.PRODUCTION, true, true));
        assertTrue(AgentHpqTerminationService.keepPartyAfterRelease(
                AgentHpqSession.Mode.PRODUCTION, false, true));
    }

    @Test
    void seedCollectorsAreDistributedAcrossTheFullSourceWidth() {
        assertEquals(3, AgentHpqCoordinator.distributedSourceIndex(0, 3, 18));
        assertEquals(9, AgentHpqCoordinator.distributedSourceIndex(1, 3, 18));
        assertEquals(15, AgentHpqCoordinator.distributedSourceIndex(2, 3, 18));
    }

    @Test
    void hpqDefenseReactionIsStaggeredButDeterministic() {
        assertEquals(0L, AgentHpqCoordinator.defenseReactionDelayMs(77L, 1001, 0, 2500L));
        long second = AgentHpqCoordinator.defenseReactionDelayMs(77L, 1002, 1, 2500L);
        long third = AgentHpqCoordinator.defenseReactionDelayMs(77L, 1003, 2, 2500L);

        assertTrue(second >= 450L && second <= 2500L);
        assertTrue(third >= 450L && third <= 2500L);
        assertEquals(second,
                AgentHpqCoordinator.defenseReactionDelayMs(77L, 1002, 1, 2500L));
    }

    @Test
    void defendersReceiveSeparateUpperGuardFootholdsAroundTheMoonBunny() {
        Point leader = AgentHpqDefinition.defenseGuardPoints().get(2);
        Point leftGuard = AgentHpqCoordinator.defenseGuardPoint(0, 2, true);
        Point rightGuard = AgentHpqCoordinator.defenseGuardPoint(1, 2, true);

        assertTrue(leader.y < AgentHpqDefinition.MOON_BUNNY_POSITION.y);
        assertTrue(leftGuard.y < -300 && rightGuard.y < -300);
        assertTrue(leftGuard.distance(rightGuard) > 500.0d);
        assertTrue(leftGuard.distance(leader) > 250.0d);
        assertTrue(rightGuard.distance(leader) > 250.0d);
    }

    @Test
    void aCompanionRunHearsCompanionLinesAndAStockRunKeepsItsPlainOnes() {
        AgentHpqSession companionRun = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, 1, 3, 1_000L);
        companionRun.setCompanionAdopted(true);
        AgentHpqSession stockRun = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, 1, 3, 1_000L);

        assertEquals("ours", AgentHpqCoordinator.voice(companionRun, "ours", "Stock."));
        assertNull(AgentHpqCoordinator.voice(companionRun, null, "Stock."), "a companion run says no status line");
        assertEquals("Stock.", AgentHpqCoordinator.voice(stockRun, "ours", "Stock."));
        // The stock lines are byte for byte what they were.
        assertEquals("Party ready. Everyone is here. Starting HPQ in 5 seconds.",
                AgentHpqCoordinator.STOCK_READY_LINE);
        assertEquals("Talk to Tory to claim your HPQ reward and return to Henesys.",
                AgentHpqCoordinator.STOCK_CLAIM_LINE);
        assertEquals("HPQ complete. Waiting at Tory for the next run.", AgentHpqCoordinator.STOCK_COMPLETE_LINE);
    }

    @Test
    void theClearComesBeforeTheBunnyLost() {
        try (HpqRunFixture run = new HpqRunFixture()) {
            Character ben = run.human(1, "Ben", new Point(429, 83));
            Character mira = run.companion(2, "Mira", new Point(-183, -188));
            Character tobi = run.companion(3, "Tobi", new Point(-293, -188));
            Monster bunny = run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
            AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DELIVERING_CAKES, ben, mira, tobi);
            AgentHpqCoordinator.tick(session, 2_000L);

            // Growlie took the cakes: the event is cleared and the Bunny went with the stage before the warp.
            when(run.event.isEventCleared()).thenReturn(true);
            run.kill(bunny);
            AgentHpqCoordinator.tick(session, 3_000L);

            assertEquals(AgentHpqSession.Phase.BONUS_DECISION, session.phase());
            assertFalse(session.bunnyLost());
            assertTrue(session.rewardEligibilityFrozen());
        }
    }

    @Test
    void aCompanionRunKeepsGuardingWhileTheLeaderDelivers() {
        try (HpqRunFixture run = new HpqRunFixture()) {
            Character ben = run.human(1, "Ben", new Point(429, 83));
            Character mira = run.companion(2, "Mira", new Point(-183, -188));
            Character tobi = run.companion(3, "Tobi", new Point(-293, -188));
            run.holds(ben, AgentHpqDefinition.RICE_CAKE, 10);
            run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
            run.monster(HpqRunFixture.STAGE_MOBS, new Point(-120, -188));
            MapItem cake = run.drop(AgentHpqDefinition.RICE_CAKE, new Point(-170, -188), false, 0, 1_000L);
            AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DELIVERING_CAKES, ben, mira, tobi);

            AgentHpqCoordinator.tick(session, 2_000L);

            Set<Integer> mobs = Set.of(HpqRunFixture.STAGE_MOBS);
            verify(run.actions).grindFromAnchor(run.entry(mira), new Point(-183, -188), mobs, mobs);
            verify(run.actions).grindFromAnchor(run.entry(tobi), new Point(-293, -188), mobs, mobs);
            run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(cake)), never());
            run.said(session, "all-ten", mira, "that's all ten. growlie's down where we came in.");
            assertEquals(AgentHpqSession.Phase.DELIVERING_CAKES, session.phase());
        }
    }

    @Test
    void theLeaderHoldingTenStartsTheDeliveryAndTheGuardsStay() {
        try (HpqRunFixture run = new HpqRunFixture()) {
            Character ben = run.human(1, "Ben", new Point(429, 83));
            Character mira = run.companion(2, "Mira", new Point(600, 83));
            Character tobi = run.companion(3, "Tobi", new Point(600, 83));
            run.holds(ben, AgentHpqDefinition.RICE_CAKE, 10);
            run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
            AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);

            AgentHpqCoordinator.tick(session, 2_000L);

            assertEquals(AgentHpqSession.Phase.DELIVERING_CAKES, session.phase());
            verify(run.actions).navigate(run.entry(mira), new Point(-183, -188), true);
            verify(run.actions).navigate(run.entry(tobi), new Point(-293, -188), true);
        }
    }

    @Test
    void companionsMayClaimOnlyOnceTheLeaderIsOffTheClearMapAndPigTownOrOutOrTheWaitIsOver() {
        assertFalse(AgentHpqCompanionPlay.companionsMayClaim(true, AgentHpqDefinition.CLEAR_MAP, false));
        assertFalse(AgentHpqCompanionPlay.companionsMayClaim(true, AgentHpqDefinition.BONUS_MAP, false));
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(true, AgentHpqDefinition.REWARD_EXIT_MAP, false));
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(false, AgentHpqDefinition.CLEAR_MAP, false));
        assertTrue(AgentHpqCompanionPlay.companionsMayClaim(true, AgentHpqDefinition.BONUS_MAP, true));
    }

    private static MapItem drop(int itemId, boolean playerDrop, boolean pickedUp) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        when(drop.isPickedUp()).thenReturn(pickedUp);
        return drop;
    }
}
