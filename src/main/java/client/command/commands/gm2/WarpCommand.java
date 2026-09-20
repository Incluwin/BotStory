/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;

public class WarpCommand extends Command {
    {
        setDescription("Warp to a map.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !warp <mapid|map name>");
            return;
        }

        String targetQuery = joinStringFrom(params, 0);
        MapleMap target = null;

        try {
            target = c.getChannelServer().getMapFactory().getMap(Integer.parseInt(targetQuery));
        } catch (NumberFormatException ignored) {
            // Fall through to string-based lookup.
        }

        if (target == null) {
            MapSearchHelper.MapMatch match = MapSearchHelper.findFirstMap(targetQuery);
            if (match != null) {
                target = c.getChannelServer().getMapFactory().getMap(match.mapId());
            }
        }

        if (target == null) {
            player.yellowMessage("Map '" + targetQuery + "' is invalid.");
            return;
        }

        if (!player.isAlive()) {
            player.dropMessage(1, "This command cannot be used when you're dead.");
            return;
        }

        if (!player.isGM()) {
            if (player.getEventInstance() != null || MiniDungeonInfo.isDungeonMap(player.getMapId()) || FieldLimit.CANNOTMIGRATE.check(player.getMap().getFieldLimit())) {
                player.dropMessage(1, "This command cannot be used in this map.");
                return;
            }
        }

        // expedition issue with this command detected thanks to Masterrulax
        player.saveLocationOnWarp();
        player.changeMap(target, target.getRandomPlayerSpawnpoint());
    }
}
