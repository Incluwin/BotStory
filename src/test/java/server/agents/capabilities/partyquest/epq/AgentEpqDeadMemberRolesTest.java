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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 4: a dead member holds no EPQ role and no stage count. A corpse cannot carry the stage-5 stone, lure
 * stage-2 bugs, catch its stage-4 flowers or drop what it holds, so the work Agent is the lowest living Agent, flowers
 * and lures go to the living, and the stage totals count only what the living hold. A bottle a corpse holds on stage 2
 * reopens the "four bottles secured" mark until four are reachable again.
 */
class AgentEpqDeadMemberRolesTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character pia = run.companion(5, "Pia", AgentEpqDefinition.STAGE_FOUR_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una, pia);
    }

    /** A stock run: for the base fixes' all-mode rules a companion run now plays differently (Steps 2-4). */
    private AgentEpqSession stockSession(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false, phase, ben, mira, tobi, una, pia);
    }

    private void everyoneOn(int mapId) {
        for (Character member : List.of(ben, mira, tobi, una, pia)) run.onMap(member, mapId);
    }

    @Test
    void theWorkAgentIsTheLowestLivingAgent() {
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_FOUR);
        when(mira.getHp()).thenReturn(0);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        assertEquals(tobi.getId(), session.workAgentId());

        when(mira.getHp()).thenReturn(300);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        assertEquals(mira.getId(), session.workAgentId());
    }

    @Test
    void aCorpseOwnsNoFlowerAndALivingAgentTakesItAsItsOwn() {
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_FOUR);
        run.monster(AgentEpqDefinition.STAGE_ONE_MOB); // object ids 30000 and 30001: not flowers
        run.monster(AgentEpqDefinition.STAGE_ONE_MOB);
        Monster flower = run.monster(AgentEpqDefinition.POISON_FLOWER);
        when(flower.getMap()).thenReturn(run.map);
        assertEquals(tobi.getId(), AgentEpqParticipationPolicy.assignedAgent(session.seed(), flower.getObjectId(),
                List.of(2, 3, 4, 5)), "the full roster gives this flower to Tobi");
        assertEquals(mira.getId(), AgentEpqParticipationPolicy.assignedAgent(session.seed(), flower.getObjectId(),
                List.of(2, 4, 5)), "the living roster gives it to Mira");
        when(tobi.getHp()).thenReturn(0); // Tobi lies dead on the map
        when(run.actions.itemCount(mira, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(2);
        try (MockedStatic<AgentEpqCaptureRuntime> capture = mockStatic(AgentEpqCaptureRuntime.class);
             EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            capture.when(() -> AgentEpqCaptureRuntime.ready(flower)).thenReturn(true);
            capture.when(() -> AgentEpqCaptureRuntime.capture(mira, flower)).thenReturn(true);

            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

            capture.verify(() -> AgentEpqCaptureRuntime.capture(mira, flower));
            assertEquals(1, lines.startingWith("EPQ capture:").size());
            assertTrue(lines.startingWith("EPQ capture:").getFirst().contains(" own=true "),
                    lines.startingWith("EPQ capture:")::toString);
        }
        verify(run.map, never()).instanceMapForceRespawn();
    }

    @Test
    void aCorpsesMarblesDoNotCountTowardTheTwenty() {
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_FOUR);
        for (Character agent : List.of(mira, una, pia)) {
            when(run.actions.itemCount(agent, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(6);
        }
        when(tobi.getHp()).thenReturn(0);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.MONSTER_MARBLE)).thenReturn(2);
        when(run.actions.itemCount(una, AgentEpqDefinition.PURIFICATION_MARBLE)).thenReturn(1);
        when(run.actions.npcPosition(una, AgentEpqDefinition.STAGE_NPC)).thenReturn(new Point(48, 1));
        holds(una, InventoryType.ETC, AgentEpqDefinition.MONSTER_MARBLE, 6);
        InventoryGateway gateway = mock(InventoryGateway.class);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventory = mockStatic(AgentInventoryGatewayRuntime.class)) {
            inventory.when(AgentInventoryGatewayRuntime::inventory).thenReturn(gateway);

            AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);
        }
        verify(gateway, never()).dropItem(eq(una), any(), anyShort(), anyShort());
    }

    @Test
    void aCorpseIsNoLurerAndItsBottleDoesNotCount() {
        everyoneOn(AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = stockSession(AgentEpqSession.Phase.STAGE_TWO);
        stageTwoReactors((byte) 3);
        when(tobi.getHp()).thenReturn(0); // the first lurer by today's order
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);

        AgentEpqCoordinator.tickMember(session, run.entry(pia), pia, 2_000L);

        assertFalse(session.stageTwoBottlesSecured(), "three reachable bottles are not four");
        verify(run.map).instanceMapForceRespawn(); // Pia lures in Tobi's place: no bugs up, so she calls them
    }

    @Test
    void aCorpseHoldingASecuredBottleReopensTheBottles() {
        everyoneOn(AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_TWO);
        stageTwoReactors((byte) 3);
        session.secureStageTwoBottles(1_500L);
        when(tobi.getHp()).thenReturn(0);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);

        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);

        assertFalse(session.stageTwoBottlesSecured());
        verify(run.actions, never()).navigate(eq(run.entry(una)), eq(new Point(900, 0)), anyBoolean());
    }

    @Test
    void aCorpseWithoutABottleLeavesTheSecuredMark() {
        everyoneOn(AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_TWO);
        stageTwoReactors((byte) 3);
        session.secureStageTwoBottles(1_500L);
        when(tobi.getHp()).thenReturn(0);

        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);

        assertTrue(session.stageTwoBottlesSecured());
        verify(run.actions).navigate(run.entry(una), new Point(900, 0), true);
    }

    /**
     * A corpse holding a bottle while the living still reach four keeps the secured mark: reopening and re-securing it
     * on every pass would count as progress each time and keep the watchdog from ever seeing a stall.
     */
    @Test
    void aCorpsesBottleWithFourStillReachableLeavesTheMarkAndTheProgressClock() {
        everyoneOn(AgentEpqDefinition.STAGE_TWO_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_TWO);
        stageTwoReactors((byte) 3);
        session.secureStageTwoBottles(1_500L);
        when(tobi.getHp()).thenReturn(0);
        when(run.actions.itemCount(tobi, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
        when(run.actions.itemCount(ben, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1); // the fourth, alive

        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 2_000L);
        AgentEpqCoordinator.tickMember(session, run.entry(una), una, 3_000L);

        assertTrue(session.stageTwoBottlesSecured());
        assertEquals(1_500L, session.lastProgressAtMs(), "no pass counts as progress");
        verify(run.actions, org.mockito.Mockito.times(2)).navigate(run.entry(una), new Point(900, 0), true);
    }

    @Test
    void theStoneCollectorIsTheLivingWorkAgentAndMayLootTheStone() {
        everyoneOn(AgentEpqDefinition.STAGE_FIVE_MAP);
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_FIVE);
        when(mira.getHp()).thenReturn(0);
        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertTrue(AgentEpqCoordinator.mayCollectStageFiveStone(session, tobi.getId()));
        assertFalse(AgentEpqCoordinator.mayCollectStageFiveStone(session, mira.getId()));
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(tobi, AgentEpqDefinition.MAGIC_STONE));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, AgentEpqDefinition.MAGIC_STONE));
    }

    private void stageTwoReactors(byte spineState) {
        Reactor spine = reactor(new Point(-400, 0), spineState);
        Reactor pond = reactor(new Point(400, 0), (byte) 0);
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        when(run.actions.portalPosition(any(), eq(3))).thenReturn(new Point(900, 0));
    }

    private static Reactor reactor(Point position, byte state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getState()).thenReturn(state);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        return reactor;
    }

    private static void holds(Character member, InventoryType type, int itemId, int quantity) {
        Inventory inventory = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 1);
        when(inventory.findById(itemId)).thenReturn(item);
        when(member.getInventory(type)).thenReturn(inventory);
    }
}
