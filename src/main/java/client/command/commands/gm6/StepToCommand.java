package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Portal;

import java.util.stream.Collectors;

/**
 * Test tooling: move yourself to a portal of the map you stand on, or to the portal closest to an NPC there. It goes
 * through {@link Character#changeMap(MapleMap, Portal)}, which tells a party quest's event that you stayed on the same
 * map and lands you in the event's own copy of it. Never use '!warpto' or '!warp' inside a party quest: '!warpto'
 * (forceChangeMap) tells the event you left, which ends the run when you lead it, and '!warp' resolves the map
 * outside the instance.
 */
public class StepToCommand extends Command {
    static final String USAGE = "Syntax: !stepto <portal name> | !stepto npc <npc id> (moves you on your current map)";
    private static final Logger log = LoggerFactory.getLogger(StepToCommand.class);

    {
        setDescription("Move to a portal or NPC on your current map; safe inside a party quest.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage(USAGE);
            return;
        }
        MapleMap map = player.getMap();
        Portal portal;
        String what;
        if (params[0].equalsIgnoreCase("npc")) {
            int npcId;
            try {
                npcId = Integer.parseInt(params.length > 1 ? params[1] : "");
            } catch (NumberFormatException e) {
                player.yellowMessage(USAGE);
                return;
            }
            NPC npc = map.getNPCById(npcId);
            if (npc == null) {
                player.yellowMessage("NPC " + npcId + " is not on this map.");
                return;
            }
            portal = map.findClosestPortal(npc.getPosition());
            what = "npc " + npcId;
        } else {
            portal = map.getPortal(params[0]);
            what = "portal " + params[0];
        }
        if (portal == null) {
            String names = map.getPortals().stream().map(Portal::getName).collect(Collectors.joining(", "));
            player.yellowMessage("No such " + what + " here. Portals on this map: " + names);
            return;
        }
        log.info("gm stepto: gm={} map={} {} portal={} at=({},{})", player.getName(), player.getMapId(), what,
                portal.getName(), portal.getPosition().x, portal.getPosition().y);
        player.changeMap(map, portal);
    }
}
