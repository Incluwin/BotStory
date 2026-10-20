package server.partyquest;

import client.Character;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.List;

/**
 * CWKPQ stage 5, the Test of Unity: each class room gives its weapon once (the master's {@code glpq5_room} bit, or the
 * pirate room's one real chest), and a weapon lost before its relic took it (a drop that expired, a holder who left the
 * run) locked the run for good. The stock fix agreed for the companion work (CWKPQ plan Question 4 (a)): a room's
 * master gives a lost weapon once more, and only when no copy of it is left anywhere in the instance and its relic is
 * still empty. Called from the masters' scripts (9201107-9201111).
 */
public final class CwkpqRelicWeapons {
    /** The hall with the five relics. */
    static final int UNITY_MAP_ID = 610_030_500;
    /** Every map of the instance a weapon can be held or lie on: the hall and the class rooms. */
    static final List<Integer> STAGE_MAPS = List.of(UNITY_MAP_ID, 610_030_510, 610_030_520, 610_030_521, 610_030_522,
            610_030_530, 610_030_540, 610_030_550);
    /** The pirate room and its one real chest, which drops the gun. */
    static final int PIRATE_ROOM_MAP_ID = 610_030_550;
    static final int GUN_CHEST_REACTOR_ID = 6_102_001;
    /** The event property that marks a weapon given a second time (one bit per weapon, as glpq5_room). */
    static final String REISSUED = "glpq5_reissued";

    /** The five weapons, each with its relic in the hall (Reactor.wz: type 100, state 0 -> 1 when it takes one). */
    public enum Weapon {
        PRIMAL_CLAW(4_001_256, 6_109_008),
        STAFF_OF_FIRST_MAGIC(4_001_257, 6_109_007),
        ANCESTRAL_BOW(4_001_258, 6_109_006),
        MASTER_SWORD(4_001_259, 6_109_005),
        FORBIDDEN_GUN(4_001_260, 6_109_009);

        final int itemId;
        final int relicReactorId;

        Weapon(int itemId, int relicReactorId) {
            this.itemId = itemId;
            this.relicReactorId = relicReactorId;
        }

        public int itemId() {
            return itemId;
        }

        static Weapon of(int itemId) {
            for (Weapon weapon : values()) {
                if (weapon.itemId == itemId) {
                    return weapon;
                }
            }
            return null;
        }
    }

    private CwkpqRelicWeapons() {
    }

    /**
     * True when the master may give weapon {@code itemId} once more: its relic is still empty, no player in the event
     * holds one, none lies on any map of the stage, and it was not given a second time already.
     */
    public static boolean lost(EventInstanceManager event, int itemId) {
        Weapon weapon = Weapon.of(itemId);
        if (event == null || weapon == null || reissued(event, weapon) || relicTook(event, weapon)) {
            return false;
        }
        for (Character player : event.getPlayers()) {
            if (player.getItemQuantity(itemId, false) > 0) {
                return false;
            }
        }
        for (int mapId : STAGE_MAPS) {
            MapleMap map = event.getMapInstance(mapId);
            if (map != null && map.getDroppedItemsCountById(itemId) > 0) {
                return false;
            }
        }
        return true;
    }

    /** True when the pirate room's real chest was broken (the gun dropped once); before that the gun is not lost. */
    public static boolean gunChestBroken(EventInstanceManager event) {
        MapleMap room = event == null ? null : event.getMapInstance(PIRATE_ROOM_MAP_ID);
        if (room == null) {
            return false;
        }
        for (Reactor reactor : room.getAllReactors()) {
            if (reactor.getId() == GUN_CHEST_REACTOR_ID) {
                return !reactor.isAlive() || reactor.getState() >= 4;
            }
        }
        return false;
    }

    /** Marks weapon {@code itemId} as given a second time; the caller gives it. */
    public static void markReissued(EventInstanceManager event, int itemId) {
        Weapon weapon = Weapon.of(itemId);
        if (event != null && weapon != null) {
            event.setProperty(REISSUED, String.valueOf(bits(event) | (1 << weapon.ordinal())));
        }
    }

    private static boolean reissued(EventInstanceManager event, Weapon weapon) {
        return (bits(event) & (1 << weapon.ordinal())) != 0;
    }

    private static int bits(EventInstanceManager event) {
        String value = event.getProperty(REISSUED);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean relicTook(EventInstanceManager event, Weapon weapon) {
        MapleMap hall = event.getMapInstance(UNITY_MAP_ID);
        if (hall == null) {
            return false;
        }
        for (Reactor reactor : hall.getAllReactors()) {
            if (reactor.getId() == weapon.relicReactorId) {
                return reactor.getState() >= 1;
            }
        }
        return false;
    }
}
