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
import server.agents.field.AgentFieldObservationState;
import server.agents.operations.events.AgentMobKilledEvent;
import server.agents.operations.events.AgentOperationalEventProjectionState;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * KPQ stage 1 fast enough for the event timer (P6): live, a human and three companions took about 11 minutes, because
 * only a rotating sweeper picked up other members' coupons, helpers fought map-wide, and two of three passes dropped
 * one by one beside the human leader expired before the pickup.
 */
class StageOnePaceTest {
    private static final long SWEEP_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.COUPON_SWEEP_INTERVAL_MS");
    private static final int ANCHOR_MOVE_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.HELPER_ANCHOR_MOVE_PX");
    private static final String PASSES_AT_FEET = "Ben, the passes are at your feet. pick them up and talk to Cloto.";
    private static final String PASS_DROPPED = AgentKpqCoordinator.stageOnePassDropMessage("Ben");

    private KpqRunFixture run;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentModeService> modes;
    // The coordinator's own gateway is the real one; these are what its grind, stop and ground calls reach.
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
    void everyCollectorGoesForItsOwnNearestFloorCoupon() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 0, 0);
        Character pip = at(run.companion(3, "Pip"), 45, 0);
        Character tobi = at(run.companion(4, "Tobi"), 600, -240);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip, tobi);
        collecting(session, mira, 0);
        collecting(session, pip, 0);
        collecting(session, tobi, 0);
        // The one nearest to Pip is nearer still to Mira, so Pip takes the next one.
        MapItem nearMira = coupon(11, 20, 0);
        MapItem nearPip = coupon(12, 90, 0);
        MapItem nearTobi = coupon(13, 640, -240);
        when(run.map.getDroppedItems()).thenReturn(List.of(nearMira, nearPip, nearTobi));

        AgentKpqCoordinator.tick(session, 1_000L + SWEEP_INTERVAL_MS);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(2), nearMira));
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(3), nearPip));
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(4), nearTobi));
    }

    @Test
    void aLoneCouponGoesToTheCollectorBesideItNotTheEarlierOneAcrossTheMap() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), -900, 0);
        Character pip = at(run.companion(3, "Pip"), 400, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        collecting(session, mira, 0);
        collecting(session, pip, 0);
        MapItem besidePip = coupon(11, 600, 0);
        when(run.map.getDroppedItems()).thenReturn(List.of(besidePip));

        AgentKpqCoordinator.tick(session, 1_000L + SWEEP_INTERVAL_MS);

        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(3), besidePip));
        loot.verify(() -> AgentGrindLootStateRuntime.setObjectiveLootTarget(eq(entry(2)), any()), never());
    }

    @Test
    void helpersFightAtTheNeediestCollectorAndStayForItsSmallMoves() {
        Character kai = at(run.companion(1, "Kai"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 100, 0);
        Character pip = at(run.companion(3, "Pip"), 500, -240);
        Character tobi = at(run.companion(4, "Tobi"), 0, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, mira, pip, tobi);
        collecting(session, mira, 0);
        session.member(2).markPassCreated();
        session.member(2).markPassDelivered();
        session.member(2).setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        // Pip still needs all ten coupons, Tobi only three.
        collecting(session, pip, 0);
        collecting(session, tobi, 7);
        Point firstSpot = new Point(500, -240);
        Point movedOn = new Point(500 + ANCHOR_MOVE_PX + 30, -240);

        AgentKpqCoordinator.tick(session, 2_000L);
        when(pip.getPosition()).thenReturn(new Point(500 + ANCHOR_MOVE_PX - 20, -240));
        AgentKpqCoordinator.tick(session, 3_000L);
        when(pip.getPosition()).thenReturn(movedOn);
        AgentKpqCoordinator.tick(session, 4_000L);

        ArgumentCaptor<Point> anchors = ArgumentCaptor.forClass(Point.class);
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(2)), anchors.capture(), any()), times(3));
        assertEquals(List.of(firstSpot, firstSpot, movedOn), anchors.getAllValues());
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(3)), any(), any()), never());
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(4)), any(), any()), never());
    }

    @Test
    void helpersStayWithOneCollectorWhileTwoEqualNeedsTakeTurnsPickingUp() {
        Character kai = at(run.companion(1, "Kai"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 100, 0);
        Character pip = at(run.companion(3, "Pip"), 500, -240);
        Character tobi = at(run.companion(4, "Tobi"), 0, -1156);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, mira, pip, tobi);
        collecting(session, mira, 0);
        session.member(2).markPassCreated();
        session.member(2).markPassDelivered();
        session.member(2).setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        collecting(session, pip, 0);
        collecting(session, tobi, 0);
        Point atPip = new Point(500, -240);
        Point atTobi = new Point(0, -1156);

        // Both need ten, so the helpers go to Pip; then the two keep passing each other by one coupon.
        AgentKpqCoordinator.tick(session, 2_000L);
        when(pip.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(1);
        AgentKpqCoordinator.tick(session, 3_000L);
        when(tobi.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(1);
        AgentKpqCoordinator.tick(session, 4_000L);
        when(pip.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(2);
        AgentKpqCoordinator.tick(session, 5_000L);
        // Pip is done collecting, so the helpers move on to Tobi.
        session.member(3).markPassCreated();
        session.member(3).setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        AgentKpqCoordinator.tick(session, 6_000L);

        ArgumentCaptor<Point> anchors = ArgumentCaptor.forClass(Point.class);
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(2)), anchors.capture(), any()), times(5));
        assertEquals(List.of(atPip, atPip, atPip, atPip, atTobi), anchors.getAllValues());
    }

    @Test
    void withAHumanLeaderThePassesWaitForTheLastOneAndGoDownTogether() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 10, 0);
        Character pip = at(run.companion(3, "Pip"), 20, 0);
        Character tobi = at(run.companion(4, "Tobi"), 300, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, mira, pip, tobi);
        // A companion run says the pickup line even with coordination chat off.
        session.setMemberCoordinationChatEnabled(false);
        holdingPass(session, mira);
        holdingPass(session, pip);
        collecting(session, tobi, 4);

        AgentKpqCoordinator.tick(session, 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(2)), eq(new Point(300, 0)), any()));
        modes.verify(() -> AgentModeService.startFarmHere(eq(entry(3)), eq(new Point(300, 0)), any()));

        // Tobi hands in his coupons and comes back to the others.
        when(tobi.getPosition()).thenReturn(new Point(30, 0));
        holdingPass(session, tobi);
        AgentKpqCoordinator.tick(session, 3_000L);

        for (int companion = 2; companion <= 4; companion++) {
            AgentRuntimeEntry dropper = entry(companion);
            items.verify(() -> AgentScriptItemActionService.dropItem(
                    dropper, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
        }
        List<MapItem> passes = List.of(pass(21), pass(22), pass(23));
        when(run.map.getDroppedItems()).thenReturn(passes);
        AgentKpqCoordinator.tick(session, 4_000L);
        AgentKpqCoordinator.tick(session, 5_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASS_DROPPED)), never());
    }

    @Test
    void aSecondHumanKeepingItsPassDoesNotHoldBackTheLeadersCue() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character ann = at(run.human(2, "Ann"), 400, 0);
        Character mira = at(run.companion(3, "Mira"), 10, 0);
        Character pip = at(run.companion(4, "Pip"), 20, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, ann, mira, pip);
        session.setMemberCoordinationChatEnabled(false);
        holdingPass(session, mira);
        holdingPass(session, pip);
        // Ann traded her coupons, which releases the companions' passes, but she keeps her own pass in her bag.
        when(run.event.gridCheck(ann)).thenReturn(0);
        when(ann.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);

        AgentKpqCoordinator.tick(session, 2_000L);
        List<MapItem> passes = List.of(pass(21), pass(22));
        when(run.map.getDroppedItems()).thenReturn(passes);
        AgentKpqCoordinator.tick(session, 3_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(3), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
        // Their passes expire where they lie while Ben waits on Ann: he hears about them now.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), times(1));
    }

    @Test
    void anAgentRunForAHumanWithChatOffStillHearsEachPassDrop() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 10, 0);
        Character pip = at(run.companion(3, "Pip"), 20, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, mira, pip);
        session.setMemberCoordinationChatEnabled(false);
        holdingPass(session, mira);
        holdingPass(session, pip);

        AgentKpqCoordinator.tick(session, 2_000L);
        List<MapItem> passes = List.of(pass(21), pass(22));
        when(run.map.getDroppedItems()).thenReturn(passes);
        AgentKpqCoordinator.tick(session, 3_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, PASS_DROPPED));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(pip, PASS_DROPPED));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), never());
    }

    @Test
    void anAgentLeaderStillTakesEachPassAsItComes() {
        Character kai = at(run.companion(1, "Kai"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 10, 0);
        Character pip = at(run.companion(3, "Pip"), 300, 0);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, mira, pip);
        holdingPass(session, mira);
        collecting(session, pip, 4);

        AgentKpqCoordinator.tick(session, 2_000L);

        items.verify(() -> AgentScriptItemActionService.dropItem(
                entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
        assertEquals(AgentKpqMemberState.Role.COMBAT_HELPER, session.member(2).role());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), never());
    }

    @Test
    void theClearLogsWhatEachCompanionDidInStageOne() {
        Character ben = at(run.human(1, "Ben"), 0, 0);
        Character mira = at(run.companion(2, "Mira"), 500, 0);
        Character pip = at(run.companion(3, "Pip"), 20, 0);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.ENTERING, ben, mira, pip);
        // What Mira did before this run is not stage 1's.
        kill(mira, 1);
        entry(2).capabilityStates().require(AgentFieldObservationState.STATE_KEY).attack(1, 0);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            AgentKpqCoordinator.tick(session, 2_000L);
            collecting(session, mira, 0);
            session.member(3).markPassCreated(2_500L);
            holdingPass(session, pip);
            when(mira.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(4);
            AgentKpqCoordinator.tick(session, 3_000L);
            kill(mira, 2);
            kill(mira, 3);
            AgentFieldObservationState field = entry(2).capabilityStates().require(AgentFieldObservationState.STATE_KEY);
            field.attack(1, 0);
            field.attack(1, 0);
            field.attack(0, 1);
            when(mira.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(10);
            AgentKpqCoordinator.tick(session, 4_000L);
            when(run.event.getProperty("1stageclear")).thenReturn("true");
            AgentKpqCoordinator.tick(session, 5_000L);
            AgentKpqCoordinator.tick(session, 6_000L);

            String id = session.sessionId();
            assertEquals(List.of(
                            "KPQ Stage 1 summary: session=" + id + " agent=Mira stageMs=3000 kills=2 coupons=10 "
                                    + "attacks=3 hits=2 misses=1 passAtMs=-1 roleMs={COUPON_COLLECTOR=3000}",
                            "KPQ Stage 1 summary: session=" + id + " agent=Pip stageMs=3000 kills=0 coupons=0 "
                                    + "attacks=0 hits=0 misses=0 passAtMs=500 roleMs={PASS_DELIVERER=3000}"),
                    log.startingWith("KPQ Stage 1 summary: "));
        }
    }

    private static void kill(Character companion, int mobObjectId) {
        entry(companion.getId()).capabilityStates().require(AgentOperationalEventProjectionState.STATE_KEY)
                .record(new AgentMobKilledEvent(companion.getId(), 0L, AgentKpqDefinition.STAGE_1_MAP,
                        9_300_001, mobObjectId, 32, ""));
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private static Character at(Character member, int x, int y) {
        when(member.getPosition()).thenReturn(new Point(x, y));
        return member;
    }

    /** Cloto gave this companion a question of ten coupons, and it is fighting for them. */
    private static void collecting(AgentKpqSession session, Character companion, int have) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(10);
        member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
        member.beginStageMovement(1, 0L);
        when(companion.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(have);
    }

    /** Cloto took this companion's coupons and gave it the pass, which is still in its bag. */
    private void holdingPass(AgentKpqSession session, Character companion) {
        collecting(session, companion, 0);
        AgentKpqMemberState member = session.member(companion.getId());
        member.markPassCreated();
        member.setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        passesHeld.put(companion, 1);
        when(companion.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))
                .thenAnswer(call -> passesHeld.get(companion));
    }

    private static MapItem coupon(int objectId, int x, int y) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.COUPON_ITEM);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getPosition()).thenReturn(new Point(x, y));
        return drop;
    }

    private static MapItem pass(int objectId) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getPosition()).thenReturn(new Point(0, 0));
        return drop;
    }
}
