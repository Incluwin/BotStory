package server.living.population.coliseum;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.LifeFactory;
import server.life.NPC;
import server.living.population.Population;
import server.maps.Foothold;
import server.maps.MapObject;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The coliseum's people in Ariant (docs/PVP_PLAN.md V4): stock NPC templates spawned server-side on every population
 * channel, the way the needs board's are ({@code NeedBoards}). Their stock spawns elsewhere keep their scripts: only the
 * objects spawned here open the coliseum, by object id, before any script starts. Checked every minute (a reloaded map
 * loses them) and taken down when the coliseum is switched off or the population stops.
 */
final class ColiseumNpcs {
    private static final Logger log = LoggerFactory.getLogger(ColiseumNpcs.class);
    private static final int GROUND_REACH_PX = 300;

    /** What an NPC here does. */
    enum Role { ARENA_MASTER, QUARTERMASTER }

    /** A spawn: its role, the stock template, the map and a point above the ground it stands on. */
    record Placement(Role role, int npcId, int mapId, int x, int y) { }

    /**
     * Tigun (2101004), Ariant's palace guard, keeps the ladder on Ariant's main street between the Battle Arena
     * transport (9010022, x 868) and the town's west side; Jano (2101010) trades for Trophies beside him (V4.3).
     */
    static final List<Placement> PLACEMENTS = List.of(
            new Placement(Role.ARENA_MASTER, 2101004, Coliseum.ARIANT, 560, 240),
            new Placement(Role.QUARTERMASTER, 2101010, Coliseum.ARIANT, 440, 240));

    private final Population population;
    /** "channel:mapId:role" -> the object id of the NPC there. */
    private final Map<String, Integer> spawned = new ConcurrentHashMap<>();

    ColiseumNpcs(Population population) {
        this.population = population;
    }

    private static String key(int channel, int mapId, Role role) {
        return channel + ":" + mapId + ":" + role;
    }

    void ensure(boolean quartermaster) {
        for (int channel : population.channels()) {
            for (Placement placement : PLACEMENTS) {
                if (placement.role() == Role.QUARTERMASTER && !quartermaster) {
                    continue;
                }
                MapleMap map = population.map(channel, placement.mapId());
                if (map == null) {
                    continue;
                }
                Integer objectId = spawned.get(key(channel, placement.mapId(), placement.role()));
                if (objectId != null && map.getMapObject(objectId) instanceof NPC npc && npc.getId() == placement.npcId()) {
                    continue;
                }
                spawn(channel, map, placement);
            }
        }
    }

    private void spawn(int channel, MapleMap map, Placement placement) {
        NPC npc = LifeFactory.getNPC(placement.npcId());
        Point above = new Point(placement.x(), placement.y());
        Point ground = map.getGroundBelow(above);
        Foothold foothold = ground == null ? null : map.getFootholds().findBelow(above);
        if (npc == null || ground == null || foothold == null || ground.y - placement.y() > GROUND_REACH_PX) {
            log.warn("coliseum NPC {} at map {} ({}, {}) has no ground under it; not placed", placement.npcId(),
                    placement.mapId(), placement.x(), placement.y());
            return;
        }
        npc.setPosition(ground);
        npc.setCy(ground.y);
        npc.setRx0(ground.x + 50);
        npc.setRx1(ground.x - 50);
        npc.setFh(foothold.getId());
        map.addMapObject(npc);
        map.broadcastMessage(PacketCreator.spawnNPC(npc));
        spawned.put(key(channel, placement.mapId(), placement.role()), npc.getObjectId());
        log.debug("coliseum {} up at map {} ch{} ({}, {})", placement.role(), placement.mapId(), channel, ground.x,
                ground.y);
    }

    /** Takes every coliseum NPC down. */
    void removeAll() {
        for (Map.Entry<String, Integer> entry : spawned.entrySet()) {
            String[] parts = entry.getKey().split(":");
            MapleMap map = population.map(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            MapObject object = map == null ? null : map.getMapObject(entry.getValue());
            if (object instanceof NPC npc) {
                map.removeMapObject(object);
                map.broadcastMessage(PacketCreator.removeNPCController(npc.getObjectId()));
                map.broadcastMessage(PacketCreator.removeNPC(npc.getObjectId()));
            }
        }
        spawned.clear();
    }

    boolean any() {
        return !spawned.isEmpty();
    }

    /** The role of the coliseum NPC {@code objectId} on the player's map, or null when it is not one of ours. */
    Role roleOf(Character player, int objectId) {
        if (player == null || player.getMap() == null || player.getClient() == null) {
            return null;
        }
        for (Placement placement : PLACEMENTS) {
            Integer ours = spawned.get(key(player.getClient().getChannel(), player.getMapId(), placement.role()));
            if (ours != null && ours == objectId) {
                return placement.role();
            }
        }
        return null;
    }
}
