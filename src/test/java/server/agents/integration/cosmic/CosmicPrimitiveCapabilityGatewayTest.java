package server.agents.integration.cosmic;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.movement.AgentFarmAnchorStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.capabilities.supplies.AgentSupplyInventorySnapshot;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;
import server.quest.Quest;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CosmicPrimitiveCapabilityGatewayTest {
    @Test
    void ordinaryPreferredGrindReleasesAnAuthoredCombatAnchor() {
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(105100400);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, agent, null);
        AgentFarmAnchorStateRuntime.setFarmAnchor(
                entry, new Point(520, 258), agent.getMapId());
        AgentModeStateRuntime.startGrinding(entry);

        CosmicPrimitiveCapabilityGateway.INSTANCE.grind(
                entry, Set.of(6400009), Set.of(8830009));

        assertFalse(AgentFarmAnchorStateRuntime.hasFarmAnchor(entry));
    }

    @Test
    void hpPotionCountIsWhatTheSupplySnapshotCountsAndUnknownWithoutACharacter() {
        Character agent = mock(Character.class);
        try (MockedStatic<AgentSupplyInventorySnapshot> supplies = mockStatic(AgentSupplyInventorySnapshot.class)) {
            supplies.when(() -> AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.HP_POTION))
                    .thenReturn(12);

            assertEquals(12, CosmicPrimitiveCapabilityGateway.INSTANCE.hpPotionCount(agent));
        }
        assertEquals(-1, CosmicPrimitiveCapabilityGateway.INSTANCE.hpPotionCount(null));
    }

    @Test
    void aDropLandingIsPreviewedByTheMapsOwnPlacementOfAPlayersDrop() {
        // Stage 1 of KPQ, where the ramp over the leader's foothold runs within 85 px of it.
        MapleMap stageOne = AgentNavigationMapLoader.loadMapGeometry(103000800);
        stageOne.generateMapDropRangeCache();
        Character dropper = mock(Character.class);
        when(dropper.getMap()).thenReturn(stageOne);

        // Live (2026-10-02, kpq-fea2a83b): dropped at (470,111), 6 px from the leader, the pass landed on the ramp.
        assertEquals(new Point(470, 28),
                CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(dropper, new Point(470, 111)));
        // 16 px further left the ramp is high enough over him: it lands on his foothold.
        assertEquals(new Point(454, 112),
                CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(dropper, new Point(454, 112)));
        Point from = new Point(470, 111);
        assertEquals(stageOne.calcDropPos(new Point(from), new Point(from)),
                CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(dropper, from));
        // Past the map's drop bounds the map clamps its own copy, never the caller's point.
        Point outside = new Point(100_000, 111);
        Point clamped = CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(dropper, outside);
        assertEquals(new Point(100_000, 111), outside);
        assertTrue(clamped.x < 100_000, clamped::toString);

        assertNull(CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(dropper, null));
        assertNull(CosmicPrimitiveCapabilityGateway.INSTANCE.predictDropLanding(mock(Character.class),
                new Point(470, 111)));
    }

    @Test
    void questProgressReadsOrdinaryProgressKey() {
        Character agent = mock(Character.class);
        QuestStatus status = mock(QuestStatus.class);
        when(agent.getQuestNoAdd(any(Quest.class))).thenReturn(status);
        when(status.getProgress(3300001)).thenReturn("7");

        assertEquals(7, CosmicPrimitiveCapabilityGateway.INSTANCE
                .questProgress(agent, 2330, 3300001));
    }

    @Test
    void questProgressResolvesInfoNumberToSlotZero() {
        Character agent = mock(Character.class);
        QuestStatus status = mock(QuestStatus.class);
        when(agent.getQuestNoAdd(any(Quest.class))).thenReturn(status);
        when(status.getInfoNumber()).thenReturn((short) 2314);
        when(status.getProgress(0)).thenReturn("1");

        assertEquals(1, CosmicPrimitiveCapabilityGateway.INSTANCE
                .questProgress(agent, 2314, 2314));
    }
}
