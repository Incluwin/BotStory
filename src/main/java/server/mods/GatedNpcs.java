package server.mods;

import server.life.LifeFactory;
import server.life.NPC;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.function.Predicate;

/**
 * NPCs a client can only draw after a client patch: each is placed on its map when the map loads, and only while its
 * server mod is on, so a client without the patch is never sent one. Cryssea 9010018 has a String.wz name and no
 * Npc.wz image in any WZ set until the Masteria patch gives her a Custom.wz look (docs/ITCG_MASTERIA_PLAN.md section 5).
 */
public final class GatedNpcs {
    /** {@code npcId} stands at x, on the ground below y, on {@code mapId} while {@code mod} is on. */
    record Placement(ServerMod mod, int npcId, int mapId, int x, int y) {
    }

    /** Cryssea on New Leaf City's Town Center street, between Kate and Rob. */
    static final List<Placement> ALL = List.of(new Placement(ServerMods.CRYSSEA, 9_010_018, 600_000_000, 1_720, 495));

    private GatedNpcs() {
    }

    /** The placements for {@code mapId} whose mod is on. */
    static List<Placement> on(int mapId, Predicate<ServerMod> modOn) {
        return ALL.stream().filter(p -> p.mapId() == mapId && modOn.test(p.mod())).toList();
    }

    /** Called by MapFactory after a map's own life loaded. */
    public static void place(MapleMap map) {
        for (Placement placement : on(map.getId(), ServerMods.get()::isOn)) {
            Point at = new Point(placement.x(), placement.y());
            Foothold ground = map.getFootholds() == null ? null : map.getFootholds().findBelow(at);
            NPC npc = LifeFactory.getNPC(placement.npcId());
            int y = ground == null ? placement.y() : ground.calculateFooting(placement.x());
            npc.setPosition(new Point(placement.x(), y));
            npc.setCy(y);
            npc.setRx0(placement.x() - 50);
            npc.setRx1(placement.x() + 50);
            npc.setFh(ground == null ? 0 : ground.getId());
            map.addMapObject(npc);
        }
    }
}
