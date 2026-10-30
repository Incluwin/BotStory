package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A GM's gift (!giveitem) to a character whose tab cannot hold it all gives nothing and says so. P19 Showa runs
 * (2026-10-15): Power Elixirs given to companions with full Use tabs never landed (addById fills what room there is
 * and the rest is lost), the companion's own "inventory full" notice reached no one, and the GM read "Item given".
 */
class ItemCommandGiftTest {
    private static final int POWER_ELIXIR = 2_000_005;
    private static final short MANY = 700;

    private final Client companionClient = mock(Client.class);
    private final Character companion = mock(Character.class);
    private final Client gmClient = mock(Client.class);
    private final Character gm = mock(Character.class);
    private MockedStatic<InventoryManipulator> inventory;

    @BeforeEach
    void wire() {
        when(companionClient.getPlayer()).thenReturn(companion);
        when(companion.getName()).thenReturn("Gunnar");
        when(gmClient.getPlayer()).thenReturn(gm);
        when(gm.getName()).thenReturn("Bossfriend");
        inventory = mockStatic(InventoryManipulator.class);
        inventory.when(() -> InventoryManipulator.addById(
                        org.mockito.ArgumentMatchers.any(), anyInt(), anyShort(), anyString(), anyInt(), anyShort(), anyLong()))
                .thenReturn(true);
    }

    @AfterEach
    void close() {
        inventory.close();
    }

    @Test
    void aGiftToAFullTabGivesNothingAndSaysSo() {
        inventory.when(() -> InventoryManipulator.checkSpace(companionClient, POWER_ELIXIR, MANY, "Bossfriend"))
                .thenReturn(false);

        assertFalse(ItemCommand.give(companionClient, gm, POWER_ELIXIR, MANY, (short) 0));

        inventory.verify(() -> InventoryManipulator.addById(
                org.mockito.ArgumentMatchers.any(), anyInt(), anyShort(), anyString(), anyInt(), anyShort(), anyLong()),
                never());
        verify(gm).yellowMessage("Gunnar has no room for 700 of that in the use tab: nothing was given.");
    }

    @Test
    void aGiftThatFitsLands() {
        inventory.when(() -> InventoryManipulator.checkSpace(companionClient, POWER_ELIXIR, MANY, "Bossfriend"))
                .thenReturn(true);

        assertTrue(ItemCommand.give(companionClient, gm, POWER_ELIXIR, MANY, (short) 0));

        inventory.verify(() -> InventoryManipulator.addById(eq(companionClient), eq(POWER_ELIXIR), eq(MANY),
                eq("Bossfriend"), eq(-1), eq((short) 0), eq(-1L)));
        verify(gm, never()).yellowMessage(anyString());
    }

    @Test
    void anItemForTheGmItselfIsAddedAsBefore() {
        assertTrue(ItemCommand.give(gmClient, gm, POWER_ELIXIR, MANY, (short) 0));

        inventory.verify(() -> InventoryManipulator.checkSpace(org.mockito.ArgumentMatchers.any(), anyInt(), anyInt(),
                anyString()), never());
        verify(gm, never()).yellowMessage(anyString());
    }
}
