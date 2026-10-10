package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.contracts.AgentDisposition;
import server.agents.capabilities.contracts.AgentInventoryReservation;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentInventoryReservationGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.inventory.KeptCopyFixture.OVERALL;
import static server.agents.capabilities.inventory.KeptCopyFixture.overall;
import static server.agents.capabilities.inventory.KeptCopyFixture.warriorWithBag;

/**
 * The equipment capability's reservations hold the kept copy itself, not every copy of its id. Every other reader of
 * the reservation ledger keeps its old meaning: id reservations of other capabilities (quest and loot objectives, a
 * public sale offer) still protect every copy, and the id view still counts the kept copies.
 */
class AgentInventoryReservationKeptCopyTest {
    private static final long NOW = 1_000L;

    @Test
    void keptCopyProtectsOnlyItself() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept, weaker));

        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, kept, NOW), "the kept copy is reserved");
        assertTrue(AgentInventoryReservationRuntime.mayConsume(entry, weaker, NOW),
                "a weaker copy of the same id is not reserved by the kept copy");
        assertEquals(List.of(weaker), AgentInventoryReservationRuntime.unreservedItems(entry, List.of(kept, weaker), NOW));
    }

    @Test
    void giveAndTradePathsSeeTheSamePerItemAnswer() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept, weaker));

        assertFalse(AgentInventoryReservationGatewayRuntime.mayConsume(entry, kept, NOW));
        assertTrue(AgentInventoryReservationGatewayRuntime.mayConsume(entry, weaker, NOW));
        assertEquals(List.of(weaker),
                AgentInventoryReservationGatewayRuntime.unreservedItems(entry, List.of(kept, weaker), NOW));
    }

    @Test
    void dropPathNeverDropsTheKeptCopy() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);
        Character warrior = warriorWithBag(kept, weaker);
        AgentRuntimeEntry entry = refreshedEntry(warrior);
        InventoryGateway inventory = KeptCopyFixture.inventoryGateway();

        int dropped = AgentInventoryDropService.dropFromBag(entry, warrior, InventoryType.EQUIP, inventory,
                item -> true);

        assertEquals(1, dropped);
        verify(inventory).dropItem(warrior, InventoryType.EQUIP, (short) 2, (short) 1);
        verify(inventory, never()).dropItem(eq(warrior), eq(InventoryType.EQUIP), eq((short) 1), anyShort());
    }

    @Test
    void idViewStillCountsTheKeptCopies() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept, weaker));

        assertTrue(AgentInventoryReservationRuntime.isReserved(entry, OVERALL, NOW),
                "loot targeting still sees the kept id as reserved");
        assertEquals(1, AgentInventoryReservationRuntime.ledger(entry).reservedQuantity(OVERALL, NOW),
                "one kept copy is one reserved unit of the id");
    }

    @Test
    void objectiveReservationsStillProtectEveryCopyOfTheirId() {
        Equip kept = overall(1, 5, 2);
        Equip weaker = overall(2, 3, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept, weaker));

        AgentInventoryReservationRuntime.reserveObjectiveItems(entry, Map.of(OVERALL, 1),
                AgentInventoryReservationRuntime.LOOT_CAPABILITY, AgentDisposition.QUEST_RESERVE,
                "the owner asked for this item", 900, NOW);

        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, kept, NOW + 1));
        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, weaker, NOW + 1),
                "an id reservation of another capability still holds every copy");
        assertEquals(2, AgentInventoryReservationRuntime.ledger(entry).reservedQuantity(OVERALL, NOW + 1));
    }

    @Test
    void aSaleOfferStillProtectsEveryCopyOfItsId() {
        Equip first = overall(1, 3, 2);
        Equip second = overall(2, 3, 2);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        AgentInventoryReservationRuntime.ledger(entry).reserve(new AgentInventoryReservation("open-chat:offer",
                OVERALL, 1, AgentDisposition.TRADE_RESERVE, "open-chat", "public sale offer", 900, NOW + 60_000));

        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, first, NOW));
        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, second, NOW));
        assertTrue(AgentInventoryReservationRuntime.mayConsume(entry, second, NOW + 60_000), "the offer expires");
    }

    @Test
    void releasingTheEquipmentCapabilityFreesTheKeptCopy() {
        Equip kept = overall(1, 5, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept));

        AgentInventoryReservationRuntime.releaseCapability(entry, AgentInventoryReservationRuntime.EQUIPMENT_CAPABILITY);

        assertTrue(AgentInventoryReservationRuntime.mayConsume(entry, kept, NOW));
        assertFalse(AgentInventoryReservationRuntime.isReserved(entry, OVERALL, NOW));
    }

    @Test
    void aRefreshMovesTheReservationToTheNewKeptCopy() {
        Equip old = overall(1, 4, 2);
        Character warrior = warriorWithBag(old);
        AgentRuntimeEntry entry = refreshedEntry(warrior);
        Equip better = overall(2, 6, 2);
        Character upgraded = warriorWithBag(old, better);
        refresh(entry, upgraded);

        assertTrue(AgentInventoryReservationRuntime.mayConsume(entry, old, NOW), "the old kept copy is beaten now");
        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, better, NOW));
    }

    @Test
    void anItemOfAnotherIdIsNeverHeldByTheKeptCopy() {
        Equip kept = overall(1, 5, 2);
        AgentRuntimeEntry entry = refreshedEntry(warriorWithBag(kept));
        Item potion = mock(Item.class);
        when(potion.getItemId()).thenReturn(2000000);

        assertTrue(AgentInventoryReservationRuntime.mayConsume(entry, potion, NOW));
        assertFalse(AgentInventoryReservationRuntime.mayConsume(entry, null, NOW));
        assertTrue(AgentInventoryReservationRuntime.mayConsume(null, kept, NOW), "no Agent entry, no reservation");
    }

    private static AgentRuntimeEntry refreshedEntry(Character warrior) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(warrior, null, null);
        refresh(entry, warrior);
        return entry;
    }

    private static void refresh(AgentRuntimeEntry entry, Character warrior) {
        InventoryGateway inventory = KeptCopyFixture.inventoryGateway();
        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
            AgentInventoryReservationRuntime.refreshEquipmentReservations(entry, warrior, NOW);
        }
    }
}
