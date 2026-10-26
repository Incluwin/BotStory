package server.living.roster;

import server.maps.MapleMap;

import java.awt.Point;

/**
 * What the population reads of a boss's entrance for its citizen expeditions (docs/CITIZEN_EXPEDITIONS_PLAN.md): where
 * a member waits beside the entry NPC, and the store on the way there a citizen buys its potions at on arrival.
 */
public final class BossEntrances {
    private BossEntrances() {
    }

    /** Where the member of {@code ordinal} waits beside boss {@code key}'s entry NPC on {@code map}; else null. */
    public static Point spot(String key, MapleMap map, int ordinal) {
        BossEntrance entrance = BossEntrance.of(key);
        return entrance == null ? null : entrance.spot(map, ordinal);
    }

    /** The store on the way to boss {@code key} (Ant Tunnel Park's 24 Hr Mobile Store for Balrog); 0 with none. */
    public static int approachStoreNpcId(String key) {
        BossEntrance entrance = BossEntrance.of(key);
        return entrance == null ? 0 : entrance.approachStoreNpcId();
    }
}
