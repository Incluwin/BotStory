package server.living.population;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.knowledge.KnowledgeTestKit;
import server.knowledge.WorldKnowledge;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Buy, don't receive, gear (docs/ECONOMY_PLAN.md E3): a citizen's level-band upgrade becomes an errand to buy a fitting
 * piece at a stall; a listing that fits the slot, band, job and stats and beats what it wears is worth up to 1.3x the
 * index to it; what it buys is worn; and only an errand that found nothing in time, or a pick it cannot pay for, is
 * handed over as before.
 */
class GearErrandsTest {
    private static final int CITIZEN = 4242;
    private static final int MAPLE_SWORD = 1302020; // reqLevel 35
    private static final int RED_KATANA = 1302012;  // reqLevel 70, a one-handed sword like it
    private static final int[] STATS = {300, 60, 4, 4};

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();

    private GearErrands errands(int... pricedItems) {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        List<int[]> rows = new ArrayList<>();
        for (int itemId : pricedItems) {
            rows.add(new int[] {itemId, 100_000, 3});
        }
        when(store.prices()).thenReturn(rows);
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        Market market = new Market(population, itemId -> 40_000L); // an unpriced equip seeds at 2.5x: 100k
        when(population.market()).thenReturn(market);
        return market.gearErrands();
    }

    private static Character fighter(int meso) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("Brannoc");
        when(agent.getLevel()).thenReturn(75);
        when(agent.getJob()).thenReturn(Job.FIGHTER);
        when(agent.getGender()).thenReturn(0);
        when(agent.getStr()).thenReturn(STATS[0]);
        when(agent.getDex()).thenReturn(STATS[1]);
        when(agent.getInt()).thenReturn(STATS[2]);
        when(agent.getLuk()).thenReturn(STATS[3]);
        when(agent.getMeso()).thenReturn(meso);
        Item sword = new Item(MAPLE_SWORD, (short) -11, (short) 1);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenReturn(sword);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        Inventory bag = mock(Inventory.class);
        when(bag.list()).thenReturn(List.of());
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        return agent;
    }

    private static int pick(WorldKnowledge k) {
        return CitizenGear.upgradeFor(k, "weapon", MAPLE_SWORD, 75, Job.FIGHTER, 0, STATS, CITIZEN * 31L + "weapon".hashCode());
    }

    @Test
    void anUpgradeBecomesAnErrandThatAFittingListingAnswers() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            int pick = pick(WorldKnowledge.current());
            GearErrands errands = errands(pick, RED_KATANA, MAPLE_SWORD);
            Character agent = fighter(1_000_000);

            assertTrue(errands.holdBack(agent, "weapon", pick), "it shops for the sword instead of receiving it");
            assertTrue(errands.any(CITIZEN));
            assertTrue(errands.holdBack(agent, "weapon", pick), "still shopping at the next load");
            assertEquals(130_000L, errands.offer(agent, RED_KATANA), "a reqLevel-70 sword: up to 1.3x its 100k index");
            assertEquals(0L, errands.offer(agent, MAPLE_SWORD), "no better than the one it wears");
        }
    }

    @Test
    void anErrandThatFoundNothingInTimeHandsThePieceOver() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            int pick = pick(WorldKnowledge.current());
            GearErrands errands = errands(pick);
            config.MARKET_GEAR_ERRAND_HOURS = 0;
            Character agent = fighter(1_000_000);

            assertTrue(errands.holdBack(agent, "weapon", pick));
            assertFalse(errands.holdBack(agent, "weapon", pick), "its time is up: handed over as before");
            assertFalse(errands.any(CITIZEN));
            assertTrue(errands.report().contains("handedOver=1"), errands.report());
        }
    }

    @Test
    void aCitizenThatCannotPayOrWithTheSwitchOffReceivesAsBefore() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            int pick = pick(WorldKnowledge.current());
            GearErrands errands = errands(pick);
            assertFalse(errands.holdBack(fighter(50_000), "weapon", pick), "its 100k index price is more than it has");
            config.MARKET_BUY_GEAR = false;
            assertFalse(errands.holdBack(fighter(1_000_000), "weapon", pick));
            assertFalse(errands.any(CITIZEN));
        }
    }

    @Test
    void whatItBuysIsWornAndTheErrandEnds() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed();
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class)) {
            int pick = pick(WorldKnowledge.current());
            GearErrands errands = errands(pick, RED_KATANA);
            Character agent = fighter(1_000_000);
            equipment.when(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort())).thenReturn(true);
            errands.holdBack(agent, "weapon", pick);

            errands.bought(agent, RED_KATANA);

            equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, RED_KATANA, (short) -11));
            assertFalse(errands.any(CITIZEN));
            assertTrue(errands.report().contains("bought=1"), errands.report());
        }
    }

    @Test
    void theUpgradeOfAShoppingCitizenAddsNothing() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed();
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class)) {
            int pick = pick(WorldKnowledge.current());
            GearErrands errands = errands(pick);
            Character agent = fighter(1_000_000);

            CitizenGear.upgrade(agent, Job.FIGHTER, null, errands);

            inventory.verify(() -> InventoryManipulator.addById(any(), eq(pick), anyShort()), never());
            assertTrue(errands.any(CITIZEN));
        }
    }
}
