package server.agents.capabilities.inventory;

import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentDisposition;
import server.agents.capabilities.contracts.AgentInventoryReservation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentInventoryReservationLedgerTest {
    @Test
    void aggregatesReservationsAndExpiresThem() {
        AgentInventoryReservationLedger ledger = new AgentInventoryReservationLedger();
        ledger.reserve(new AgentInventoryReservation("quest", 4000000, 3,
                AgentDisposition.QUEST_RESERVE, "quest", "objective", 100, 1_000));
        ledger.reserve(new AgentInventoryReservation("trade", 4000000, 2,
                AgentDisposition.TRADE_RESERVE, "trade", "offer", 20, 0));

        assertEquals(5, ledger.reservedQuantity(4000000, 999));
        assertEquals(2, ledger.reservedQuantity(4000000, 1_000));
        assertEquals(1, ledger.reservations(1_000).size());
    }

    @Test
    void atomicallyReplacesOnlyOneCapabilitiesReservations() {
        AgentInventoryReservationLedger ledger = new AgentInventoryReservationLedger();
        ledger.reserve(new AgentInventoryReservation("quest:old", 4000000, 3,
                AgentDisposition.QUEST_RESERVE, "quest", "old", 100, 0));
        ledger.reserve(new AgentInventoryReservation("trade", 2000000, 2,
                AgentDisposition.TRADE_RESERVE, "trade", "offer", 20, 0));

        ledger.replaceCapabilityReservations("quest", List.of(
                new AgentInventoryReservation("quest:new", 4000001, 5,
                        AgentDisposition.QUEST_RESERVE, "quest", "new", 100, 0)));

        assertEquals(0, ledger.reservedQuantity(4000000, 1));
        assertEquals(5, ledger.reservedQuantity(4000001, 1));
        assertEquals(2, ledger.reservedQuantity(2000000, 1));
    }

    @Test
    void itemReservationsHoldOnlyTheirItemsWhileIdReservationsHoldEveryCopy() {
        AgentInventoryReservationLedger ledger = new AgentInventoryReservationLedger();
        Item kept = item(1051014);
        Item otherCopy = item(1051014);
        Item questCopy = item(1040000);
        Item otherQuestCopy = item(1040000);

        ledger.replaceCapabilityItemReservations("equipment", List.of(
                new AgentInventoryReservation("equipment:1051014", 1051014, 1,
                        AgentDisposition.BUILD_RESERVE, "equipment", "kept", 500, 0)), List.of(kept));
        ledger.reserve(new AgentInventoryReservation("quest:1040000", 1040000, 1,
                AgentDisposition.QUEST_RESERVE, "quest", "objective", 900, 0));

        assertTrue(ledger.reservesItem(kept, 1));
        assertFalse(ledger.reservesItem(otherCopy, 1), "an item reservation holds only its own item");
        assertTrue(ledger.reservesItem(questCopy, 1));
        assertTrue(ledger.reservesItem(otherQuestCopy, 1), "an id reservation holds every copy");
        assertEquals(1, ledger.reservedQuantity(1051014, 1), "the id view still counts the item reservation");
        assertFalse(ledger.reservesItem(null, 1));
    }

    @Test
    void anIdReservationOfAnotherCapabilityStillHoldsCopiesOfAKeptId() {
        AgentInventoryReservationLedger ledger = new AgentInventoryReservationLedger();
        Item kept = item(1051014);
        Item otherCopy = item(1051014);
        ledger.replaceCapabilityItemReservations("equipment", List.of(
                new AgentInventoryReservation("equipment:1051014", 1051014, 1,
                        AgentDisposition.BUILD_RESERVE, "equipment", "kept", 500, 0)), List.of(kept));
        ledger.reserve(new AgentInventoryReservation("open-chat:offer", 1051014, 1,
                AgentDisposition.TRADE_RESERVE, "open-chat", "sale", 900, 50));

        assertTrue(ledger.reservesItem(otherCopy, 49));
        assertFalse(ledger.reservesItem(otherCopy, 50), "the sale offer expired");
        assertTrue(ledger.reservesItem(kept, 50));
    }

    @Test
    void releasingOrReplacingTheCapabilityDropsItsHeldItems() {
        AgentInventoryReservationLedger ledger = new AgentInventoryReservationLedger();
        Item kept = item(1051014);
        Item otherCopy = item(1051014);
        ledger.replaceCapabilityItemReservations("equipment", List.of(
                new AgentInventoryReservation("equipment:1051014", 1051014, 1,
                        AgentDisposition.BUILD_RESERVE, "equipment", "kept", 500, 0)), List.of(kept));

        ledger.releaseCapability("equipment");
        assertFalse(ledger.reservesItem(kept, 1));

        ledger.replaceCapabilityReservations("equipment", List.of(
                new AgentInventoryReservation("equipment:1051014", 1051014, 1,
                        AgentDisposition.BUILD_RESERVE, "equipment", "kept", 500, 0)));
        assertTrue(ledger.reservesItem(otherCopy, 1), "plain reservations of the capability hold by id again");

        ledger.clear();
        assertFalse(ledger.reservesItem(otherCopy, 1));
    }

    private static Item item(int itemId) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        return item;
    }
}
