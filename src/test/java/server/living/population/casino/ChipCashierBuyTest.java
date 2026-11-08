package server.living.population.casino;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen's chips at the counter (live 2026-11-08: citizens sent to blackjack carried 96 of 96 Etc slots, could not
 * take their chips, and both tables waited with nobody seated). A full tab sells junk there first, as at any shop.
 */
class ChipCashierBuyTest {
    @Test
    void aFullEtcTabMakesRoomAtTheCounterThenBuys() {
        Client client = mock(Client.class);
        Character agent = citizen(client);
        AtomicBoolean roomMade = new AtomicBoolean();
        AtomicInteger kinds = new AtomicInteger();
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), anyString()))
                    .thenAnswer(ignored -> roomMade.get());

            assertTrue(ChipCashier.buy(agent, 300_000, (who, chipKinds) -> {
                kinds.set(chipKinds);
                roomMade.set(true);
            }));

            assertEquals(2, kinds.get(), "a 250k and a 50k chip");
            verify(agent).gainMeso(-300_000, false);
            inventory.verify(() -> InventoryManipulator.addById(eq(client), anyInt(), anyShort()), atLeastOnce());
        }
    }

    @Test
    void aTabWithNothingToSellBuysNothing() {
        Client client = mock(Client.class);
        Character agent = citizen(client);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), anyString())).thenReturn(false);

            assertFalse(ChipCashier.buy(agent, 300_000, (who, chipKinds) -> { }));

            verify(agent, never()).gainMeso(anyInt(), anyBoolean());
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
    }

    private static Character citizen(Client client) {
        Character agent = mock(Character.class);
        when(agent.getClient()).thenReturn(client);
        when(agent.getMeso()).thenReturn(1_000_000);
        return agent;
    }
}
