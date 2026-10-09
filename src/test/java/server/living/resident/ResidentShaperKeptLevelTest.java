package server.living.resident;

import client.AbstractCharacterObject;
import client.Character;
import client.Client;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * '!setlevel Flint 25' then '!setlevel Flint keep' on a never-shaped Gunslinger persona: the set keeps a Beginner a
 * Beginner when no job is asked, and the starter auto-assignment (USE_AUTOASSIGN_STARTERS_AP) spends every AP on the
 * Beginner curve (STR 113 DEX 24 INT 4 LUK 4, AP 0). Shaping it at that kept level advanced it to Pirate with no AP
 * left, so its loadout's DEX build never applied, and its loadout gun (nautilus-gun-l33: 1492004, level 30) was above
 * the level, so it kept the creator's sword. Found by review 2026-10-08.
 */
class ResidentShaperKeptLevelTest {
    private static final short WEAPON = -11;
    private static final short HAT = -1;
    private static final int CREATORS_SWORD = 1302000;
    private static final int GARNIER = 1492000; // the Pirate starter kit's gun, reqLevel 10
    private static final int LEVEL_25_GUN = 1492003;
    private static final int LEVEL_30_GUN = 1492004; // nautilus-gun-l33's own
    private static final int LEVEL_25_KNUCKLE = 1482003;
    private static final int BANDANA = 1002019;
    private static final int OLD_WOODEN_STAFF = 1382004; // ellinia-mage-l30's staff, reqLevel 20
    private static final int LEVEL_15_STAFF = 1382003;
    private static final int LEVEL_8_WAND = 1372005;
    private static final int MOUNTAIN_CROSSBOW = 1462000; // henesys-crossbow-l30's, reqLevel 28
    private static final int LEVEL_10_BOW = 1452002;
    private static final int MOP = 1442021; // a beginner-townsfolk work tool
    private static final Loadout GUN_LOADOUT = new Loadout("nautilus-gun-l33", List.of(LEVEL_30_GUN, BANDANA),
            List.of(new ItemStack(2000016, 40), new ItemStack(2330000, 1000)), Map.of("dex", 4, "str", 1),
            Map.of("weapon", List.of(LEVEL_25_GUN, LEVEL_30_GUN)));

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
            return false;
        }
    };
    private MockedStatic<InventoryManipulator> inventory;
    private MockedStatic<AgentEquipmentService> equipment;
    private Character agent;
    private Inventory worn;

    @BeforeEach
    void rig() {
        weapon(CREATORS_SWORD, 0, WeaponType.SWORD1H);
        weapon(GARNIER, 10, WeaponType.GUN);
        weapon(LEVEL_25_GUN, 25, WeaponType.GUN);
        weapon(LEVEL_30_GUN, 30, WeaponType.GUN);
        weapon(LEVEL_25_KNUCKLE, 25, WeaponType.KNUCKLE);
        weapon(OLD_WOODEN_STAFF, 20, WeaponType.STAFF);
        weapon(LEVEL_15_STAFF, 15, WeaponType.STAFF);
        weapon(LEVEL_8_WAND, 8, WeaponType.WAND);
        weapon(MOUNTAIN_CROSSBOW, 28, WeaponType.CROSSBOW);
        weapon(LEVEL_10_BOW, 10, WeaponType.BOW);
        slots.put(BANDANA, HAT);
        inventory = mockStatic(InventoryManipulator.class);
        inventory.when(() -> InventoryManipulator.addById(any(), anyInt(), anyShort())).thenReturn(true);
        equipment = mockStatic(AgentEquipmentService.class);
        equipment.when(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort())).thenReturn(true);
        agent = mock(Character.class);
        worn = mock(Inventory.class);
        when(agent.getInventory(InventoryType.EQUIP)).thenReturn(mock(Inventory.class));
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
    }

    @AfterEach
    void release() {
        equipment.close();
        inventory.close();
    }

    @Test
    void aBeginnerKeptAtTwentyFiveIsAdvancedWithItsBeginnerApSpentAgainByItsLoadout() throws Exception {
        Character flint = beginner(25, 113, 24, 4, 4);

        keptAt(flint, 25);

        assertEquals(Job.PIRATE, flint.getJob());
        assertEquals(25, flint.getLevel());
        // the Pirate floors (STR 4, DEX 20, INT 4, LUK 4), then all 113 points by the loadout's DEX 4 : STR 1
        assertEquals(List.of(27, 110, 4, 4), stats(flint));
        assertEquals(0, flint.getRemainingAp());
    }

    @Test
    void aBeginnerKeptBelowItsFirstJobStaysABeginnerWithItsStats() throws Exception {
        Character flint = beginner(8, 44, 8, 4, 4);

        keptAt(flint, 8);

        assertEquals(Job.BEGINNER, flint.getJob());
        assertEquals(List.of(44, 8, 4, 4), stats(flint));
        assertEquals(0, flint.getRemainingAp());
    }

    @Test
    void aGunslingerKeptBelowItsLoadoutGunWearsTheBestGunTheLevelAllows() {
        as(Job.PIRATE, 25);
        wearing(CREATORS_SWORD);
        cannotWear.add(LEVEL_30_GUN);

        assertTrue(ResidentShaper.wearAllowedWeapon(agent, GUN_LOADOUT,
                List.of(CREATORS_SWORD, GARNIER, LEVEL_25_GUN, LEVEL_30_GUN, LEVEL_25_KNUCKLE), items));

        inventory.verify(() -> InventoryManipulator.addById(any(), eq(LEVEL_25_GUN), eq((short) 1)));
        equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, LEVEL_25_GUN, WEAPON));
    }

    @Test
    void nothingWhenItsLoadoutWeaponFitsOrItHoldsOneAsGood() {
        as(Job.OUTLAW, 33);
        wearing(CREATORS_SWORD);
        assertFalse(ResidentShaper.wearAllowedWeapon(agent, GUN_LOADOUT, List.of(GARNIER, LEVEL_25_GUN), items),
                "shaping wore the loadout's own gun");

        as(Job.PIRATE, 25);
        cannotWear.add(LEVEL_30_GUN);
        wearing(LEVEL_25_GUN);
        assertFalse(ResidentShaper.wearAllowedWeapon(agent, GUN_LOADOUT, List.of(GARNIER, LEVEL_25_GUN), items));

        inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        equipment.verify(() -> AgentEquipmentService.equipInSlot(any(), anyInt(), anyShort()), never());
    }

    @Test
    void aMageBelowEveryStaffFallsBackToAWand() {
        as(Job.MAGICIAN, 12);
        wearing(CREATORS_SWORD);
        cannotWear.addAll(List.of(OLD_WOODEN_STAFF, LEVEL_15_STAFF, LEVEL_10_BOW));
        Loadout mage = new Loadout("ellinia-mage-l30", List.of(OLD_WOODEN_STAFF), List.of(), Map.of("int", 4),
                Map.of());

        assertTrue(ResidentShaper.wearAllowedWeapon(agent, mage,
                List.of(CREATORS_SWORD, OLD_WOODEN_STAFF, LEVEL_15_STAFF, LEVEL_8_WAND, LEVEL_10_BOW), items));

        equipment.verify(() -> AgentEquipmentService.equipInSlot(agent, LEVEL_8_WAND, WEAPON));
    }

    @Test
    void aWeaponThatShootsIsNoFallbackForAnotherType() {
        as(Job.BOWMAN, 15); // a crossbow persona kept below every crossbow: its loadout stocks bolts, not arrows
        wearing(CREATORS_SWORD);
        cannotWear.add(MOUNTAIN_CROSSBOW);
        Loadout crossbow = new Loadout("henesys-crossbow-l30", List.of(MOUNTAIN_CROSSBOW),
                List.of(new ItemStack(2061000, 1500)), Map.of("dex", 4, "str", 1), Map.of());

        assertFalse(ResidentShaper.wearAllowedWeapon(agent, crossbow, List.of(MOUNTAIN_CROSSBOW, LEVEL_10_BOW), items));

        inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
    }

    @Test
    void theFallbackWeaponsAreLoadoutGearItsOwnOutfitAndTheStarterKitsNotOtherOutfits() {
        Loadout townsfolk = new Loadout("beginner-townsfolk", List.of(), List.of(), Map.of(),
                Map.of("weapon", List.of(MOP)));
        Loadout gunslinger = new Loadout("nautilus-gunslinger-l30", List.of(GARNIER, BANDANA), List.of(), Map.of(),
                Map.of("weapon", List.of(1492002)));

        Set<Integer> fallback = ResidentShaper.fallbackWeapons(GUN_LOADOUT,
                List.of(townsfolk, gunslinger, GUN_LOADOUT));

        assertTrue(fallback.containsAll(List.of(GARNIER, LEVEL_25_GUN, LEVEL_30_GUN, 1482000)), fallback.toString());
        assertFalse(fallback.contains(MOP), "a work tool is a look");
        assertFalse(fallback.contains(1492002), "another loadout's look");
        assertFalse(fallback.contains(BANDANA));
    }

    private void as(Job job, int level) {
        when(agent.getJob()).thenReturn(job);
        when(agent.getLevel()).thenReturn(level);
    }

    private void wearing(int itemId) {
        Item piece = mock(Item.class);
        when(piece.getItemId()).thenReturn(itemId);
        when(worn.getItem(WEAPON)).thenReturn(piece);
        when(worn.countById(itemId)).thenReturn(1);
    }

    private void weapon(int itemId, int level, WeaponType type) {
        slots.put(itemId, WEAPON);
        levels.put(itemId, level);
        weapons.put(itemId, type);
    }

    /** The level, job and AP step of kept-level shaping, as a GUNSLINGER with nautilus-gun-l33. */
    private static void keptAt(Character chr, int level) {
        ResidentShaper.advanceAndSpendAp(chr, Job.GUNSLINGER, level, GUN_LOADOUT, true);
    }

    private static List<Integer> stats(Character chr) {
        return List.of(chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk());
    }

    /**
     * CharacterAdminSetLevelTest's reflective fixture (no map, party, guild or database), its stats and AP real. Only
     * a job change is stubbed to set the job: its guild, map and broadcast work needs a live world.
     */
    private static Character beginner(int level, int str, int dex, int intel, int luk) throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Character chr = constructor.newInstance();
        set(chr, "client", mock(Client.class));
        set(chr, "canRecvPartySearchInvite", false);
        set(chr, "equipchanged", false); // nothing worn: recalcLocalStats skips ItemInformationProvider (database)
        chr.setJob(Job.BEGINNER);
        chr.setLevel(level);
        Method stats = AbstractCharacterObject.class.getDeclaredMethod("updateStrDexIntLuk", int.class, int.class,
                int.class, int.class, int.class);
        stats.setAccessible(true); // protected outside client
        stats.invoke(chr, str, dex, intel, luk, 0);
        Character real = spy(chr);
        doAnswer(inv -> {
            real.setJob(inv.getArgument(0));
            return true;
        }).when(real).changeJob(any());
        return real;
    }

    private static void set(Character chr, String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chr, value);
    }
}
