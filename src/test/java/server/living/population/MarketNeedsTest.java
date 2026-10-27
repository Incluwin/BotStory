package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService.ObservedStall;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService.PurchaseResult;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.living.population.CitizenSession.Activity;
import server.living.population.needs.NeedLedger;
import server.maps.PlayerShop;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The market follows needs (docs/BOT_CONCEPT_PLAN.md F1): a browsing citizen with an open scroll or gear want buys
 * exactly that item from a player's shop at up to its offer - above the 90%-of-index rule and outside the daily browse
 * budget - and the request is then met; never above its offer, never a bundle, never an equip below base.
 */
class MarketNeedsTest {
    private static final int CITIZEN = 5001;
    private static final int ANN = 10;
    private static final int ROOM = 910000003;
    private static final int CLAW_ATT = 2044701;
    private static final int GARNIER = 1472030;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PopulationContent content = mock(PopulationContent.class);
    private final NeedLedger needs = mock(NeedLedger.class);
    private final Character agent = mock(Character.class);
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedConstruction<AgentFreeMarketBuyerService> buyers;
    private MockedStatic<Market> names;
    private CitizenSession session;
    private List<PlayerShop.ListingView> listings;

    @BeforeEach
    void kimBrowsesAnnsShop() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(presence);
        when(population.content()).thenReturn(content);
        when(population.needs()).thenReturn(needs);
        when(store.prices()).thenReturn(List.of(new int[]{CLAW_ATT, 2_500_000, 3}, new int[]{GARNIER, 4_000_000, 3}));
        Citizen citizen = new Citizen(CITIZEN, "Kim", "evening", 0, 0, "victoria", "assassin", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = new CitizenSession(citizen, 0L, 3_600_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        session.activity = Activity.MARKET;
        when(presence.sessions()).thenReturn(List.of(session));
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getMapId()).thenReturn(ROOM);
        when(agent.getMeso()).thenReturn(5_000_000);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
        // item names are WZ data, which needs the database
        names = mockStatic(Market.class,
                call -> "itemName".equals(call.getMethod().getName()) ? "an item" : call.callRealMethod());
        buyers = mockConstruction(AgentFreeMarketBuyerService.class, (buyer, context) -> {
            when(buyer.observeNearby(agent)).thenAnswer(call -> List.of(new ObservedStall(77, ANN, "Ann", ROOM, 0, "p",
                    listings)));
            when(buyer.buy(eq(agent), eq(77), anyInt(), anyShort())).thenAnswer(call -> new PurchaseResult(true, "OK", ANN,
                    listings.get(0).itemId(), 1, -listings.get(0).bundlePrice()));
        });
    }

    @AfterEach
    void close() {
        names.close();
        buyers.close();
        identity.close();
    }

    private static PlayerShop.ListingView listing(int itemId, short perBundle, int price, Map<String, Object> stats) {
        return new PlayerShop.ListingView(0, itemId, perBundle, (short) 1, price, "f", stats);
    }

    @Test
    void aWantedScrollIsBoughtAtUpToTheOfferAndTheRequestIsMet() {
        when(needs.marketOffer(eq(CITIZEN), eq(CLAW_ATT), any(), anyLong())).thenReturn(2_600_000L);
        listings = List.of(listing(CLAW_ATT, (short) 1, 2_550_000, Map.of())); // over 90% of the 2.5m index
        new Market(population).tick();

        AgentFreeMarketBuyerService buyer = buyers.constructed().get(0);
        verify(buyer).buy(agent, 77, 0, (short) 1);
        verify(needs).boughtAtMarket(eq(session), eq(agent), eq(CLAW_ATT), eq(ANN), anyLong());
    }

    @Test
    void neverAboveTheOfferNorABundle() {
        when(needs.marketOffer(eq(CITIZEN), eq(CLAW_ATT), any(), anyLong())).thenReturn(2_000_000L);
        listings = List.of(listing(CLAW_ATT, (short) 1, 2_550_000, Map.of()));
        new Market(population).tick();
        verify(buyers.constructed().get(0), never()).buy(any(), anyInt(), anyInt(), anyShort());

        when(needs.marketOffer(eq(CITIZEN), eq(CLAW_ATT), any(), anyLong())).thenReturn(0L);
        listings = List.of(listing(CLAW_ATT, (short) 10, 2_000_000, Map.of())); // a bundle at 200k: under the index
        new Market(population).tick();
        verify(needs, never()).boughtAtMarket(any(), any(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void anEquipBelowBaseIsNotWhatItAskedFor() {
        Map<String, Object> weak = Map.of("weaponAttack", 1);
        when(needs.marketOffer(eq(CITIZEN), eq(GARNIER), any(), anyLong())).thenReturn(5_000_000L);
        when(needs.marketOffer(eq(CITIZEN), eq(GARNIER), eq(weak), anyLong())).thenReturn(0L); // the ledger judges the stats
        listings = List.of(listing(GARNIER, (short) 1, 3_000_000, weak));
        new Market(population).tick();
        verify(buyers.constructed().get(0), never()).buy(any(), anyInt(), anyInt(), anyShort());
        verify(needs, never()).boughtAtMarket(any(), any(), anyInt(), anyInt(), anyLong());
    }
}
