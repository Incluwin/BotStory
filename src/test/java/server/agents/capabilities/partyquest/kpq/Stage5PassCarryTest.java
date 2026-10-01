package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackPlanScoringPolicy;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapItem;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run's companions carry the stage-5 passes up to the human leader by Cloto (pace plan C2). Live (runs 6
 * and D) only the leader could pick them up: they lay 2,300 px below Cloto for 106-134 s, or expired there, until the
 * missing-pass recovery made them up.
 */
class Stage5PassCarryTest {
    private static final int JR_NECKI = 9_300_000;
    private static final int KING_SLIME = 9_300_003;
    private static final Point BEN_BY_CLOTO = new Point(-210, -2721);
    private static final Point KING_SLIMES_FLOOR = new Point(160, -451);
    private static final String ENTER = "let's clear these out and get the 10 passes.";
    private static final String WAIT = "Ben, wait by cloto, we'll bring the passes.";
    private static final String AT_FEET = "Ben, the passes are at your feet. grab them and talk to cloto.";
    private static final String GRAB_SHOES = "i'll grab the squishy shoes.";
    private static final String RIGHT_HERE = "Ben, can't get to you, i dropped the passes right here by me.";
    private static final long HANDOVER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_PASS_HANDOVER_MS");

    private KpqRunFixture run;
    // The coordinator's own gateway is the real one; these are what its grind, move, stop, loot and drop calls reach.
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentGrindLootTargetService> lootTargets;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentInventoryGatewayRuntime> inventory;
    // Which mob each one hurts most is AgentKpqStageFiveTargetPolicyTest's; here every estimate is the same.
    private MockedStatic<AgentAttackPlanScoringPolicy> scoring;
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final Map<Character, Integer> passesHeld = new HashMap<>();
    private Character ben;
    private Character mira;
    private Character pip;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        modes = mockStatic(AgentModeService.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        lootTargets = mockStatic(AgentGrindLootTargetService.class);
        items = mockStatic(AgentScriptItemActionService.class);
        inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        scoring = mockStatic(AgentAttackPlanScoringPolicy.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventoryGateway);
        when(inventoryGateway.addItem(any(), anyInt(), anyShort())).thenReturn(true);
        // Passes leave the bag when their drop goes through.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyShort()))
                .thenAnswer(call -> {
                    AgentRuntimeEntry dropper = call.getArgument(0);
                    short quantity = call.getArgument(3);
                    passesHeld.replaceAll((holder, held) ->
                            entry(holder.getId()) == dropper ? held - quantity : held);
                    return true;
                });
        ben = onStageFive(run.human(1, "Ben"), BEN_BY_CLOTO);
        mira = onStageFive(run.companion(2, "Mira"), new Point(0, -451));
        pip = onStageFive(run.companion(3, "Pip"), new Point(300, -451));
    }

    @AfterEach
    void tearDown() {
        scoring.close();
        inventory.close();
        items.close();
        lootTargets.close();
        loot.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modeState.close();
        modes.close();
        run.close();
    }

    @Test
    void whileMonstersLiveTheCompanionsKeepThePassesTheyPickUp() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);
        holding(mira, 3);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
    }

    @Test
    void aStockHumanLedRunStillLeavesThePassesToItsLeader() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqMemberState.Role.COMBAT_HELPER, session.member(2).role());
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
        assertTrue(said().contains("Ben, please collect the 10 passes while we clear the monsters."), said()::toString);
    }

    @Test
    void theLeaderIsAskedToWaitByClotoAndNeverNudgedToCollect() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        fighting(JR_NECKI);

        // Long past the delayed nudge's 30 s.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 40_000L);
        AgentKpqCoordinator.tick(session, 80_000L);

        assertEquals(List.of(ENTER, WAIT), said());
    }

    @Test
    void onceNothingIsLeftToFightTheyBringThePassesUpAndTheLastOneSaysSo() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);

        // King Slime's revive grace runs out at 4 s; nothing is left to fetch, so they set out.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_100L);
        AgentKpqCoordinator.tick(session, 5_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, false));
        modes.verify(() -> AgentModeService.startMoveTo(entry(3), BEN_BY_CLOTO, false));
        // From here none of them picks a pass back up, not even one another dropped at Ben's feet.
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
        assertFalse(AgentKpqSessionRegistry.canLootPass(3));

        at(mira, BEN_BY_CLOTO.x + 30, BEN_BY_CLOTO.y);
        at(pip, BEN_BY_CLOTO.x - 30, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 6), times(1));
        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 4), times(1));
        // Once all ten are down, from the last one there.
        assertEquals(1, said().stream().filter(AT_FEET::equals).count(), said()::toString);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, AT_FEET));
    }

    @Test
    void aCarrierStillFetchingAPassHoldsTheOthersBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        holding(pip, 4);
        fetching(pip, true);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_100L);
        AgentKpqCoordinator.tick(session, 5_000L);

        modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));

        fetching(pip, false);
        AgentKpqCoordinator.tick(session, 6_000L);
        AgentKpqCoordinator.tick(session, 7_000L);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), BEN_BY_CLOTO, false));
    }

    @Test
    void aPassTooFreshToPickUpYetHoldsTheHandOverBack() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 6);
        MapItem fresh = mock(MapItem.class);
        when(fresh.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(run.map.getDroppedItems()).thenReturn(List.of(fresh));
        try (MockedStatic<AgentLootEligibility> eligibility = mockStatic(AgentLootEligibility.class)) {
            eligibility.when(() -> AgentLootEligibility.isWaitingForTargetAge(
                    any(), any(), any(), eq(fresh), anyLong(), anyLong())).thenReturn(true);

            AgentKpqCoordinator.tick(session, 2_000L);
            AgentKpqCoordinator.tick(session, 4_100L);
            AgentKpqCoordinator.tick(session, 5_000L);

            modes.verify(() -> AgentModeService.startMoveTo(any(), eq(BEN_BY_CLOTO), anyBoolean()), never());
            assertTrue(AgentKpqSessionRegistry.canLootPass(2));
        }
    }

    @Test
    void passesOnTheirWayAreNotMadeUpButLostOnesStillAre() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);

        // Mira picked up all ten and climbs to Ben for well over the 7 s grace.
        for (long nowMs = 2_000L; nowMs <= 20_000L; nowMs += 2_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        verify(inventoryGateway, never()).addItem(any(), anyInt(), anyShort());

        // She drops them at Ben's feet, and they expire there before he picks them up.
        at(mira, BEN_BY_CLOTO.x + 30, BEN_BY_CLOTO.y);
        AgentKpqCoordinator.tick(session, 21_000L);
        AgentKpqCoordinator.tick(session, 28_100L);

        verify(inventoryGateway, times(1))
                .addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
    }

    @Test
    void oneThatCannotGetUpToTheLeaderLeavesThePassesWhereItIsAndSaysSo() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 10);

        // They set out at 4.1 s, and Mira's route up never gets her near Ben.
        AgentKpqCoordinator.tick(session, 2_000L);
        long setOutMs = 4_100L;
        for (long nowMs = setOutMs; nowMs < setOutMs + HANDOVER_MS; nowMs += 10_000L) {
            AgentKpqCoordinator.tick(session, nowMs);
        }
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());

        AgentKpqCoordinator.tick(session, setOutMs + HANDOVER_MS);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 10), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, RIGHT_HERE));
        assertFalse(said().contains(AT_FEET), said()::toString);

        // Gone from the floor before Ben fetches them, they are made up 7 s later, long before the phase timeout.
        AgentKpqCoordinator.tick(session, setOutMs + HANDOVER_MS + 1_000L);
        AgentKpqCoordinator.tick(session, setOutMs + HANDOVER_MS + 8_100L);

        verify(inventoryGateway, times(1))
                .addItem(ben, AgentKpqDefinition.PASS_ITEM, (short) 10);
    }

    @Test
    void whatACarrierStillHoldsWhenStageFiveClearsIsDroppedThere() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        holding(mira, 3);
        when(run.event.getProperty("5stageclear")).thenReturn("true");

        AgentKpqCoordinator.tick(session, 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 3), times(1));
        assertEquals(AgentKpqSession.Phase.CLAIMING_REWARDS, session.phase());
    }

    @Test
    void companionsOnStageFiveBeforeTheLeaderPickUpPassesAndSweepTheRest() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster necki = fighting(JR_NECKI).getFirst();

        // The first tick only staggers when each starts moving.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_000L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertTrue(AgentKpqSessionRegistry.canLootPass(2));

        when(necki.isAlive()).thenReturn(false);
        AgentKpqCoordinator.tick(session, 5_000L);

        lootTargets.verify(() -> AgentGrindLootTargetService.prepareNearestObjectiveItem(
                entry(2), mira, Set.of(AgentKpqDefinition.PASS_ITEM)), atLeast(1));
        assertEquals(AgentKpqSession.Phase.STAGE_4, session.phase());
    }

    @Test
    void kingSlimesLootDelayHoldsTheCarriersBackBeforeTheLeaderArrivesToo() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_4, ben, mira, pip);
        when(ben.getMapId()).thenReturn(AgentKpqDefinition.STAGE_4_MAP);
        when(run.event.getProperty("4stageclear")).thenReturn("true");
        Monster kingSlime = fighting(KING_SLIME).getFirst();
        // The pickup check reads the wall clock.
        long nowMs = System.currentTimeMillis();
        session.markProgress(nowMs);

        AgentKpqCoordinator.tick(session, nowMs);
        AgentKpqCoordinator.tick(session, nowMs + 2_000L);
        when(kingSlime.isAlive()).thenReturn(false);
        AgentKpqCoordinator.tick(session, nowMs + 2_100L);

        assertEquals(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER, session.member(2).role());
        assertFalse(AgentKpqSessionRegistry.canLootPass(2));
    }

    @Test
    void theShoesWinnerGoesRightAfterKingSlimeWhenNoHumanIsNearThem() {
        AgentKpqSession session = shoesOnKingSlimesFloor();

        // King Slime's revive grace runs out at 4 s and the shoes are seen; his 3 s loot delay follows.
        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_100L);
        AgentKpqCoordinator.tick(session, 7_200L);

        assertTrue(said().contains(GRAB_SHOES), said()::toString);
    }

    @Test
    void aHumanBesideTheShoesStillGetsTheFirstChance() {
        AgentKpqSession session = shoesOnKingSlimesFloor();
        at(ben, KING_SLIMES_FLOOR.x - 10, KING_SLIMES_FLOOR.y);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 4_100L);
        AgentKpqCoordinator.tick(session, 7_200L);
        assertFalse(said().contains(GRAB_SHOES), said()::toString);

        AgentKpqCoordinator.tick(session, 11_200L);
        assertTrue(said().contains(GRAB_SHOES), said()::toString);
    }

    /** King Slime is down and his shoes lie on his floor; Ben holds no pass yet. */
    private AgentKpqSession shoesOnKingSlimesFloor() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, mira, pip);
        MapItem shoes = mock(MapItem.class);
        when(shoes.getItemId()).thenReturn(AgentKpqDefinition.SQUISHY_SHOES);
        when(shoes.getPosition()).thenReturn(KING_SLIMES_FLOOR);
        when(run.map.getDroppedItems()).thenReturn(List.of(shoes));
        return session;
    }

    private List<String> said() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), lines.capture()), atLeast(0));
        return lines.getAllValues();
    }

    private void holding(Character companion, int passes) {
        passesHeld.put(companion, passes);
        when(companion.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))
                .thenAnswer(call -> passesHeld.get(companion));
    }

    private void fetching(Character companion, boolean fetching) {
        lootTargets.when(() -> AgentGrindLootTargetService.prepareNearestObjectiveItem(
                entry(companion.getId()), companion, Set.of(AgentKpqDefinition.PASS_ITEM))).thenReturn(fetching);
    }

    private static Character onStageFive(Character member, Point position) {
        when(member.getMapId()).thenReturn(AgentKpqDefinition.STAGE_5_MAP);
        return at(member, position.x, position.y);
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private List<Monster> fighting(int... mobIds) {
        List<Monster> monsters = java.util.Arrays.stream(mobIds).mapToObj(Stage5PassCarryTest::monster).toList();
        when(run.map.getAllMonsters()).thenReturn(monsters);
        return monsters;
    }

    private static Monster monster(int mobId) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        return monster;
    }
}
