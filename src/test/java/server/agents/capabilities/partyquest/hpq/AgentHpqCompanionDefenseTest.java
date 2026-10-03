package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.plans.AgentScriptItemActionService;
import server.life.Monster;
import server.maps.MapItem;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run's Moon Bunny defense (plan 2.3): guards stand on posts around the live Bunny (it lands at about
 * (-183,-188), not at its authored (-183,-433)), the companion on the centre post collects the Bunny's own cakes and
 * walks them to the leader's feet in batches, never takes back a stack it handed over until its re-collect is due, and
 * leaves its cakes where it stands before it goes down.
 */
class AgentHpqCompanionDefenseTest {
    private static final int CAKE = AgentHpqDefinition.RICE_CAKE;
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(150, -188));
    private final Character mira = run.companion(2, "Mira", new Point(600, 83));
    private final Character tobi = run.companion(3, "Tobi", new Point(600, 83));
    private final Character pip = run.companion(4, "Pip", new Point(600, 83));

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentHpqSession defending() {
        return run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi, pip);
    }

    @Test
    void theGuardsTakePostsAroundTheLiveBunnyAndTheCentreOneCollects() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(-183, -188), true);
        verify(run.actions).navigate(run.entry(tobi), new Point(-293, -188), true);
        verify(run.actions).navigate(run.entry(pip), new Point(-73, -188), true);
        assertEquals(mira.getId(), session.cakeCollectorId());
        assertEquals(AgentHpqMemberState.Role.CAKE_COLLECTOR, session.member(mira.getId()).role());
        run.said(session, "bunny-out", mira, "the bunny's out! keep the monsters off it.");
    }

    @Test
    void beforeTheBunnyIsSeenTheyGuardTheGroundUnderItsSpawn() {
        doReturn(new Point(-183, -188)).when(run.actions)
                .groundPoint(any(), eq(AgentHpqDefinition.MOON_BUNNY_POSITION));
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(-183, -188), true);
        verify(run.actions).navigate(run.entry(tobi), new Point(-293, -188), true);
        run.neverSaid("bunny-out");
    }

    @Test
    void guardsFightFromTheirPostsWhenTheBunnyIsThreatenedAndTheCollectorDoesNotLoot() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.monster(HpqRunFixture.STAGE_MOBS, new Point(-100, -188));
        run.moveTo(tobi, new Point(-293, -188));
        MapItem cake = run.drop(CAKE, new Point(-170, -188), false, 0, 1_500L);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        Set<Integer> mobs = Set.of(HpqRunFixture.STAGE_MOBS);
        verify(run.actions).grindFromAnchor(run.entry(tobi), new Point(-293, -188), mobs, mobs);
        verify(run.actions).grindFromAnchor(run.entry(mira), new Point(-183, -188), mobs, mobs);
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), cake), never());
        verify(run.actions, never()).grind(any(), any());
    }

    @Test
    void theCollectorLootsOnlyTheBunnysOwnCakes() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        MapItem natural = run.drop(CAKE, new Point(-170, -188), false, 0, 1_500L);
        MapItem handedOver = run.drop(CAKE, new Point(150, -188), true, mira.getId(), 1_500L);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), natural));
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), handedOver),
                never());
        assertTrue(AgentHpqSessionRegistry.canLootRiceCake(mira, natural));
        assertFalse(AgentHpqSessionRegistry.canLootRiceCake(mira, handedOver), "the leader's to pick up");
        assertFalse(AgentHpqSessionRegistry.canLootRiceCake(tobi, natural), "a guard collects nothing");
    }

    @Test
    void aBatchGoesDownAtTheLeadersFeetInOneDrop() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(mira, CAKE, 3);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(150, -188), true);
        run.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

        run.moveTo(mira, new Point(140, -188));
        AgentHpqCoordinator.tick(session, 3_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC, CAKE,
                (short) 3));
        run.said(session, "cakes-dropped-1", mira, "the rice cakes are at your feet, Ben. pick them up.");
        run.inventory.verify(() -> AgentInventoryStateRuntime.setLootInhibitMs(run.entry(mira), 3_000));
        assertEquals(1, session.handOvers());
        assertFalse(session.leaderToldGrowlie(), "three of ten: not yet growlie");
    }

    @Test
    void theHandOverThatMakesTheTenSendsHimToGrowlie() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(140, -188));
        run.holds(mira, CAKE, 3);
        run.holds(ben, CAKE, 7);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.said(session, "cakes-dropped-1", mira,
                "the rice cakes are at your feet, Ben. pick them up and talk to growlie.");
        assertTrue(session.leaderToldGrowlie());
    }

    @Test
    void afterALossASingleCakeIsNotWalkedOverOnItsOwn() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        when(run.hpq.property(any(), eq("bunnyCake"))).thenReturn("12");
        run.holds(mira, CAKE, 1);
        run.holds(ben, CAKE, 5);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(0L, session.handOverStartedAtMs());
        verify(run.actions, never()).navigate(run.entry(mira), new Point(150, -188), true);
        verify(run.actions).navigate(run.entry(mira), new Point(-183, -188), true);
    }

    @Test
    void withTheBunnyDoneTheLastCakesGoOverWhenTheyAndTheStacksLyingForHimMakeTen() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        when(run.hpq.property(any(), eq("bunnyCake"))).thenReturn("12");
        run.moveTo(ben, new Point(429, 83));
        run.holds(mira, CAKE, 2);
        run.holds(ben, CAKE, 5);
        MapItem lying = run.drop(CAKE, new Point(420, 83), true, mira.getId(), 1_500L);
        client.inventory.Item three = org.mockito.Mockito.mock(client.inventory.Item.class);
        when(three.getQuantity()).thenReturn((short) 3);
        when(lying.getItem()).thenReturn(three);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(run.entry(mira), new Point(429, 83), true);
        assertTrue(session.handOverStartedAtMs() > 0L);
    }

    @Test
    void aBatchStaysWithTheCollectorWhileTheLeaderIsFar() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.moveTo(ben, new Point(429, 83));
        run.holds(mira, CAKE, 3);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions, never()).navigate(run.entry(mira), new Point(429, 83), true);
        assertEquals(0L, session.handOverStartedAtMs());
    }

    @Test
    void theCollectorNeverTakesBackTheStackItHandedOver() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        MapItem stack = run.drop(CAKE, new Point(150, -188), true, mira.getId(), 2_000L);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 3_000L);

        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(stack)), never());
        verify(run.actions).stop(run.entry(mira));
        assertFalse(AgentHpqSessionRegistry.canLootRiceCake(mira, stack));
    }

    @Test
    void aLeaderNobodyCanReachGetsTheCakesByGrowlie() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(mira, CAKE, 3);
        when(run.actions.canReach(eq(run.entry(mira)), eq(mira), any())).thenReturn(false);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);
        verify(run.actions).navigate(run.entry(mira), new Point(429, 83), true);

        run.moveTo(mira, new Point(420, 83));
        AgentHpqCoordinator.tick(session, 9_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC, CAKE,
                (short) 3));
        run.said(session, "cakes-by-growlie-1", mira, "Ben, couldn't get to you, left the cakes by growlie.");
        assertFalse(session.leaderToldGrowlie());
    }

    @Test
    void cakesByGrowlieThatMakeTheTenSpareHimTheGrowlieLine() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(mira, CAKE, 3);
        run.holds(ben, CAKE, 7);
        when(run.actions.canReach(eq(run.entry(mira)), eq(mira), any())).thenReturn(false);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);
        run.moveTo(mira, new Point(420, 83));
        AgentHpqCoordinator.tick(session, 9_000L);

        run.said(session, "cakes-by-growlie-1", mira, "Ben, couldn't get to you, left the cakes by growlie.");
        assertTrue(session.leaderToldGrowlie(), "he picks them up beside growlie");
    }

    @Test
    void aRecollectedStackTakenToGrowlieSaysWhereItIs() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        MapItem stack = run.drop(CAKE, new Point(150, -188), true, mira.getId(), 10_000L);
        AgentHpqSession session = defending();
        AgentHpqCoordinator.tick(session, 160_000L);
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), stack));

        run.pickUp(stack);
        run.holds(mira, CAKE, 3);
        when(run.actions.canReach(eq(run.entry(mira)), eq(mira), any())).thenReturn(false);
        AgentHpqCoordinator.tick(session, 161_000L);
        verify(run.actions).navigate(run.entry(mira), new Point(429, 83), true);

        run.moveTo(mira, new Point(420, 83));
        AgentHpqCoordinator.tick(session, 162_000L);

        run.said(session, "cakes-by-growlie-1", mira, "Ben, couldn't get to you, left the cakes by growlie.");
        run.neverSaid("cakes-recollected-1");
    }

    @Test
    void aWalkPastTheTimeoutTakesTheCakesToGrowlie() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(mira, CAKE, 3);
        AgentHpqSession session = defending();
        AgentHpqCoordinator.tick(session, 2_000L);

        run.moveTo(mira, new Point(0, -188));
        AgentHpqCoordinator.tick(session, 32_000L);
        verify(run.actions, never()).navigate(run.entry(mira), new Point(429, 83), true);

        AgentHpqCoordinator.tick(session, 32_001L);
        verify(run.actions).navigate(run.entry(mira), new Point(429, 83), true);
    }

    @Test
    void aStackLeftLyingIsNudgedAboutThenPickedBackUpAtItsDueTimeAndDroppedAgain() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        MapItem stack = run.drop(CAKE, new Point(150, -188), true, mira.getId(), 10_000L);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 49_999L);
        run.neverSaid("cakes-nudge-" + stack.getObjectId());
        AgentHpqCoordinator.tick(session, 50_000L);
        run.said(session, "cakes-nudge-" + stack.getObjectId(), mira,
                "Ben, the cakes are still lying there. growlie's down where we came in.");

        AgentHpqCoordinator.tick(session, 159_999L);
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(stack)), never());
        assertFalse(AgentHpqSessionRegistry.canLootRiceCake(mira, stack));

        AgentHpqCoordinator.tick(session, 160_000L);
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), stack));
        assertTrue(AgentHpqSessionRegistry.canLootRiceCake(mira, stack), "its re-collect is due");

        run.pickUp(stack);
        run.holds(mira, CAKE, 3);
        run.moveTo(mira, new Point(140, -188));
        AgentHpqCoordinator.tick(session, 161_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC, CAKE,
                (short) 3));
        run.said(session, "cakes-recollected-1", mira,
                "picked the cakes back up so they don't vanish. here they are again.");
        assertEquals(1, session.recollects());
        assertFalse(AgentHpqSessionRegistry.canLootRiceCake(mira, stack), "the re-collect is over");
    }

    @Test
    void aStackLeftByACompanionSinceTakenOutOfTheRunIsStillNudgedAboutAndPickedBackUp() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        // Pip left its cakes before it went down, revived outside, was tapped out and ejected.
        MapItem stack = run.drop(CAKE, new Point(-150, -188), true, pip.getId(), 10_000L);
        AgentHpqSession session = defending();
        session.removeMember(pip.getId());
        AgentHpqSessionRegistry.unindexMember(session, pip.getId());
        run.leaveParty(pip);

        AgentHpqCoordinator.tick(session, 50_000L);
        run.said(session, "cakes-nudge-" + stack.getObjectId(), mira,
                "Ben, the cakes are still lying there. growlie's down where we came in.");

        AgentHpqCoordinator.tick(session, 160_000L);
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), stack));
        assertTrue(AgentHpqSessionRegistry.canLootRiceCake(mira, stack), "its re-collect is due");
    }

    @Test
    void aCollectorAboutToGoDownLeavesItsCakesAndTheNextCompanionCollects() {
        run.dropsSucceed();
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(mira, CAKE, 4);
        when(mira.getHp()).thenReturn(100);
        when(run.actions.hpPotionCount(mira)).thenReturn(0);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(run.entry(mira), InventoryType.ETC, CAKE,
                (short) 4));
        run.said(session, "cakes-left-" + mira.getId(), mira, "almost out of pots, leaving the cakes here, grab them.");

        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(tobi.getId(), session.cakeCollectorId());
        assertEquals(AgentHpqMemberState.Role.CAKE_COLLECTOR, session.member(tobi.getId()).role());
        assertEquals(AgentHpqMemberState.Role.BUNNY_GUARD, session.member(mira.getId()).role());
    }

    @Test
    void aCollectorLowOnHpWithPotionsLeftKeepsItsCakes() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.moveTo(ben, new Point(429, 83));
        run.holds(mira, CAKE, 2);
        when(mira.getHp()).thenReturn(100);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        run.neverSaid("cakes-left-" + mira.getId());
        assertEquals(mira.getId(), session.cakeCollectorId());
    }

    @Test
    void aCollectorOutOfPotionsAtFullHpKeepsItsCakes() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.moveTo(ben, new Point(429, 83));
        run.holds(mira, CAKE, 2);
        when(run.actions.hpPotionCount(mira)).thenReturn(0);
        AgentHpqSession session = defending();

        AgentHpqCoordinator.tick(session, 2_000L);

        run.items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        run.neverSaid("cakes-left-" + mira.getId());
        assertEquals(mira.getId(), session.cakeCollectorId());
    }

    @Test
    void companionsThatEachLeftTheirCakesOnceCollectAgainOnceOutOfDanger() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        MapItem natural = run.drop(CAKE, new Point(-170, -188), false, 0, 1_500L);
        AgentHpqSession session = defending();
        for (Character companion : new Character[] {mira, tobi, pip}) {
            session.member(companion.getId()).retireFromCakes();
        }

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(mira.getId(), session.cakeCollectorId());
        assertFalse(session.member(mira.getId()).retiredFromCakes(), "back above the dying share, potions in hand");
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), natural));
        assertTrue(AgentHpqSessionRegistry.canLootRiceCake(mira, natural));
    }

    @Test
    void withEveryCompanionAboutToGoDownOneHoldsTheRoleAndLootsNothing() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        MapItem natural = run.drop(CAKE, new Point(-170, -188), false, 0, 1_500L);
        for (Character companion : new Character[] {mira, tobi, pip}) {
            when(companion.getHp()).thenReturn(100);
            when(run.actions.hpPotionCount(companion)).thenReturn(0);
        }
        AgentHpqSession session = defending();

        // Each in turn takes the role, finds itself about to go down and retires; the last keeps it.
        for (long now = 2_000L; now <= 6_000L; now += 1_000L) AgentHpqCoordinator.tick(session, now);

        assertEquals(pip.getId(), session.cakeCollectorId(), "the run keeps a collector");
        assertEquals(AgentHpqMemberState.Role.CAKE_COLLECTOR, session.member(pip.getId()).role());
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(natural)), never());
    }

    @Test
    void aLeaderBackBelowTenSendsTheRunBackToCollecting() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        run.moveTo(mira, new Point(-183, -188));
        run.holds(ben, CAKE, 10);
        MapItem natural = run.drop(CAKE, new Point(-170, -188), false, 0, 1_500L);
        AgentHpqSession session = defending();
        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.DELIVERING_CAKES, session.phase());
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(any(), eq(natural)), never());

        run.holds(ben, CAKE, 8);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase());
        run.loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(run.entry(mira), natural));
        assertTrue(AgentHpqSessionRegistry.canLootRiceCake(mira, natural));
    }

    @Test
    void theBunnyLostEndsTheRunAndIsRemembered() {
        Monster bunny = run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = defending();
        AgentHpqCoordinator.tick(session, 2_000L);

        run.kill(bunny);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        assertTrue(session.bunnyLost());
    }

    @Test
    void theBunnyBeingHitIsSaidOnceAndThenOnlyAfterTheSpacing() {
        Monster bunny = run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = defending();
        AgentHpqCoordinator.tick(session, 2_000L);

        when(bunny.getHp()).thenReturn(900L);
        AgentHpqCoordinator.tick(session, 3_000L);
        run.said(session, "bunny-hit-1", mira, "the bunny's getting hit, on it!");

        when(bunny.getHp()).thenReturn(800L);
        AgentHpqCoordinator.tick(session, 4_000L);
        run.neverSaid("bunny-hit-2");
        when(bunny.getHp()).thenReturn(700L);
        AgentHpqCoordinator.tick(session, 33_000L);
        run.said(session, "bunny-hit-2", mira, "the bunny's getting hit, on it!");
    }
}
