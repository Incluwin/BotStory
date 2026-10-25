package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.PacketGateway;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-25 live (OPQ run 5): with Pqfriend leading, the companions broke the twenty clouds, left their pieces in five
 * stacks by the hand-off point and waited. The offering of twenty at Eak's light was left to the human leader and
 * nobody told him; the entrance took eleven minutes (84.5 s in run 4). Now the lowest-id companion in the entrance
 * gathers the stacks, offers twenty at the light, and calls the leader once Eak stands there. An Agent leader still
 * carries its own clouds.
 */
class AgentOpqConductedEntranceTest {
    private static final int CLOUD = AgentOpqDefinition.CLOUD_PIECE;
    private static final Point HANDOFF = new Point(170, 96);
    private static final Point EAK_LIGHT = new Point(377, 66);

    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final PacketGateway packets = mock(PacketGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<MapObject> lying = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockedStatic<AgentPacketGatewayRuntime> packetRuntime = mockStatic(AgentPacketGatewayRuntime.class);
        packetRuntime.when(AgentPacketGatewayRuntime::packets).thenReturn(packets);
        statics.add(packetRuntime);
        MockedStatic<AgentInventoryGatewayRuntime> inventoryRuntime = mockStatic(AgentInventoryGatewayRuntime.class);
        inventoryRuntime.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        statics.add(inventoryRuntime);
        when(run.map.getItems()).thenReturn(lying);
        when(run.actions.groundPoint(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        run.entries.values().forEach(entry -> when(entry.capabilityStates())
                .thenReturn(new AgentCapabilityStateRegistry()));
        for (Character member : List.of(ben, mira, kes, pel, hal, gus)) stand(member, HANDOFF);
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        run.close();
    }

    private AgentOpqSession benLeads() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.ENTRANCE, ben, mira, kes, pel, hal, gus);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void stand(Character member, Point at) {
        when(member.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(member.getPosition()).thenReturn(new Point(at));
    }

    private void holds(Character member, int quantity) {
        when(member.getItemQuantity(CLOUD, false)).thenReturn(quantity);
        Inventory etc = mock(Inventory.class);
        Item item = mock(Item.class);
        when(item.getQuantity()).thenReturn((short) quantity);
        when(item.getPosition()).thenReturn((short) 3);
        when(etc.findById(CLOUD)).thenReturn(quantity > 0 ? item : null);
        when(member.getInventory(InventoryType.ETC)).thenReturn(etc);
    }

    private void cloudsLying(int objectId, Point at) {
        MapItem drop = mock(MapItem.class);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(CLOUD);
        when(drop.getPosition()).thenReturn(new Point(at));
        lying.add(drop);
    }

    private void tick(AgentOpqSession session, Character companion, long nowMs) {
        AgentOpqCoordinator.tickMember(session, run.entries.get(companion.getId()), companion, nowMs);
    }

    @Test
    void theLowestIdCompanionInTheEntranceCarriesForAHumanLeader() {
        AgentOpqSession session = benLeads();

        assertEquals(mira.getId(), AgentOpqCoordinator.entranceCarrier(session));

        when(mira.getMapId()).thenReturn(AgentOpqDefinition.CENTER_MAP); // gone ahead: Kes takes the clouds over
        assertEquals(kes.getId(), AgentOpqCoordinator.entranceCarrier(session));
    }

    @Test
    void anAgentLeaderStillCarriesItsOwnClouds() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.ENTRANCE,
                mira, kes, pel, hal, gus, ben);

        assertEquals(mira.getId(), AgentOpqCoordinator.entranceCarrier(session));
    }

    @Test
    void theOthersLeaveTheirStacksAndTheCarrierGathersThem() {
        AgentOpqSession session = benLeads();
        holds(kes, 4);
        holds(mira, 3);
        cloudsLying(51, HANDOFF);

        tick(session, kes, 10_000L);
        tick(session, mira, 10_000L);

        verify(inventory).dropItem(kes, InventoryType.ETC, (short) 3, (short) 4);
        verify(mira).pickupItem(lying.get(0));
        verify(inventory, never()).dropItem(eq(mira), any(), anyShort(), anyShort());
    }

    @Test
    void theCarrierOffersTwentyAtTheLightThenCallsTheLeaderOnceEakStandsThere() {
        AgentOpqSession session = benLeads();
        holds(mira, 20);
        stand(mira, EAK_LIGHT);

        tick(session, mira, 10_000L);

        verify(inventory).dropItem(mira, InventoryType.ETC, (short) 3, (short) 20);
        verify(packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());

        holds(mira, 0);
        when(run.actions.npcPosition(mira, AgentOpqDefinition.EAK_NPC)).thenReturn(new Point(380, 66));
        tick(session, mira, 20_000L);
        tick(session, mira, 30_000L);

        verify(packets, times(1)).broadcastChatText(mira,
                "Ben, the clouds are in. eak's up in the gazebo, go talk to him.", false, 0);
    }
}
