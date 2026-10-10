package server;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.inventory.AgentBagFullSpeech;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Trade.completeTrade's space check (live 2026-10-10: a cap given to a companion with 96/96 Equip slots ended in "Trade
 * unsuccessful." and the owner's "Partner does not have enough inventory space"): the side whose bag has no room is
 * told to AgentBagFullSpeech, which lets an owned companion say so. The stock refusal itself is unchanged.
 */
class TradeBagFullRefusalTest {
    private final Character owner = character(1);
    private final Character companion = character(2);
    private final Trade ownerTrade = new Trade((byte) 0, owner);
    private final Trade companionTrade = new Trade((byte) 1, companion);

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("c" + id);
        when(character.canHoldMeso(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(character.canHoldUniques(anyList())).thenReturn(true);
        return character;
    }

    /** The owner put a cap in and pressed Trade; the companion presses Trade after it, as it always does. */
    private void bothPressTrade() {
        ownerTrade.setPartner(companionTrade);
        companionTrade.setPartner(ownerTrade);
        ownerTrade.setFullTrade(true);
        companionTrade.setFullTrade(true);
        when(owner.getTrade()).thenReturn(ownerTrade);
        when(companion.getTrade()).thenReturn(companionTrade);
        ownerTrade.addItem(new Item(1002000, (short) 1, (short) 1));

        Trade.completeTrade(owner);
        Trade.completeTrade(companion);
    }

    @Test
    void aCompanionWithoutRoomIsToldItsBagWasFull() {
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentBagFullSpeech> speech = mockStatic(AgentBagFullSpeech.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(org.mockito.ArgumentMatchers.eq(companion), anyList()))
                    .thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(org.mockito.ArgumentMatchers.eq(owner), anyList()))
                    .thenReturn(true);

            bothPressTrade();

            speech.verify(() -> AgentBagFullSpeech.tradeRefused(companion), times(1));
            speech.verify(() -> AgentBagFullSpeech.tradeRefused(owner), never());
            org.mockito.Mockito.verify(owner).message("Partner does not have enough inventory space to complete the trade.");
        }
    }

    @Test
    void anOwnerWithoutRoomIsToldTheSameWayAndTheCompanionIsNot() {
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentBagFullSpeech> speech = mockStatic(AgentBagFullSpeech.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(org.mockito.ArgumentMatchers.eq(companion), anyList()))
                    .thenReturn(true);
            bags.when(() -> Inventory.checkSpotsAndOwnership(org.mockito.ArgumentMatchers.eq(owner), anyList()))
                    .thenReturn(false);

            bothPressTrade();

            speech.verify(() -> AgentBagFullSpeech.tradeRefused(owner), times(1));
            speech.verify(() -> AgentBagFullSpeech.tradeRefused(companion), never());
        }
    }
}
