package server.living.roster;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.progression.AgentPotionSupplierCatalog;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where companions sign up for a boss (P7, flow 4): the boss run's entry NPC on its recruit map (Mu Young in her room,
 * 105100100, for Easy Balrog), and where each companion waiting to sign up stands beside her, by its ordinal in the
 * run, the way a lineup's extras wait beside a party quest's entry NPC (PqEntrance.Beside). Also the map on the way
 * there whose store its companions top up at ({@code approachMapId}; 0: none), since no shop is in reach of her room.
 * Two bosses can share an entry NPC's room and an approach map (P11: Easy and Normal Balrog share Mu Young's room and
 * Ant Tunnel Park); their entrances then share her spots too, and the living world picks the boss by the expedition
 * type (her window's own choice) or, for potions, by the larger standard ({@link #allAtRecruitMap},
 * {@link #allAtApproachMap}).
 * A bench ally (Part B) comes into the room at its arrival portal, where players coming down to her land, and leaves
 * by its exit portal. {@code holdsTripsAtEntry} (P8): a companion's potion trip out of the recruit map is to be held
 * there while the switch lists the boss, since the way back is too long (Zakum: no route leads back into Adobis's
 * room).
 */
record BossEntrance(AgentBossRunDefinition definition, PqEntrance.Beside beside, int approachMapId,
                    String arrivalPortal, String exitPortal, boolean holdsTripsAtEntry) {
    /**
     * Mu Young stands at 617,246 (WZ 105100100 life/0): her companions wait at x 567, 537, 507, 477 and 447. On the
     * walk down from Sleepywood they pass Ant Tunnel Park (105070001), where the 24 Hr Mobile Store (1061001) sells
     * Unagi and Pure Water. Allies come in at top00 (-65,-264), where the stairway's six portals under00..05
     * (105100000) land, and leave by outPerrion (-691,246), the room's way out to Perion.
     */
    static final BossEntrance EASY_BALROG = new BossEntrance(AgentBossRunCatalog.EASY_BALROG,
            new PqEntrance.Beside(-50, -30, true), 105_070_001, "top00", "outPerrion", false);
    // slot: zakum
    /**
     * Adobis stands at -800,-236 (WZ 211042400 life/0) on one continuous floor: her companions wait at x -850, -880,
     * -910 and on. Players reach the Door to Zakum through Ice Valley II (211040200), where Glibber (2030009) sells
     * Unagi, Pure Water, bullets and crossbow arrows. Allies come in and leave by west00 (-1601,-339), the room's way
     * back to the door (211042300). No shop is in reach of her room and no route leads back into it, so a trip out is
     * to be held there.
     */
    static final BossEntrance ZAKUM = new BossEntrance(AgentBossRunCatalog.ZAKUM,
            new PqEntrance.Beside(-50, -30, true), 211_040_200, "west00", "west00", true);

    // slot: balrog_normal
    /**
     * Normal Balrog (P11) signs up with Mu Young too: the same room, spots, approach store and portals as Easy's.
     */
    static final BossEntrance BALROG_NORMAL = new BossEntrance(AgentBossRunCatalog.BALROG_NORMAL,
            new PqEntrance.Beside(-50, -30, true), 105_070_001, "top00", "outPerrion", false);

    private static final List<BossEntrance> ALL = List.of(EASY_BALROG, ZAKUM, BALROG_NORMAL);
    /**
     * Where the entry NPC stands, by the map instance she was found on: looked for once per map (MapleMap.getNPCById
     * copies every object on it under its lock, and she never moves), not on every tick a companion walks up.
     */
    private static final Map<MapleMap, NpcSpot> NPC_SPOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private record NpcSpot(int npcId, Point at) { }

    /**
     * The first entrance whose entry NPC stands on {@code mapId} (its recruit map), or null on any other map. Entrances
     * that share a room share the entry NPC and her spots, so any of them places a waiting companion.
     */
    static BossEntrance atRecruitMap(int mapId) {
        List<BossEntrance> here = allAtRecruitMap(mapId);
        return here.isEmpty() ? null : here.getFirst();
    }

    /** Every entrance whose entry NPC stands on {@code mapId}, in catalog order; empty on any other map. */
    static List<BossEntrance> allAtRecruitMap(int mapId) {
        return ALL.stream().filter(entrance -> entrance.definition.recruitMapId() == mapId).toList();
    }

    /** Every entrance whose approach map (the one with a store on the way there) is {@code mapId}, in catalog order. */
    static List<BossEntrance> allAtApproachMap(int mapId) {
        return ALL.stream().filter(entrance -> entrance.approachMapId > 0 && entrance.approachMapId == mapId)
                .toList();
    }

    /**
     * True when {@code owner}, passing the approach map, is on the way to this boss: always for a boss with no marker
     * item (Easy Balrog: Ant Tunnel Park is on the walk down to Mu Young and nowhere else); else only while they carry
     * one (Zakum: the Eye of Fire, which the Door to Zakum asks every player for), since Ice Valley II is also a Yeti
     * field and the taxi stop for Sharp Cliff and the Dead Mine. Memory only.
     */
    boolean approachMeantBy(Character owner) {
        List<Integer> markers = definition.markerItemIds();
        return markers.isEmpty() || owner != null && markers.stream().anyMatch(owner::haveItem);
    }

    /** The entrance of boss {@code key} (any case, spaces around it ignored), or null when there is none. */
    static BossEntrance of(String key) {
        AgentBossRunDefinition wanted = AgentBossRunCatalog.of(key);
        for (BossEntrance entrance : ALL) {
            if (entrance.definition == wanted) {
                return entrance;
            }
        }
        return null;
    }

    /**
     * Where the companion of ordinal {@code ordinal} waits on {@code map}: {@code dx + step * ordinal} along from the
     * entry NPC, on the ground below when the entrance is grounded; null when she is not on that map.
     */
    Point spot(MapleMap map, int ordinal) {
        Point npcAt = npcAt(map);
        if (npcAt == null) {
            return null;
        }
        Point point = new Point(npcAt.x + beside.dx() + beside.step() * ordinal, npcAt.y);
        Point ground = beside.grounded() ? map.getPointBelow(point) : null;
        return ground != null ? new Point(ground) : point;
    }

    /**
     * The store on the approach map ({@code approachMapId}): the NPC the potion supplier catalog lists on that map
     * (Ant Tunnel Park's 24 Hr Mobile Store, 1061001, for Easy Balrog); 0 with no approach map or no supplier on it.
     * A bench ally buys at its prices on arrival (CompanionBossAllies), as it never walks past it.
     */
    int approachStoreNpcId() {
        if (approachMapId <= 0) {
            return 0;
        }
        for (AgentPotionSupplierCatalog.Supplier supplier : AgentPotionSupplierCatalog.suppliers()) {
            if (supplier.mapId() == approachMapId) {
                return supplier.npcId();
            }
        }
        return 0;
    }

    /** Where an ally comes into {@code map} (its arrival portal), or null when that map has no such portal. */
    Point arrivalAt(MapleMap map) {
        return portalAt(map, arrivalPortal);
    }

    /** Where an ally leaves {@code map} (its exit portal), or null when that map has no such portal. */
    Point exitAt(MapleMap map) {
        return portalAt(map, exitPortal);
    }

    private static Point portalAt(MapleMap map, String name) {
        Portal portal = map == null || name == null ? null : map.getPortal(name);
        Point at = portal == null ? null : portal.getPosition();
        return at == null ? null : new Point(at);
    }

    private Point npcAt(MapleMap map) {
        if (map == null) {
            return null;
        }
        NpcSpot known = NPC_SPOTS.get(map);
        if (known != null && known.npcId() == definition.entryNpcId()) {
            return known.at();
        }
        NPC npc = map.getNPCById(definition.entryNpcId());
        Point at = npc == null ? null : npc.getPosition();
        if (at == null) {
            return null;
        }
        Point copy = new Point(at);
        NPC_SPOTS.put(map, new NpcSpot(definition.entryNpcId(), copy));
        return copy;
    }
}
