package server.living.roster;

import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.life.NPC;
import server.maps.MapleMap;

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
 */
record BossEntrance(AgentBossRunDefinition definition, PqEntrance.Beside beside, int approachMapId) {
    /**
     * Mu Young stands at 617,246 (WZ 105100100 life/0): her companions wait at x 567, 537, 507, 477 and 447. On the
     * walk down from Sleepywood they pass Ant Tunnel Park (105070001), where the 24 Hr Mobile Store (1061001) sells
     * Unagi and Pure Water.
     */
    static final BossEntrance EASY_BALROG = new BossEntrance(AgentBossRunCatalog.EASY_BALROG,
            new PqEntrance.Beside(-50, -30, true), 105_070_001);
    // slot: zakum

    private static final List<BossEntrance> ALL = List.of(EASY_BALROG);
    /**
     * Where the entry NPC stands, by the map instance she was found on: looked for once per map (MapleMap.getNPCById
     * copies every object on it under its lock, and she never moves), not on every tick a companion walks up.
     */
    private static final Map<MapleMap, NpcSpot> NPC_SPOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private record NpcSpot(int npcId, Point at) { }

    /** The entrance whose entry NPC stands on {@code mapId} (its recruit map), or null on any other map. */
    static BossEntrance atRecruitMap(int mapId) {
        for (BossEntrance entrance : ALL) {
            if (entrance.definition.recruitMapId() == mapId) {
                return entrance;
            }
        }
        return null;
    }

    /** The entrance whose approach map (the one with a store on the way there) is {@code mapId}, or null. */
    static BossEntrance atApproachMap(int mapId) {
        for (BossEntrance entrance : ALL) {
            if (entrance.approachMapId > 0 && entrance.approachMapId == mapId) {
                return entrance;
            }
        }
        return null;
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
