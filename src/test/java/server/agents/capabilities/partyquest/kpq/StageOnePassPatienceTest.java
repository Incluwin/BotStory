package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.MapGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stage-1 pass a companion run's human leader leaves lying. Live (2026-10-02, run kpq-af7b89bb) three companions
 * dropped their passes at his feet, he picked up two, and the third held the missing-pass recovery back until it
 * expired 180 s later: a 195 s hand-in against 25 s. Now he hears about it once, and past the patience the pass stops
 * blocking the recovery, which then takes the stage-1 passes back as Cloto's clear does (one picked up after it would
 * count toward stage 5's ten). Stock and Agent-led runs keep the old gate (any floor pass blocks it) and their passes.
 */
class StageOnePassPatienceTest {
    private static final long NUDGE_MS = tuning("STAGE1_PASS_NUDGE_MS");
    private static final long PATIENCE_MS = tuning("STAGE1_FLOOR_PASS_PATIENCE_MS");
    private static final long GRACE_MS = tuning("MISSING_PASS_GRACE_MS");
    private static final long DROPPED_AT_MS = 2_000L;
    private static final String NUDGE = "Ben, there's still a pass right by your feet.";
    private static final String NUDGE_PLURAL = "Ben, there are still passes right by your feet.";
    private static final List<String> NUDGES = List.of(NUDGE, NUDGE_PLURAL,
            "Ben, you left a pass on the ground back there.", "Ben, you left some passes on the ground back there.");
    private static final String RECOVERY = "KPQ Stage 1 missing-pass recovery: ";
    private static final String TAKEN_BACK = "KPQ Stage 1 passes taken back at the recovery: ";
    private static final Pattern BARE_COUNTER = Pattern.compile("\\d+\\s*/\\s*\\d+");

    private KpqRunFixture run;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentGroundingService> grounding;
    private MockedStatic<AgentMapGatewayRuntime> maps;
    private MockedStatic<AgentInventoryGatewayRuntime> inventories;
    private final MapGateway mapGateway = mock(MapGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final Map<Character, Integer> passesHeld = new HashMap<>();
    /** What lies on stage 1's floor. */
    private final List<MapItem> floor = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        loot = mockStatic(AgentGrindLootStateRuntime.class);
        items = mockStatic(AgentScriptItemActionService.class);
        modes = mockStatic(AgentModeService.class);
        farmAnchors = mockStatic(AgentFarmAnchorStateRuntime.class);
        variation = mockStatic(AgentCombatVariationRuntime.class);
        objective = mockStatic(AgentCombatObjectiveTargetStateRuntime.class);
        modeState = mockStatic(AgentModeStateRuntime.class);
        grounding = mockStatic(AgentGroundingService.class);
        // A pass leaves the bag when its drop goes through.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), eq((short) 1)))
                .thenAnswer(call -> {
                    AgentRuntimeEntry dropper = call.getArgument(0);
                    passesHeld.replaceAll((holder, held) -> entry(holder.getId()) == dropper ? held - 1 : held);
                    return true;
                });
        when(run.map.getDroppedItems()).thenAnswer(call -> List.copyOf(floor));
        // An expired drop leaves the floor; the take-back leaves the bag, as Cloto's clear does.
        maps = mockStatic(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        when(mapGateway.expireItemDrop(any(), any())).thenAnswer(call -> {
            floor.remove(call.<MapItem>getArgument(1));
            return true;
        });
        inventories = mockStatic(AgentInventoryGatewayRuntime.class);
        inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        doAnswer(call -> {
            passesHeld.computeIfPresent(call.getArgument(0), (holder, held) -> held - call.<Integer>getArgument(3));
            return null;
        }).when(inventory).removeById(any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), anyInt(),
                eq(false), eq(false));
    }

    @AfterEach
    void tearDown() {
        inventories.close();
        maps.close();
        grounding.close();
        modeState.close();
        objective.close();
        variation.close();
        farmAnchors.close();
        modes.close();
        items.close();
        loot.close();
        run.close();
    }

    @Test
    void aPassLeftAtTheLeadersFeetIsMentionedOnceAndClearsTheStageAfterThePatienceNotItsExpiry() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        session.setMemberCoordinationChatEnabled(false);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        for (int companion = 2; companion <= 4; companion++) {
            AgentRuntimeEntry dropper = entry(companion);
            items.verify(() -> AgentScriptItemActionService.dropItem(
                    dropper, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
        }
        // He picks up two; SilentSin's lies at his feet.
        holds(ben, 2);
        MapItem leftLying = pass(22, silentSin, -8, DROPPED_AT_MS);
        lying(leftLying);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS - 1L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);
        // In the voice of the companion that dropped it.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE), times(1));

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS - 1L);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS - 1L);
        verify(run.event, never()).setProperty("1stageclear", "true");

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            String restore = restores.get(0);
            assertTrue(restore.contains(" leaderPasses=2/3 floorPasses=1 defect=true cause=stale-floor-passes "
                    + "trigger=grace-timeout "), restore);
            assertEquals(List.of(TAKEN_BACK + "session=" + session.sessionId() + " leader=Ben(1) fromLeader=2 "
                    + "floorDrops=1/1"), log.startingWith(TAKEN_BACK));
        }
        // At 65 s after the drop, not at its 180 s expiry; the hand-in span is kept out of the calibration.
        verify(run.event).setProperty("1stageclear", "true");
        assertTrue(session.pace().snapshot().stageOnePassesRestored());
        // Cloto's clear would have taken his two, and the one left lying can no longer be picked up.
        verify(mapGateway).expireItemDrop(run.map, leftLying);
        verify(inventory).removeById(ben, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, 2, false, false);
        assertNoPassLeft(ben);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), times(1));
        assertSpokenLikePlayers();
    }

    @Test
    void aPassYoungerThanThePatienceStillHoldsTheRecoveryBack() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        // SilentSin's pass lies since the drop; Fenna's went down 30 s later (she was knocked off his spot).
        long laterDropMs = DROPPED_AT_MS + 30_000L;
        holds(ben, 1);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS), pass(23, fenna, 6, laterDropMs));

        // SilentSin's is past the patience, and would clear the stage after the grace on its own; Fenna's is not.
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS);
        AgentKpqCoordinator.tick(session, laterDropMs + PATIENCE_MS - 1L);
        verify(run.event, never()).setProperty("1stageclear", "true");
        // Both lie by his feet: he hears it once, from the one whose pass lies longest.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE_PLURAL), times(1));

        AgentKpqCoordinator.tick(session, laterDropMs + PATIENCE_MS);
        AgentKpqCoordinator.tick(session, laterDropMs + PATIENCE_MS + GRACE_MS - 1L);
        verify(run.event, never()).setProperty("1stageclear", "true");
        verify(mapGateway, never()).expireItemDrop(any(), any());
        AgentKpqCoordinator.tick(session, laterDropMs + PATIENCE_MS + GRACE_MS);
        verify(run.event).setProperty("1stageclear", "true");
        assertNoPassLeft(ben);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE_PLURAL)), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());
    }

    @Test
    void aPassACompanionStillHoldsBlocksTheRecoveryHoweverOldTheFloorPassIs() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        // Fenna never gets to him.
        Character fenna = at(run.companion(4, "Fenna"), 300, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 1);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));

        for (long nowMs = DROPPED_AT_MS + PATIENCE_MS; nowMs <= DROPPED_AT_MS + PATIENCE_MS + 3 * GRACE_MS;
             nowMs += GRACE_MS) {
            AgentKpqCoordinator.tick(session, nowMs);
        }

        assertEquals(1, passesHeld.get(fenna));
        verify(run.event, never()).setProperty("1stageclear", "true");
        assertNothingTakenBack();
    }

    @Test
    void aLeaderWhoWalkedOffHearsWhereThePassStillLies() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 1);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));
        // He went back up to fight.
        at(ben, 600, -400);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, "Ben, you left a pass on the ground back there."),
                times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());
    }

    @Test
    void passesOnlySomeOfWhichLieByHisFeetAreCalledLeftBackThere() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 1);
        // Liriki's lies where he stood before; Fenna's, the last listed, lies by his feet.
        lying(pass(21, liriki, -300, DROPPED_AT_MS), pass(23, fenna, 6, DROPPED_AT_MS));

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(liriki,
                "Ben, you left some passes on the ground back there."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE_PLURAL)), never());
    }

    @Test
    void aStockHumanLedRunStillWaitsForEveryFloorPassAndNeverNudges() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 60, 30);
        Character pip = at(run.companion(3, "Pip"), -80, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        holdingPass(session, mira);
        holdingPass(session, pip);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 1);
        lying(pass(22, pip, -80, DROPPED_AT_MS));

        assertOldGate(session, ben, DROPPED_AT_MS + 170_000L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());
    }

    @Test
    void anAllAgentRunStillWaitsForEveryFloorPass() {
        Character kai = at(run.companion(1, "Kai"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 10, 0);
        Character pip = at(run.companion(3, "Pip"), 20, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, mira, pip);
        holdingPass(session, mira);
        holdingPass(session, pip);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(kai, 1);
        lying(pass(22, pip, 20, DROPPED_AT_MS));

        assertOldGate(session, kai, DROPPED_AT_MS + 170_000L);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq("Kai, there's still a pass right by your feet.")),
                never());
    }

    @Test
    void anAgentLedCompanionRunStillWaitsForEveryFloorPass() {
        Character kai = at(run.companion(1, "Kai"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 10, 0);
        Character pip = at(run.companion(3, "Pip"), 20, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, kai, mira, pip);
        holdingPass(session, mira);
        holdingPass(session, pip);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(kai, 1);
        lying(pass(22, pip, 20, DROPPED_AT_MS));

        assertOldGate(session, kai, DROPPED_AT_MS + 170_000L);
    }

    @Test
    void aRecoveryPastThePatienceTakesThePassesBackSoNoneCanReachStageFive() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        // He picks up none of the three at his feet. Left there past the clear, he could pick them up and carry them
        // into stage 5, where Cloto counts any pass toward the ten.
        holds(ben, 0);
        lying(pass(21, liriki, -10, DROPPED_AT_MS), pass(22, silentSin, -8, DROPPED_AT_MS),
                pass(23, fenna, 6, DROPPED_AT_MS));

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(liriki, NUDGE_PLURAL), times(1));
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, DROPPED_AT_MS + PATIENCE_MS + GRACE_MS);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            assertTrue(restores.get(0).contains(" leaderPasses=0/3 floorPasses=3 defect=true cause=stale-floor-passes "
                    + "trigger=grace-timeout "), restores.get(0));
            assertEquals(List.of(TAKEN_BACK + "session=" + session.sessionId() + " leader=Ben(1) fromLeader=0 "
                    + "floorDrops=3/3"), log.startingWith(TAKEN_BACK));
        }

        verify(run.event).setProperty("1stageclear", "true");
        assertNoPassLeft(ben);
        verify(mapGateway, times(3)).expireItemDrop(eq(run.map), any());
        verify(inventory, never()).removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void aSecondHumansLyingPassNeverHasTheRunSpeak() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character ann = at(run.human(2, "Ann"), 30, 0);
        Character liriki = at(run.companion(3, "Liriki"), -10, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, ann, liriki);
        holdingPass(session, liriki);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        // He picked up Liriki's. Ann (Cloto made hers: her grid reads 0) dropped hers by him, and he left it.
        holds(ben, 1);
        lying(pass(22, ann, 5, DROPPED_AT_MS));

        for (long at = DROPPED_AT_MS + NUDGE_MS; at < DROPPED_AT_MS + PATIENCE_MS; at += GRACE_MS) {
            AgentKpqCoordinator.tick(session, at);
        }

        for (String nudge : NUDGES) {
            run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(nudge)), never());
        }
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(ann), any()), never());
    }

    @Test
    void whileTheRerollQuestionIsOutTheLyingPassIsNeitherMentionedNorLetGo() {
        long answerMs = 90_000L;
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        // The yaml's re-roll defaults, but a question that waits 90 s for his answer.
        session.setRerollPolicy(new AgentKpqRerollPolicy(true, 75_000L, 60_000L, 2, 900_000L, answerMs, 250));
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 2);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));
        long askedAtMs = DROPPED_AT_MS + 1_000L;
        session.markRerollAsked(liriki.getId(), ben.getId(), "thirty-fives", "wanna bail and run it again?",
                askedAtMs);
        long settlesAtMs = askedAtMs + answerMs;

        // The question is the only thing asked of him, and the pass he has not heard about holds the recovery back.
        for (long at = DROPPED_AT_MS + NUDGE_MS; at < settlesAtMs; at += GRACE_MS) {
            AgentKpqCoordinator.tick(session, at);
        }
        assertTrue(session.rerollPending());
        verify(run.event, never()).setProperty("1stageclear", "true");
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());

        // It goes unanswered: he hears about the pass, and the recovery follows after the grace.
        AgentKpqCoordinator.tick(session, settlesAtMs);
        assertFalse(session.rerollPending());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE), times(1));
        AgentKpqCoordinator.tick(session, settlesAtMs + GRACE_MS - 1L);
        verify(run.event, never()).setProperty("1stageclear", "true");
        AgentKpqCoordinator.tick(session, settlesAtMs + GRACE_MS);
        verify(run.event).setProperty("1stageclear", "true");
        assertNoPassLeft(ben);
    }

    @Test
    void aRecoveryForAMemberTheRunNoLongerHasIsNoDefectAndStillTakesHisPassesBack() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character ann = run.human(2, "Ann");
        Character liriki = at(run.companion(3, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(4, "SilentSin"), -8, 0);
        // Ann logged out and was dropped from the run; the party, and Cloto, still count her.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin);
        run.offline(ann);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        // He picked up both.
        holds(ben, 2);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + 1_000L);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, DROPPED_AT_MS + 1_000L + GRACE_MS);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            assertTrue(restores.get(0).contains(" leaderPasses=2/3 floorPasses=0 defect=false cause=member-gone "
                    + "trigger=grace-timeout "), restores.get(0));
            assertEquals(List.of(TAKEN_BACK + "session=" + session.sessionId() + " leader=Ben(1) fromLeader=2 "
                    + "floorDrops=0/0"), log.startingWith(TAKEN_BACK));
        }
        verify(run.event).setProperty("1stageclear", "true");
        assertNoPassLeft(ben);
    }

    @Test
    void aPassWhoseDropperIsNoLongerLoadedIsMentionedByTheNarrator() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character liriki = at(run.companion(2, "Liriki"), -10, 0);
        Character silentSin = at(run.companion(3, "SilentSin"), -8, 0);
        Character fenna = at(run.companion(4, "Fenna"), 6, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, liriki, silentSin, fenna);
        holdingPass(session, liriki);
        holdingPass(session, silentSin);
        holdingPass(session, fenna);
        AgentKpqCoordinator.tick(session, DROPPED_AT_MS);
        holds(ben, 2);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));
        // SilentSin's Agent was unloaded (its owner logged out); its character is still online for the moment.
        run.withoutRuntimeEntry(silentSin);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);

        // Liriki narrates the run.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(liriki, NUDGE), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, NUDGE), never());
    }

    @Test
    void aFloorPassStopsCountingAsOnItsWayAtThePatienceByItsOwnDropTime() {
        Character mira = run.companion(2, "Mira");
        MapItem old = pass(22, mira, 0, DROPPED_AT_MS);
        MapItem young = pass(23, mira, 0, DROPPED_AT_MS + PATIENCE_MS);

        assertEquals(1, AgentKpqCoordinator.stageOneWaitingFloorPasses(
                List.of(old, young), DROPPED_AT_MS + PATIENCE_MS, PATIENCE_MS));
        assertEquals(1, AgentKpqCoordinator.stageOneWaitingFloorPasses(
                List.of(old), DROPPED_AT_MS + PATIENCE_MS - 1L, PATIENCE_MS));
        assertEquals(0, AgentKpqCoordinator.stageOneWaitingFloorPasses(
                List.of(old), DROPPED_AT_MS + PATIENCE_MS, PATIENCE_MS));
    }

    @Test
    void theNudgeLinesSoundLikePlayers() {
        for (String line : List.of(
                AgentKpqPassHandOver.stageOneNudgeLine("Ben", 1, true),
                AgentKpqPassHandOver.stageOneNudgeLine("Ben", 2, true),
                AgentKpqPassHandOver.stageOneNudgeLine("Ben", 1, false),
                AgentKpqPassHandOver.stageOneNudgeLine("Ben", 2, false))) {
            String spoken = line.substring("Ben".length());
            assertEquals(spoken.toLowerCase(java.util.Locale.ROOT), spoken, line);
            assertFalse(line.contains("->") || BARE_COUNTER.matcher(line).find(), line);
        }
        assertEquals(NUDGE, AgentKpqPassHandOver.stageOneNudgeLine("Ben", 1, true));
        assertEquals(NUDGE_PLURAL, AgentKpqPassHandOver.stageOneNudgeLine("Ben", 2, true));
        assertEquals("Ben, you left some passes on the ground back there.",
                AgentKpqPassHandOver.stageOneNudgeLine("Ben", 2, false));
    }

    /**
     * Today's gate: a floor pass holds the recovery back however old it is; once none lies there it runs after the
     * grace, and it is not a stale-pass restore. The leader keeps the pass he holds, as before.
     */
    private void assertOldGate(AgentKpqSession session, Character leader, long untilMs) {
        for (long at = DROPPED_AT_MS + GRACE_MS; at <= untilMs; at += GRACE_MS) {
            AgentKpqCoordinator.tick(session, at);
        }
        verify(run.event, never()).setProperty("1stageclear", "true");

        floor.clear();
        AgentKpqCoordinator.tick(session, untilMs + 1_000L);
        verify(run.event, never()).setProperty("1stageclear", "true");
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, untilMs + 1_000L + GRACE_MS);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            assertTrue(restores.get(0).contains(" floorPasses=0 defect=true cause=passes-gone trigger=grace-timeout "),
                    restores.get(0));
            assertEquals(List.of(), log.startingWith(TAKEN_BACK));
        }
        verify(run.event).setProperty("1stageclear", "true");
        assertEquals(1, leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false));
        assertNothingTakenBack();
    }

    /** No stage-1 pass is left for anyone to carry into stage 5: none on the floor, none in the leader's bag. */
    private void assertNoPassLeft(Character leader) {
        assertEquals(List.of(), List.copyOf(floor));
        assertEquals(0, leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false));
    }

    private void assertNothingTakenBack() {
        verify(mapGateway, never()).expireItemDrop(any(), any());
        verify(inventory, never()).removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    private void assertSpokenLikePlayers() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), lines.capture()), atLeast(0));
        for (String line : lines.getAllValues()) {
            assertFalse(line.contains("->"), () -> "status arrow: " + line);
            assertFalse(BARE_COUNTER.matcher(line).find(), () -> "bare counter: " + line);
        }
    }

    private static long tuning(String key) {
        return config.AgentTuning.longValue("server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator." + key);
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    /** Cloto took this companion's coupons and gave it the pass, which is still in its bag. */
    private void holdingPass(AgentKpqSession session, Character companion) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(10);
        member.beginStageMovement(1, 0L);
        member.markPassCreated();
        member.setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        passesHeld.put(companion, 1);
        when(companion.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))
                .thenAnswer(call -> passesHeld.get(companion));
    }

    /** What lies on stage 1's floor from now on. */
    private void lying(MapItem... passes) {
        floor.clear();
        floor.addAll(List.of(passes));
    }

    /** The leader holds this many passes he picked up. */
    private void holds(Character leader, int passes) {
        passesHeld.put(leader, passes);
        when(leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))
                .thenAnswer(call -> passesHeld.get(leader));
    }

    /** A pass {@code owner} dropped at {@code droppedAtMs}, lying at x on the leader's level. */
    private static MapItem pass(int objectId, Character owner, int x, long droppedAtMs) {
        int ownerId = owner.getId();
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(droppedAtMs);
        when(drop.getPosition()).thenReturn(new Point(x, 0));
        return drop;
    }
}
