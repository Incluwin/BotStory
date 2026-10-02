package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatObjectiveTargetStateRuntime;
import server.agents.capabilities.combat.AgentCombatVariationRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
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
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a companion run's stage-1 holder drops its pass for the human leader. The map lands a player's drop on the
 * first foothold under a point 85 px over the dropper: live (2026-10-02, kpq-fea2a83b) Faro723 dropped its pass 6 px
 * right of the leader at (464,111) and it landed at (470,28), on the ramp 83 px over him, out of his reach; only the
 * stage-1 patience recovery cleared the stage, 73 s after the hand-in started instead of about 25 s. Now the drop is
 * previewed with the map's own placement, and a holder whose pass would be caught steps aside along his foothold to
 * where it lands on it. The map here is stage 1's own geometry, through the coordinator's real gateway.
 */
class PassDropLandingTest {
    private static final long DROPPED_AT_MS = 2_000L;
    /** Where the leader and Faro723 stood live, on his foothold under the ramp. */
    private static final Point LIVE_LEADER = new Point(464, 111);
    private static final Point LIVE_DROPPER = new Point(470, 111);
    private static final String PASSES_AT_FEET = "Ben, the passes are at your feet. pick them up and talk to cloto.";

    private static MapleMap stageOne;

    private KpqRunFixture run;
    private MockedStatic<AgentGrindLootStateRuntime> loot;
    private MockedStatic<AgentScriptItemActionService> items;
    private MockedStatic<AgentModeService> modes;
    private MockedStatic<AgentFarmAnchorStateRuntime> farmAnchors;
    private MockedStatic<AgentCombatVariationRuntime> variation;
    private MockedStatic<AgentCombatObjectiveTargetStateRuntime> objective;
    private MockedStatic<AgentModeStateRuntime> modeState;
    private MockedStatic<AgentMapGatewayRuntime> maps;
    private MockedStatic<AgentInventoryGatewayRuntime> inventories;
    private final MapGateway mapGateway = mock(MapGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final Map<Character, Integer> passesHeld = new HashMap<>();
    /** What lies on stage 1's floor. */
    private final List<MapItem> floor = new CopyOnWriteArrayList<>();
    private long clockMs;
    private int nextObjectId = 60;
    private Character liriki;

    @BeforeAll
    static void loadStageOne() {
        stageOne = AgentNavigationMapLoader.loadMapGeometry(AgentKpqDefinition.STAGE_1_MAP);
        stageOne.generateMapDropRangeCache();
    }

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
        maps = mockStatic(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        inventories = mockStatic(AgentInventoryGatewayRuntime.class);
        inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        when(run.map.getDroppedItems()).thenAnswer(call -> List.copyOf(floor));
        stageOneGeometry();
        // A pass that is dropped leaves the bag and lands where the map's own drop placement puts it.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), eq((short) 1)))
                .thenAnswer(call -> {
                    AgentRuntimeEntry dropper = call.getArgument(0);
                    for (Character holder : List.copyOf(passesHeld.keySet())) {
                        if (entry(holder.getId()) != dropper) continue;
                        passesHeld.merge(holder, -1, Integer::sum);
                        Point from = new Point(holder.getPosition());
                        floor.add(pass(holder, run.map.calcDropPos(from, from)));
                    }
                    return true;
                });
    }

    @AfterEach
    void tearDown() {
        inventories.close();
        maps.close();
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
    void aPassTheRampOverTheLeaderWouldCatchIsDroppedFromWhereItLandsOnHisFoothold() {
        Character ben = at(run.human(1, "Ben"), LIVE_LEADER);
        Character faro = at(run.companion(2, "Faro723"), LIVE_DROPPER);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        // Where it stands, 6 px from him, its pass would land on the ramp: it does not drop, and steps aside to the
        // nearest spot on his foothold from which a drop lands on it, wherever within 10 px of it it stops.
        tick(session, DROPPED_AT_MS);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        Point spot = new Point(454, 112);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), spot, true));
        for (int x = spot.x - 10; x <= spot.x + 10; x++) {
            Point from = new Point(x, stageOne.getPointBelow(new Point(x, 100)).y);
            assertEquals(from, stageOne.calcDropPos(new Point(from), new Point(from)), "caught from " + from);
        }

        at(faro, spot);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS + 1_000L);

            // On his foothold beside him, where the preview said, and within his reach.
            items.verify(() -> AgentScriptItemActionService.dropItem(
                    entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
            assertEquals(List.of(new Point(454, 112)), floor.stream().map(MapItem::getPosition).toList());
            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=1 agent=Faro723 "
                            + "reason=beside passes=1 dx=-10 dy=1 sameFoothold=true leaderFooting=GROUND spot=feet "
                            + "predicted=(454,112) landed=[(454,112):dx=-10:dy=1:passes=1:oid=60:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
        // 10 px from him it is still at his feet, and he is told so.
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), times(1));
    }

    @Test
    void aLeaderFurtherUpUnderTheRampIsToldThePassLiesJustToHisLeft() {
        // Under the ramp's middle the nearest clear spot is 45 px left of him, by the live leader's spot.
        Point leader = groundUnderRamp(500);
        Character ben = at(run.human(1, "Ben"), leader);
        Character faro = at(run.companion(2, "Faro723"), groundUnderRamp(506));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);
        assertEquals(new Point(500, 109), leader);

        tick(session, DROPPED_AT_MS);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        Point spot = new Point(455, 112);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), spot, true));

        at(faro, spot);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS + 1_000L);

            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=1 agent=Faro723 "
                            + "reason=beside passes=1 dx=-45 dy=3 sameFoothold=true leaderFooting=GROUND spot=moved "
                            + "predicted=(455,112) landed=[(455,112):dx=-45:dy=3:passes=1:oid=60:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
        // On his floor, in his reach, and he is told where: not at his feet.
        assertEquals(List.of(spot), floor.stream().map(MapItem::getPosition).toList());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(),
                eq("Ben, the passes are just to your left. pick them up and talk to cloto.")), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(PASSES_AT_FEET)), never());
    }

    @Test
    void aCatchNarrowerThanAStepUnderTheHolderIsNotDroppedIntoButSteppedOutOf() {
        // A flat floor at y 0 under a ledge 40 px up only 3 px wide, x 2 to 4: drops from his spot and 5 px either
        // side of it land on the floor, but Faro, 3 px from him, would drop onto the ledge.
        geometry(point -> new Point(point.x, point.x >= 2 && point.x <= 4 && point.y <= -40 ? -40 : 0));
        Character ben = at(run.human(1, "Ben"), new Point(0, 0));
        Character faro = at(run.companion(2, "Faro723"), new Point(3, 0));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        // It steps to the nearest spot whose stops all land on the floor and leave out where it stands, and drops
        // there; before, it dropped where it stood, onto the ledge.
        tick(session, DROPPED_AT_MS);
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
        Point spot = new Point(-10, 0);
        modes.verify(() -> AgentModeService.startMoveTo(entry(2), spot, true));

        at(faro, spot);
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS + 1_000L);

            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=1 agent=Faro723 "
                            + "reason=beside passes=1 dx=-10 dy=0 sameFoothold=true leaderFooting=GROUND spot=feet "
                            + "predicted=(-10,0) landed=[(-10,0):dx=-10:dy=0:passes=1:oid=60:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
    }

    @Test
    void onItsWayToALeaderWithNothingOverHimItWalksOntoHisSpotAfterThreePreviewsAndNoSearch() {
        Point leader = groundUnderRamp(580);
        Character ben = at(run.human(1, "Ben"), leader);
        Character faro = at(run.companion(2, "Faro723"), groundUnderRamp(640));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        tick(session, DROPPED_AT_MS);

        modes.verify(() -> AgentModeService.startMoveTo(entry(2), leader, true));
        // Its own drop, and drops from his spot and 10 px either side of it; the search along his foothold would
        // read about 30 more.
        verify(run.map, times(4)).calcDropPos(any(), any());
        items.verify(() -> AgentScriptItemActionService.dropItem(any(), any(), anyInt(), anyShort()), never());
    }

    @Test
    void withNothingOverTheLeaderThePassIsDroppedAtHisFeetAtOnceAsBefore() {
        // Cloto's floor, with nothing within 85 px over it.
        Point leader = ground(640);
        Point dropper = ground(646);
        Character ben = at(run.human(1, "Ben"), leader);
        Character faro = at(run.companion(2, "Faro723"), dropper);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS);

            items.verify(() -> AgentScriptItemActionService.dropItem(
                    entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
            assertEquals(List.of(dropper), floor.stream().map(MapItem::getPosition).toList());
            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=1 agent=Faro723 "
                            + "reason=beside passes=1 dx=6 dy=" + (dropper.y - leader.y) + " sameFoothold=true "
                            + "leaderFooting=GROUND spot=feet predicted=" + text(dropper) + " landed=[" + text(dropper)
                            + ":dx=6:dy=" + (dropper.y - leader.y) + ":passes=1:oid=60:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
        modes.verify(() -> AgentModeService.startMoveTo(eq(entry(2)), any(), anyBoolean()), never());
    }

    @Test
    void withAPlatformOverTheWholeSearchThePassIsDroppedAtHisFeetAsBeforeAndTheLineSaysSo() {
        // A flat floor at y 0 under a platform at y -40 as wide as the map: every drop from the floor lands on it.
        geometry(point -> new Point(point.x, point.y <= -40 ? -40 : 0));
        Character ben = at(run.human(1, "Ben"), new Point(0, 0));
        Character faro = at(run.companion(2, "Faro723"), new Point(6, 0));
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS);

            items.verify(() -> AgentScriptItemActionService.dropItem(
                    entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
            assertEquals(List.of("KPQ pass drop: session=" + session.sessionId() + " stage=1 agent=Faro723 "
                            + "reason=beside passes=1 dx=6 dy=0 sameFoothold=true leaderFooting=GROUND spot=none "
                            + "predicted=(6,-40) landed=[(6,-40):dx=6:dy=-40:passes=1:oid=60:ffa=false]"),
                    log.startingWith("KPQ pass drop: "));
        }
        modes.verify(() -> AgentModeService.startMoveTo(eq(entry(2)), any(), anyBoolean()), never());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(),
                eq("Ben, the passes landed up above you. hop up, grab them and talk to cloto.")), times(1));
    }

    @Test
    void aStockHumanLedRunStillDropsBesideTheLeaderAtOnceWithoutAPreview() {
        Character ben = at(run.human(1, "Ben"), LIVE_LEADER);
        Character faro = at(run.companion(2, "Faro723"), LIVE_DROPPER);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, faro, delivered());
        holdingPass(session, faro);

        assertDropsAtOnceWithoutAPreview(session);
    }

    @Test
    void anAllAgentRunStillDropsBesideItsLeaderAtOnceWithoutAPreview() {
        Character kai = at(run.companion(5, "Kai"), LIVE_LEADER);
        Character faro = at(run.companion(2, "Faro723"), LIVE_DROPPER);
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, kai, faro, delivered());
        holdingPass(session, faro);
        holds(kai, 0);

        assertDropsAtOnceWithoutAPreview(session);
    }

    @Test
    void aCompanionRunAnAgentLeadsStillDropsBesideItsLeaderAtOnceWithoutAPreview() {
        Character kai = at(run.companion(5, "Kai"), LIVE_LEADER);
        Character faro = at(run.companion(2, "Faro723"), LIVE_DROPPER);
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, kai, faro, delivered());
        holdingPass(session, faro);
        holds(kai, 0);

        assertDropsAtOnceWithoutAPreview(session);
    }

    /** Today's stock drop: within NEAR_PX of the leader, on the first tick, with no preview and no drop line. */
    private void assertDropsAtOnceWithoutAPreview(AgentKpqSession session) {
        // The leader picking passes up is not this test's business.
        items.when(() -> AgentScriptItemActionService.dropItem(
                        any(), eq(InventoryType.ETC), eq(AgentKpqDefinition.PASS_ITEM), eq((short) 1)))
                .thenAnswer(call -> {
                    passesHeld.replaceAll((holder, held) -> entry(holder.getId()) == call.getArgument(0)
                            ? held - 1 : held);
                    return true;
                });
        try (KpqLogLines log = new KpqLogLines(AgentKpqCoordinator.class)) {
            tick(session, DROPPED_AT_MS);

            items.verify(() -> AgentScriptItemActionService.dropItem(
                    entry(2), InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1), times(1));
            assertEquals(List.of(), log.startingWith("KPQ pass drop: "));
        }
        verify(run.map, never()).calcDropPos(any(), any());
        modes.verify(() -> AgentModeService.startMoveTo(eq(entry(2)), any(), anyBoolean()), never());
        assertTrue(session.member(2).passDelivered());
    }

    private void tick(AgentKpqSession session, long nowMs) {
        clockMs = nowMs;
        AgentKpqCoordinator.tick(session, nowMs);
    }

    /** The fixture's map stands for stage 1's own: its foothold lookup and its placement of a player's drop. */
    private void stageOneGeometry() {
        doAnswer(call -> stageOne.getPointBelow(new Point(call.<Point>getArgument(0))))
                .when(run.map).getPointBelow(any());
        doAnswer(call -> stageOne.calcDropPos(call.getArgument(0), call.getArgument(1)))
                .when(run.map).calcDropPos(any(), any());
    }

    /**
     * The fixture's map stands for one whose ground under a point is {@code pointBelow}'s, placing a player's drop as
     * MapleMap.calcDropPos does: on the first foothold under a point 85 px over the dropper.
     */
    private void geometry(UnaryOperator<Point> pointBelow) {
        doAnswer(call -> pointBelow.apply(new Point(call.<Point>getArgument(0)))).when(run.map).getPointBelow(any());
        doAnswer(call -> {
            Point from = call.getArgument(0);
            return pointBelow.apply(new Point(from.x, from.y - 85));
        }).when(run.map).calcDropPos(any(), any());
    }

    private static Point ground(int x) {
        return stageOne.getPointBelow(new Point(x, 0));
    }

    /** The floor under the ramp at {@code x}, looked for from under the ramp. */
    private static Point groundUnderRamp(int x) {
        return stageOne.getPointBelow(new Point(x, 100));
    }

    private static String text(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private static AgentRuntimeEntry entry(int companionId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(companionId);
    }

    private static Character at(Character member, Point position) {
        when(member.getPosition()).thenReturn(new Point(position));
        return member;
    }

    /**
     * Cloto took this companion's coupons and gave it the pass, which is still in its bag; the run's third member,
     * Liriki, dropped its own pass earlier and the leader picked it up.
     */
    private void holdingPass(AgentKpqSession session, Character companion) {
        AgentKpqMemberState member = passCreated(session, companion);
        member.setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
        holds(companion, 1);
        AgentKpqMemberState third = passCreated(session, liriki);
        third.markPassDelivered();
        third.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        holds(liriki, 0);
    }

    private static AgentKpqMemberState passCreated(AgentKpqSession session, Character companion) {
        AgentKpqMemberState member = session.member(companion.getId());
        member.markQuestionRequested();
        member.setCouponTarget(10);
        member.beginStageMovement(1, 0L);
        member.markPassCreated();
        return member;
    }

    /** The run's third member, a companion standing well away from the hand-over. */
    private Character delivered() {
        liriki = at(run.companion(3, "Liriki"), new Point(300, 100));
        return liriki;
    }

    private void holds(Character member, int passes) {
        passesHeld.put(member, passes);
        when(member.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenAnswer(call -> passesHeld.get(member));
    }

    /** One pass {@code owner} just dropped, as the map keeps it where it landed. */
    private MapItem pass(Character owner, Point landed) {
        int ownerId = owner.getId();
        MapItem drop = mock(MapItem.class);
        Item stack = mock(Item.class);
        when(stack.getQuantity()).thenReturn((short) 1);
        when(drop.getItem()).thenReturn(stack);
        when(drop.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(drop.getObjectId()).thenReturn(nextObjectId++);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getDropTime()).thenReturn(clockMs);
        when(drop.getPosition()).thenReturn(new Point(landed));
        when(drop.isPlayerDrop()).thenReturn(true);
        return drop;
    }
}
