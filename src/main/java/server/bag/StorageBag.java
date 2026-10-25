package server.bag;

import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * One Storage Bag: {@link #SLOTS} fixed slots, kept in place between deposits (only {@link #organize} compacts). Every
 * change is all or nothing: a deposit that does not fit whole leaves the bag as it was. Stacks merge only with the
 * same item, owner, flag and expiration, so a bag never launders a flag. Thread-safe on its own monitor, which is a
 * leaf lock: nothing else is locked while it is held.
 */
public final class StorageBag {
    public static final int SLOTS = 200;

    private final StorageBagKind kind;
    private final Item[] slots = new Item[SLOTS];
    /** Rows a load could not place (more than {@link #SLOTS}): never shown, saved back as they came. */
    private final List<Item> overflow = new ArrayList<>();

    StorageBag(StorageBagKind kind) {
        this.kind = kind;
    }

    public StorageBagKind kind() {
        return kind;
    }

    /** A loaded row: its saved slot when that is valid and free, else the first free one, else overflow. */
    synchronized void placeLoaded(Item item) {
        int slot = item.getPosition();
        if (slot < 0 || slot >= SLOTS || slots[slot] != null) {
            slot = firstFree();
        }
        if (slot < 0) {
            overflow.add(item);
            return;
        }
        item.setPosition((short) slot);
        slots[slot] = item;
    }

    /**
     * Stores the whole {@code item} (the bag keeps this object), or nothing. Dropped on an empty {@code target} slot it
     * goes there; on a slot holding the same stack it tops that stack up first; otherwise (or with target -1) it tops
     * up the bag's stacks and takes the first free slots.
     */
    synchronized boolean deposit(Item item, int target, IntUnaryOperator slotMax) {
        boolean stackable = stackable(item);
        int max = stackable ? Math.max(1, slotMax.applyAsInt(item.getItemId())) : 1;
        boolean validTarget = target >= 0 && target < SLOTS;
        boolean emptyTarget = validTarget && slots[target] == null;

        List<Integer> stacks = new ArrayList<>();
        if (stackable && !emptyTarget) {
            if (validTarget && sameStack(slots[target], item)) {
                stacks.add(target);
            }
            for (int i = 0; i < SLOTS; i++) {
                if (i != target && sameStack(slots[i], item)) {
                    stacks.add(i);
                }
            }
        }
        int left = item.getQuantity();
        for (int i : stacks) {
            left -= Math.min(left, Math.max(0, max - slots[i].getQuantity()));
        }
        List<Integer> free = new ArrayList<>();
        if (emptyTarget) {
            free.add(target);
        }
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == null && i != target) {
                free.add(i);
            }
        }
        int needed = left <= 0 ? 0 : (left + max - 1) / max;
        if (needed > free.size()) {
            return false;
        }

        left = item.getQuantity();
        for (int i : stacks) {
            int moved = Math.min(left, Math.max(0, max - slots[i].getQuantity()));
            slots[i].setQuantity((short) (slots[i].getQuantity() + moved));
            left -= moved;
        }
        for (int n = 0; n < needed; n++) {
            int slot = free.get(n);
            int quantity = Math.min(left, max);
            Item stack = n == needed - 1 ? item : item.copy();
            stack.setQuantity((short) quantity);
            stack.setPosition((short) slot);
            slots[slot] = stack;
            left -= quantity;
        }
        return true;
    }

    /** Removes and returns the item in a slot, or null when the slot is empty or out of range. */
    synchronized Item takeOut(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return null;
        }
        Item item = slots[slot];
        slots[slot] = null;
        return item;
    }

    /** Puts back an item taken out that could not go where it was meant to (its slot, else the first free one). */
    synchronized void putBack(int slot, Item item) {
        if (slot < 0 || slot >= SLOTS || slots[slot] != null) {
            slot = firstFree();
        }
        if (slot < 0) {
            overflow.add(item);
            return;
        }
        item.setPosition((short) slot);
        slots[slot] = item;
    }

    /** A copy of the item in a slot, or null. */
    synchronized Item peek(int slot) {
        return slot < 0 || slot >= SLOTS || slots[slot] == null ? null : slots[slot].copy();
    }

    /**
     * Within the bag: onto an empty slot the item moves; onto the same stack it merges (what does not fit stays put);
     * onto anything else the two swap. False only when {@code src} is empty or a slot is out of range.
     */
    synchronized boolean move(int src, int dst, IntUnaryOperator slotMax) {
        if (src < 0 || src >= SLOTS || dst < 0 || dst >= SLOTS || slots[src] == null) {
            return false;
        }
        if (src == dst) {
            return true;
        }
        Item source = slots[src];
        Item target = slots[dst];
        if (target == null) {
            place(source, dst);
            slots[src] = null;
        } else if (sameStack(target, source)) {
            int max = Math.max(1, slotMax.applyAsInt(source.getItemId()));
            int moved = Math.min(source.getQuantity(), Math.max(0, max - target.getQuantity()));
            target.setQuantity((short) (target.getQuantity() + moved));
            if (moved == source.getQuantity()) {
                slots[src] = null;
            } else {
                source.setQuantity((short) (source.getQuantity() - moved));
            }
        } else {
            place(source, dst);
            place(target, src);
        }
        return true;
    }

    /**
     * The Organize button: merges every stack it can, sorts by item id and packs the bag from slot 0. Keeps every
     * item's quantity (rows in overflow come in while there is room).
     */
    synchronized void organize(IntUnaryOperator slotMax) {
        List<Item> all = new ArrayList<>();
        for (Item item : slots) {
            if (item != null) {
                all.add(item);
            }
        }
        all.addAll(overflow);
        overflow.clear();

        Map<List<Object>, Integer> totals = new LinkedHashMap<>();
        Map<List<Object>, Item> firsts = new LinkedHashMap<>();
        List<Item> packed = new ArrayList<>();
        for (Item item : all) {
            if (!stackable(item)) {
                packed.add(item);
                continue;
            }
            List<Object> key = List.of(item.getItemId(), Objects.toString(item.getOwner(), ""), item.getFlag(),
                    item.getExpiration());
            totals.merge(key, (int) item.getQuantity(), Integer::sum);
            firsts.putIfAbsent(key, item);
        }
        totals.forEach((key, total) -> {
            Item first = firsts.get(key);
            int max = Math.max(1, slotMax.applyAsInt(first.getItemId()));
            for (int left = total; left > 0; left -= max) {
                Item stack = left == total ? first : first.copy();
                stack.setQuantity((short) Math.min(left, max));
                packed.add(stack);
            }
        });
        packed.sort(Comparator.comparingInt(Item::getItemId).thenComparing(Item::getQuantity, Comparator.reverseOrder()));

        java.util.Arrays.fill(slots, null);
        for (int i = 0; i < packed.size(); i++) {
            if (i < SLOTS) {
                place(packed.get(i), i);
            } else {
                overflow.add(packed.get(i));
            }
        }
    }

    /** Copies of the items in their slots, slot order (what the window and a save see). */
    synchronized List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Item item : slots) {
            if (item != null) {
                out.add(item.copy());
            }
        }
        return out;
    }

    /** Copies of every row to save: the slots and any overflow. */
    synchronized List<Item> rowsToSave() {
        List<Item> out = items();
        for (Item item : overflow) {
            out.add(item.copy());
        }
        return out;
    }

    synchronized int size() {
        int n = 0;
        for (Item item : slots) {
            if (item != null) {
                n++;
            }
        }
        return n;
    }

    private void place(Item item, int slot) {
        item.setPosition((short) slot);
        slots[slot] = item;
    }

    private int firstFree() {
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    static boolean stackable(Item item) {
        return ItemConstants.getInventoryType(item.getItemId()) != InventoryType.EQUIP
                && !ItemConstants.isRechargeable(item.getItemId());
    }

    private static boolean sameStack(Item a, Item b) {
        return a != null && b != null && a.getItemId() == b.getItemId() && stackable(a)
                && Objects.equals(a.getOwner(), b.getOwner()) && a.getFlag() == b.getFlag()
                && a.getExpiration() == b.getExpiration();
    }
}
