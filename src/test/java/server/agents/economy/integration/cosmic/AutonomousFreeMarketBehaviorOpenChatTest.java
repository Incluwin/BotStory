package server.agents.economy.integration.cosmic;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.economy.communication.EconomicIntent;
import server.agents.economy.decision.AgentNeed;
import server.agents.economy.market.EconomicReason;
import server.agents.economy.market.FreeMarketPhysicalGateway;
import server.agents.economy.persistence.DecisionEvidence;
import server.agents.economy.persistence.EconomyEvidenceJournal;
import server.agents.economy.scenario.EconomyEngineConfig;
import server.agents.economy.scenario.NamedRandomStreams;
import server.agents.economy.session.CommerceParticipant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The market behavior around an open-chat purchase the buyer cannot reach. */
class AutonomousFreeMarketBehaviorOpenChatTest {
    private static final int ROOM = 910000001;

    /**
     * Deploy review 2026-10-10: an UNAVAILABLE approach (the seller left the offer's room, or the buyer has no Agent
     * runtime) returned a revisit before the stall trip, so the buyer never inspected a stall and wrote one
     * OPEN_CHAT_PURCHASE row per 5-second revisit until the seller came back or the offer expired (PT10M). Now the
     * buyer goes on with its stall trip in the same tick and leaves the offer alone for the seller's minimum repeat
     * delay (PT1M): one row per delay, the trip walking on at every revisit.
     */
    @Test
    void aBuyerThatCannotReachTheSellerGoesOnWithItsStallTrip() {
        UUID runId = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        Character seller = character(77);
        Character buyer = character(88);
        Inventory equips = mock(Inventory.class);
        Item sword = new Item(1302000, (short) 2, (short) 1);
        when(seller.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(equips.getItem((short) 2)).thenReturn(sword);
        EconomyParticipantRegistry participants = new EconomyParticipantRegistry(id -> null);
        participants.admitted(profile("seller"), seller);
        participants.admitted(profile("buyer"), buyer);
        CosmicAgentEconomyFacade economy = echoingEconomy(runId);
        AtomicInteger approaches = new AtomicInteger();
        CosmicOpenChatSaleService openChat = new CosmicOpenChatSaleService(runId, "cfg", "catalog",
                openChatConfig(), new NamedRandomStreams(1), participants, economy,
                mock(EconomyEvidenceJournal.class), mock(CosmicNegotiatedTradeExecutor.class),
                mock(CosmicOpenChatSaleService.ReservationGateway.class), (itemId, quantity) -> 100_000,
                (speaker, text) -> { }, (shopper, target) -> {
                    approaches.incrementAndGet();
                    return CosmicCounterpartyApproachService.Status.UNAVAILABLE;
                });
        openChat.prepare(seller, profile("seller"), new MarketSellerPlan(List.of(), List.of(
                new AgentFreeMarketStallService.Listing(InventoryType.EQUIP, (short) 2, (short) 1, (short) 1,
                        400_000)), ROOM, "shop"), at);
        openChat.progressSeller(seller, profile("seller"), at.plusSeconds(5)); // the offer is called out

        FreeMarketPhysicalGateway physical = mock(FreeMarketPhysicalGateway.class);
        FreeMarketPhysicalGateway.StallTarget stall = new FreeMarketPhysicalGateway.StallTarget(5, 66, ROOM, 300, 0);
        when(physical.requestEntrance(buyer)).thenReturn(FreeMarketPhysicalGateway.ActionStatus.ARRIVED);
        when(physical.requestRoom(buyer, ROOM)).thenReturn(FreeMarketPhysicalGateway.ActionStatus.ARRIVED);
        when(physical.visibleStalls(buyer)).thenReturn(List.of(stall));
        when(physical.requestApproach(buyer, stall)).thenReturn(FreeMarketPhysicalGateway.ActionStatus.ASSIGNED);
        EconomyEvidenceJournal journal = mock(EconomyEvidenceJournal.class);
        List<String> decisions = new ArrayList<>();
        doAnswer(call -> decisions.add(call.<DecisionEvidence>getArgument(0).decisionKind()))
                .when(journal).appendDecision(any());
        AgentNeed swordNeed = new AgentNeed(1302000, 0, 1, 1, EconomicReason.EQUIPMENT_UPGRADE,
                at.plusSeconds(30), 400_000, Set.of(), Set.of(), "test");
        AutonomousFreeMarketBehavior market = new AutonomousFreeMarketBehavior(runId, "cfg", "catalog",
                marketConfig(), new NamedRandomStreams(1), physical, (agent, profile, logicalAt) -> List.of(swordNeed),
                (agent, profile, observations, base, logicalAt) -> base, journal,
                mock(CosmicMarketSellerPlanReader.class), mock(CosmicMarketSellerGateway.class), economy,
                (agent, profile, attempted) -> Optional.empty(),
                AutonomousFreeMarketBehavior.AmbientBehavior.disabled(),
                AutonomousFreeMarketBehavior.NegotiationBehavior.disabled(),
                AutonomousFreeMarketBehavior.ScrollBehavior.disabled(),
                AutonomousFreeMarketBehavior.OfferReviewBehavior.disabled(),
                AutonomousFreeMarketBehavior.QuestBehavior.disabled(),
                AutonomousFreeMarketBehavior.ArrangementBehavior.disabled(), ROOM, ROOM, session(),
                Duration.ofSeconds(5), Duration.ofMinutes(15), Duration.ofHours(4), Duration.ofSeconds(1),
                openChat);

        for (int second = 6; second <= 61; second += 5) // twelve revisits within the minute
            market.perform(buyer, profile("buyer"), at.plusSeconds(second));

        assertAll(
                () -> assertEquals(1, decisions.stream().filter("OPEN_CHAT_PURCHASE"::equals).count(),
                        decisions::toString),
                () -> assertEquals(1, approaches.get(), "the seller is not looked for on every revisit"),
                () -> verify(physical, times(12)).requestApproach(buyer, stall)); // the trip walks on, every revisit

        market.perform(buyer, profile("buyer"), at.plusSeconds(66)); // the minute is over

        assertAll(
                () -> assertEquals(2, decisions.stream().filter("OPEN_CHAT_PURCHASE"::equals).count(),
                        decisions::toString),
                () -> assertEquals(2, approaches.get(), "it looks for the seller again"),
                () -> verify(physical, times(13)).requestApproach(buyer, stall));
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        Inventory empty = mock(Inventory.class);
        when(empty.list()).thenReturn(List.of());
        when(character.getId()).thenReturn(id);
        when(character.getMapId()).thenReturn(ROOM);
        when(character.getMeso()).thenReturn(1_000_000);
        when(character.getLevel()).thenReturn(30);
        when(character.getJob()).thenReturn(Job.WARRIOR);
        when(character.getInventory(any())).thenReturn(empty);
        return character;
    }

    private static CosmicAgentEconomyFacade echoingEconomy(UUID runId) {
        CosmicAgentEconomyFacade economy = mock(CosmicAgentEconomyFacade.class);
        when(economy.publishIntent(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                anyLong(), anyInt(), anyString(), anyMap(), any(), any())).thenAnswer(call -> new EconomicIntent(
                UUID.randomUUID(), runId, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6),
                call.getArgument(7), call.getArgument(8), call.<Map<String, Object>>getArgument(9),
                call.getArgument(10), call.<Instant>getArgument(10).plus(call.<Duration>getArgument(11)),
                EconomicIntent.Status.OPEN));
        return economy;
    }

    private static EconomyEngineConfig.Market marketConfig() {
        EconomyEngineConfig.Market value = new EconomyEngineConfig.Market();
        value.minimumRepriceInterval = "PT30M";
        value.stallInspectionDurationPerListing = "PT0.5S";
        value.minimumRoomsPerTrip = 1;
        value.maximumRoomsPerTrip = 1;
        return value;
    }

    private static EconomyEngineConfig.Session session() {
        EconomyEngineConfig.Session value = new EconomyEngineConfig.Session();
        value.exitWhenPrimaryGoalsComplete = true;
        value.maximumConsecutiveUnproductiveStalls = 12;
        value.knowledgeOnlyBrowsingEnabled = true;
        return value;
    }

    private static EconomyEngineConfig.OpenChatSelling openChatConfig() {
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
        value.allowedMaps = List.of(ROOM);
        value.flavorTemplate = "S> {item_stats} {item_name} {ask} meso, trade me";
        return value;
    }

    private static CommerceParticipant profile(String id) {
        return new CommerceParticipant(id, "warrior", .5, .5, .5, .5,
                .5, .5, 24, .5, .5);
    }
}
