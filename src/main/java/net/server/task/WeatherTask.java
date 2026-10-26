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
package net.server.task;

import net.server.world.World;
import server.mods.ServerMods;
import server.weather.WeatherPackets;
import server.weather.WeatherService;

/**
 * Keeps every client in the world looking at the same sky.
 *
 * <p>Two jobs, and only the second one is strictly necessary:
 * <ol>
 *   <li>Re-roll the weather when it is due (suspended while a GM override holds).</li>
 *   <li>Re-broadcast the state, so client clocks cannot drift apart.</li>
 * </ol>
 *
 * <p>The day/night phase itself needs no ticking at all: it is a pure function of wall
 * time on both ends (see {@link WeatherService#minuteOfDay()}). This task exists because
 * WEATHER is stateful and because a client that missed a packet should not stay wrong
 * for a whole session. Twenty-eight bytes per player per minute.
 *
 * <p>Runs on a {@code TimerManager} worker. That pool has four threads shared by every
 * recurring task in the process, so this must stay cheap: it does no I/O, takes no lock,
 * and the per-player send is already individually guarded inside
 * {@link WeatherPackets#broadcast}.
 */
public class WeatherTask extends BaseTask implements Runnable {

    /** Broadcast period. Also the granularity at which a due weather roll is noticed. */
    public static final long INTERVAL_MS = 60L * 1000L;

    public WeatherTask(World world) {
        super(world);
    }

    @Override
    public void run() {
        // World.shutdown() cancels with cancel(false), which does not interrupt a tick
        // in flight, and then nulls the player storage. A tick that was already running
        // can still land here afterwards.
        if (wserv == null || wserv.getPlayerStorage() == null || !ServerMods.get().isOn(ServerMods.WEATHER)) {
            return;
        }

        // Every world schedules this shared server-wide sky. rollIfDue synchronizes the
        // contenders, so exactly one task performs a due roll and all worlds broadcast
        // the resulting regional state.
        boolean changed = WeatherService.rollIfDue();

        // A changed sky still broadcasts un-snapped: the client fades into new weather,
        // which is the whole point of the fade machinery. Only map entry snaps.
        WeatherPackets.broadcast(wserv, false);

        // BotStory: cosmetic only. The release's blizzard slow (WeatherDebuff) and night-only mobs
        // (NocturnalMobService) also rode this tick; they are not ported.

        if (changed) {
            log.debug("World {} weather -> {}", wserv.getId(),
                    WeatherService.skyName(WeatherService.currentSky()));
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WeatherTask.class);
}
