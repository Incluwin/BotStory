package server.living.population.casino;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What players at a casino table do with the floor: drop chips from their own bag as a bet, pick up a pile, and the
 * dealer's cards, which are only pictures (shown, then swept) and never anyone's loot. The SoloMapling DropCommands
 * this replaces made every chip from nothing; here a bet, a payout and a pickup move real items between bags.
 */
public final class Floor {
    /** How far in front of a seat a dropped chip still counts as its bet. */
    public static final int BET_REACH_PX = 160;

    private Floor() {
    }

    /** The chips {@code who} dropped within reach of {@code seat} that still lie on the floor. */
    public static List<MapItem> chipsBy(MapleMap map, Character who, Point seat) {
        List<MapItem> out = new ArrayList<>();
        if (map == null || who == null || seat == null) {
            return out;
        }
        for (MapObject object : map.getMapObjectsInRange(seat, (double) BET_REACH_PX * BET_REACH_PX,
                List.of(MapObjectType.ITEM))) {
            if (object instanceof MapItem item && !item.isPickedUp() && item.getMeso() <= 0 && item.getItem() != null
                    && Chips.isChip(item.getItemId()) && item.getDropper() == who) {
                out.add(item);
            }
        }
        return out;
    }

    /** A pile of floor chips counted by chip, item id to count. */
    public static Map<Integer, Integer> count(List<MapItem> chips) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (MapItem chip : chips) {
            out.merge(chip.getItemId(), (int) chip.getItem().getQuantity(), Integer::sum);
        }
        return out;
    }

    /** True while every one of these drops still lies on the floor. */
    public static boolean allDown(MapleMap map, List<MapItem> drops) {
        for (MapItem drop : drops) {
            if (drop.isPickedUp() || map == null || map.getMapObject(drop.getObjectId()) != drop) {
                return false;
            }
        }
        return !drops.isEmpty();
    }

    /**
     * {@code from} drops chips out of its own bag at {@code at}: a payout or a citizen's bet that only {@code owner} can pick
     * up, or ({@code owner} null) an ordinary player drop anyone may. Returns the drops; empty when it lacks the chips.
     */
    public static List<MapItem> dropChips(Character from, Map<Integer, Integer> chips, Point at, Character owner) {
        List<MapItem> out = new ArrayList<>();
        MapleMap map = from.getMap();
        if (map == null) {
            return out;
        }
        for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
            if (from.getItemQuantity(chip.getKey(), false) < chip.getValue()) {
                return out;
            }
        }
        int offset = 0;
        for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
            InventoryManipulator.removeById(from.getClient(), InventoryType.ETC, chip.getKey(), chip.getValue(), true, false);
            Item item = new Item(chip.getKey(), (short) 0, (short) (int) chip.getValue());
            MapItem drop = drop(map, from, owner == null ? from : owner, item, new Point(at.x + offset, at.y), owner != null);
            offset += 18;
            if (drop != null) {
                out.add(drop);
            }
        }
        return out;
    }

    /**
     * {@code by} takes these drops into its bag whoever may loot them: a dealer taking the bets in when betting closes, so
     * a bet is held until the hand is settled and cannot be picked back up. Returns what it took, item id to count.
     */
    public static Map<Integer, Integer> take(Character by, List<MapItem> drops) {
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        MapleMap map = by.getMap();
        if (map == null || by.getClient() == null) {
            return taken;
        }
        for (MapItem drop : drops) {
            Item item = drop.getItem();
            if (item == null) {
                continue;
            }
            drop.lockItem();
            try {
                if (drop.isPickedUp() || map.getMapObject(drop.getObjectId()) != drop
                        || !InventoryManipulator.checkSpace(by.getClient(), item.getItemId(), item.getQuantity(), item.getOwner())) {
                    continue;
                }
                map.pickItemDrop(PacketCreator.removeItemFromMap(drop.getObjectId(), 2, by.getId()), drop);
                InventoryManipulator.addFromDrop(by.getClient(), item, false);
                taken.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
            } finally {
                drop.unlockItem();
            }
        }
        return taken;
    }

    /**
     * {@code by} pays {@code mesos} in chips at {@code at} that only {@code to} can pick up: from the chips it holds, and
     * what it lacks bought at the cashier first (a runner to the counter). Returns the drops; they are worth less than
     * {@code mesos} only when its purse or bag could not cover the chips it lacked (see {@link #purse} for the rest).
     */
    public static List<MapItem> payOut(Character by, long mesos, Point at, Character to) {
        Map<Integer, Integer> chips = Chips.from(held(by), mesos);
        long missing = mesos - Chips.value(chips);
        if (missing > 0 && ChipCashier.buy(by, missing)) {
            chips = Chips.from(held(by), mesos);
        }
        return chips.isEmpty() ? new ArrayList<>() : dropChips(by, chips, at, to);
    }

    /**
     * {@code by} hands {@code to} what it still owes straight from its purse (a host settling up when it ran out of
     * chips); false, nothing moved, when it cannot.
     */
    public static boolean purse(Character by, Character to, long mesos) {
        if (mesos <= 0) {
            return true;
        }
        if (by.getMeso() < mesos || mesos > Integer.MAX_VALUE || !to.canHoldMeso((int) mesos)) {
            return false;
        }
        try (var ignored = MesoFlows.as(MesoAccount.TRADE)) {
            by.gainMeso((int) -mesos, false);
            to.gainMeso((int) mesos, true);
        }
        return true;
    }

    /** The chips a character holds, item id to count. */
    public static Map<Integer, Integer> held(Character chr) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int chip : Chips.ids()) {
            int count = chr.getItemQuantity(chip, false);
            if (count > 0) {
                out.put(chip, count);
            }
        }
        return out;
    }

    /** {@code who} picks up these drops into its bag, as a player walking over them would. */
    public static void pickUp(Character who, List<MapItem> drops) {
        for (MapItem drop : drops) {
            if (!drop.isPickedUp()) {
                who.pickupItem(drop);
            }
        }
    }

    /** A picture on the floor (a dealt card): the dealer's alone, never loot, swept with {@link #sweep}. */
    public static MapItem show(Character by, int itemId, Point at) {
        MapleMap map = by.getMap();
        if (map == null) {
            return null;
        }
        Item item = ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP
                ? ItemInformationProvider.getInstance().getEquipById(itemId) : new Item(itemId, (short) 0, (short) 1);
        return drop(map, by, by, item, at, true);
    }

    /** {@code by} sweeps pictures off the floor: they fly to it and are gone, its bag unchanged. */
    public static void sweep(Character by, List<MapItem> shown) {
        MapleMap map = by.getMap();
        for (MapItem item : shown) {
            if (map == null || item.isPickedUp()) {
                continue;
            }
            item.lockItem();
            try {
                if (!item.isPickedUp()) {
                    map.pickItemDrop(PacketCreator.removeItemFromMap(item.getObjectId(), 2, by.getId()), item);
                }
            } finally {
                item.unlockItem();
            }
        }
        shown.clear();
    }

    /**
     * Spawns {@code item} as a drop and returns it. {@code ownerOnly}: only {@code owner} ever picks it up (a payout, a
     * card); otherwise it is an ordinary player drop.
     */
    static MapItem drop(MapleMap map, MapObject dropper, Character owner, Item item, Point at, boolean ownerOnly) {
        map.spawnItemDrop(dropper, owner, item, at, !ownerOnly, !ownerOnly);
        for (MapObject object : map.getMapObjectsInRange(at, 1_000_000d, List.of(MapObjectType.ITEM))) {
            if (object instanceof MapItem drop && drop.getItem() == item) {
                drop.setPermanentOwner(ownerOnly);
                return drop;
            }
        }
        return null;
    }
}
