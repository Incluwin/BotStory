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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
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
 * blocking the recovery. Stock and Agent-led runs keep the old gate: any floor pass blocks it.
 */
class StageOnePassPatienceTest {
    private static final long NUDGE_MS = tuning("STAGE1_PASS_NUDGE_MS");
    private static final long PATIENCE_MS = tuning("STAGE1_FLOOR_PASS_PATIENCE_MS");
    private static final long GRACE_MS = tuning("MISSING_PASS_GRACE_MS");
    private static final long DROPPED_AT_MS = 2_000L;
    private static final String NUDGE = "Ben, there's still a pass right by your feet.";
    private static final String NUDGE_PLURAL = "Ben, there are still passes right by your feet.";
    private static final String RECOVERY = "KPQ Stage 1 missing-pass recovery: ";
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
    private final Map<Character, Integer> passesHeld = new HashMap<>();

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
    }

    @AfterEach
    void tearDown() {
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
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(2);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));

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
        }
        // At 65 s after the drop, not at its 180 s expiry; the hand-in span is kept out of the calibration.
        verify(run.event).setProperty("1stageclear", "true");
        assertTrue(session.pace().snapshot().stageOnePassesRestored());
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
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
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
        AgentKpqCoordinator.tick(session, laterDropMs + PATIENCE_MS + GRACE_MS);
        verify(run.event).setProperty("1stageclear", "true");
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
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));

        for (long nowMs = DROPPED_AT_MS + PATIENCE_MS; nowMs <= DROPPED_AT_MS + PATIENCE_MS + 3 * GRACE_MS;
             nowMs += GRACE_MS) {
            AgentKpqCoordinator.tick(session, nowMs);
        }

        assertEquals(1, passesHeld.get(fenna));
        verify(run.event, never()).setProperty("1stageclear", "true");
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
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        lying(pass(22, silentSin, -8, DROPPED_AT_MS));
        // He went back up to fight.
        at(ben, 600, -400);

        AgentKpqCoordinator.tick(session, DROPPED_AT_MS + NUDGE_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(silentSin, "Ben, you left a pass on the ground back there."),
                times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(NUDGE)), never());
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
        when(ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        lying(pass(22, pip, -80, DROPPED_AT_MS));

        assertOldGate(session, DROPPED_AT_MS + 170_000L);
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
        when(kai.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        lying(pass(22, pip, 20, DROPPED_AT_MS));

        assertOldGate(session, DROPPED_AT_MS + 170_000L);
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
        when(kai.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);
        lying(pass(22, pip, 20, DROPPED_AT_MS));

        assertOldGate(session, DROPPED_AT_MS + 170_000L);
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
     * grace, and it is not a stale-pass restore.
     */
    private void assertOldGate(AgentKpqSession session, long untilMs) {
        for (long at = DROPPED_AT_MS + GRACE_MS; at <= untilMs; at += GRACE_MS) {
            AgentKpqCoordinator.tick(session, at);
        }
        verify(run.event, never()).setProperty("1stageclear", "true");

        when(run.map.getDroppedItems()).thenReturn(List.of());
        AgentKpqCoordinator.tick(session, untilMs + 1_000L);
        verify(run.event, never()).setProperty("1stageclear", "true");
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, untilMs + 1_000L + GRACE_MS);
            List<String> restores = log.startingWith(RECOVERY);
            assertEquals(1, restores.size(), restores::toString);
            assertTrue(restores.get(0).contains(" floorPasses=0 defect=true cause=passes-gone trigger=grace-timeout "),
                    restores.get(0));
        }
        verify(run.event).setProperty("1stageclear", "true");
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
        List<MapItem> drops = List.of(passes);
        when(run.map.getDroppedItems()).thenReturn(drops);
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
