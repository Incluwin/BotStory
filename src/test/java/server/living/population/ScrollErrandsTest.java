package server.living.population;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.CitizenSession.Activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Scroll errands (docs/ECONOMY_PLAN.md 10.14): one opens only for a fitting scroll on sale at a price the citizen pays,
 * at most SCROLL_ERRANDS_MAX at once, and ends when it bought, gave up, ran out of time or nothing is on sale for it.
 */
class ScrollErrandsTest {
    private static final int GLOVES = 1082002;
    private static final int GLOVE_ATT = 2040804;
    private static final int DARK_GLOVE_ATT = 2040817;
    private static final int CHAOS = 2049100;
    private static final int FLAG = 2040727;
    private static final int GLOVE_HP = 2040801;
    private static final long INDEX = 400_000L;
    private static final long HOUR = 3_600_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final Map<Integer, Map<String, Integer>> stats = Map.of(
            GLOVE_ATT, Map.of("PAD", 2, "success", 60, "cursed", 0),
            DARK_GLOVE_ATT, Map.of("PAD", 3, "success", 30, "cursed", 50),
            CHAOS, Map.of("success", 60, "cursed", 0),
            FLAG, Map.of("success", 10, "cursed", 0),
            GLOVE_HP, Map.of("MHP", 5, "success", 100, "cursed", 0));
    private final Map<Integer, Long> sale = new HashMap<>();
    private final Map<AgentRuntimeEntry, Character> agents = new HashMap<>();
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private CitizenScrolling scrolling;
    private ScrollErrands errands;

    @BeforeEach
    void wire() {
        config.SCROLL_ERRANDS_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.personaCharacterIds()).thenReturn(Set.of());
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenAnswer(call -> agents.get(call.getArgument(0)));
        scrolling = new CitizenScrolling(population, itemId -> INDEX, stats::get, (scrollId, equipId) -> equipId == GLOVES,
                (who, scrollSlot, equipSlot) -> "SUCCESS");
        errands = new ScrollErrands(population, scrolling, bound -> 0);
        sale.put(GLOVE_ATT, 450_000L);
    }

    @AfterEach
    void close() {
        identity.close();
    }

    private Character agentOf(CitizenSession session) {
        return agents.get(session.entry);
    }

    private CitizenSession session(int id, Set<String> flags) {
        Citizen citizen = new Citizen(id, "Citizen" + id, "evening", 0, 0, "victoria", "warrior-hero", "relaxed-v1", "default",
                1.0d, 0, 1, new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        when(population.citizen(id)).thenReturn(citizen);
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * HOUR, false);
        session.activity = Activity.TRAIN;
        session.channel = 1;
        session.entry = mock(AgentRuntimeEntry.class);
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getJob()).thenReturn(Job.HERO);
        when(agent.getLevel()).thenReturn(50);
        when(agent.getMeso()).thenReturn(5_000_000);
        when(agent.getClient()).thenReturn(mock(Client.class));
        bag(agent, List.of(), (short) 4);
        Equip gloves = mock(Equip.class);
        when(gloves.getItemId()).thenReturn(GLOVES);
        when(gloves.getUpgradeSlots()).thenReturn((byte) 5);
        Inventory worn = mock(Inventory.class);
        when(worn.list()).thenReturn(List.of(gloves));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        agents.put(session.entry, agent);
        return session;
    }

    private static void bag(Character agent, List<Item> scrolls, short free) {
        Inventory use = mock(Inventory.class);
        when(use.list()).thenReturn(scrolls);
        when(use.getNumFreeSlot()).thenReturn(free);
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
    }

    private void tick(long now, CitizenSession... sessions) {
        errands.tick(List.of(sessions), channel -> sale, now);
    }

    private boolean opens(CitizenSession session) {
        tick(0L, session);
        return errands.any(session.characterId);
    }

    @Test
    void aFittingScrollOnSaleOpensOneUpToThePriceItPays() {
        CitizenSession lumi = session(1, Set.of());
        assertTrue(opens(lumi));
        assertEquals(Math.round(INDEX * config.SCROLL_BUY_OVER_INDEX), errands.offer(agentOf(lumi), GLOVE_ATT));
        assertEquals(0L, errands.offer(agentOf(lumi), DARK_GLOVE_ATT));
        assertEquals(1, errands.demand(2, GLOVE_ATT), "what another's stall is ranked by");
        assertEquals(0, errands.demand(1, GLOVE_ATT), "not its own want");
    }

    @Test
    void noErrandForACursedChaosFlagOrUselessScrollNorOneOverItsOffer() {
        for (int scroll : new int[]{DARK_GLOVE_ATT, CHAOS, FLAG, GLOVE_HP}) {
            sale.clear();
            sale.put(scroll, 1L);
            assertFalse(opens(session(scroll, Set.of())), "scroll " + scroll);
        }
        sale.clear();
        sale.put(GLOVE_ATT, Math.round(INDEX * config.SCROLL_BUY_OVER_INDEX) + 1L);
        assertFalse(opens(session(2, Set.of())), "over 1.3 x the index");
    }

    @Test
    void noErrandForOneThatHoldsAUsableScrollIsPoorFullOrMindsAStall() {
        CitizenSession holder = session(1, Set.of());
        Item own = mock(Item.class);
        when(own.getItemId()).thenReturn(GLOVE_ATT);
        when(own.getQuantity()).thenReturn((short) 1);
        bag(agentOf(holder), List.of(own), (short) 4);
        assertFalse(opens(holder));
        CitizenSession poor = session(2, Set.of());
        when(agentOf(poor).getMeso()).thenReturn(1_000);
        assertFalse(opens(poor));
        CitizenSession full = session(3, Set.of());
        bag(agentOf(full), List.of(), (short) 0);
        assertFalse(opens(full));
        assertFalse(opens(session(4, Set.of(Citizen.FLAG_MERCHANT))));
        CitizenSession running = session(5, Set.of());
        running.activity = Activity.JOB;
        assertFalse(opens(running));
    }

    @Test
    void atMostTheCapAreOutAndItsEndLetsTheNextGo() {
        config.SCROLL_ERRANDS_MAX = 2;
        CitizenSession[] all = {session(1, Set.of()), session(2, Set.of()), session(3, Set.of())};
        tick(0L, all);
        assertEquals(2, List.of(all).stream().filter(s -> errands.any(s.characterId)).count());
        errands.bought(all[0], GLOVE_ATT, 0L);
        tick(1L, all);
        assertFalse(errands.any(1), "it rests first");
        assertTrue(errands.any(2));
        assertTrue(errands.any(3));
    }

    @Test
    void itEndsWhenBoughtGivenUpOutOfTimeOrNothingIsOnSaleAndThenRests() {
        CitizenSession lumi = session(1, Set.of());
        assertTrue(opens(lumi));
        lumi.activity = Activity.MARKET;
        lumi.lastPlanAtMs = 77L;
        errands.bought(lumi, 1302012, 0L);
        assertTrue(errands.any(1), "a sword is not its scroll");
        errands.bought(lumi, GLOVE_ATT, 0L);
        assertFalse(errands.any(1));
        assertEquals(0L, lumi.lastPlanAtMs, "it plans anew instead of standing about");
        tick(config.SCROLL_ERRAND_RETRY_HOURS * HOUR - 1L, lumi);
        assertFalse(errands.any(1));
        long next = config.SCROLL_ERRAND_RETRY_HOURS * HOUR;
        tick(next, lumi);
        assertTrue(errands.any(1));
        errands.gaveUp(lumi, next);
        assertFalse(errands.any(1));

        CitizenSession slow = session(2, Set.of());
        assertTrue(opens(slow));
        tick(config.SCROLL_ERRAND_HOURS * HOUR, slow);
        assertFalse(errands.any(2));

        CitizenSession late = session(3, Set.of());
        assertTrue(opens(late));
        sale.clear();
        tick(1L, late);
        assertFalse(errands.any(3), "sold to somebody else");
    }

    @Test
    void oneThatLeftIsForgottenAndSoIsEveryOneWhenTheSwitchGoesOff() {
        CitizenSession lumi = session(1, Set.of());
        assertTrue(opens(lumi));
        tick(1L);
        assertFalse(errands.any(1));
        CitizenSession bran = session(2, Set.of());
        assertTrue(opens(bran));
        config.SCROLL_ERRANDS_ENABLED = false;
        assertFalse(errands.any(2));
        assertEquals(0L, errands.offer(agentOf(bran), GLOVE_ATT));
        tick(2L, bran);
        assertEquals(0, errands.demand(9, GLOVE_ATT));
    }

    @Test
    void offNoOneIsEvenLookedAt() {
        config.SCROLL_ERRANDS_ENABLED = false;
        errands.tick(List.of(session(1, Set.of())), channel -> {
            throw new AssertionError("read the stalls");
        }, 0L);
        assertFalse(errands.any(1));
    }

    // ---- the stall cap -------------------------------------------------------------------

    private static Item stack(int itemId) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        return item;
    }

    private static List<Integer> ids(List<Item> items) {
        return items.stream().map(Item::getItemId).toList();
    }

    @Test
    void theCapKeepsTheDearestScrollOfAnyKindAndTheOneMostWanted() {
        List<Item> ranked = List.of(stack(CHAOS), stack(4010000), stack(GLOVE_HP), stack(GLOVE_ATT), stack(1302012));
        assertEquals(List.of(CHAOS, 4010000, GLOVE_ATT, 1302012),
                ids(Market.capScrolls(ranked, 2, itemId -> itemId == GLOVE_ATT ? 3 : 0, itemId -> false)));
        assertEquals(List.of(CHAOS, 4010000, GLOVE_ATT, 1302012),
                ids(Market.capScrolls(ranked, 2, itemId -> 0, itemId -> itemId == GLOVE_ATT)), "useful to some job");
        assertEquals(List.of(CHAOS, 4010000, 1302012), ids(Market.capScrolls(ranked, 1, itemId -> 9, itemId -> true)));
        assertEquals(List.of(4010000, 1302012), ids(Market.capScrolls(ranked, 0, itemId -> 9, itemId -> true)));
    }

    @Test
    void aSecondKindGoesBeforeASecondStackAndAStallWithinTheCapIsUntouched() {
        List<Item> ranked = List.of(stack(GLOVE_ATT), stack(GLOVE_ATT), stack(GLOVE_HP));
        assertEquals(List.of(GLOVE_ATT, GLOVE_HP), ids(Market.capScrolls(ranked, 2, itemId -> 0, itemId -> true)));
        List<Item> twoStacks = List.of(stack(GLOVE_ATT), stack(GLOVE_ATT), stack(GLOVE_ATT));
        assertEquals(2, Market.capScrolls(twoStacks, 2, itemId -> 0, itemId -> true).size());
        List<Item> few = new ArrayList<>(List.of(stack(CHAOS), stack(GLOVE_ATT), stack(4010000)));
        assertTrue(few == Market.capScrolls(few, 2, itemId -> 0, itemId -> true));
    }
}
