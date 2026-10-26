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
package server.lamp;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.weather.WeatherPackets;

/**
 * LP_LampPreview: put a lamp on the client's map, where the player stands. BotStory sends it as S->C 0x17D
 * LAMP_PREVIEW (the release's 0x373F), to a client with the weather feature active.
 *
 * <p>This is a placement TOOL, not a game feature. Lamps that ship are compiled into
 * {@code g_aManualLamps} in {@code client/lamps.cpp}; the point of the preview is to
 * find the coordinates to put there. The command prints the same numbers it sends, so
 * what you see standing in the map is exactly what gets written into the table.
 *
 * <pre>
 *   short  opcode   0x17D
 *   byte   mode     0 = place a lamp, 1 = clear every preview on this map
 *   byte   lamp     index into LampCatalog, which is generated from the same table
 *                   as the client's kLamps. Ignored when clearing.
 *   int    mapId    map that was current when the command was executed
 *   int    x        world x, from the server's own view of the player
 *   int    y        world y; the client snaps the lamp to the foothold under x
 * </pre>
 *
 * <p>Sixteen bytes including the opcode -- 2 + 1 + 1 + 4 + 4 + 4 -- fixed size in both
 * modes: the clear still carries the unused lamp and
 * position so the client decoder has one length to check rather than a branch before it
 * knows what it is reading.
 *
 * <p>The COORDINATES COME FROM THE SERVER rather than being read on the client, so the
 * lamp the player sees and the numbers printed to chat cannot disagree - which is the
 * entire value of the tool.
 *
 * <p>The preview is not persisted anywhere. It lives in the client's cached copy of the
 * field property, so it survives walking around and disappears when the client is
 * restarted or the property is re-read.
 *
 * <p><b>Opcode equality with the DLL is enforced by nothing but this comment</b> and docs/CUSTOM_OPCODES.md:
 * the client's {@code weathermod.cpp} must route 0x17D.
 *
 * @see LampCatalog
 */
public final class LampPackets {

    private LampPackets() {
    }

    public static final int MODE_PLACE = 0;
    public static final int MODE_CLEAR = 1;

    public static Packet lampPreview(int mode, int lampId, int mapId, int x, int y) {
        OutPacket p = OutPacket.create(SendOpcode.LAMP_PREVIEW);
        p.writeByte(mode);
        p.writeByte(lampId);
        p.writeInt(mapId);
        p.writeInt(x);
        p.writeInt(y);
        return p;
    }

    /** Place a preview lamp at the character's current position. */
    public static void sendTo(Character chr, int lampId, int mapId, int x, int y) {
        if (!WeatherPackets.wants(chr)) {
            return;
        }
        chr.sendPacket(lampPreview(MODE_PLACE, lampId, mapId, x, y));
    }

    /**
     * Remove every preview lamp from the map this player is standing in.
     *
     * <p>Client-side only, and only for this player: previews live in that client's
     * cached copy of the field property, so there is nothing shared to clean up and
     * another player in the same map never saw them in the first place.
     */
    public static void clear(Character chr) {
        if (!WeatherPackets.wants(chr)) {
            return;
        }
        chr.sendPacket(lampPreview(MODE_CLEAR, 0, chr.getMapId(), 0, 0));
    }
}
