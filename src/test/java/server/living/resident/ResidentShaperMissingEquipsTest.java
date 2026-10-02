package server.living.resident;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Loadout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A loadout edited after a bot was shaped keeps its shaped version, so the bot is never reshaped for it: the pieces it
 * does not wear go on where the slot is empty, holds something it cannot use or holds a lower-level piece, a citizen's
 * carried copy first, never leaving a new copy behind and never touching what a Resident's wardrobe dresses.
 */
class ResidentShaperMissingEquipsTest {
    private static final short HAT = -1;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    private static final int GLADIUS = 1302008; // perion-warrior-l30's sword, reqLevel 30
    private static final int OLD_WOODEN_STAFF = 1382004; // ellinia-mage-l30's staff, reqLevel 20
    private static final int RYDEN = 1452005; // a bow, reqLevel 30: above the crossbow, still the wrong weapon
    private static final int MOUNTAIN_CROSSBOW = 1462000; // henesys-crossbow-l30, reqLevel 28
    private static final int CROSSBOW_ARROWS = 2061000;
    private static final int POTIONS = 2000016;
    private static final int JOUSTING_HELMET = 1002023; // reqLevel 30
    private static final int RED_DUKE = 1002025; // reqLevel 40
    private static final int STEEL_SHIELD = 1092000;
    private static final int TWO_HANDED_SWORD = 1402010;

    private final Map<Integer, Short> slots = new HashMap<>();
    private final Map<Integer, Integer> levels = new HashMap<>();
    private final Map<Integer, WeaponType> weapons = new HashMap<>();
    private final Set<Integer> cannotWear = new HashSet<>();
    private final ResidentShaper.ItemRules items = new ResidentShaper.ItemRules() {
        @Override
        public short slot(int itemId) {
            return slots.getOrDefault(itemId, (short) 0);
        }

        @Override
        public int reqLevel(int itemId) {
            return levels.getOrDefault(itemId, 0);
        }

        @Override
        public boolean meetsRequirements(Character agent, int itemId) {
            return !cannotWear.contains(itemId);
        }

        @Override
        public WeaponType weaponType(int itemId) {
            return weapons.getOrDefault(itemId, WeaponType.NOT_A_WEAPON);
        }

        @Override
        public boolean twoHanded(int itemId) {
            return weapons.get(itemId) == WeaponType.SWORD2H;
        }
    };
    private MockedStatic<InventoryManipulator> inventory;
    private MockedStatic<AgentEquipmentService> equipment;
    private Character agent;
    private Inventory bag;
    private Inventory worn;
    private Inventory use;

    @BeforeEach
    void rig() {
        item(GLADIUS, WEAPON, 30, WeaponType.SWORD1H);
        item(OLD_WOODEN_STAFF, WEAPON, 20, WeaponType.STAFF);
        item(RYDEN, WEAPON, 30, WeaponType.BOW);
        item(MOUNTAIN_CROSSBOW, WEAPON, 28, WeaponType.CROSSBOW);
        item(TWO_HANDED_SWORD, WEAPON, 30, WeaponType.SWORD2H);
        item(JOUSTING_HELMET, HAT, 30, null);
        item(RED_DUKE, HAT, 40, null);
        item(STEEL_SHIELD, SHIELD, 15, null);
        inventory = mockStatic(InventoryManipulator.class);
        inventory.when(() -> InventoryManipulator.addById(any(), anyInt(), anyShort())).thenReturn(true);
        equipment = mockStatic(AgentEquipmentService.class);
        equipment.when(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort())).thenReturn(true);
        agent = mock(Character.class);
        bag = mock(Inventory.class);
        worn = mock(Inventory.class);
        use = mock(Inventory.class);
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        when(agent.getInventory(InventoryType.USE)).thenReturn(use);
    }

    @AfterEach
    void release() {
        equipment.close();
        inventory.close();
    }

    @Test
    void aMageSwingingASwordGetsItsStaff() {
        as(Job.FP_WIZARD, 32);
        wearing(WEAPON, GLADIUS); // above the staff's level: only the weapon branch makes it replaceable

        assertTrue(ResidentShaper.wearMissingEquips(agent, loadout(List.of(OLD_WOODEN_STAFF)), false, items));

        inventory.verify(() -> InventoryManipulator.addById(any(), eq(OLD_WOODEN_STAFF), eq((short) 1)));
        equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, OLD_WOODEN_STAFF, WEAPON));
        verify(agent).saveCharToDB();
    }

    @Test
    void aCrossbowmanOnABowGetsTheBandCrossbowAndItsBolts() {
        as(Job.CROSSBOWMAN, 31);
        wearing(WEAPON, RYDEN);
        Loadout band = new Loadout("henesys-crossbow-l30", List.of(MOUNTAIN_CROSSBOW),
                List.of(new ItemStack(POTIONS, 40), new ItemStack(CROSSBOW_ARROWS, 1500)), Map.of(), Map.of());

        assertTrue(ResidentShaper.wearMissingEquips(agent, band, false, items));

        equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, MOUNTAIN_CROSSBOW, WEAPON));
        inventory.verify(() -> InventoryManipulator.addById(any(), eq(CROSSBOW_ARROWS), eq((short) 1500)));
        inventory.verify(() -> InventoryManipulator.addById(any(), eq(POTIONS), anyShort()), never());
    }

    @Test
    void aCitizenWearsTheCopyItCarriesInsteadOfANewOne() {
        as(Job.FIGHTER, 32);
        when(bag.countById(GLADIUS)).thenReturn(1); // given at 10-29 for later, never put on

        assertTrue(ResidentShaper.wearMissingEquips(agent, loadout(List.of(GLADIUS)), false, items));

        equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, GLADIUS, WEAPON));
        inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
    }

    @Test
    void nothingWhenItWearsBetterCannotWearItOrTheWardrobeTookItOff() {
        as(Job.FIGHTER, 35);
        wearing(HAT, RED_DUKE);
        assertFalse(ResidentShaper.wearMissingEquips(agent, loadout(List.of(JOUSTING_HELMET)), false, items));

        cannotWear.add(GLADIUS); // too weak for its STR gate, say
        assertFalse(ResidentShaper.wearMissingEquips(agent, loadout(List.of(GLADIUS)), false, items));

        when(worn.getItem(HAT)).thenReturn(null); // a bare-headed Resident: the outfit took the hat off
        when(bag.countById(JOUSTING_HELMET)).thenReturn(1);
        assertFalse(ResidentShaper.wearMissingEquips(agent, loadout(List.of(JOUSTING_HELMET)), true, items));

        inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        equipment.verify(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort()), never());
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void theWardrobesWeaponIsLeftAloneAndAPieceItCouldNotWearLeavesNoCopy() {
        as(Job.FIGHTER, 33);
        Item added = mock(Item.class);
        when(added.getItemId()).thenReturn(JOUSTING_HELMET);
        when(added.getPosition()).thenReturn((short) 7);
        when(bag.list()).thenReturn(List.of(added));
        equipment.when(() -> AgentEquipmentService.equipInSlot(any(), eq(JOUSTING_HELMET), anyShort())).thenReturn(false);
        Loadout dressed = new Loadout("perion-warrior-l30", List.of(GLADIUS, JOUSTING_HELMET), List.of(), Map.of(),
                Map.of("weapon", List.of(GLADIUS)));

        assertFalse(ResidentShaper.wearMissingEquips(agent, dressed, true, items));

        inventory.verify(() -> InventoryManipulator.addById(any(), eq(GLADIUS), anyShort()), never());
        inventory.verify(() -> InventoryManipulator.removeFromSlot(any(), eq(InventoryType.EQUIP), eq((short) 7), eq((short) 1),
                anyBoolean()));
        verify(agent, never()).saveCharToDB();
    }

    @Test
    void aShieldNeverPushesATwoHandedWeaponOff() {
        as(Job.FIGHTER, 40);
        wearing(WEAPON, TWO_HANDED_SWORD);

        assertFalse(ResidentShaper.wearMissingEquips(agent, loadout(List.of(STEEL_SHIELD)), false, items));

        inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
    }

    private void as(Job job, int level) {
        when(agent.getJob()).thenReturn(job);
        when(agent.getLevel()).thenReturn(level);
    }

    private void wearing(short slot, int itemId) {
        Item piece = mock(Item.class);
        when(piece.getItemId()).thenReturn(itemId);
        when(worn.getItem(slot)).thenReturn(piece);
        when(worn.countById(itemId)).thenReturn(1);
    }

    private void item(int itemId, short slot, int level, WeaponType type) {
        slots.put(itemId, slot);
        levels.put(itemId, level);
        if (type != null) {
            weapons.put(itemId, type);
        }
    }

    private static Loadout loadout(List<Integer> equips) {
        return new Loadout("test", equips, List.of(), Map.of(), Map.of());
    }
}
