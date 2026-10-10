package server.agents.economy.integration.cosmic;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.coordinator.world.InviteCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.economy.social.TradeOffer;
import server.economy.EconomyItemEvidence;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class CosmicNegotiatedTradeExecutorTest {
    @Test
    void refusesTradeWhenAgentsHaveNotWalkedTogether() {
        Character first = mock(Character.class);
        Character second = mock(Character.class);
        when(first.getClient()).thenReturn(mock(client.Client.class));
        when(second.getClient()).thenReturn(mock(client.Client.class));
        when(first.getMap()).thenReturn(mock(server.maps.MapleMap.class));
        when(second.getMap()).thenReturn(mock(server.maps.MapleMap.class));
        var executor = new CosmicNegotiatedTradeExecutor(
                id -> "a".equals(id) ? first : second, 120);

        var result = executor.execute("deal", "a", new TradeOffer(1, Map.of()),
                "b", new TradeOffer(0, Map.of(4000000, 1)));

        assertFalse(result.succeeded());
        verify(first, never()).setTrade(any());
    }

    /** A free-market trader standing next to the other one, with a real trade slot (server.Trade sets it). */
    private static Character trader(int id, MapleMap room) {
        Character character = mock(Character.class);
        AtomicReference<Trade> trade = new AtomicReference<>();
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("trader" + id);
        when(character.getLevel()).thenReturn(70);
        when(character.getMeso()).thenReturn(5_000_000);
        when(character.canHoldMeso(anyInt())).thenReturn(true);
        when(character.canHoldUniques(anyList())).thenReturn(true);
        when(character.getClient()).thenReturn(mock(Client.class));
        when(character.getMap()).thenReturn(room);
        when(character.getMapId()).thenReturn(910000000);
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(character.getTrade()).thenAnswer(invocation -> trade.get());
        doAnswer(invocation -> {
            trade.set(invocation.getArgument(0));
            return null;
        }).when(character).setTrade(any());
        return character;
    }

    /**
     * Deploy review 2026-10-10: a refusal closes the trade on both sides just as a settlement does (getTrade() is null
     * either way), and the executor read "both closed" as committed. Here the real server.Trade refuses it: the
     * receiver is under level 15 and the mesos pass its 1,000,000-a-day limit.
     */
    @Test
    void aNegotiatedTradeRefusedByTheDailyMesoLimitIsNotCommitted() {
        MapleMap room = mock(MapleMap.class);
        Character first = trader(940_001, room);
        Character second = trader(940_002, room);
        when(second.getLevel()).thenReturn(10);
        var executor = new CosmicNegotiatedTradeExecutor(id -> "a".equals(id) ? first : second, 120);

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class);
             MockedStatic<Inventory> bags = mockStatic(Inventory.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(true);

            var result = executor.execute("deal-daily-limit", "a", new TradeOffer(1_500_000, Map.of()),
                    "b", new TradeOffer(10, Map.of()));

            assertFalse(result.succeeded(), result.evidence());
            assertEquals("Cosmic trade did not settle", result.evidence());
        }
        assertNull(first.getTrade(), "the refusal closed the trade");
        assertNull(second.getTrade());
        verify(first).gainMeso(eq(1_500_000), anyBoolean(), anyBoolean(), anyBoolean()); // its offer came back
        verify(second, never()).addMesosTraded(anyInt());
    }

    /** The open-chat sale to an Agent buyer: the buyer's bag has no room for the item, so server.Trade refuses it. */
    @Test
    void anExactItemSaleRefusedForTheBuyersFullBagIsNotCommitted() {
        MapleMap room = mock(MapleMap.class);
        Character buyer = trader(940_011, room);
        Character seller = trader(940_012, room);
        Item stack = new Item(4000000, (short) 3, (short) 5);
        Inventory etc = mock(Inventory.class);
        when(seller.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(etc.listById(4000000)).thenReturn(List.of(stack));
        String fingerprint = EconomyItemEvidence.describe(stack).fingerprint();
        var executor = new CosmicNegotiatedTradeExecutor(id -> "buyer".equals(id) ? buyer : seller, 120);

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class);
             MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignoredDrops = mockStatic(InventoryManipulator.class);
             MockedStatic<CosmicTradeOfferPlacer> placer = mockStatic(CosmicTradeOfferPlacer.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(buyer), anyList())).thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(seller), anyList())).thenReturn(true);
            placer.when(() -> CosmicTradeOfferPlacer.placeExact(eq(seller), eq(InventoryType.ETC), eq((short) 3),
                    eq(4000000), eq(fingerprint), eq(5), eq((byte) 1))).thenAnswer(invocation -> {
                        Item offered = stack.copy();
                        offered.setPosition((short) 1);
                        return seller.getTrade().addItem(offered);
                    });

            var result = executor.executeExactItem("open-chat:sale-1", "buyer", 400_000, "seller",
                    4000000, fingerprint, 5);

            assertFalse(result.succeeded(), result.evidence());
            assertEquals("Cosmic exact-item trade did not settle", result.evidence());
            assertTrue(result.tradeOpened(), "the trade's own refusal");
        }
        assertNull(buyer.getTrade(), "the refusal closed the trade");
        assertNull(seller.getTrade());
        verify(buyer).message("There is not enough inventory space to complete the trade.");
    }

    /**
     * Deploy review 2026-10-10: a seller busy with a human is no refusal of the buyer. The result says no trade opened,
     * both when the seller is already in the human's trade and when only the human's invite waits for its answer.
     */
    @Test
    void anExactItemSaleToASellerBusyWithAHumanOpensNoTrade() {
        MapleMap room = mock(MapleMap.class);
        Character buyer = trader(940_031, room);
        Character seller = trader(940_032, room);
        Character human = trader(940_033, room);
        var executor = new CosmicNegotiatedTradeExecutor(id -> "buyer".equals(id) ? buyer : seller, 120);

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class)) {
            Trade.startTrade(human);
            Trade.inviteTrade(human, seller);
            var trading = executor.executeExactItem("open-chat:sale-2", "buyer", 400_000, "seller",
                    4000000, "fingerprint", 5);
            Trade.cancelTrade(human, Trade.TradeResult.NO_RESPONSE);
            InviteCoordinator.createInvite(InviteCoordinator.InviteType.TRADE, human, human.getId(), seller.getId());
            try {
                var invited = executor.executeExactItem("open-chat:sale-3", "buyer", 400_000, "seller",
                        4000000, "fingerprint", 5);

                assertEquals("counterparty is already trading", trading.evidence());
                assertFalse(trading.tradeOpened());
                assertEquals("Cosmic trade invitation was rejected", invited.evidence());
                assertFalse(invited.tradeOpened());
            } finally {
                InviteCoordinator.removeInvite(InviteCoordinator.InviteType.TRADE, seller.getId());
            }
        }
        assertNull(buyer.getTrade());
        assertNull(seller.getTrade());
    }

    /** The commit path itself needs the item data (its trade log), so the settlement is the stubbed press here. */
    @Test
    void aTradeThatSettledIsCommitted() {
        MapleMap room = mock(MapleMap.class);
        Character first = trader(940_021, room);
        Character second = trader(940_022, room);
        var executor = new CosmicNegotiatedTradeExecutor(id -> "a".equals(id) ? first : second, 120);
        Trade firstTrade = mock(Trade.class);
        Trade secondTrade = mock(Trade.class);
        when(firstTrade.isFullTrade()).thenReturn(true);
        when(secondTrade.isFullTrade()).thenReturn(true);
        when(firstTrade.getOfferedMesos()).thenReturn(50_000);
        when(secondTrade.getOfferedMesos()).thenReturn(10);
        AtomicBoolean settled = new AtomicBoolean();
        when(firstTrade.isSettled()).thenAnswer(invocation -> settled.get());
        when(secondTrade.isSettled()).thenAnswer(invocation -> settled.get());

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            trades.when(() -> Trade.startTrade(first, "deal-settles")).thenAnswer(invocation -> {
                first.setTrade(firstTrade);
                second.setTrade(secondTrade);
                return null;
            });
            trades.when(() -> Trade.completeTrade(second)).thenAnswer(invocation -> {
                settled.set(true); // the commit marks both sides settled, then closes both
                first.setTrade(null);
                second.setTrade(null);
                return null;
            });

            var result = executor.execute("deal-settles", "a", new TradeOffer(50_000, Map.of()),
                    "b", new TradeOffer(10, Map.of()));

            assertTrue(result.succeeded(), result.evidence());
            assertEquals("Cosmic PLAYER_TRADE committed", result.evidence());
            assertEquals("deal-settles", result.transactionId());
        }
    }
}
