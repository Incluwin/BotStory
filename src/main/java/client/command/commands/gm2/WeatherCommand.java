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
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.weather.WeatherPackets;
import server.weather.WeatherProfile;
import server.weather.WeatherService;

/**
 * {@code !weather} - inspect and force the world sky.
 *
 * <pre>
 *   !weather                     what time it is and what the sky is doing
 *   !weather day|dusk|night|dawn force the TIME (freezes the clock)
 *   !weather clear|rain|snow|... force the WEATHER (any WeatherProfile name)
 *   !weather night rain          both, in either order
 *   !weather 21:30               freeze the clock at an exact time
 *   !weather auto                release both and hand the world back
 * </pre>
 *
 * <p><b>Time and weather are two independent axes</b>, which is the whole point of the
 * grammar: "rainy night" is a time AND a sky, and there is no single word for it. Any
 * word from either axis can be given alone or together, in any order.
 *
 * <p>Both overrides apply to the WHOLE WORLD, not to the caller's map, because that is
 * what the client models: {@code client/weather.cpp} holds one world state and filters
 * it per field. A map with no sky (a cave, anything underwater) ignores both.
 *
 * <p>Overrides expire after {@link WeatherService#OVERRIDE_HOLD_MS} so that a GM who
 * freezes midnight and then logs off does not leave the world there. {@code auto} clears
 * them immediately.
 */
public class WeatherCommand extends Command {

    {
        setDescription("Show or force the world time and weather.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character chr = c.getPlayer();

        if (params.length == 0) {
            report(chr);
            return;
        }

        Byte sky = null;
        Integer time = null;
        boolean bareSky = false;

        for (String raw : params) {
            String arg = raw.toLowerCase();

            // Any profile name from the enum, so adding a sky needs no change here.
            WeatherProfile p = WeatherProfile.byName(arg);
            if (p != null) {
                sky = p.id();
                continue;
            }

            switch (arg) {
                case "sunny":
                    sky = WeatherProfile.CLEAR.id();
                    continue;
                case "rainy":
                    sky = WeatherProfile.RAIN.id();
                    continue;
                case "snowy":
                    sky = WeatherProfile.SNOW.id();
                    continue;
                case "day":
                case "noon":
                    time = WeatherService.TIME_DAY;
                    continue;
                case "night":
                    time = WeatherService.TIME_NIGHT;
                    continue;
                case "midnight":
                    // Full night AND the map's own sky hidden, so only the moon and the
                    // starfields are left. Sets the time too: the bare sky flag carries
                    // none of its own, and a black sky with a moon at noon is a
                    // different thing to be looking at.
                    time = WeatherService.TIME_NIGHT;
                    bareSky = true;
                    continue;
                case "dawn":
                case "sunrise":
                    time = WeatherService.TIME_DAWN;
                    continue;
                case "dusk":
                case "sunset":
                    time = WeatherService.TIME_DUSK;
                    continue;
                case "auto":
                    WeatherService.clearTimeOverride();
                    WeatherService.clearSkyOverride();
                    WeatherPackets.broadcast(c.getWorldServer(), false);
                    chr.dropMessage(6, "Time and weather handed back to the world.");
                    return;
                default:
                    break;
            }

            Integer parsed = parseClock(arg);
            if (parsed == null) {
                chr.dropMessage(5, "Unknown option '" + raw + "'.");
                chr.dropMessage(5, "Usage: !weather [day|dusk|night|midnight|dawn] [" + WeatherProfile.namesForUsage() + "] | HH:MM | auto");
                return;
            }
            time = parsed;
        }

        if (time != null) {
            WeatherService.setTime(time);
            // Bare sky is a midnight preview, not an independent sticky weather mode.
            WeatherService.setBareSky(bareSky);
        }
        if (sky != null) {
            WeatherService.setSky(sky, WeatherService.OVERRIDE_HOLD_MS);
        }

        // Un-snapped: the point of forcing it from in game is to watch it fade.
        WeatherPackets.broadcast(c.getWorldServer(), false);
        report(chr);
    }

    private static void report(Character chr) {
        String clock = WeatherService.clockString();
        boolean frozen = WeatherService.isTimeOverridden();
        chr.dropMessage(6, "Time " + clock + (frozen ? " (FROZEN)" : " (running)")
                + ", night level " + String.format("%.2f", WeatherService.nightLevel()));
        chr.dropMessage(6, "Sky: " + WeatherService.skyName(WeatherService.currentSky())
                + (WeatherService.isOverridden() ? " (forced)" : " (automatic)"));
        if (frozen || WeatherService.isOverridden()) {
            chr.dropMessage(6, "!weather auto to hand it back.");
        } else {
            chr.dropMessage(6, "!weather [day|dusk|night|dawn] [" + WeatherProfile.namesForUsage() + "] | HH:MM | auto");
        }
    }

    /** "21:30" or "2130" or "21" to in-game minutes, or null if it is not a clock. */
    private static Integer parseClock(String s) {
        try {
            int h;
            int m = 0;
            int colon = s.indexOf(':');
            if (colon >= 0) {
                h = Integer.parseInt(s.substring(0, colon));
                m = Integer.parseInt(s.substring(colon + 1));
            } else if (s.length() == 4) {
                h = Integer.parseInt(s.substring(0, 2));
                m = Integer.parseInt(s.substring(2));
            } else {
                h = Integer.parseInt(s);
            }
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
