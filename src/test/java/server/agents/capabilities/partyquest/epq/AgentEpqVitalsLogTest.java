package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.life.Monster;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 1: the rerun's evidence lines, logged in every mode and changing nothing. Each member's HP and potions
 * after every phase change and at release ('EPQ vitals:'), what a member held when it went down ('EPQ down detail:'),
 * where each objective drop was made and where it lands ('EPQ drop:'), and every successful flower capture ('EPQ
 * capture:'). The lines the base smoke already reads stay byte for byte as they were.
 */
class AgentEpqVitalsLogTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.ENTRANCE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.ENTRANCE_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private void vitals() {
        hp(ben, 480, 500, 3, 2);
        hp(mira, 50, 4_400, 113, 40);
        hp(tobi, 2_000, 2_100, 0, 7);
        hp(una, 1_000, 1_000, 25, 25);
    }

    private void hp(Character member, int hp, int maxHp, int hpPots, int mpPots) {
        when(member.getHp()).thenReturn(hp);
        when(member.getCurrentMaxHp()).thenReturn(maxHp);
        when(run.actions.hpPotionCount(member)).thenReturn(hpPots);
        when(run.actions.mpPotionCount(member)).thenReturn(mpPots);
    }

    private static final String MEMBERS = "{Ben:480/500 hpPots=3 mpPots=2, Mira:50/4400 hpPots=113 mpPots=40, "
            + "Tobi:2000/2100 hpPots=0 mpPots=7, Una:1000/1000 hpPots=25 mpPots=25}";

    @Test
    void aStockRunLogsEveryMembersVitalsRightAfterThePhaseLine() {
        vitals();
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 4_000L);
            AgentEpqCoordinator.tickSession(session, 5_000L); // no change, no line

            assertEquals(List.of(
                    "EPQ phase: session=" + session.sessionId()
                            + " PREPARING->ENTERING phaseMs=3000 runMs=3000 companionRun=false",
                    "EPQ vitals: session=" + session.sessionId() + " phase=ENTERING members=" + MEMBERS),
                    lines.startingWith("EPQ "));
        }
    }

    @Test
    void aCompanionRunLogsItsVitalsBetweenThePhaseAndThePotionsLines() {
        vitals();
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 4_000L);

            assertEquals(List.of(
                    "EPQ phase: session=" + session.sessionId()
                            + " PREPARING->ENTERING phaseMs=3000 runMs=3000 companionRun=true",
                    "EPQ vitals: session=" + session.sessionId() + " phase=ENTERING members=" + MEMBERS,
                    "EPQ potions: session=" + session.sessionId() + " start={Mira:113/40, Tobi:0/7, Una:25/25}"),
                    lines.startingWith("EPQ "), "the existing lines are byte for byte as they were");
        }
    }

    @Test
    void anOfflineMemberIsLeftOutOfTheVitals() {
        vitals();
        run.offline(una);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 4_000L);

            assertEquals(List.of("EPQ vitals: session=" + session.sessionId() + " phase=ENTERING members="
                            + "{Ben:480/500 hpPots=3 mpPots=2, Mira:50/4400 hpPots=113 mpPots=40, "
                            + "Tobi:2000/2100 hpPots=0 mpPots=7}"),
                    lines.startingWith("EPQ vitals:"));
        }
    }

    @Test
    void aMemberDownLogsWhatItHeldOncePerDeath() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        hp(tobi, 0, 4_400, 0, 12);
        when(tobi.getPosition()).thenReturn(new Point(310, 150));
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MAGIC_STONE)).thenReturn(1);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(6);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(2);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 3_000L); // still down: nothing more

            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ member down: session=" + id + " member=3 type=AGENT map=930000600 phase=BOSS",
                    "EPQ down detail: session=" + id + " member=Tobi(3) maxHp=4400 hpPots=0 mpPots=12 "
                            + "items={bottles=1, stone=1, marbles=6, purifiers=2} map=930000600 at=(310,150)"),
                    lines.startingWith("EPQ ").stream()
                            .filter(line -> line.startsWith("EPQ member down:") || line.startsWith("EPQ down detail:"))
                            .toList());
        }
    }

    @Test
    void aNonLeaderMarbleDropNearEllinLogsWhereItWasMadeAndWhereItLands() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.npcPosition(tobi, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20, 20, 20, 0);
        holds(tobi, AgentEpqDefinition.MONSTER_MARBLE, 20);
        when(run.actions.predictDropLanding(tobi, new Point(0, 0))).thenReturn(new Point(12, 1));
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = inventoryGateway();
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of("EPQ drop: session=" + session.sessionId()
                            + " key=stage4-handoff member=Tobi at=(0,0) target=(0,0) landing=(12,1)"),
                    lines.startingWith("EPQ drop:"));
        }
    }

    @Test
    void anUnknownLandingIsLoggedAsNone() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.npcPosition(tobi, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20, 20, 20, 0);
        holds(tobi, AgentEpqDefinition.MONSTER_MARBLE, 20);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = inventoryGateway();
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of("EPQ drop: session=" + session.sessionId()
                            + " key=stage4-handoff member=Tobi at=(0,0) target=(0,0) landing=none"),
                    lines.startingWith("EPQ drop:"));
        }
    }

    @Test
    void aFailedDropLogsNothing() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        when(run.actions.npcPosition(tobi, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(20, 20, 20, 0);
        // No inventory to drop from: the drop fails.
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ drop:"));
        }
    }

    @Test
    void aStageTwoBottleDropLogsItsKey() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
        Reactor spine = reactor(new Point(-40, 0));
        Reactor pond = reactor(new Point(400, 0));
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        when(tobi.getPosition()).thenReturn(new Point(-40, 0));
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
        holds(tobi, AgentEpqDefinition.PURIFIED_POISON, 1);
        when(run.actions.predictDropLanding(tobi, new Point(-40, 0))).thenReturn(new Point(-38, 0));
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = inventoryGateway();
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of("EPQ drop: session=" + session.sessionId()
                            + " key=stage2-bottle-dropped member=Tobi at=(-40,0) target=(-40,0) landing=(-38,0)"),
                    lines.startingWith("EPQ drop:"));
        }
    }

    @Test
    void releaseLogsTheVitalsInAStockRunWithoutThePotionsLine() {
        vitals();
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqTerminationService.release(session, "stopped by operator", 4_000L, false);

            assertEquals(List.of("EPQ vitals: session=" + session.sessionId() + " phase=STAGE_ONE members="
                    + MEMBERS), lines.startingWith("EPQ vitals:"));
            assertEquals(List.of(), lines.startingWith("EPQ potions:"));
        }
    }

    @Test
    void releaseLogsTheVitalsInACompanionRunAfterThePotionsLine() {
        vitals();
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqTerminationService.release(session, "stopped by operator", 4_000L, false);

            List<String> evidence = lines.startingWith("EPQ ");
            assertEquals("EPQ potions: session=" + session.sessionId()
                    + " start= end={Mira:113/40, Tobi:0/7, Una:25/25}", evidence.get(0));
            assertEquals("EPQ vitals: session=" + session.sessionId() + " phase=STAGE_ONE members=" + MEMBERS,
                    evidence.get(1));
        }
    }

    @Test
    void aMemberThatCannotBeReadGivesVitalsUnavailableAndNeverThrows() {
        vitals();
        when(tobi.getCurrentMaxHp()).thenThrow(new IllegalStateException("character unloading"));
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            assertDoesNotThrow(() -> AgentEpqTerminationService.release(session, "stopped by operator", 4_000L,
                    false));

            assertEquals(List.of("EPQ vitals: session=" + session.sessionId()
                    + " phase=STAGE_ONE members=vitals unavailable"), lines.startingWith("EPQ vitals:"));
            assertEquals(1, lines.startingWith("EPQ session released:").size());
        }
    }

    @Test
    void aSuccessfulCaptureLogsOneCaptureLine() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster flower = run.monster(AgentEpqDefinition.POISON_FLOWER);
        when(flower.getMap()).thenReturn(run.map);
        Character owner = owner(session, flower);
        when(run.actions.itemCount(owner, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(3);
        when(run.actions.itemCount(owner, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(4, 4, 5);
        when(run.actions.itemCount(ben, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(2);
        try (MockedStatic<AgentEpqCaptureRuntime> capture = mockStatic(AgentEpqCaptureRuntime.class);
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            capture.when(() -> AgentEpqCaptureRuntime.ready(flower)).thenReturn(true);
            capture.when(() -> AgentEpqCaptureRuntime.capture(owner, flower)).thenReturn(true);

            AgentEpqCoordinator.tickMember(session, run.entry(owner), owner, 2_000L);

            assertEquals(List.of("EPQ capture: session=" + session.sessionId() + " member=" + owner.getName()
                            + " flower=" + flower.getObjectId() + " own=true marbles=7 map=930000400"),
                    lines.startingWith("EPQ capture:"));
        }
    }

    @Test
    void aFailedCaptureLogsNothing() {
        for (Character member : List.of(ben, mira, tobi, una)) run.onMap(member, AgentEpqDefinition.STAGE_FOUR_MAP);
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        Monster flower = run.monster(AgentEpqDefinition.POISON_FLOWER);
        when(flower.getMap()).thenReturn(run.map);
        Character owner = owner(session, flower);
        when(run.actions.itemCount(owner, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(3);
        try (MockedStatic<AgentEpqCaptureRuntime> capture = mockStatic(AgentEpqCaptureRuntime.class);
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            capture.when(() -> AgentEpqCaptureRuntime.ready(flower)).thenReturn(true);
            capture.when(() -> AgentEpqCaptureRuntime.capture(owner, flower)).thenReturn(false);

            AgentEpqCoordinator.tickMember(session, run.entry(owner), owner, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ capture:"));
        }
    }

    /** The Agent the stock assignment gives this flower to. */
    private Character owner(AgentEpqSession session, Monster flower) {
        int id = AgentEpqParticipationPolicy.assignedAgent(session.seed(), flower.getObjectId(), List.of(2, 3, 4));
        return id == 2 ? mira : id == 3 ? tobi : una;
    }

    private void holds(Character member, int itemId, int quantity) {
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 1);
        when(etc.findById(itemId)).thenReturn(item);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
    }

    private static MockedStatic<AgentInventoryGatewayRuntime> inventoryGateway() {
        MockedStatic<AgentInventoryGatewayRuntime> inventory = mockStatic(AgentInventoryGatewayRuntime.class);
        InventoryGateway gateway = mock(InventoryGateway.class);
        inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(gateway);
        return inventory;
    }

    private static Reactor reactor(Point position) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        return reactor;
    }
}
