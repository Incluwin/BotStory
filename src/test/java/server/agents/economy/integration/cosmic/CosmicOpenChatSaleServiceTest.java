package server.agents.economy.integration.cosmic;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.economy.communication.EconomicIntent;
import server.agents.economy.decision.AgentNeed;
import server.agents.economy.market.EconomicReason;
import server.agents.economy.persistence.EconomyEvidenceJournal;
import server.agents.economy.scenario.EconomyEngineConfig;
import server.agents.economy.scenario.NamedRandomStreams;
import server.agents.economy.session.CommerceParticipant;
import server.agents.economy.social.TradeExecutionGateway;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CosmicOpenChatSaleServiceTest {
    @Test
    void reservesOneRealBundlePublishesIntentAndAdvertisesOnSchedule() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        Character seller = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        Item item = new Item(1302000, (short) 2, (short) 2);
        when(seller.getId()).thenReturn(77);
        when(seller.getMapId()).thenReturn(910000000);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(inventory);
        when(inventory.getItem((short) 2)).thenReturn(item);

        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        UUID intentId = UUID.randomUUID();
        when(economy.publishIntent(eq("seller"), eq(""), eq(EconomicIntent.Kind.SELL_INTEREST),
                eq(1302000), anyString(), eq(1), eq(400_000L), eq(910000000), anyString(),
                anyMap(), eq(at), eq(Duration.ofMinutes(10))))
                .thenReturn(new EconomicIntent(intentId, runId, "seller", "",
                        EconomicIntent.Kind.SELL_INTEREST, 1302000, "fingerprint", 1,
                        400_000, 910000000, "sale", java.util.Map.of(), at,
                        at.plus(Duration.ofMinutes(10)), EconomicIntent.Status.OPEN));
        List<String> chat = new ArrayList<>();
        CosmicOpenChatSaleService.ReservationGateway reservations =
                mock(CosmicOpenChatSaleService.ReservationGateway.class);
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        CosmicNegotiatedTradeExecutor executor = mock(CosmicNegotiatedTradeExecutor.class);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, economy,
                mock(EconomyEvidenceJournal.class), executor,
                reservations, (itemId, quantity) -> 100_000,
                (speaker, text) -> chat.add(text),
                (buyer, target) -> CosmicCounterpartyApproachService.Status.ASSIGNED);
        MarketSellerPlan plan = new MarketSellerPlan(List.of(), List.of(
                new AgentFreeMarketStallService.Listing(InventoryType.EQUIP,
                        (short) 2, (short) 1, (short) 2, 400_000)),
                910000001, "shop");

        var prepared = service.prepare(seller, profile("seller"), plan, at);

        assertTrue(prepared.selected());
        assertEquals(1302000, prepared.itemId());
        assertEquals(400_000, prepared.askMesos());
        assertEquals(360_000, prepared.reserveMesos());
        assertEquals(1, prepared.plan().stallListings().getFirst().bundles());
        verify(reservations).reserve(eq(seller), any(UUID.class), eq(1302000), eq(1),
                eq(Duration.ofMinutes(10)));
        assertTrue(chat.isEmpty());

        var active = service.progressSeller(seller, profile("seller"), at.plusSeconds(5));

        assertTrue(active.attempted());
        assertFalse(active.done());
        assertEquals(1, chat.size());
        assertEquals(1, service.views().getFirst().advertisements());

        Character buyer = mock(Character.class);
        when(buyer.getMapId()).thenReturn(910000000);
        when(buyer.getMeso()).thenReturn(1_000_000);
        AgentNeed need = new AgentNeed(1302000, 0, 1, 1, EconomicReason.EQUIPMENT_UPGRADE,
                at.plusSeconds(30), 400_000, java.util.Set.of(), java.util.Set.of(), "test");
        var approach = service.attemptPurchase(buyer, profile("buyer"), List.of(need), at.plusSeconds(6));

        assertEquals("APPROACH_ASSIGNED", approach.outcome());
        assertEquals(Boolean.TRUE, approach.evidence().get("externalActionPending"));
        verifyNoInteractions(executor);

        CosmicOpenChatSaleService.ReservationGateway restoredReservations =
                mock(CosmicOpenChatSaleService.ReservationGateway.class);
        CosmicOpenChatSaleService restored = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), new EconomyParticipantRegistry(id -> null), economy,
                mock(EconomyEvidenceJournal.class), mock(CosmicNegotiatedTradeExecutor.class),
                restoredReservations, (itemId, quantity) -> 100_000, (speaker, text) -> { });
        restored.restoreState(service.snapshotState());

        assertEquals(1, restored.views().size());
        restored.progressSeller(seller, profile("seller"), at.plusSeconds(6));
        verify(restoredReservations).reserve(eq(seller), any(UUID.class), eq(1302000), eq(1),
                eq(Duration.ofSeconds(594)));
        restored.shutdown();
        verify(restoredReservations).release(77);
        assertTrue(restored.views().isEmpty());
    }

    @Test
    void refusesOfferWhoseNpcOpportunityFloorExceedsAsk() {
        Character seller = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        Item item = new Item(1302000, (short) 2, (short) 1);
        when(seller.getId()).thenReturn(77);
        when(seller.getMapId()).thenReturn(910000000);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(inventory);
        when(inventory.getItem((short) 2)).thenReturn(item);
        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(UUID.randomUUID(), "cfg", "cat",
                config(), new NamedRandomStreams(1), new EconomyParticipantRegistry(id -> null), economy,
                mock(EconomyEvidenceJournal.class), mock(CosmicNegotiatedTradeExecutor.class),
                mock(CosmicOpenChatSaleService.ReservationGateway.class),
                (itemId, quantity) -> 500_000, (speaker, text) -> { });
        MarketSellerPlan plan = new MarketSellerPlan(List.of(), List.of(
                new AgentFreeMarketStallService.Listing(InventoryType.EQUIP,
                        (short) 2, (short) 1, (short) 1, 400_000)), 910000001, "shop");

        var prepared = service.prepare(seller, profile("seller"), plan,
                Instant.parse("2026-01-01T00:00:00Z"));

        assertFalse(prepared.selected());
        verifyNoInteractions(economy);
    }

    @Test
    void structuredOfferAndAcceptanceSettleOnlyAfterExactCosmicTradeCommits() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        Character seller = mock(Character.class); Character buyer = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        when(seller.getId()).thenReturn(77); when(buyer.getId()).thenReturn(88);
        when(seller.getMapId()).thenReturn(910000000); when(buyer.getMapId()).thenReturn(910000000);
        when(buyer.getMeso()).thenReturn(1_000_000);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(inventory);
        when(inventory.getItem((short) 2)).thenReturn(new Item(1302000, (short) 2, (short) 1));
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller); participants.admitted(profile("buyer"), buyer);
        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        AtomicInteger intents = new AtomicInteger();
        when(economy.publishIntent(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                anyLong(), anyInt(), anyString(), anyMap(), any(), any())).thenAnswer(call -> {
            String actor = call.getArgument(0); String counterparty = call.getArgument(1);
            EconomicIntent.Kind kind = call.getArgument(2); int itemId = call.getArgument(3);
            String fingerprint = call.getArgument(4); int quantity = call.getArgument(5);
            long mesos = call.getArgument(6); Integer map = call.getArgument(7);
            String text = call.getArgument(8); java.util.Map<String, Object> attributes = call.getArgument(9);
            Instant logicalAt = call.getArgument(10); Duration lifetime = call.getArgument(11);
            return new EconomicIntent(new UUID(0, intents.incrementAndGet()), runId, actor, counterparty,
                    kind, itemId, fingerprint, quantity, mesos, map, text, attributes, logicalAt,
                    logicalAt.plus(lifetime), EconomicIntent.Status.OPEN);
        });
        CosmicNegotiatedTradeExecutor executor = mock(CosmicNegotiatedTradeExecutor.class);
        when(executor.executeExactItem(anyString(), eq("buyer"), eq(400_000L), eq("seller"),
                eq(1302000), anyString(), eq(1))).thenReturn(
                new server.agents.economy.social.TradeExecutionGateway.Result(true, "trade-1", "settled", true));
        CosmicOpenChatSaleService.ReservationGateway reservations =
                mock(CosmicOpenChatSaleService.ReservationGateway.class);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, economy,
                mock(EconomyEvidenceJournal.class), executor, reservations,
                (itemId, quantity) -> 100_000, (speaker, text) -> { },
                (shopper, target) -> CosmicCounterpartyApproachService.Status.ARRIVED);
        MarketSellerPlan plan = new MarketSellerPlan(List.of(), List.of(
                new AgentFreeMarketStallService.Listing(InventoryType.EQUIP,
                        (short) 2, (short) 1, (short) 1, 400_000)), 910000001, "shop");
        service.prepare(seller, profile("seller"), plan, at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5));
        AgentNeed need = new AgentNeed(1302000, 0, 1, 1, EconomicReason.EQUIPMENT_UPGRADE,
                at.plusSeconds(30), 400_000, java.util.Set.of(), java.util.Set.of(), "test");

        var result = service.attemptPurchase(buyer, profile("buyer"), List.of(need), at.plusSeconds(6));

        assertTrue(result.sold()); assertTrue(result.done()); assertEquals("AGENT_TRADE_SETTLED", result.outcome());
        verify(economy, never()).resolveIntent(anyString(), any(), eq(EconomicIntent.Status.ACCEPTED),
                any(), anyString());
        verify(economy, times(3)).resolveIntent(anyString(), any(), eq(EconomicIntent.Status.SETTLED),
                any(), anyString());
        verify(reservations).release(77);
        assertTrue(service.views().isEmpty());
    }

    /**
     * Deploy review 2026-10-10: a human buyer's trade closed the offer SETTLED ("HUMAN_TRADE_SETTLED") whenever the
     * seller's trade was gone after its press, and a refusal closes it too. Here the real server.Trade refuses the trade
     * because the buyer's bag has no room for the item. That is no sale: as after a cancel, the offer stays up, its
     * intent stays open and its reservation stays held.
     */
    @Test
    void aHumanTradeRefusedForTheBuyersFullBagIsNoSale() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(77, room);
        Character buyer = trader(88, room);
        Inventory equips = mock(Inventory.class);
        Item sword = new Item(1302000, (short) 2, (short) 1);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(equips.getItem((short) 2)).thenReturn(sword);
        when(equips.listById(1302000)).thenReturn(List.of(sword));
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        when(economy.publishIntent(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                anyLong(), anyInt(), anyString(), anyMap(), any(), any())).thenAnswer(call -> new EconomicIntent(
                UUID.randomUUID(), runId, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6),
                call.getArgument(7), call.getArgument(8), call.getArgument(9), call.getArgument(10),
                call.<Instant>getArgument(10).plus(call.<Duration>getArgument(11)), EconomicIntent.Status.OPEN));
        CosmicOpenChatSaleService.ReservationGateway reservations =
                mock(CosmicOpenChatSaleService.ReservationGateway.class);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, economy,
                mock(EconomyEvidenceJournal.class), mock(CosmicNegotiatedTradeExecutor.class), reservations,
                (itemId, quantity) -> 100_000, (speaker, text) -> { });
        service.prepare(seller, profile("seller"), new MarketSellerPlan(List.of(), List.of(
                new AgentFreeMarketStallService.Listing(InventoryType.EQUIP,
                        (short) 2, (short) 1, (short) 1, 400_000)), 910000001, "shop"), at);
        Trade buyerTrade = new Trade((byte) 0, buyer);
        Trade sellerTrade = new Trade((byte) 1, seller);
        buyer.setTrade(buyerTrade);
        seller.setTrade(sellerTrade);
        buyerTrade.setPartner(sellerTrade);
        sellerTrade.setPartner(buyerTrade);
        buyerTrade.setFullTrade(true);
        sellerTrade.setFullTrade(true);

        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignoredDrops = mockStatic(InventoryManipulator.class);
             MockedStatic<CosmicTradeOfferPlacer> placer = mockStatic(CosmicTradeOfferPlacer.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(buyer), anyList())).thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(seller), anyList())).thenReturn(true);
            placer.when(() -> CosmicTradeOfferPlacer.placeExact(eq(seller), eq(InventoryType.EQUIP), eq((short) 2),
                    eq(1302000), anyString(), eq(1), eq((byte) 1))).thenAnswer(invocation -> {
                        Item offered = sword.copy();
                        offered.setPosition((short) 1);
                        return sellerTrade.addItem(offered);
                    });

            assertTrue(service.handleManualTrade(seller)); // the seller puts the sword in
            buyerTrade.setMeso(400_000); // the buyer pays the ask and presses Trade
            Trade.completeTrade(buyer);
            assertTrue(service.handleManualTrade(seller)); // the seller presses Trade: refused, buyer's bag is full

            verify(buyer).message("There is not enough inventory space to complete the trade.");
            assertNull(seller.getTrade(), "the refusal closed the trade");
            assertFalse(service.handleManualTrade(seller)); // its next tick: the closed trade, as after a cancel
        }

        verify(economy, never()).resolveIntent(anyString(), any(), eq(EconomicIntent.Status.SETTLED),
                any(), anyString());
        verify(economy, never()).resolveIntent(anyString(), any(), any(), any(), eq("HUMAN_TRADE_SETTLED"));
        verify(reservations, never()).release(anyInt());
        assertEquals(1, service.views().size(), "the offer is still up");
    }

    /**
     * Deploy review 2026-10-10: since a refused trade is no sale (788b8972c), the refused Agent purchase kept the offer
     * up, and nothing stopped the same buyer picking it again. Its need was unchanged (owned items only, no bag check),
     * so every revisit opened the same doomed trade with the same three public lines, and whenever the buyer had walked
     * off to a stall the approach sent it back to the seller first. Here the real server.Trade refuses the trade for
     * the buyer's full bag once; its next revisits, beside the seller and then across the room, leave that offer alone.
     */
    @Test
    void anAgentBuyerRefusedForItsFullBagDoesNotTryThatOfferAgainNorWalkBackToTheSeller() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(77, room);
        Character buyer = trader(88, room);
        AtomicReference<Point> buyerAt = new AtomicReference<>(new Point(0, 0));
        when(buyer.getPosition()).thenAnswer(invocation -> buyerAt.get());
        Inventory equips = mock(Inventory.class);
        Item sword = new Item(1302000, (short) 2, (short) 1);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(equips.getItem((short) 2)).thenReturn(sword);
        when(equips.listById(1302000)).thenReturn(List.of(sword));
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        List<String> chat = new ArrayList<>();
        List<Character> approached = new ArrayList<>();
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, echoingEconomy(runId),
                mock(EconomyEvidenceJournal.class),
                new CosmicNegotiatedTradeExecutor(id -> "buyer".equals(id) ? buyer : seller, 160),
                mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> chat.add(text), (shopper, target) -> {
                    approached.add(target);
                    return shopper.getPosition().distance(target.getPosition()) <= 160
                            ? CosmicCounterpartyApproachService.Status.ARRIVED
                            : CosmicCounterpartyApproachService.Status.ASSIGNED;
                });
        service.prepare(seller, profile("seller"), swordPlan(), at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5)); // its first advertisement
        chat.clear();

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class);
             MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignoredDrops = mockStatic(InventoryManipulator.class);
             MockedStatic<CosmicTradeOfferPlacer> placer = mockStatic(CosmicTradeOfferPlacer.class)) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(buyer), anyList())).thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(seller), anyList())).thenReturn(true);
            placer.when(() -> CosmicTradeOfferPlacer.placeExact(eq(seller), eq(InventoryType.EQUIP), eq((short) 2),
                    eq(1302000), anyString(), eq(1), eq((byte) 1))).thenAnswer(invocation -> {
                        Item offered = sword.copy();
                        offered.setPosition((short) 1);
                        return seller.getTrade().addItem(offered);
                    });

            var refused = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(6));

            assertEquals("Cosmic exact-item trade did not settle", refused.outcome());
            assertFalse(refused.sold());
            verify(buyer).message("There is not enough inventory space to complete the trade.");

            var besideTheSeller = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(11)); // the next action poll
            buyerAt.set(new Point(900, 0)); // then off to a stall across the room
            var acrossTheRoom = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(16));

            assertAll(
                    () -> assertFalse(besideTheSeller.attempted(), besideTheSeller.outcome()),
                    () -> assertFalse(acrossTheRoom.attempted(), acrossTheRoom.outcome()),
                    () -> verify(buyer, times(1)).setTrade(argThat(Objects::nonNull)), // the refused trade only
                    () -> assertEquals(3, chat.size(), "the offer, the yes and the sorry, once: " + chat),
                    () -> assertEquals(List.of(seller), approached, "the buyer is not sent back to the seller"),
                    () -> assertEquals(1, service.views().size(), "the offer is still up for other buyers"));
        }
    }

    /** The refusal is that buyer's: another Agent buyer still buys the offer, and a checkpoint keeps the memory. */
    @Test
    void anotherAgentBuyerStillBuysAnOfferOneBuyerWasRefused() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(77, room);
        Character buyer = trader(88, room);
        Character other = trader(99, room);
        Inventory equips = mock(Inventory.class);
        Item sword = new Item(1302000, (short) 2, (short) 1);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(equips.getItem((short) 2)).thenReturn(sword);
        when(equips.listById(1302000)).thenReturn(List.of(sword));
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        participants.admitted(profile("other"), other);
        CosmicAgentEconomyFacade economy = echoingEconomy(runId);
        CosmicNegotiatedTradeExecutor executor = mock(CosmicNegotiatedTradeExecutor.class);
        when(executor.executeExactItem(anyString(), eq("buyer"), eq(400_000L), eq("seller"),
                eq(1302000), anyString(), eq(1))).thenReturn(new TradeExecutionGateway.Result(false,
                "open-chat:sale", "Cosmic exact-item trade did not settle", true));
        when(executor.executeExactItem(anyString(), eq("other"), eq(400_000L), eq("seller"),
                eq(1302000), anyString(), eq(1))).thenReturn(new TradeExecutionGateway.Result(true,
                "open-chat:sale", "Cosmic exact-item PLAYER_TRADE committed", true));
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, economy, mock(EconomyEvidenceJournal.class),
                executor, mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> { }, (shopper, target) -> CosmicCounterpartyApproachService.Status.ARRIVED);
        service.prepare(seller, profile("seller"), swordPlan(), at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5));

        assertFalse(service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                at.plusSeconds(6)).sold());
        assertFalse(service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                at.plusSeconds(11)).attempted());
        CosmicOpenChatSaleService restored = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, economy, mock(EconomyEvidenceJournal.class),
                executor, mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> { }, (shopper, target) -> CosmicCounterpartyApproachService.Status.ARRIVED);
        restored.restoreState(service.snapshotState());
        assertFalse(restored.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                at.plusSeconds(11)).attempted(), "the checkpoint keeps the refusal");

        var sold = service.attemptPurchase(other, profile("other"), List.of(swordNeed(at)), at.plusSeconds(12));

        assertTrue(sold.sold(), sold.outcome());
        assertEquals("AGENT_TRADE_SETTLED", sold.outcome());
        verify(executor, times(1)).executeExactItem(anyString(), eq("buyer"), anyLong(), anyString(),
                anyInt(), anyString(), anyInt());
        assertTrue(service.views().isEmpty());
    }

    /**
     * Deploy review 2026-10-10 (on 91c58c607): every failed Agent trade put the buyer on the offer's refused list, also
     * one that failed because the seller was busy, before any trade opened. A human who invites the seller holds it
     * busy (handleManualTrade keeps that trade up to the PT2M negotiation timeout), so one busy moment shut the Agent
     * buyer out for the offer's whole lifetime, after both had said their lines into the busy seller. Here a human
     * invites the seller, then trades with it: the buyer leaves the offer alone without a word, and buys it once the
     * human's trade closed.
     */
    @Test
    void anAgentBuyerLeavesASellerInAHumansTradeAloneAndBuysOnceItIsFree() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(940_101, room);
        Character buyer = trader(940_102, room);
        Character human = trader(940_103, room);
        swordIn(seller);
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        List<String> chat = new ArrayList<>();
        CosmicNegotiatedTradeExecutor executor = settlesBetweenFreeTraders(buyer, seller);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, echoingEconomy(runId),
                mock(EconomyEvidenceJournal.class), executor,
                mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> chat.add(text),
                (shopper, target) -> CosmicCounterpartyApproachService.Status.ARRIVED);
        service.prepare(seller, profile("seller"), swordPlan(), at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5)); // its first advertisement
        chat.clear();

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class)) {
            Trade.startTrade(human);
            Trade.inviteTrade(human, seller); // the human's invite waits for the seller's answer
            var invited = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(6));
            Trade.visitTrade(seller, human); // the seller takes the human's trade
            var trading = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(11));
            Trade.cancelTrade(human, Trade.TradeResult.NO_RESPONSE); // the human walks away without a deal

            assertAll(
                    () -> assertFalse(invited.attempted(), invited.outcome()),
                    () -> assertFalse(trading.attempted(), trading.outcome()),
                    () -> assertTrue(chat.isEmpty(), "no lines into a busy seller: " + chat),
                    () -> verify(executor, never()).executeExactItem(anyString(), anyString(), anyLong(),
                            anyString(), anyInt(), anyString(), anyInt()),
                    () -> verify(buyer, never()).setTrade(argThat(Objects::nonNull)));
        }

        var free = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)), at.plusSeconds(16));

        assertTrue(free.sold(), free.outcome());
        assertEquals("AGENT_TRADE_SETTLED", free.outcome());
        assertTrue(service.views().isEmpty());
    }

    /**
     * The race past that check: a human invites the seller while the buyer walks up. The executor finds the seller
     * already trading, and no trade opened, so the buyer is not held to it: it buys once the human's trade closed.
     */
    @Test
    void aSellerAHumanInvitesJustBeforeTheTradeDoesNotShutTheBuyerOut() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(940_111, room);
        Character buyer = trader(940_112, room);
        Character human = trader(940_113, room);
        swordIn(seller);
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        AtomicInteger approaches = new AtomicInteger();
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, echoingEconomy(runId),
                mock(EconomyEvidenceJournal.class), settlesBetweenFreeTraders(buyer, seller),
                mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> { }, (shopper, target) -> {
                    if (approaches.getAndIncrement() == 0) { // the human gets there first
                        Trade.startTrade(human);
                        Trade.inviteTrade(human, target);
                    }
                    return CosmicCounterpartyApproachService.Status.ARRIVED;
                });
        service.prepare(seller, profile("seller"), swordPlan(), at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5));

        try (MockedStatic<PacketCreator> ignoredPackets = mockStatic(PacketCreator.class)) {
            var raced = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)),
                    at.plusSeconds(6));

            assertEquals("counterparty is already trading", raced.outcome());
            assertFalse(raced.sold());
            verify(buyer, never()).setTrade(argThat(Objects::nonNull)); // no trade opened
            Trade.cancelTrade(human, Trade.TradeResult.NO_RESPONSE);
        }

        var free = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)), at.plusSeconds(11));

        assertTrue(free.sold(), free.outcome());
        assertEquals(2, approaches.get());
    }

    /**
     * Deploy review 2026-10-10: when the seller had left the offer's room (or the buyer had no Agent runtime), the
     * approach came back UNAVAILABLE on every revisit, and the buyer stayed on that offer until the seller came back or
     * the offer expired. Now the buyer leaves that offer alone for the seller's minimum repeat delay (PT1M here), other
     * buyers may still try it, and after the delay the buyer tries it again.
     */
    @Test
    void aBuyerThatCannotReachTheSellerLeavesThatOfferAloneForAWhile() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        MapleMap room = mock(MapleMap.class);
        Character seller = trader(77, room);
        Character buyer = trader(88, room);
        Character other = trader(99, room);
        swordIn(seller);
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        participants.admitted(profile("other"), other);
        List<Character> approachedBy = new ArrayList<>();
        CosmicNegotiatedTradeExecutor executor = mock(CosmicNegotiatedTradeExecutor.class);
        CosmicOpenChatSaleService service = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                config(), new NamedRandomStreams(1), participants, echoingEconomy(runId),
                mock(EconomyEvidenceJournal.class), executor,
                mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> { }, (shopper, target) -> {
                    approachedBy.add(shopper);
                    return CosmicCounterpartyApproachService.Status.UNAVAILABLE;
                });
        service.prepare(seller, profile("seller"), swordPlan(), at);
        service.progressSeller(seller, profile("seller"), at.plusSeconds(5));

        var unreachable = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)), at.plusSeconds(6));
        var another = service.attemptPurchase(other, profile("other"), List.of(swordNeed(at)), at.plusSeconds(7));
        var within = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)), at.plusSeconds(65));
        var after = service.attemptPurchase(buyer, profile("buyer"), List.of(swordNeed(at)), at.plusSeconds(66));

        assertAll(
                () -> assertEquals("APPROACH_UNAVAILABLE", unreachable.outcome()),
                () -> assertEquals(Boolean.FALSE, unreachable.evidence().get("externalActionPending")),
                () -> assertFalse(within.attempted(), within.outcome()),
                () -> assertEquals("APPROACH_UNAVAILABLE", another.outcome(), "other buyers may still try it"),
                () -> assertEquals("APPROACH_UNAVAILABLE", after.outcome(), "after the delay it tries again"),
                () -> assertEquals(List.of(buyer, other, buyer), approachedBy),
                () -> verifyNoInteractions(executor),
                () -> assertEquals(1, service.views().size()));
    }

    /**
     * The real executor, except that a trade between the two when both are free settles: the settlement itself needs
     * the database (anAgentBuyerRefusedForItsFullBag... runs the real trade up to its refusal).
     */
    private static CosmicNegotiatedTradeExecutor settlesBetweenFreeTraders(Character buyer, Character seller) {
        CosmicNegotiatedTradeExecutor executor = spy(new CosmicNegotiatedTradeExecutor(
                id -> "buyer".equals(id) ? buyer : seller, 160));
        doAnswer(call -> buyer.getTrade() == null && seller.getTrade() == null
                ? new TradeExecutionGateway.Result(true, call.getArgument(0),
                        "Cosmic exact-item PLAYER_TRADE committed", true)
                : call.callRealMethod()).when(executor).executeExactItem(anyString(), anyString(), anyLong(),
                anyString(), anyInt(), anyString(), anyInt());
        return executor;
    }

    private static void swordIn(Character seller) {
        Inventory equips = mock(Inventory.class);
        Item sword = new Item(1302000, (short) 2, (short) 1);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(equips.getItem((short) 2)).thenReturn(sword);
        when(equips.listById(1302000)).thenReturn(List.of(sword));
    }

    private static CosmicAgentEconomyFacade echoingEconomy(UUID runId) {
        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        when(economy.publishIntent(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                anyLong(), anyInt(), anyString(), anyMap(), any(), any())).thenAnswer(call -> new EconomicIntent(
                UUID.randomUUID(), runId, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6),
                call.getArgument(7), call.getArgument(8), call.getArgument(9), call.getArgument(10),
                call.<Instant>getArgument(10).plus(call.<Duration>getArgument(11)), EconomicIntent.Status.OPEN));
        return economy;
    }

    private static MarketSellerPlan swordPlan() {
        return new MarketSellerPlan(List.of(), List.of(new AgentFreeMarketStallService.Listing(InventoryType.EQUIP,
                (short) 2, (short) 1, (short) 1, 400_000)), 910000001, "shop");
    }

    private static AgentNeed swordNeed(Instant at) {
        return new AgentNeed(1302000, 0, 1, 1, EconomicReason.EQUIPMENT_UPGRADE,
                at.plusSeconds(30), 400_000, java.util.Set.of(), java.util.Set.of(), "test");
    }

    /** A free-market character with a real trade slot (server.Trade sets it). */
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
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(character.getMap()).thenReturn(room);
        when(character.getMapId()).thenReturn(910000000);
        when(character.getTrade()).thenAnswer(invocation -> trade.get());
        doAnswer(invocation -> {
            trade.set(invocation.getArgument(0));
            return null;
        }).when(character).setTrade(any());
        return character;
    }

    private static EconomyEngineConfig.OpenChatSelling config() {
        EconomyEngineConfig.OpenChatSelling value = new EconomyEngineConfig.OpenChatSelling();
        value.enabled = true;
        value.eligibleAgentRatio = 1;
        value.maximumActiveOffersPerAgent = 1;
        value.maximumActiveOffersPerRoom = 8;
        value.minimumNpcPremiumBasisPoints = 1000;
        value.maximumNegotiatedDiscountBasisPoints = 1000;
        value.initialAdvertisementDelay = "PT5S";
        value.minimumRepeatDelay = "PT1M";
        value.maximumRepeatDelay = "PT3M";
        value.maximumAdvertisements = 3;
        value.offerLifetime = "PT10M";
        value.negotiationTimeout = "PT2M";
        value.humanTradeEnabled = true;
        value.agentTradeEnabled = true;
        value.allowedMaps = List.of(910000000);
        value.flavorTemplate = "S> {item_stats} {item_name} {ask} meso, trade me";
        return value;
    }

    private static CommerceParticipant profile(String id) {
        return new CommerceParticipant(id, "warrior", .5, .5, .5, .5,
                .5, .5, 24, .5, .5);
    }
}
