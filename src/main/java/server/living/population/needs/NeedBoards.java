package server.living.population.needs;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.LifeFactory;
import server.life.NPC;
import server.living.config.ContentLoader;
import server.living.config.Node;
import server.living.population.Population;
import server.maps.Foothold;
import server.maps.MapObject;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The request boards (docs/BOT_CONCEPT_PLAN.md F1): stock Bulletin Board NPCs (9040011) spawned server-side, the way
 * {@code !npc} spawns a template, at the places {@code needs.yaml} lists (the FM entrance and the main towns), on every
 * population channel; no client change. 9040011 already stands in the Guild PQ maps with its stock script: only the
 * objects spawned here open the needs board, by object id, before any script starts. They are checked every minute
 * (a reloaded map loses them) and taken down when the population stops.
 */
public final class NeedBoards {
    private static final Logger log = LoggerFactory.getLogger(NeedBoards.class);
    public static final int BOARD_NPC = 9040011;
    public static final String FILE = "needs.yaml";
    /** A board stands on the ground under its point, within this many pixels; further down it is not placed. */
    private static final int GROUND_REACH_PX = 300;

    /** Where a board stands: a map, and a point above the ground it is put on. */
    public record Placement(int mapId, int x, int y) { }

    private final Population population;
    private final NeedScreens screens;
    private volatile List<Placement> placements;
    /** "channel:mapId" -> the board's object id on that map instance. */
    private final Map<String, Integer> spawned = new ConcurrentHashMap<>();

    NeedBoards(Population population, NeedLedger ledger) {
        this.population = population;
        this.screens = new NeedScreens(population, ledger);
    }

    public NeedScreens screens() {
        return screens;
    }

    /** The placements of {@code needs.yaml} in the population content folder (none when it is missing). */
    static List<Placement> load(Path file) {
        List<Placement> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (Node board : ContentLoader.read(file).get("boards").items()) {
            out.add(new Placement(board.get("map").integer(), board.get("x").integer(), board.get("y").integer()));
        }
        return out;
    }

    List<Placement> placements() {
        List<Placement> current = placements;
        if (current == null) {
            current = load(Path.of(population.config().CONTENT_DIR).resolve(FILE));
            placements = current;
        }
        return current;
    }

    private static String key(int channel, int mapId) {
        return channel + ":" + mapId;
    }

    /** Every board stands on every population channel; one gone (a map reloaded) is put back. */
    void ensure() {
        for (int channel : population.channels()) {
            for (Placement placement : placements()) {
                MapleMap map = population.map(channel, placement.mapId());
                if (map == null) {
                    continue;
                }
                Integer objectId = spawned.get(key(channel, placement.mapId()));
                if (objectId != null && map.getMapObject(objectId) instanceof NPC npc && npc.getId() == BOARD_NPC) {
                    continue;
                }
                spawn(channel, map, placement);
            }
        }
    }

    private void spawn(int channel, MapleMap map, Placement placement) {
        NPC npc = LifeFactory.getNPC(BOARD_NPC);
        Point ground = map.getGroundBelow(new Point(placement.x(), placement.y()));
        Foothold foothold = ground == null ? null : map.getFootholds().findBelow(new Point(placement.x(), placement.y()));
        if (npc == null || ground == null || foothold == null || ground.y - placement.y() > GROUND_REACH_PX) {
            log.warn("needs board at map {} ({}, {}) has no ground under it; not placed", placement.mapId(),
                    placement.x(), placement.y());
            return;
        }
        npc.setPosition(ground);
        npc.setCy(ground.y);
        npc.setRx0(ground.x + 50);
        npc.setRx1(ground.x - 50);
        npc.setFh(foothold.getId());
        map.addMapObject(npc);
        map.broadcastMessage(PacketCreator.spawnNPC(npc));
        spawned.put(key(channel, placement.mapId()), npc.getObjectId());
        log.debug("needs board up at map {} ch{} ({}, {})", placement.mapId(), channel, ground.x, ground.y);
    }

    /** Takes every board down (the population stops). */
    public void removeAll() {
        for (Map.Entry<String, Integer> entry : spawned.entrySet()) {
            String[] parts = entry.getKey().split(":");
            MapleMap map = population.map(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            MapObject object = map == null ? null : map.getMapObject(entry.getValue());
            if (object instanceof NPC npc && npc.getId() == BOARD_NPC) {
                map.removeMapObject(object);
                map.broadcastMessage(PacketCreator.removeNPCController(npc.getObjectId()));
                map.broadcastMessage(PacketCreator.removeNPC(npc.getObjectId()));
            }
        }
        spawned.clear();
    }

    /** A player clicked an NPC: one of these boards opens the needs board (true); anything else is not ours. */
    public boolean clicked(Character player, int objectId) {
        if (player == null || player.getMap() == null) {
            return false;
        }
        Integer ours = spawned.get(key(player.getClient().getChannel(), player.getMapId()));
        if (ours == null || ours != objectId) {
            return false;
        }
        if (!population.needs().on()) {
            return false;
        }
        screens.board(player, player.getMapId());
        return true;
    }
}
