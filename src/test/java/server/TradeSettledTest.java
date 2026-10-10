package server;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Trade.isSettled: true only once a trade went through. An Agent's completion thanks only for a settled trade
 * (deploy review 2026-10-10: it thanked after the one-of-a-kind and meso refusals too). Every refusal closes the trade
 * with neither side settled.
 */
class TradeSettledTest {
    private final Character giver = character(1);
    private final Character receiver = character(2);
    private final Trade giverTrade = new Trade((byte) 0, giver);
    private final Trade receiverTrade = new Trade((byte) 1, receiver);

    private static Character character(int id) {
        Character character = mock(Character.class);
        AtomicReference<Trade> trade = new AtomicReference<>();
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("c" + id);
        when(character.getLevel()).thenReturn(70);
        when(character.getMeso()).thenReturn(5_000_000);
        when(character.canHoldMeso(anyInt())).thenReturn(true);
        when(character.canHoldUniques(anyList())).thenReturn(true);
        when(character.getTrade()).thenAnswer(invocation -> trade.get());
        doAnswer(invocation -> {
            trade.set(invocation.getArgument(0));
            return null;
        }).when(character).setTrade(any());
        return character;
    }

    /** The giver puts the gift in and presses Trade, then the receiver presses Trade. */
    private void bothPressTrade(Item item, int meso) {
        giver.setTrade(giverTrade);
        receiver.setTrade(receiverTrade);
        giverTrade.setPartner(receiverTrade);
        receiverTrade.setPartner(giverTrade);
        giverTrade.setFullTrade(true);
        receiverTrade.setFullTrade(true);
        if (item != null) {
            giverTrade.addItem(item);
        }
        if (meso > 0) {
            giverTrade.setMeso(meso);
        }
        Trade.completeTrade(giver);
        assertTrue(giverTrade.isLocked(), "the giver's press locks its side");
        Trade.completeTrade(receiver);
    }

    private void assertClosedUnsettled() {
        assertFalse(giverTrade.isSettled());
        assertFalse(receiverTrade.isSettled());
        assertNull(giver.getTrade(), "the refusal closed the trade");
        assertNull(receiver.getTrade());
    }

    private void refuse(Item item, int meso, boolean receiverHasRoom) {
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(receiver), anyList())).thenReturn(receiverHasRoom);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(giver), anyList())).thenReturn(true);

            bothPressTrade(item, meso);
        }
    }

    @Test
    void theOneOfAKindLimitLeavesItUnsettled() {
        when(receiver.canHoldUniques(anyList())).thenReturn(false);

        refuse(new Item(1032033, (short) 1, (short) 1), 0, false); // Protector Rock

        assertClosedUnsettled();
    }

    @Test
    void noBagRoomLeavesItUnsettled() {
        refuse(new Item(1002000, (short) 1, (short) 1), 0, false);

        assertClosedUnsettled();
    }

    @Test
    void noMesoRoomLeavesItUnsettled() {
        when(receiver.canHoldMeso(anyInt())).thenReturn(false);

        refuse(null, 50_000, true);

        assertClosedUnsettled();
    }

    @Test
    void theDailyMesoLimitUnderLevelFifteenLeavesItUnsettled() {
        when(receiver.getLevel()).thenReturn(10);

        refuse(null, 1_500_000, true);

        assertClosedUnsettled();
    }

    /**
     * The commit path cannot run in a unit test (its trade log loads ItemInformationProvider, which needs the database),
     * so this pins where the flag is set: on both sides, after the economy transaction returned, and nowhere else.
     */
    @Test
    void onlyTheCommitPathSetsItAfterTheEconomyTransaction() throws IOException {
        String source = Files.readString(Path.of("src/main/java/server/Trade.java"));
        int commit = source.indexOf("EconomyTransactionCoordinator.execute(local.settlementId");
        int localSettled = source.indexOf("local.settled = true;");
        int partnerSettled = source.indexOf("partner.settled = true;");
        int closed = source.indexOf("partner.getChr().setTrade(null);\n            chr.setTrade(null);");

        assertTrue(commit > 0 && localSettled > commit && partnerSettled > localSettled && closed > partnerSettled,
                "settled after the transaction, before both sides close");
        assertEquals(2, source.split("settled = true", -1).length - 1, "set in one place only");
    }
}
