package server.bag;

import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBagTest {
    private static final int ORE = 4010000;        // Bronze Ore
    private static final int OTHER_ORE = 4010001;  // Steel Ore
    private static final int CHAIR = 3010000;
    private static final IntUnaryOperator MAX_100 = id -> id / 10000 == 301 ? 1 : 100;

    private static Item ore(int id, int quantity) {
        Item item = new Item(id, (short) 0, (short) quantity);
        item.setOwner("");
        return item;
    }

    /** Item.setFlag and the public Equip constructor read ItemInformationProvider, which needs the database. */
    private static void flag(Item item, int flag) throws Exception {
        java.lang.reflect.Field field = Item.class.getDeclaredField("flag");
        field.setAccessible(true);
        field.setShort(item, (short) flag);
    }

    private static Equip mount() throws Exception {
        java.lang.reflect.Constructor<Equip> constructor =
                Equip.class.getDeclaredConstructor(int.class, short.class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1902000, (short) 0, 0, false);
    }

    private static int total(StorageBag bag, int itemId) {
        return bag.items().stream().filter(i -> i.getItemId() == itemId).mapToInt(Item::getQuantity).sum();
    }

    @Test
    void aDropOnAnEmptySlotLandsThereWithoutMerging() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        assertTrue(bag.deposit(ore(ORE, 40), 7, MAX_100));
        assertTrue(bag.deposit(ore(ORE, 10), 20, MAX_100));
        assertEquals(40, bag.peek(7).getQuantity());
        assertEquals(10, bag.peek(20).getQuantity());
    }

    @Test
    void anywhereTopsUpStacksFirstThenTakesFreeSlots() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        bag.deposit(ore(ORE, 90), 3, MAX_100);
        assertTrue(bag.deposit(ore(ORE, 30), -1, MAX_100));
        assertEquals(100, bag.peek(3).getQuantity());
        assertEquals(20, bag.peek(0).getQuantity(), "the rest takes the first free slot");
        assertEquals(120, total(bag, ORE));
    }

    @Test
    void aDropOnTheSameStackTopsThatStackUpFirst() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        bag.deposit(ore(ORE, 50), 0, MAX_100);
        bag.deposit(ore(ORE, 50), 5, MAX_100);
        assertTrue(bag.deposit(ore(ORE, 60), 5, MAX_100));
        assertEquals(100, bag.peek(5).getQuantity());
        assertEquals(60, bag.peek(0).getQuantity());
    }

    @Test
    void aDepositThatDoesNotFitWholeChangesNothing() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        for (int i = 0; i < StorageBag.SLOTS - 1; i++) {
            assertTrue(bag.deposit(ore(OTHER_ORE, 100), i, MAX_100));
        }
        bag.deposit(ore(ORE, 95), -1, MAX_100);              // the last slot
        List<Item> before = bag.items();
        assertFalse(bag.deposit(ore(ORE, 10), -1, MAX_100)); // 5 fit in the stack, 5 have nowhere to go
        assertEquals(95, bag.peek(StorageBag.SLOTS - 1).getQuantity(), "the stack was not topped up");
        assertEquals(before.size(), bag.items().size());
        assertTrue(bag.deposit(ore(ORE, 5), -1, MAX_100), "what fits whole still goes in");
    }

    @Test
    void stacksWithAnotherFlagOrOwnerNeverMerge() throws Exception {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        Item untradeable = ore(ORE, 10);
        flag(untradeable, 8);
        bag.deposit(untradeable, 0, MAX_100);
        Item owned = ore(ORE, 10);
        owned.setOwner("Crewfriend");
        bag.deposit(owned, -1, MAX_100);
        bag.deposit(ore(ORE, 10), -1, MAX_100);
        assertEquals(3, bag.size(), "a flag is never lost into a plain stack");
        bag.organize(MAX_100);
        assertEquals(3, bag.size());
        assertEquals(1, bag.items().stream().filter(i -> i.getFlag() == 8).count());
    }

    @Test
    void chairsAndMountsTakeOneSlotEach() throws Exception {
        StorageBag chairs = new StorageBag(StorageBagKind.CHAIR);
        chairs.deposit(ore(CHAIR, 1), -1, MAX_100);
        chairs.deposit(ore(CHAIR, 1), -1, MAX_100);
        assertEquals(2, chairs.size());
        StorageBag mounts = new StorageBag(StorageBagKind.MOUNT);
        mounts.deposit(mount(), -1, MAX_100);
        mounts.deposit(mount(), -1, MAX_100);
        assertEquals(2, mounts.size());
    }

    @Test
    void moveGoesToAnEmptySlotMergesTheSameStackAndSwapsOthers() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        bag.deposit(ore(ORE, 70), 0, MAX_100);
        bag.deposit(ore(ORE, 50), 1, MAX_100);
        bag.deposit(ore(OTHER_ORE, 5), 2, MAX_100);

        assertTrue(bag.move(0, 1, MAX_100));
        assertEquals(100, bag.peek(1).getQuantity());
        assertEquals(20, bag.peek(0).getQuantity(), "what does not fit stays put");

        assertTrue(bag.move(2, 0, MAX_100));
        assertEquals(OTHER_ORE, bag.peek(0).getItemId());
        assertEquals(ORE, bag.peek(2).getItemId());

        assertTrue(bag.move(2, 150, MAX_100));
        assertNull(bag.peek(2));
        assertEquals(20, bag.peek(150).getQuantity());
        assertFalse(bag.move(3, 4, MAX_100), "an empty source");
        assertFalse(bag.move(0, StorageBag.SLOTS, MAX_100));
    }

    @Test
    void organizeKeepsEveryItemOfABagOverOneHundredTwentySevenItems() {
        // the original release's Organize emptied any bag of more than 127 items
        StorageBag bag = new StorageBag(StorageBagKind.CHAIR);
        for (int i = 0; i < 180; i++) {
            bag.deposit(ore(CHAIR + (i % 30), 1), i, MAX_100);
        }
        bag.organize(MAX_100);
        assertEquals(180, bag.size());
        List<Item> items = bag.items();
        for (int i = 0; i < items.size(); i++) {
            assertEquals(i, items.get(i).getPosition(), "packed from slot 0");
        }
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).getItemId() <= items.get(i).getItemId(), "sorted by item id");
        }
    }

    @Test
    void organizeMergesStacksAndKeepsTheTotals() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        for (int i = 0; i < 150; i++) {
            bag.deposit(ore(i % 2 == 0 ? ORE : OTHER_ORE, 7), i, MAX_100);
        }
        bag.organize(MAX_100);
        assertEquals(525, total(bag, ORE));
        assertEquals(525, total(bag, OTHER_ORE));
        assertEquals(12, bag.size(), "6 stacks of each: 5 full and one of 25");
    }

    @Test
    void aLoadKeepsSavedSlotsAndNeverDropsARow() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        Item high = ore(ORE, 1);
        high.setPosition((short) 199);
        bag.placeLoaded(high);
        Item clash = ore(OTHER_ORE, 1);
        clash.setPosition((short) 199);
        bag.placeLoaded(clash);
        assertEquals(ORE, bag.peek(199).getItemId(), "a slot over 127 loads where it was");
        assertEquals(OTHER_ORE, bag.peek(0).getItemId(), "a clash takes the first free slot");

        for (int i = 0; i < StorageBag.SLOTS; i++) {
            bag.placeLoaded(ore(ORE, 1));
        }
        assertEquals(StorageBag.SLOTS, bag.size());
        assertEquals(StorageBag.SLOTS + 2, bag.rowsToSave().size(), "rows past 200 are saved back, not deleted");
    }

    @Test
    void takeOutAndPutBack() {
        StorageBag bag = new StorageBag(StorageBagKind.ORE);
        bag.deposit(ore(ORE, 30), 12, MAX_100);
        Item item = bag.takeOut(12);
        assertEquals(30, item.getQuantity());
        assertNull(bag.peek(12));
        bag.putBack(12, item);
        assertEquals(30, bag.peek(12).getQuantity());
        assertNull(bag.takeOut(-1));
        assertNull(bag.takeOut(StorageBag.SLOTS));
    }

    @Test
    void theBagsHoldWhatTheirFiltersName() {
        assertEquals(StorageBagKind.ORE, StorageBagKind.forItem(4004000));
        assertEquals(StorageBagKind.SCROLL, StorageBagKind.forItem(2040001));
        assertEquals(StorageBagKind.SCROLL, StorageBagKind.forItem(2340000), "White Scroll");
        assertEquals(StorageBagKind.CHAIR, StorageBagKind.forItem(3010000));
        assertEquals(StorageBagKind.MOUNT, StorageBagKind.forItem(1902000));
        assertEquals(StorageBagKind.MOUNT, StorageBagKind.forItem(1912000));
        assertNull(StorageBagKind.forItem(2000000), "a potion");
        assertNull(StorageBagKind.forItem(4000000), "a monster drop");
        assertNull(StorageBagKind.of(4));
        assertEquals(StorageBagKind.MOUNT, StorageBagKind.of(3));
    }
}
