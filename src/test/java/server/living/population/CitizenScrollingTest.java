package server.living.population;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession.Activity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Citizens scroll their own gear (docs/ECONOMY_PLAN.md E3): out and about, a citizen uses the scroll from its bag worth
 * most to its job on a worn piece with slots left, up to its daily cap; never a scroll that can destroy the piece; a
 * browsing citizen with meso to spare buys a fitting one when its bag has none and uses it at once; a merchant keeps the
 * scrolls it would use off its stall.
 */
class CitizenScrollingTest {
    private static final int ID = 900;
    private static final int GLOVES = 1082002;
    private static final short GLOVE_SLOT = -8;
    private static final int GLOVE_ATT = 2040804;
    private static final int GLOVE_DEX = 2040805;
    private static final int DARK_GLOVE_ATT = 2040817;
    private static final int CHAOS = 2049100;
    private static final int STAFF_MATT = 2043802;
    private static final long DAY = 86_400_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final Map<Integer, Map<String, Integer>> stats = Map.of(
            GLOVE_ATT, Map.of("PAD", 2, "success", 60, "cursed", 0),
            GLOVE_DEX, Map.of("DEX", 2, "success", 60, "cursed", 0),
            DARK_GLOVE_ATT, Map.of("PAD", 3, "success", 30, "cursed", 50),
            CHAOS, Map.of("success", 60, "cursed", 0),
            STAFF_MATT, Map.of("MAD", 2, "success", 60, "cursed", 0));
    private final Set<Integer> forGloves = Set.of(GLOVE_ATT, GLOVE_DEX, DARK_GLOVE_ATT);
    private final List<short[]> applied = new ArrayList<>();
    private final List<Item> bag = new ArrayList<>();
    private Citizen citizen;
    private CitizenSession session;
    private Character agent;
    private Equip gloves;
    private CitizenScrolling scrolling;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.personaCharacterIds()).thenReturn(Set.of());
        citizen = citizen(Set.of());
        when(population.citizen(ID)).thenAnswer(inv -> citizen);
        session = new CitizenSession(citizen, 0L, DAY, false);
        session.activity = Activity.TRAIN;
        agent = mock(Character.class);
        when(agent.getId()).thenReturn(ID);
        when(agent.getName()).thenReturn("Lumi");
        when(agent.getJob()).thenReturn(Job.HERO);
        when(agent.getLevel()).thenReturn(50);
        when(agent.getMeso()).thenReturn(5_000_000);
        Client client = mock(Client.class);
        when(client.tryacquireClient()).thenReturn(true);
        when(agent.getClient()).thenReturn(client);
        Inventory use = mock(Inventory.class);
        when(use.list()).thenAnswer(inv -> List.copyOf(bag));
        when(use.findById(anyInt())).thenAnswer(inv -> bag.stream()
                .filter(item -> item.getItemId() == (int) inv.getArgument(0)).findFirst().orElse(null));
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
        gloves = mock(Equip.class);
        when(gloves.getItemId()).thenReturn(GLOVES);
        when(gloves.getPosition()).thenReturn(GLOVE_SLOT);
        when(gloves.getUpgradeSlots()).thenReturn((byte) 5);
        Inventory equipped = mock(Inventory.class);
        when(equipped.list()).thenReturn(List.of(gloves));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        scrolling = new CitizenScrolling(population, itemId -> 400_000L, stats::get,
                (scrollId, equipId) -> equipId == GLOVES && forGloves.contains(scrollId),
                (who, scrollSlot, equipSlot) -> {
                    applied.add(new short[]{scrollSlot, equipSlot});
                    return "SUCCESS";
                });
        scrolling.names = itemId -> "item " + itemId;
        scrolling.roll = () -> 0.0d;
    }

    @Test
    void itScrollsAtAboutOneTickInFour() {
        bag.add(new Item(GLOVE_ATT, (short) 7, (short) 9));
        scrolling.roll = () -> 0.3d;
        scrolling.tick(session, agent, 10 * DAY);
        assertTrue(applied.isEmpty(), "a roll over 0.25 waits for a later tick");
        scrolling.roll = () -> 0.2d;
        scrolling.tick(session, agent, 10 * DAY + 120_000L);
        assertEquals(1, applied.size());
    }

    @Test
    void itUsesTheScrollWorthMostToItsJobOnAWornPieceWithSlots() {
        bag.add(new Item(GLOVE_DEX, (short) 3, (short) 1));
        bag.add(new Item(GLOVE_ATT, (short) 7, (short) 2));
        scrolling.tick(session, agent, 10 * DAY);
        assertEquals(1, applied.size(), "one scroll a tick");
        assertEquals(7, applied.get(0)[0], "attack beats DEX for a Hero");
        assertEquals(GLOVE_SLOT, applied.get(0)[1]);
        assertTrue(scrolling.json().contains("\"used\":1") && scrolling.json().contains("\"success\":1"));
    }

    @Test
    void neverAScrollThatCanDestroyThePieceOrOneItsJobHasNoUseFor() {
        bag.add(new Item(DARK_GLOVE_ATT, (short) 1, (short) 1));
        bag.add(new Item(CHAOS, (short) 2, (short) 1));
        bag.add(new Item(STAFF_MATT, (short) 3, (short) 1));
        scrolling.tick(session, agent, 10 * DAY);
        assertTrue(applied.isEmpty(), "a dark scroll, a chaos scroll and magic attack for a Hero");
        assertEquals(0.0d, CitizenScrolling.value(Job.HERO.getId(), stats.get(STAFF_MATT)));
        assertEquals(2.0d, CitizenScrolling.value(Job.BISHOP.getId(), stats.get(STAFF_MATT)), 1e-9);
    }

    @Test
    void noSlotsLeftNothingAndTheDailyCapHolds() {
        bag.add(new Item(GLOVE_ATT, (short) 7, (short) 9));
        config.SCROLLS_PER_DAY = 2;
        for (int tick = 0; tick < 4; tick++) {
            scrolling.tick(session, agent, 10 * DAY + tick * 120_000L);
        }
        assertEquals(2, applied.size(), "SCROLLS_PER_DAY");
        scrolling.tick(session, agent, 11 * DAY);
        assertEquals(3, applied.size(), "a new day");

        when(gloves.getUpgradeSlots()).thenReturn((byte) 0);
        scrolling.tick(session, agent, 12 * DAY);
        assertEquals(3, applied.size(), "no slots left on its gloves");
    }

    @Test
    void onlyOutAndAboutWithItsStallShutAndNeverAHiredHandOrPersona() {
        bag.add(new Item(GLOVE_ATT, (short) 7, (short) 9));
        session.activity = Activity.PQ;
        scrolling.tick(session, agent, 10 * DAY);
        session.activity = Activity.MARKET;
        session.stallOpen = true;
        scrolling.tick(session, agent, 10 * DAY);
        session.stallOpen = false;
        citizen = citizen(Set.of(Citizen.FLAG_HIRED));
        scrolling.tick(session, agent, 10 * DAY);
        assertTrue(applied.isEmpty(), "in a run, behind its stall, a player's hired hand");

        citizen = citizen(Set.of());
        when(store.personaCharacterIds()).thenReturn(Set.of(ID));
        CitizenScrolling persona = new CitizenScrolling(population, itemId -> 400_000L, stats::get,
                (scrollId, equipId) -> true, (who, scrollSlot, equipSlot) -> "SUCCESS");
        persona.tick(session, agent, 10 * DAY);
        assertTrue(persona.json().contains("\"used\":0"), "a persona's gear is drawn elsewhere");

        scrolling.tick(session, agent, 10 * DAY);
        assertEquals(1, applied.size(), "browsing, its stall shut");
        config.MARKET_SCROLLING = false;
        scrolling.tick(session, agent, 10 * DAY + 120_000L);
        assertEquals(1, applied.size(), "switched off");
    }

    @Test
    void itBuysAFittingScrollOnlyWithMesoToSpareAndNoneOfItsOwnToUseFirst() {
        // 1.5 x expectedMeso(50) = 1.06m kept back; 5m on hand
        assertEquals(520_000L, scrolling.offer(agent, GLOVE_ATT), "1.3 x the 400k index");
        assertEquals(0L, scrolling.offer(agent, GLOVE_DEX), "DEX is a Hero's second stat: +2 is 0.6 points, under SCROLL_BUY_MIN_VALUE");
        assertEquals(0L, scrolling.offer(agent, DARK_GLOVE_ATT), "never a dark scroll");
        assertEquals(0L, scrolling.offer(agent, STAFF_MATT), "nothing it wears takes it");

        when(agent.getMeso()).thenReturn(1_400_000);
        long keptBack = Math.round(1.5d * Market.expectedMeso(50));
        assertEquals(1_400_000L - keptBack, scrolling.offer(agent, GLOVE_ATT), "no more than it has over what it keeps back");
        when(agent.getMeso()).thenReturn(1_000_000);
        assertEquals(0L, scrolling.offer(agent, GLOVE_ATT), "nothing to spare");

        when(agent.getMeso()).thenReturn(5_000_000);
        bag.add(new Item(GLOVE_ATT, (short) 7, (short) 1));
        assertEquals(0L, scrolling.offer(agent, GLOVE_ATT), "it uses its own first");
    }

    @Test
    void aBoughtScrollIsUsedAtOnce() {
        bag.add(new Item(GLOVE_ATT, (short) 12, (short) 1));
        scrolling.bought(agent, GLOVE_ATT);
        assertEquals(1, applied.size());
        assertEquals(12, applied.get(0)[0]);
        assertTrue(scrolling.json().contains("\"bought\":1"));
    }

    @Test
    void itKeepsTheScrollsItWouldUseOffItsStall() {
        assertTrue(scrolling.keeps(agent, new Item(GLOVE_ATT, (short) 1, (short) 1)));
        assertFalse(scrolling.keeps(agent, new Item(STAFF_MATT, (short) 2, (short) 1)), "listed for a mage to buy");
        assertFalse(scrolling.keeps(agent, new Item(DARK_GLOVE_ATT, (short) 3, (short) 1)), "listed: it never uses one");
    }

    @Test
    void cygnusAndAranScrollAsTheirExplorerBranch() {
        assertEquals(1, CitizenScrolling.branch(Job.ARAN4.getId()));
        assertEquals(2, CitizenScrolling.branch(Job.BLAZEWIZARD3.getId()));
        assertEquals(1, CitizenScrolling.branch(Job.BEGINNER.getId()));
        assertEquals(52, CitizenScrolling.branch(Job.OUTLAW.getId()));
        assertEquals(5, CitizenScrolling.branch(Job.BUCCANEER.getId()));
    }

    private static Citizen citizen(Set<String> flags) {
        return new Citizen(ID, "Lumi", "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
