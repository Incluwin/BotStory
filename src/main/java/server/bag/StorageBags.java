package server.bag;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * One character's Storage Bags (docs/STORAGE_BAG.md): the four bags and their Auto toggles. The bags load on the
 * character's first use of the bag window, or at login when an Auto toggle is on; until then nothing is read or saved.
 * They save in the character save's INVENTORY section, in the same transaction as the inventory, so an item moved
 * between the two is in exactly one place after any save. The transfers run under the character's monitor (the
 * handler takes it), which {@link Character#saveCharToDB} also holds while it copies what it saves.
 */
public final class StorageBags {
    private static final Logger log = LoggerFactory.getLogger(StorageBags.class);
    public static final String FEATURE = "storage_bag";

    private final int characterId;
    private final boolean[] auto = new boolean[StorageBagKind.values().length];
    private volatile Map<StorageBagKind, StorageBag> bags;   // null until loaded
    private volatile StorageBagKind open;                    // the window's tab, null while it is closed

    StorageBags(int characterId) {
        this.characterId = characterId;
    }

    /** At login: the Auto toggles, and the bags when one is on (an Auto pickup needs them). Null if the read fails. */
    public static StorageBags load(Connection con, int characterId) {
        StorageBags out = new StorageBags(characterId);
        try (PreparedStatement ps = con.prepareStatement("SELECT auto_ore, auto_scroll, auto_chair, auto_mount "
                + "FROM character_storage_bag WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (int i = 0; i < out.auto.length; i++) {
                        out.auto[i] = rs.getBoolean(i + 1);
                    }
                }
            }
            for (boolean on : out.auto) {
                if (on) {
                    out.ensureLoaded();
                    break;
                }
            }
        } catch (SQLException e) {
            log.error("Storage Bags of character {} did not load; they stay unused this session", characterId, e);
            return null;
        }
        return out;
    }

    /** The four bags, read from the database on first use. */
    synchronized Map<StorageBagKind, StorageBag> ensureLoaded() throws SQLException {
        if (bags == null) {
            Map<StorageBagKind, StorageBag> loaded = new EnumMap<>(StorageBagKind.class);
            for (StorageBagKind kind : StorageBagKind.values()) {
                StorageBag bag = new StorageBag(kind);
                for (Pair<Item, InventoryType> row : kind.factory().loadItems(characterId, false)) {
                    bag.placeLoaded(row.getLeft());
                }
                loaded.put(kind, bag);
            }
            bags = loaded;
        }
        return bags;
    }

    public boolean isLoaded() {
        return bags != null;
    }

    public boolean isAuto(StorageBagKind kind) {
        synchronized (auto) {
            return auto[kind.ordinal()];
        }
    }

    void setAuto(StorageBagKind kind, boolean on) {
        synchronized (auto) {
            auto[kind.ordinal()] = on;
        }
    }

    // ---- what the save writes -----------------------------------------------------------------------------------

    /** What the next save writes, copied now; null while the bags were never loaded (nothing to write). */
    public SaveSnapshot saveSnapshot() {
        Map<StorageBagKind, StorageBag> loaded = bags;
        if (loaded == null) {
            return null;
        }
        Map<StorageBagKind, List<Item>> rows = new EnumMap<>(StorageBagKind.class);
        loaded.forEach((kind, bag) -> rows.put(kind, bag.rowsToSave()));
        boolean[] autoCopy;
        synchronized (auto) {
            autoCopy = auto.clone();
        }
        return new SaveSnapshot(characterId, rows, autoCopy);
    }

    public record SaveSnapshot(int characterId, Map<StorageBagKind, List<Item>> rows, boolean[] auto) {
        /** Inside the character save's transaction; every failure propagates so the whole save rolls back. */
        public void save(Connection con) throws SQLException {
            for (Map.Entry<StorageBagKind, List<Item>> entry : rows.entrySet()) {
                List<Pair<Item, InventoryType>> typed = new ArrayList<>();
                for (Item item : entry.getValue()) {
                    typed.add(new Pair<>(item, ItemConstants.getInventoryType(item.getItemId())));
                }
                entry.getKey().factory().saveItems(typed, characterId, con);
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO character_storage_bag "
                    + "(characterid, auto_ore, auto_scroll, auto_chair, auto_mount) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE auto_ore = VALUES(auto_ore), auto_scroll = VALUES(auto_scroll), "
                    + "auto_chair = VALUES(auto_chair), auto_mount = VALUES(auto_mount)")) {
                ps.setInt(1, characterId);
                for (int i = 0; i < auto.length; i++) {
                    ps.setBoolean(i + 2, auto[i]);
                }
                ps.executeUpdate();
            }
        }
    }

    // ---- the window -------------------------------------------------------------------------------------------------

    /** Whether the server half is on and this client's storage_bag feature is active. */
    public static boolean usable(Client client) {
        return ServerMods.get().isOn(ServerMods.STORAGE_BAG) && FeatureHandshake.get().isActive(client, FEATURE);
    }

    void opened(StorageBagKind kind) {
        open = kind;
    }

    void closed() {
        open = null;
    }

    StorageBag bag(StorageBagKind kind) {
        Map<StorageBagKind, StorageBag> loaded = bags;
        return loaded == null ? null : loaded.get(kind);
    }

    /**
     * Cash items and items that expire never go in a bag: the cash shop and the expiry sweep only look at the
     * inventory. One-of-a-kind items do (every mount is one, or a cash item), as in storage: one comes out only while
     * the inventory holds none, and an Auto pickup leaves them to the inventory's own rule.
     */
    static boolean bagSafe(Item item) {
        return item.getPetId() < 0 && item.getExpiration() <= 0
                && !ItemInformationProvider.getInstance().isCash(item.getItemId());
    }

    static IntUnaryOperator slotMax(Client client) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        return itemId -> ii.getSlotMax(client, itemId);
    }

    /**
     * Inventory to bag, the whole stack. The bag takes it first (all or nothing), then it leaves the inventory, both
     * under the inventory's lock. Returns the line to show the player, or null.
     */
    String deposit(Character chr, StorageBagKind kind, InventoryType type, short position, int target) {
        if (type == null || type == InventoryType.EQUIPPED || type == InventoryType.CASH || position < 1) {
            return null;
        }
        Inventory inv = chr.getInventory(type);
        if (inv == null) {
            return null;
        }
        inv.lockInventory();
        try {
            Item item = inv.getItem(position);
            if (item == null) {
                return null;
            }
            if (!kind.accepts(item.getItemId())) {
                return "The " + kind.title() + " does not take that item.";
            }
            if (!bagSafe(item)) {
                return "Cash items and items that expire cannot go in a bag.";
            }
            if (!bag(kind).deposit(item.copy(), target, slotMax(chr.getClient()))) {
                return "The " + kind.title() + " is full.";
            }
            InventoryManipulator.removeFromSlot(chr.getClient(), type, position, item.getQuantity(), false);
        } finally {
            inv.unlockInventory();
        }
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
        return null;
    }

    /**
     * Bag to inventory, the whole stack, merged into the inventory's stacks like a pickup. Only when it fits whole
     * (checked under the inventory's lock exactly as the pickup merges), so it never lands half.
     */
    String withdraw(Character chr, StorageBagKind kind, int slot) {
        StorageBag bag = bag(kind);
        Item peek = bag.peek(slot);
        if (peek == null) {
            return null;
        }
        if (ItemInformationProvider.getInstance().isPickupRestricted(peek.getItemId(), chr)
                && chr.haveItemWithId(peek.getItemId(), true)) {
            return "You can only carry one of those.";
        }
        Client client = chr.getClient();
        IntUnaryOperator slotMax = slotMax(client);
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(peek.getItemId()));
        inv.lockInventory();
        try {
            Item item = bag.takeOut(slot);
            if (item == null) {
                return null;
            }
            if (!fits(inv, item, slotMax)) {
                bag.putBack(slot, item);
                return "Make room in your inventory first.";
            }
            if (!InventoryManipulator.addFromDrop(client, item, false)) {
                bag.putBack(slot, item);
                log.error("Storage Bag withdraw of {} x{} by {} fit but was refused; left in the bag",
                        item.getItemId(), item.getQuantity(), chr.getName());
                return "Make room in your inventory first.";
            }
        } finally {
            inv.unlockInventory();
        }
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
        return null;
    }

    /** Whether addFromDrop places the whole item: its merge rule (same flag and owner) and then free slots. */
    static boolean fits(Inventory inv, Item item, IntUnaryOperator slotMax) {
        int free = inv.getNumFreeSlot();
        if (!StorageBag.stackable(item)) {
            return free >= 1;
        }
        int max = Math.max(1, slotMax.applyAsInt(item.getItemId()));
        int left = item.getQuantity();
        for (Item existing : inv.listById(item.getItemId())) {
            if (existing.getQuantity() < max && existing.getFlag() == item.getFlag()
                    && item.getOwner().equals(existing.getOwner())) {
                left -= Math.min(left, max - existing.getQuantity());
            }
        }
        return left <= 0 || free >= (left + max - 1) / max;
    }

    void move(Character chr, StorageBagKind kind, int src, int dst) {
        if (bag(kind).move(src, dst, slotMax(chr.getClient()))) {
            chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
        }
    }

    void organize(Character chr, StorageBagKind kind) {
        bag(kind).organize(slotMax(chr.getClient()));
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
    }

    void auto(Character chr, StorageBagKind kind, boolean on) {
        setAuto(kind, on);
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
    }

    // ---- pickup -------------------------------------------------------------------------------------------------

    /**
     * A pickup into a bag whose Auto is on: true when the bag took the whole item (the caller then removes it from the
     * map); false leaves the pickup to the inventory (Auto off, the bag full, or the client without the window).
     * Called under the map item's lock; takes only the bag's own lock.
     */
    public boolean autoCollect(Character chr, Item item) {
        StorageBagKind kind = item == null ? null : StorageBagKind.forItem(item.getItemId());
        if (kind == null || !isAuto(kind) || bags == null || !usable(chr.getClient()) || !bagSafe(item)
                || ItemInformationProvider.getInstance().isPickupRestricted(item.getItemId(), chr)) {
            return false;
        }
        Item copy = item.copy();
        if (!bag(kind).deposit(copy, -1, slotMax(chr.getClient()))) {
            return false;
        }
        chr.markPersistenceDirty(Character.PersistenceSection.INVENTORY);
        chr.sendPacket(PacketCreator.getShowItemGain(item.getItemId(), item.getQuantity()));
        if (open == kind) {
            chr.sendPacket(snapshot(kind));
        }
        return true;
    }

    /** The window's view of one bag. */
    net.packet.Packet snapshot(StorageBagKind kind) {
        return PacketCreator.storageBagSnapshot(kind.ordinal(), bag(kind).items(), isAuto(kind));
    }
}
