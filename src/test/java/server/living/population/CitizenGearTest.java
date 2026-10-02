package server.living.population;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.knowledge.ItemFact;
import server.knowledge.KnowledgeTestKit;
import server.knowledge.WorldKnowledge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Citizen gear by level (docs/SERVER_KNOWLEDGE_PLAN.md 8.4): a citizen past its career's last loadout band wears gear of
 * its own level band, never a downgrade or a sidegrade; low levels, Beginners and the flags off change nothing. A crowd of
 * one job and band is not one kit (tiers, kept pieces, no cape), and a pick it already owns is worn, never bought again.
 */
class CitizenGearTest {
    private static final int MAPLE_SWORD = 1302020; // perion-warrior-l30's sword, reqLevel 35
    private static final int RED_KATANA = 1302012; // reqLevel 70
    private static final int[] STATS = {300, 60, 4, 4};
    private static final int MALE = 0;
    private static final List<String> ARMOR = List.of("cap", "coat", "longcoat", "pants", "shoes", "glove", "cape");

    @Test
    void aLevel75FighterGetsASwordOfItsBand() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = WorldKnowledge.current();
            int pick = CitizenGear.upgradeFor(k, "weapon", MAPLE_SWORD, 75, Job.FIGHTER, MALE, STATS, 1);
            ItemFact sword = k.item(pick).orElseThrow();

            assertEquals(130, pick / 10_000);
            assertTrue(sword.reqLevel() >= 51 && sword.reqLevel() <= 70, sword.toString());
            for (String category : ARMOR) {
                int armor = CitizenGear.upgradeFor(k, category, 0, 75, Job.FIGHTER, MALE, STATS, category.hashCode());
                ItemFact fact = k.item(armor).orElse(null);
                assertTrue(armor == 0 || fact.reqLevel() >= 41 && !fact.cash(), category + " " + fact);
            }
        }
    }

    @Test
    void neverASidegradeAndNothingForLowLevelsOrBeginners() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = WorldKnowledge.current();
            assertTrue(k.obtainable(RED_KATANA), "precondition: a farmable reqLevel-70 sword");
            assertEquals(0, CitizenGear.upgradeFor(k, "weapon", RED_KATANA, 75, Job.FIGHTER, MALE, STATS, 1));
            assertEquals(0, CitizenGear.upgradeFor(k, "weapon", MAPLE_SWORD, 9, Job.FIGHTER, MALE, STATS, 1));
            assertEquals(0, CitizenGear.upgradeFor(k, "cap", 0, 40, Job.BEGINNER, MALE, STATS, 1));
            assertEquals(0, CitizenGear.upgradeFor(null, "cap", 0, 40, Job.FIGHTER, MALE, STATS, 1), "no knowledge");
        }
    }

    @Test
    void aCrowdOfOneJobAndBandIsNotOneKit() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = WorldKnowledge.current();
            int seeds = 400;
            int capeless = 0;
            int kept = 0;
            Set<Integer> coatLevels = new HashSet<>();
            int worn = 0; // the best coat of band 50: still within 20 levels of band 60
            for (long s = 0; s < 40; s++) {
                int coat = CitizenGear.upgradeFor(k, "coat", 0, 55, Job.FIGHTER, MALE, STATS, s);
                if (coat != 0 && (worn == 0 || k.item(coat).orElseThrow().reqLevel() > k.item(worn).orElseThrow().reqLevel())) {
                    worn = coat;
                }
            }
            assertTrue(worn != 0 && k.item(worn).orElseThrow().reqLevel() > 40, "precondition: " + worn);
            for (long s = 0; s < seeds; s++) {
                capeless += CitizenGear.upgradeFor(k, "cape", 0, 75, Job.FIGHTER, MALE, STATS, s) == 0 ? 1 : 0;
                kept += CitizenGear.upgradeFor(k, "coat", worn, 65, Job.FIGHTER, MALE, STATS, s) == 0 ? 1 : 0; // band 60
                int coat = CitizenGear.upgradeFor(k, "coat", 0, 75, Job.FIGHTER, MALE, STATS, s);
                if (coat != 0) {
                    coatLevels.add(k.item(coat).orElseThrow().reqLevel());
                }
            }
            assertTrue(capeless > seeds / 8 && capeless < seeds / 2, capeless + " of " + seeds + " stay without a cape");
            int old = CitizenGear.upgradeFor(k, "coat", 0, 25, Job.FIGHTER, MALE, STATS, 3); // band 20: far behind band 60
            int replacedOld = 0;
            for (long s = 0; s < seeds; s++) {
                replacedOld += CitizenGear.upgradeFor(k, "coat", old, 65, Job.FIGHTER, MALE, STATS, s) != 0 ? 1 : 0;
            }
            assertTrue(kept > seeds / 10, kept + " of " + seeds + " keep a coat still near the band");
            assertEquals(seeds, replacedOld, "a coat far behind the band is always replaced");
            assertTrue(coatLevels.stream().anyMatch(level -> level <= 60) && coatLevels.stream().anyMatch(level -> level > 65),
                    "the best tier and the one under it: " + coatLevels);
        }
    }

    @Test
    void aPickItAlreadyOwnsIsWornNotBoughtAgain() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed();
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class)) {
            WorldKnowledge k = WorldKnowledge.current();
            Character agent = mock(Character.class);
            when(agent.getId()).thenReturn(4242);
            when(agent.getLevel()).thenReturn(75);
            when(agent.getStr()).thenReturn(STATS[0]);
            when(agent.getDex()).thenReturn(STATS[1]);
            when(agent.getInt()).thenReturn(STATS[2]);
            when(agent.getLuk()).thenReturn(STATS[3]);
            Item sword = mock(Item.class);
            when(sword.getItemId()).thenReturn(MAPLE_SWORD);
            Inventory equipped = mock(Inventory.class);
            when(equipped.getItem((short) -11)).thenReturn(sword);
            Inventory bag = mock(Inventory.class);
            when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
            when(agent.getInventory(InventoryType.EQUIP)).thenReturn(bag);
            int pick = CitizenGear.upgradeFor(k, "weapon", MAPLE_SWORD, 75, Job.FIGHTER, MALE, STATS, 4242 * 31L + "weapon".hashCode());
            assertTrue(pick != 0);
            when(bag.countById(pick)).thenReturn(1); // shaping wore something else and left the pick in the bag last load
            equipment.when(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort())).thenReturn(true);

            CitizenGear.upgrade(agent, Job.FIGHTER, null);

            equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, pick, (short) -11));
            inventory.verify(() -> InventoryManipulator.addById(any(), eq(pick), anyShort()), never());
        }
    }

    @Test
    void upgradingIsFast() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            WorldKnowledge k = WorldKnowledge.current();
            List<String> slots = List.of("cap", "coat", "pants", "shoes", "glove", "cape", "shield", "weapon");
            for (int i = 0; i < 100; i++) { // warm up the JIT (about 0.4 ms a citizen warm, 1.6 ms cold)
                for (String slot : slots) {
                    CitizenGear.upgradeFor(k, slot, MAPLE_SWORD, 75, Job.FIGHTER, MALE, STATS, i);
                }
            }
            long perCitizenNs = Long.MAX_VALUE;
            for (int attempt = 0; attempt < 3; attempt++) { // the best of three: a busy machine slows one, not all
                long start = System.nanoTime();
                for (int i = 0; i < 50; i++) {
                    for (String slot : slots) {
                        CitizenGear.upgradeFor(k, slot, MAPLE_SWORD, 75, Job.FIGHTER, MALE, STATS, i);
                    }
                }
                perCitizenNs = Math.min(perCitizenNs, (System.nanoTime() - start) / 50);
            }
            assertTrue(perCitizenNs < 5_000_000, "8 slots took " + perCitizenNs + " ns");
        }
    }

    @Test
    void theFlagsOffChangeNothing() throws Exception {
        Character agent = mock(Character.class);
        when(agent.getLevel()).thenReturn(75);
        boolean gear = YamlConfig.config.knowledge.CITIZEN_GEAR;
        boolean decisions = YamlConfig.config.knowledge.DECISIONS;
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            YamlConfig.config.knowledge.CITIZEN_GEAR = false;
            assertFalse(CitizenGear.upgrade(agent, Job.FIGHTER, null));
            YamlConfig.config.knowledge.CITIZEN_GEAR = true;
            YamlConfig.config.knowledge.DECISIONS = false;
            assertFalse(CitizenGear.upgrade(agent, Job.FIGHTER, null));
        } finally {
            YamlConfig.config.knowledge.CITIZEN_GEAR = gear;
            YamlConfig.config.knowledge.DECISIONS = decisions;
        }
        assertFalse(CitizenGear.upgrade(agent, Job.FIGHTER, null), "nothing published");
        verify(agent, never()).getInventory(any());
        verify(agent, never()).saveCharToDB();
    }
}
