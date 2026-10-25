package server.living.roster;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRules;
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
 * room). {@code storeNpcId} (P12 G9): the store a bench ally buys at on arrival when it does not stand on the approach
 * map itself (Leafre's, on 240000002, for an approach through Leafre); 0 takes the approach map's own supplier.
 */
record BossEntrance(AgentBossRunDefinition definition, PqEntrance.Beside beside, int approachMapId,
                    String arrivalPortal, String exitPortal, boolean holdsTripsAtEntry, int storeNpcId) {
    /**
     * Mu Young stands at 617,246 (WZ 105100100 life/0): her companions wait at x 567, 537, 507, 477 and 447. On the
     * walk down from Sleepywood they pass Ant Tunnel Park (105070001), where the 24 Hr Mobile Store (1061001) sells
     * Unagi and Pure Water. Allies come in at top00 (-65,-264), where the stairway's six portals under00..05
     * (105100000) land, and leave by outPerrion (-691,246), the room's way out to Perion.
     */
    static final BossEntrance EASY_BALROG = new BossEntrance(AgentBossRunCatalog.EASY_BALROG,
            new PqEntrance.Beside(-50, -30, true), 105_070_001, "top00", "outPerrion", false, 0);
    // slot: zakum
    /**
     * Adobis stands at -800,-236 (WZ 211042400 life/0) on one continuous floor: her companions wait at x -850, -880,
     * -910 and on. Players reach the Door to Zakum through Ice Valley II (211040200), where Glibber (2030009) sells
     * Unagi, Pure Water, bullets and crossbow arrows. Allies come in and leave by west00 (-1601,-339), the room's way
     * back to the door (211042300). No shop is in reach of her room and no route leads back into it, so a trip out is
     * to be held there.
     */
    static final BossEntrance ZAKUM = new BossEntrance(AgentBossRunCatalog.ZAKUM,
            new PqEntrance.Beside(-50, -30, true), 211_040_200, "west00", "west00", true, 0);

    // slot: balrog_normal
    /**
     * Normal Balrog (P11) signs up with Mu Young too: the same room, spots, approach store and portals as Easy's.
     */
    static final BossEntrance BALROG_NORMAL = new BossEntrance(AgentBossRunCatalog.BALROG_NORMAL,
            new PqEntrance.Beside(-50, -30, true), 105_070_001, "top00", "outPerrion", false, 0);

    // slot: latanica
    /**
     * Capt. Latanica (P14), a party boss with no entry NPC: the owner leads the party through the gate's portal. Players
     * come through Boat Quay Town (541000000), where 9270022 sells potions and ammo. The gate (541010060) is 9 hops
     * from that shop, past the desk limit, so a trip out of it is held there. No bench ally comes here (no portals).
     */
    static final BossEntrance LATANICA = party(AgentBossRunCatalog.LATANICA, 541_000_000, 0);

    // slot: neo city
    /**
     * The Neo City bosses (P14), party bosses with no entry NPC. Players come through Leafre (240000000), whose store
     * (2080001) stands on 240000002, before the Time Gate. No route leads back from a gate to Leafre, so a trip out of
     * it is held there. Leafre buys the largest standard among the listed Neo City bosses.
     */
    static final BossEntrance NEO_BERGAMOT = party(AgentBossRunCatalog.NEO_BERGAMOT, 240_000_000, 2_080_001);
    static final BossEntrance NEO_DUNAS = party(AgentBossRunCatalog.NEO_DUNAS, 240_000_000, 2_080_001);
    static final BossEntrance NEO_AUFHEBEN = party(AgentBossRunCatalog.NEO_AUFHEBEN, 240_000_000, 2_080_001);
    static final BossEntrance NEO_OBERON = party(AgentBossRunCatalog.NEO_OBERON, 240_000_000, 2_080_001);
    static final BossEntrance NEO_NIBELUNG = party(AgentBossRunCatalog.NEO_NIBELUNG, 240_000_000, 2_080_001);

    // slot: papulatus
    /**
     * Papulatus (P15): no store on the way, so the gate (220080000) is a boss desk instead. A companion short of the
     * Papulatus standard there makes one trip to the Path of Time shop (6 hops, P12 G9: BOSS_DESK_MAPS and
     * COMPANION_RESTOCK_TRIP_MAPS) and comes back.
     */
    static final BossEntrance PAPULATUS = new BossEntrance(AgentBossRunCatalog.PAPULATUS,
            new PqEntrance.Beside(-50, -30, true), 0, null, null, false, 0);

    // slot: scarga
    /**
     * Aldol stands at 1693,353 (WZ 551030100 life) at the right end of the recruit map: companions wait beside him, as
     * at Adobis. Players come through Trend Zone Metropolis (550000000), whose store sells potions and ammo; Kampung has
     * none, and the gate is 11 maps from that store, past the desk limit, so a trip out of the gate is held there.
     * Allies come in and leave by west00 (-429,426), the way in from 551030000.
     */
    static final BossEntrance SCARGA = new BossEntrance(AgentBossRunCatalog.SCARGA,
            new PqEntrance.Beside(-50, -30, true), 550_000_000, "west00", "west00", true, 0);

    // slot: horntail
    /**
     * The Mark of the Squad stands at 898,260 (WZ 240050400 life) on the Cave of Life entrance: companions wait beside
     * it, as at Adobis. Players come through Leafre (240000000), whose store (2080001) stands on 240000002; no route
     * leads into 240050400 (the Slate warps players in) or back out, so a trip out of it is held there. Allies come in
     * at sp (4,175), where the Slate lands players, and leave by st00 (-185,-48) beside the Crystal of Roots, the
     * room's way out.
     */
    static final BossEntrance HORNTAIL = new BossEntrance(AgentBossRunCatalog.HORNTAIL,
            new PqEntrance.Beside(-50, -30, true), 240_000_000, "sp", "st00", true, 2_080_001);

    // slot: showa
    /**
     * Konpei stands at 1402,161 (WZ 801040004 life) at the right end of the hideout's front, a hunting map with Male
     * Boss and the gang on it: companions wait beside him, as at Adobis. Players come through Showa Town (801000000),
     * whose store (9120002) stands on 801000300; the hideout is several maps past it, so a trip out of it is held
     * there. Allies come in and leave by out00 (-1433,158), the way in from 801040003.
     */
    static final BossEntrance SHOWA = new BossEntrance(AgentBossRunCatalog.SHOWA,
            new PqEntrance.Beside(-50, -30, true), 801_000_000, "out00", "out00", true, 9_120_002);

    // slot: pinkbean
    /**
     * The Forgotten Temple Manager stands at -665,-41 (WZ 270050000 life): companions wait beside him, as at Adobis.
     * Players come through Leafre (240000000), whose store (2080001) stands on 240000002, before the dragon ride to the
     * Temple of Time; no shop is in reach of 270050000, so a trip out of it is held there. Allies come in and leave by
     * out00 (-1133,-42), the way in from 270040100.
     */
    static final BossEntrance PINKBEAN = new BossEntrance(AgentBossRunCatalog.PINKBEAN,
            new PqEntrance.Beside(-50, -30, true), 240_000_000, "out00", "out00", true, 2_080_001);

    // slot: cwkpq
    /**
     * Jack stands at 976,94 (WZ 610030020 life) in the Hall to Inner Sanctum: companions wait beside him, as at Adobis.
     * Players come through New Leaf City (600000000), whose store (9201060) is the last on the way: past it lie the
     * taxi, the Phantom Forest, the Valley of Heroes and the keep, so a trip out of the hall is held there. Allies come in
     * and leave by out00 (-915,214), where the keystone gate (glpqEnter, Gate 9201114) lands players; each needs its
     * own Crimsonwood Keystone (CompanionKeystoneGate).
     */
    static final BossEntrance CWKPQ = new BossEntrance(AgentBossRunCatalog.CWKPQ,
            new PqEntrance.Beside(-50, -30, true), 600_000_000, "out00", "out00", true, 0);

    // slot: gpq
    /**
     * Shuang stands at -58,1305 (WZ 101030104 life) on the Excavation Site camp's lower floor: companions wait beside
     * her, as at Adobis, until the run walks each to her to join (GPQ plan B4). Players come from Perion (102000000),
     * whose stores are the last on the way; the camp is a short walk back, so trips out are not held. Allies come in and
     * leave by out00 (-1996,703), the camp's way out to 101030103.
     */
    static final BossEntrance GPQ = new BossEntrance(AgentBossRunCatalog.GPQ,
            new PqEntrance.Beside(-50, -30, true), 102_000_000, "out00", "out00", false, 0);

    private static final List<BossEntrance> ALL = List.of(EASY_BALROG, ZAKUM, BALROG_NORMAL, LATANICA, NEO_BERGAMOT,
            NEO_DUNAS, NEO_AUFHEBEN, NEO_OBERON, NEO_NIBELUNG, PAPULATUS, SCARGA, HORNTAIL, SHOWA, PINKBEAN, CWKPQ, GPQ);

    /** A party boss's entrance (P14): no entry NPC to stand beside, no ally portals, trips out of the gate held. */
    private static BossEntrance party(AgentBossRunDefinition definition, int approachMapId, int storeNpcId) {
        return new BossEntrance(definition, new PqEntrance.Beside(-50, -30, true), approachMapId, null, null, true,
                storeNpcId);
    }
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
     * True when {@code owner}, passing the approach map, is on the way to this boss: always for a boss with no entry
     * item (Easy Balrog: Ant Tunnel Park is on the walk down to Mu Young and nowhere else; P20: Pink Bean, whose marker
     * item only lies on its battle map), else only while they carry one (AgentBossRules.entryItems; Zakum: the Eye of
     * Fire, which the Door to Zakum asks every player for), since Ice Valley II is also a Yeti field and the taxi stop
     * for Sharp Cliff and the Dead Mine. Memory only.
     */
    boolean approachMeantBy(Character owner) {
        List<Integer> items = AgentBossRules.entryItems(definition.key());
        return items.isEmpty() || owner != null && items.stream().anyMatch(owner::haveItem);
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
     * The store on the approach map ({@code approachMapId}): its row's {@code storeNpcId} when it names one (P12 G9),
     * else the NPC the potion supplier catalog lists on that map (Ant Tunnel Park's 24 Hr Mobile Store, 1061001, for
     * Easy Balrog); 0 with no approach map or no supplier on it. A bench ally buys at its prices on arrival
     * (CompanionBossAllies), as it never walks past it.
     */
    int approachStoreNpcId() {
        if (storeNpcId > 0) {
            return storeNpcId;
        }
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
