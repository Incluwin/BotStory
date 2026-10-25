package server.bag;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.packet.Packet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.persistence.DirtySectionTracker;
import tools.DatabaseConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Inventory to bag and back on a real character's inventories (whole stacks, all or nothing), and a save and load of
 * the bags. Needs the database (ItemInformationProvider loads from it): run with -Dcosmic.test.mysql=true. Writes only
 * inventoryitems rows of the bag types under a character id no character has, and removes them. Before the
 * character_storage_bag changeset has run, the Auto toggles go to a TEMPORARY table of this connection only.
 */
class StorageBagsMySqlIntegrationTest {
    private static final int BRONZE_ORE = 4010000;
    private static final int POTION = 2000000;
    private static final int HOG = 1902000; // a one-of-a-kind mount
    private static int characterId;

    private Character chr;
    private StorageBags bags;

    @BeforeAll
    static void connect() {
        assumeTrue(Boolean.getBoolean("cosmic.test.mysql"), "Enable explicitly with -Dcosmic.test.mysql=true");
        assertTrue(DatabaseConnection.initializeConnectionPool());
        characterId = 1_900_000_000 + ThreadLocalRandom.current().nextInt(10_000_000);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (characterId == 0) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            deleteRows(con);
        }
    }

    private static void deleteRows(Connection con) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("DELETE inventoryitems, inventoryequipment FROM inventoryitems "
                + "LEFT JOIN inventoryequipment USING(inventoryitemid) WHERE characterid = ? AND type BETWEEN 10 AND 13")) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        }
        if (tableExists(con)) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM character_storage_bag WHERE characterid = ?")) {
                ps.setInt(1, characterId);
                ps.executeUpdate();
            }
        }
    }

    private static boolean tableExists(Connection con) throws Exception {
        try (ResultSet rs = con.getMetaData().getTables(con.getCatalog(), null, "character_storage_bag", null)) {
            return rs.next();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Constructor<Character> constructor = Character.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        chr = constructor.newInstance();
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        set(Character.class, chr, "client", client);
        set(Character.class, chr, "id", characterId);

        bags = new StorageBags(characterId);
        Map<StorageBagKind, StorageBag> empty = new EnumMap<>(StorageBagKind.class);
        for (StorageBagKind kind : StorageBagKind.values()) {
            empty.put(kind, new StorageBag(kind));
        }
        set(StorageBags.class, bags, "bags", empty);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Inventory etc() {
        return chr.getInventory(InventoryType.ETC);
    }

    private static Item stack(int itemId, int quantity) {
        Item item = new Item(itemId, (short) 0, (short) quantity);
        item.setOwner("");
        return item;
    }

    private short add(Inventory inv, int itemId, int quantity, short flag) {
        Item item = stack(itemId, quantity);
        item.setFlag(flag);
        return inv.addItem(item);
    }

    @SuppressWarnings("unchecked")
    private DirtySectionTracker<Character.PersistenceSection> tracker() throws Exception {
        Field field = Character.class.getDeclaredField("persistenceDirty");
        field.setAccessible(true);
        return (DirtySectionTracker<Character.PersistenceSection>) field.get(chr);
    }

    @Test
    void aDepositMovesTheWholeStackAndDirtiesTheInventorySection() throws Exception {
        short slot = add(etc(), BRONZE_ORE, 37, (short) 0);
        DirtySectionTracker<Character.PersistenceSection> tracker = tracker();
        tracker.complete(tracker.plan(true));
        assertNull(bags.deposit(chr, StorageBagKind.ORE, InventoryType.ETC, slot, 4));
        assertNull(etc().getItem(slot));
        assertEquals(37, bags.bag(StorageBagKind.ORE).peek(4).getQuantity());
        assertTrue(tracker.dirtySnapshot().contains(Character.PersistenceSection.INVENTORY));
    }

    @Test
    void aDepositTheBagRefusesLeavesTheInventoryAlone() {
        short potion = add(chr.getInventory(InventoryType.USE), POTION, 5, (short) 0);
        assertEquals("The Scroll Bag does not take that item.",
                bags.deposit(chr, StorageBagKind.SCROLL, InventoryType.USE, potion, -1));
        assertEquals(5, chr.getInventory(InventoryType.USE).getItem(potion).getQuantity());

        short ore = add(etc(), BRONZE_ORE, 10, (short) 0);
        assertEquals("The Chair Bag does not take that item.",
                bags.deposit(chr, StorageBagKind.CHAIR, InventoryType.ETC, ore, -1));

        StorageBag full = bags.bag(StorageBagKind.ORE);
        for (int i = 0; i < StorageBag.SLOTS; i++) {
            assertTrue(full.deposit(stack(4010001, 100), i, id -> 100));
        }
        assertEquals("The Ore Bag is full.", bags.deposit(chr, StorageBagKind.ORE, InventoryType.ETC, ore, -1));
        assertEquals(10, etc().getItem(ore).getQuantity(), "nothing left the inventory");
    }

    @Test
    void equippedAndCashSlotsAreNeverDeposited() {
        assertNull(bags.deposit(chr, StorageBagKind.MOUNT, InventoryType.EQUIPPED, (short) -18, -1));
        assertNull(bags.deposit(chr, StorageBagKind.ORE, InventoryType.CASH, (short) 1, -1));
        assertNull(bags.deposit(chr, StorageBagKind.ORE, null, (short) 1, -1));
        assertEquals(0, bags.bag(StorageBagKind.ORE).size());
    }

    @Test
    void aWithdrawMergesIntoTheInventoryLikeAPickup() {
        short slot = add(etc(), BRONZE_ORE, 37, (short) 0);
        bags.deposit(chr, StorageBagKind.ORE, InventoryType.ETC, slot, 0);
        add(etc(), BRONZE_ORE, 10, (short) 0);
        assertNull(bags.withdraw(chr, StorageBagKind.ORE, 0));
        assertEquals(0, bags.bag(StorageBagKind.ORE).size());
        assertEquals(47, etc().countById(BRONZE_ORE));
        assertEquals(1, etc().listById(BRONZE_ORE).size(), "merged into the stack already there");
    }

    @Test
    void aWithdrawThatDoesNotFitWholeLeavesTheBagAlone() {
        short slot = add(etc(), BRONZE_ORE, 50, (short) 0);
        bags.deposit(chr, StorageBagKind.ORE, InventoryType.ETC, slot, 9);
        // every slot full; one stack of the same ore has room but another flag, so a pickup would not merge into it
        add(etc(), BRONZE_ORE, 1, (short) 8);
        while (etc().getNextFreeSlot() != -1) {
            add(etc(), 4000000, 1, (short) 0);
        }
        assertEquals("Make room in your inventory first.", bags.withdraw(chr, StorageBagKind.ORE, 9));
        assertEquals(50, bags.bag(StorageBagKind.ORE).peek(9).getQuantity(), "the stack stayed in its slot");
        assertEquals(1, etc().countById(BRONZE_ORE));
    }

    @Test
    void fitsCountsOnlyStacksAPickupWouldMergeInto() {
        Inventory inv = etc();
        while (inv.getNextFreeSlot() != -1) {
            add(inv, 4000000, 1, (short) 0);
        }
        assertFalse(StorageBags.fits(inv, stack(BRONZE_ORE, 40), id -> 100));
        inv.removeSlot((short) 1);
        add(inv, BRONZE_ORE, 70, (short) 0);
        assertTrue(StorageBags.fits(inv, stack(BRONZE_ORE, 30), id -> 100));
        assertFalse(StorageBags.fits(inv, stack(BRONZE_ORE, 40), id -> 100), "30 of room for 40");
    }

    @Test
    void cashAndExpiringItemsStayOut() {
        Item expiring = stack(BRONZE_ORE, 1);
        expiring.setExpiration(System.currentTimeMillis() + 60_000);
        assertFalse(StorageBags.bagSafe(expiring));
        assertFalse(StorageBags.bagSafe(stack(5150000, 1)), "a cash item");
        assertTrue(StorageBags.bagSafe(stack(BRONZE_ORE, 1)));
    }

    @Test
    void aOneOfAKindMountGoesInAndComesOutOnlyWhileTheInventoryHasNone() {
        Inventory equip = chr.getInventory(InventoryType.EQUIP);
        short slot = equip.addItem(new Equip(HOG, (short) 0));
        assertNull(bags.deposit(chr, StorageBagKind.MOUNT, InventoryType.EQUIP, slot, -1));
        assertEquals(0, equip.countById(HOG));
        assertEquals(HOG, bags.bag(StorageBagKind.MOUNT).peek(0).getItemId());

        short second = equip.addItem(new Equip(HOG, (short) 0));
        assertEquals("You can only carry one of those.", bags.withdraw(chr, StorageBagKind.MOUNT, 0));
        assertNotNull(bags.bag(StorageBagKind.MOUNT).peek(0), "still in the bag");

        equip.removeSlot(second);
        assertNull(bags.withdraw(chr, StorageBagKind.MOUNT, 0));
        assertEquals(1, equip.countById(HOG));
        assertNull(bags.bag(StorageBagKind.MOUNT).peek(0));
    }

    @Test
    void theSnapshotPacketListsSlotsItemsQuantitiesAndAuto() {
        short slot = add(etc(), BRONZE_ORE, 37, (short) 0);
        bags.deposit(chr, StorageBagKind.ORE, InventoryType.ETC, slot, 130);
        bags.setAuto(StorageBagKind.ORE, true);
        Packet packet = bags.snapshot(StorageBagKind.ORE);
        byte[] bytes = packet.getBytes();
        assertEquals(0x17E, (bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8);
        assertEquals(1, bytes[2], "snapshot");
        assertEquals(0, bytes[3], "the ore bag");
        assertEquals(1, (bytes[4] & 0xFF) | (bytes[5] & 0xFF) << 8, "one item");
        assertEquals(130, (bytes[6] & 0xFF) | (bytes[7] & 0xFF) << 8, "slot 130 travels as a short");
        assertEquals(2, bytes[8], "item info: a plain item");
        assertEquals(37, (bytes[bytes.length - 3] & 0xFF) | (bytes[bytes.length - 2] & 0xFF) << 8, "the quantity block");
        assertEquals(1, bytes[bytes.length - 1], "Auto on");
    }

    @Test
    void aSaveAndALoadKeepEveryBagItsSlotsAndItsAutoToggles() throws Exception {
        bags.bag(StorageBagKind.ORE).deposit(stack(BRONZE_ORE, 99), 150, id -> 100);
        bags.bag(StorageBagKind.SCROLL).deposit(stack(2040001, 3), 0, id -> 100);
        bags.bag(StorageBagKind.CHAIR).deposit(stack(3010000, 1), 199, id -> 1);
        Item mount = ItemInformationProvider.getInstance().getEquipById(1902000);
        bags.bag(StorageBagKind.MOUNT).deposit(mount, 5, id -> 1);
        bags.setAuto(StorageBagKind.SCROLL, true);
        StorageBags.SaveSnapshot snapshot = bags.saveSnapshot();
        bags.bag(StorageBagKind.ORE).takeOut(150);
        assertEquals(1, snapshot.rows().get(StorageBagKind.ORE).size(), "the snapshot is a copy");

        try (Connection con = DatabaseConnection.getConnection()) {
            boolean temporary = !tableExists(con);
            if (temporary) {
                try (Statement st = con.createStatement()) {
                    st.execute("CREATE TEMPORARY TABLE character_storage_bag (characterid INT NOT NULL PRIMARY KEY, "
                            + "auto_ore TINYINT(1) NOT NULL DEFAULT 0, auto_scroll TINYINT(1) NOT NULL DEFAULT 0, "
                            + "auto_chair TINYINT(1) NOT NULL DEFAULT 0, auto_mount TINYINT(1) NOT NULL DEFAULT 0)");
                }
            }
            try {
                con.setAutoCommit(false);
                snapshot.save(con);
                con.commit();
                con.setAutoCommit(true);

                StorageBags loaded = StorageBags.load(con, characterId);
                assertNotNull(loaded);
                assertTrue(loaded.isAuto(StorageBagKind.SCROLL));
                assertFalse(loaded.isAuto(StorageBagKind.ORE));
                assertTrue(loaded.isLoaded(), "an Auto toggle on loads the bags at login");
                assertEquals(99, loaded.bag(StorageBagKind.ORE).peek(150).getQuantity(), "slot 150 came back as 150");
                assertEquals(3, loaded.bag(StorageBagKind.SCROLL).peek(0).getQuantity());
                assertEquals(3010000, loaded.bag(StorageBagKind.CHAIR).peek(199).getItemId());
                Item loadedMount = loaded.bag(StorageBagKind.MOUNT).peek(5);
                assertEquals(1902000, loadedMount.getItemId());
                assertEquals(1, loadedMount.getItemType(), "an equip, with its equipment row");
                List<Item> ore = loaded.bag(StorageBagKind.ORE).items();
                assertEquals(1, ore.size());
            } finally {
                con.setAutoCommit(true);
                deleteRows(con);
                if (temporary) {
                    try (Statement st = con.createStatement()) {
                        st.execute("DROP TEMPORARY TABLE IF EXISTS character_storage_bag");
                    }
                }
            }
        }
    }
}
