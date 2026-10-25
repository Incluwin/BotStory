package server.bag;

import client.inventory.ItemFactory;
import constants.id.ItemId;

import java.util.Set;

/**
 * The four Storage Bags (docs/STORAGE_BAG.md), in the order of the bag window's tabs: the wire's bag byte is the
 * ordinal. Each keeps its items as {@code inventoryitems} rows of its own type, keyed by character.
 */
public enum StorageBagKind {
    ORE("Ore Bag", ItemFactory.ORE_BAG),
    SCROLL("Scroll Bag", ItemFactory.SCROLL_BAG),
    CHAIR("Chair Bag", ItemFactory.CHAIR_BAG),
    MOUNT("Mount Bag", ItemFactory.MOUNT_BAG);

    /** The Maker materials: ores, plates, jewels, crystals, magic stones and monster crystals. */
    static final Set<Integer> MAKER_MATERIALS = Set.of(
            4004000, 4004001, 4004002, 4004003, 4004004, 4005000, 4005001,
            4005002, 4005003, 4005004, 4007000, 4007001, 4007002, 4007003,
            4007004, 4007005, 4007006, 4007007, 4010000, 4010001, 4010002,
            4010003, 4010004, 4010005, 4010006, 4010007, 4011000, 4011001,
            4011002, 4011003, 4011004, 4011005, 4011006, 4011007, 4011008,
            4020000, 4020001, 4020002, 4020003, 4020004, 4020005, 4020006,
            4020007, 4020008, 4020009, 4021000, 4021001, 4021002, 4021003,
            4021004, 4021005, 4021006, 4021007, 4021008, 4021009, 4021010,
            4250000, 4250001, 4250002, 4250100, 4250101, 4250102, 4250200,
            4250201, 4250202, 4250300, 4250301, 4250302, 4250400, 4250401,
            4250402, 4250500, 4250501, 4250502, 4250600, 4250601, 4250602,
            4250700, 4250701, 4250702, 4250800, 4250801, 4250802, 4250900,
            4250901, 4250902, 4251000, 4251001, 4251002, 4251100, 4251101,
            4251102, 4251200, 4251201, 4251202, 4251300, 4251301, 4251302,
            4251400, 4251401, 4251402, 4260000, 4260001, 4260002, 4260003,
            4260004, 4260005, 4260006, 4260007, 4260008);

    private final String title;
    private final ItemFactory factory;

    StorageBagKind(String title, ItemFactory factory) {
        this.title = title;
        this.factory = factory;
    }

    public String title() {
        return title;
    }

    ItemFactory factory() {
        return factory;
    }

    /** The bag byte on the wire, or null for anything else. */
    public static StorageBagKind of(int wire) {
        StorageBagKind[] kinds = values();
        return wire >= 0 && wire < kinds.length ? kinds[wire] : null;
    }

    /** Whether this bag's filter takes the item id (the rest of the rules are {@link StorageBags#bagSafe}). */
    public boolean accepts(int itemId) {
        return switch (this) {
            case ORE -> MAKER_MATERIALS.contains(itemId);
            case SCROLL -> itemId / 10000 == 204 || itemId == ItemId.WHITE_SCROLL;
            case CHAIR -> itemId / 10000 == 301;
            case MOUNT -> itemId / 1000 == 1902 || itemId / 1000 == 1912;
        };
    }

    /** The bag whose filter takes the item id, or null. */
    public static StorageBagKind forItem(int itemId) {
        for (StorageBagKind kind : values()) {
            if (kind.accepts(itemId)) {
                return kind;
            }
        }
        return null;
    }
}
