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
package server.weather;

import client.Character;
import client.Client;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.world.World;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;

/**
 * LP_WeatherSync: the world's sky state, for {@code client/weather.cpp}. BotStory sends it as S->C 0x17C
 * WEATHER_SYNC (the release's 0x373D), only to a client with the weather feature active and only while the weather
 * server mod is on.
 *
 * <p>The v83 client has no day/night system and no concept of world weather, so every
 * byte here is a side channel. The packet is a RE-SYNC, not a tick: the client advances
 * its own clock between packets using {@code msPerGameMinute}, so dropping one only
 * costs a little drift.
 *
 * <pre>
 *   short  opcode            0x17C
 *   short  minuteOfDay       0..1439, in-game minutes past midnight
 *   int    msPerGameMinute   real ms per in-game minute (how fast the client advances)
 *   byte   sky               WeatherService.SKY_*
 *   byte   flags             bit0 = snap (do not fade; used on map entry)
 *   int    skyElapsedSec     how long this sky has held, for ground accumulation
 *   byte   tintR             the region's night colour, as a multiply against the art
 *   byte   tintG
 *   byte   tintB
 *   short  rainbowSecsLeft   seconds of after-the-rain rainbow still owed, 0 for none
 *   byte   palette            client-owned dusk/night palette id
 *   int    skyElapsedMs       exact sky age, for synchronized cosmetic lightning
 *   int    skyToken           random seed for that individual sky occurrence
 * </pre>
 *
 * <p>Twenty-eight bytes including the opcode. Broadcast once a minute per world plus once on every map entry, so the
 * shape is deliberately fixed-size with no strings and no counts: the client decoder
 * needs no bounds loop and a truncated packet can only produce clamped garbage for one
 * frame.
 *
 * <p><b>Opcode equality with the DLL is enforced by nothing but this comment</b> and docs/CUSTOM_OPCODES.md:
 * the client's {@code weathermod.cpp} must route 0x17C. The client never asks for weather, it is only told.
 *
 * @see WeatherService
 * @see net.server.task.WeatherTask
 */
public final class WeatherPackets {

    private WeatherPackets() {
    }

    /** The state over a given map. {@code snap} suppresses the client-side fade. */
    public static Packet weatherSync(int mapId, boolean snap) {
        int flags = 0;
        if (snap) {
            flags |= WeatherService.FLAG_SNAP;
        }
        if (WeatherService.isTimeOverridden()) {
            flags |= WeatherService.FLAG_FROZEN;
        }
        if (WeatherService.isBareSky()) {
            flags |= WeatherService.FLAG_BARESKY;
        }
        OutPacket p = OutPacket.create(SendOpcode.WEATHER_SYNC);
        p.writeShort(WeatherService.minuteOfDay());
        p.writeInt(WeatherService.msPerGameMinute());
        p.writeByte(WeatherService.skyForMap(mapId));
        p.writeByte(flags);
        // Lets a player who walks into a map that has been snowing for ten minutes see
        // ten minutes of drifts, rather than watching them build from bare ground. Only
        // the DURATION is shared: each client still picks its own deposit positions.
        p.writeInt(WeatherService.skyElapsedSecForMap(mapId));
        // The region's night colour. Sent rather than derived client side so the map to
        // region rule lives in exactly one place, the same place the sky comes from.
        final int tint = WeatherService.tintForMap(mapId);
        p.writeByte((tint >> 16) & 0xFF);
        p.writeByte((tint >> 8) & 0xFF);
        p.writeByte(tint & 0xFF);
        // A rainbow, if this region's rain has just stopped. Sent as the time REMAINING
        // rather than as a flag, so a player arriving part way through joins the one
        // already in progress instead of starting a fresh one of their own. The client
        // gates it on daylight itself, because it owns the clock.
        p.writeShort(WeatherService.rainbowSecsLeftForMap(mapId));
        // Palette RGB values deliberately do NOT live on the server. This byte is a
        // stable selector for client/weather_palettes.inc, whose table owns the art.
        p.writeByte(WeatherService.paletteForMap(mapId));
        // The existing seconds value is sufficient for ground accumulation. Lightning
        // needs the millisecond remainder too: otherwise players who entered one storm
        // from different packets would see the flash almost a second apart.
        p.writeInt(WeatherService.skyElapsedMillisForMap(mapId));
        p.writeInt(WeatherService.skyTokenForMap(mapId));
        return p;
    }

    /**
     * Push the current sky to one player, snapped.
     *
     * <p>Used on map entry. Snapped rather than faded because the player has just warped:
     * a two second fade from day to night after every portal would read as a bug.
     */
    public static void sendTo(Character chr) {
        if (!wants(chr)) {
            return;
        }
        chr.sendPacket(weatherSync(chr.getMapId(), true));
    }

    /**
     * Whether this character's client gets the sky: the weather mod is on and its client reported the weather
     * feature active. Companions and citizens never report one, so they are never sent it.
     */
    public static boolean wants(Character chr) {
        Client client = chr == null ? null : chr.getClient();
        return client != null && ServerMods.get().isOn(ServerMods.WEATHER)
                && FeatureHandshake.get().isActive(client, ServerMods.WEATHER.id());
    }

    /**
     * Push the current sky to everyone in a world.
     *
     * <p>Each send is individually guarded: {@code TimerManager}'s wrapper stops a throw
     * from cancelling the schedule, but it abandons the rest of the tick, so one bad
     * client would otherwise leave every later player in the loop unsynced.
     */
    public static void broadcast(World world, boolean snap) {
        if (world == null) {
            return;
        }
        // World.shutdown() cancels the timer with cancel(false), which does not interrupt
        // a tick already running, and then nulls players. A tick in flight can reach here
        // after that, so the storage is re-checked rather than trusted.
        var storage = world.getPlayerStorage();
        if (storage == null) {
            return;
        }
        // The sky is regional now, so one packet no longer serves everyone. Built per
        // REGION rather than per player: there are a handful of regions and potentially
        // hundreds of players, so this is a few allocations instead of one each.
        final java.util.Map<WeatherRegion, Packet> byRegion = new java.util.EnumMap<>(WeatherRegion.class);
        for (Character chr : storage.getAllCharacters()) {
            try {
                if (chr == null || !chr.isLoggedinWorld() || !wants(chr)) {
                    continue;
                }
                final int mapId = chr.getMapId();
                final WeatherRegion region = WeatherRegion.forMap(mapId);
                Packet packet = byRegion.get(region);
                if (packet == null) {
                    packet = weatherSync(mapId, snap);
                    byRegion.put(region, packet);
                }
                chr.sendPacket(packet);
            } catch (Exception e) {
                // one unhappy client must not cost the rest of the world its sky
            }
        }
    }
}
