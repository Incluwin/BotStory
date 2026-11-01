package server.living.population.casino;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.TimerManager;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.maps.ReactorFactory;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * A box that springs open and sprays its contents (SoloMapling's CustomReactor VFX): a stock reactor is spawned beside a
 * character, hit three times so it shakes, its drops fly out one after another in a back-and-forth spray, and the last
 * hit breaks it. Every drop is the owner's alone ({@link MapItem#setPermanentOwner}). The SoloMapling original also had
 * GM test commands (spawn, hit, spray at the nearest reactor); those are gone with its command set.
 */
final class ReactorShow {
    /** Pixels between neighbouring drops, and how far the spray swings to each side. */
    private static final int SPACING = 30;
    private static final int SWING = 2;
    private static final short STAGGER_MS = 200;
    private static final long BREAK_AFTER_MS = 4_000L;

    private ReactorShow() {
    }

    /** One thing in the box: an item, or mesos when {@code itemId} is 0. */
    record Prize(int itemId, int mesos) {
    }

    /**
     * Opens a box of {@code reactorId} at {@code at} for {@code owner} and sprays {@code prizes}; returns the drops on the
     * floor (some may land a moment later, as the spray staggers them).
     */
    static List<MapItem> open(Character owner, int reactorId, Point at, List<Prize> prizes) {
        List<MapItem> drops = new ArrayList<>();
        MapleMap map = owner.getMap();
        if (map == null) {
            return drops;
        }
        Reactor reactor = new Reactor(ReactorFactory.getReactorS(reactorId), reactorId);
        reactor.setPosition(new Point(at));
        reactor.resetReactorActions(0);
        map.spawnReactor(reactor);
        for (int i = 0; i < 3; i++) {
            hit(map, reactor);
        }
        Point center = new Point(at);
        short delay = 0;
        for (int index = 0; index < prizes.size(); index++) {
            Prize prize = prizes.get(index);
            center = swing(center, index);
            if (prize.itemId() == 0) {
                java.util.Set<Integer> before = mesoIds(map, center);
                map.spawnMesoDrop(prize.mesos(), map.calcDropPos(center, reactor.getPosition()), reactor, owner, false,
                        (byte) 0, delay);
                MapItem mesos = added(map, center, before);
                if (mesos != null) {
                    mesos.setPermanentOwner(true);
                    drops.add(mesos);
                }
            } else {
                Item item = ItemConstants.getInventoryType(prize.itemId()) == InventoryType.EQUIP
                        ? ItemInformationProvider.getInstance().getEquipById(prize.itemId())
                        : new Item(prize.itemId(), (short) 0, (short) 1);
                map.dropFromReactor(owner, reactor, item, new Point(center), (short) 0, delay);
                for (MapObject object : map.getMapObjectsInRange(center, 1_000_000d, List.of(MapObjectType.ITEM))) {
                    if (object instanceof MapItem drop && drop.getItem() == item) {
                        drop.setPermanentOwner(true);
                        drops.add(drop);
                    }
                }
            }
            delay += STAGGER_MS;
        }
        hit(map, reactor);
        int oid = reactor.getObjectId();
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> map.destroyReactor(oid),
                BREAK_AFTER_MS + delay);
        return drops;
    }

    private static void hit(MapleMap map, Reactor reactor) {
        reactor.setState((byte) (reactor.getState() + 1));
        map.broadcastMessage(PacketCreator.triggerReactor(reactor, 0));
    }

    /** The spray's next spot: a step right for the first {@link #SWING} drops, then back and forth across the box. */
    static Point swing(Point center, int index) {
        Point next = new Point(center);
        if (index < SWING) {
            next.x += SPACING;
        } else {
            int cycle = ((index - SWING) / (2 * SWING)) % 2;
            next.x += cycle == 0 ? -SPACING : SPACING;
        }
        return next;
    }

    private static java.util.Set<Integer> mesoIds(MapleMap map, Point at) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (MapObject object : map.getMapObjectsInRange(at, 1_000_000d, List.of(MapObjectType.ITEM))) {
            if (object instanceof MapItem drop && drop.getMeso() > 0) {
                out.add(drop.getObjectId());
            }
        }
        return out;
    }

    /** The meso drop that was not there before. */
    private static MapItem added(MapleMap map, Point at, java.util.Set<Integer> before) {
        for (MapObject object : map.getMapObjectsInRange(at, 1_000_000d, List.of(MapObjectType.ITEM))) {
            if (object instanceof MapItem drop && drop.getMeso() > 0 && !before.contains(drop.getObjectId())) {
                return drop;
            }
        }
        return null;
    }
}
