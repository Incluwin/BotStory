/*
    This file is part of a Maple Story Server and is redistributed under the
    licence below

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
package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.lamp.LampCatalog;
import server.lamp.LampPackets;

/**
 * {@code !lamp} - preview a street lamp where you are standing, and print the exact
 * coordinates so they can be written into the permanent table.
 *
 * <pre>
 *   !lamp            list the lamps, with their ids
 *   !lamp &lt;id&gt;       drop that lamp at your feet and print its coordinates
 *   !lamp clear      remove every preview lamp from this map
 * </pre>
 *
 * <p>This is {@code !pos} with a lamp attached. It reports the same position that
 * {@code !pos} does, and it sends THAT SAME POSITION to the client to draw with, so the
 * lamp you are looking at stands exactly where the printed numbers say. Reading the
 * position on the client instead would let the two drift by however far the player moved
 * between the command and the packet, which would quietly poison the table.
 *
 * <p>The client snaps the lamp's feet to the foothold under x, exactly as a permanent
 * lamp is snapped, so y only has to be roughly right - what matters is that it picks the
 * same floor. Standing on the floor you want is enough.
 *
 * <p>Previews are client-side and temporary: they live in the client's cached field
 * property and are gone on restart. Nothing is written to the database or to WZ.
 *
 * @see server.lamp.LampPackets
 * @see LampCatalog
 */
public class LampCommand extends Command {
    {
        setDescription("Preview a street lamp here and print its coordinates.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            list(player);
            return;
        }

        if (params[0].equalsIgnoreCase("clear")) {
            LampPackets.clear(player);
            player.dropMessage(6, "Cleared the preview lamps on this map.");
            return;
        }

        final int id;
        try {
            id = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.dropMessage(6, "!lamp <id> | !lamp clear. Use !lamp with no argument to list them.");
            return;
        }
        if (!LampCatalog.isValid(id)) {
            player.dropMessage(6, "No lamp " + id + ". Valid ids are 0.." + (LampCatalog.size() - 1) + ".");
            return;
        }

        final int x = player.getPosition().x;
        final int y = player.getPosition().y;
        final int mapId = player.getMapId();

        LampPackets.sendTo(player, id, mapId, x, y);

        // Printed in the exact shape of a row in g_aManualLamps, so placing a lamp
        // permanently is a copy and paste rather than a transcription. Transcribing four
        // numbers by hand is how a lamp ends up in a wall.
        player.dropMessage(6, "Lamp " + id + " (" + LampCatalog.NAMES[id] + ") at map "
                + mapId + "  (" + x + ", " + y + ")");
        player.dropMessage(6, "    { " + mapId + ", " + x + ", " + y + ", " + id + " },");
    }

    private void list(Character player) {
        player.dropMessage(6, "!lamp <id> - preview a lamp here.  !lamp clear - remove them.  "
                + LampCatalog.size() + " available:");
        for (int i = 0; i < LampCatalog.size(); i++) {
            final int lights = LampCatalog.LIGHTS[i];
            player.dropMessage(6, "  " + i + "  " + LampCatalog.NAMES[i]
                    + "  (" + lights + (lights == 1 ? " light" : " lights") + ")  "
                    + LampCatalog.WHERE[i]);
        }
    }
}
