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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world's sky: what time of day it is, and what is falling out of it.
 *
 * <p><b>The clock is not a counter.</b> {@link #minuteOfDay()} is a pure function of
 * {@code System.currentTimeMillis()}, which buys three things for free: it survives a
 * reboot without any persistence, every world and every channel agrees without any
 * synchronisation, and there is no drift to correct. Nothing here has to tick for the
 * clock to be right; the recurring task exists only to broadcast, to re-roll the
 * weather, and to keep clients from drifting between packets.
 *
 * <p>Deliberately NOT {@code Server.getCurrentTime()}: that value is advanced by a
 * constant 777 ms per disease tick and only re-pinned to wall time every five minutes,
 * so it can jump backwards. A day/night phase derived from it would stutter.
 *
 * <p><b>The sky is SERVER-WIDE, not per world.</b> This state is static while
 * {@link net.server.task.WeatherTask} is registered once per world, so N worlds share one
 * sky. That is deliberate: the brief was that every player experiences the same weather
 * simultaneously, and a per-world sky would mean two players in the same guild seeing
 * different rain. The consequence is that N world timers all call {@link #rollIfDue()},
 * so that method has to be safe against concurrent callers, which is what the
 * compare-and-set on {@code nextRollAt} is for: exactly one caller per interval wins the
 * roll and the rest observe the result.
 *
 * <p>To make weather per world instead, key these three fields by world id and give
 * {@link net.server.task.WeatherTask} its {@code wserv.getId()}; nothing else changes.
 *
 * @see WeatherPackets
 * @see net.server.task.WeatherTask
 */
public final class WeatherService {

    private WeatherService() {
    }

    // ---------------------------------------------------------------- the clock

    /**
     * Real milliseconds in one in-game day. Four real hours means an in-game minute is
     * ten real seconds, a full night runs about 100 real minutes, and a player in a
     * typical session sees the sky change at least once.
     */
    public static final long DAY_LENGTH_MS = 4L * 60L * 60L * 1000L;

    public static final int MINUTES_PER_DAY = 1440;

    /** Real milliseconds per in-game minute. The client advances its own clock by this. */
    public static int msPerGameMinute() {
        return (int) (DAY_LENGTH_MS / MINUTES_PER_DAY);
    }

    /**
     * In-game minutes past midnight, 0..1439.
     *
     * <p>Normally a pure function of wall time. While a GM time override is held the
     * clock is FROZEN at the overridden minute instead, which is why callers must go
     * through here rather than doing the modulo themselves.
     */
    public static int minuteOfDay() {
        int forced = timeOverrideMinute.get();
        if (forced >= 0 && timeOverrideUntil.get() > System.currentTimeMillis()) {
            return forced;
        }
        return wallClockMinuteOfDay();
    }

    /** The unforced clock, for showing a GM what the time would be without the override. */
    public static int wallClockMinuteOfDay() {
        long intoDay = Math.floorMod(System.currentTimeMillis(), DAY_LENGTH_MS);
        return (int) ((intoDay * MINUTES_PER_DAY) / DAY_LENGTH_MS);
    }

    // ------------------------------------------------------------ time override
    //
    // Freezing the clock is a second, independent axis from the weather: "rainy night"
    // is a time AND a sky, and before this there was no way to ask for the time half.
    //
    // It is a FREEZE, not an offset. An offset would keep advancing, so a GM who set
    // night for a screenshot would watch it drift into dawn. The frozen minute is
    // broadcast with FLAG_FROZEN so the client stops advancing its own copy too;
    // without that bit the client would creep forward between packets and get snapped
    // back once a minute, which reads as a stutter.
    //
    // The hold is finite on purpose. A GM who freezes the world at midnight and then
    // logs off should not leave it there: this self-heals, and !weather auto clears it
    // immediately.

    private static final AtomicInteger timeOverrideMinute = new AtomicInteger(-1);
    private static final AtomicLong timeOverrideUntil = new AtomicLong(0L);

    /** How long a forced time or sky holds before the world takes itself back. */
    public static final long OVERRIDE_HOLD_MS = 60L * 60L * 1000L;

    public static boolean isTimeOverridden() {
        return timeOverrideMinute.get() >= 0 && timeOverrideUntil.get() > System.currentTimeMillis();
    }

    /** Freeze the clock at {@code minute} for {@link #OVERRIDE_HOLD_MS}. */
    public static void setTime(int minute) {
        int m = Math.floorMod(minute, MINUTES_PER_DAY);
        synchronized (skyLock) {
            timeOverrideUntil.set(System.currentTimeMillis() + OVERRIDE_HOLD_MS);
            timeOverrideMinute.set(m);
        }
    }

    /** Hand the clock back to wall time. */
    public static void clearTimeOverride() {
        synchronized (skyLock) {
            bareSky = false;
            timeOverrideMinute.set(-1);
            timeOverrideUntil.set(0L);
        }
    }

    // Named times, so a GM can say "night" instead of computing a minute. Literals
    // rather than expressions over DAWN_START etc: those are declared further down and
    // a static initialiser cannot forward-reference them.
    public static final int TIME_DAWN = 6 * 60;    // 06:00, mid-ramp, half lit
    public static final int TIME_DAY = 12 * 60;    // 12:00, fully lit
    public static final int TIME_DUSK = 18 * 60;   // 18:00, mid-ramp, half dark
    public static final int TIME_NIGHT = 0;        // 00:00, fully dark

    /**
     * 0.0 at full day, 1.0 at full night, ramped across dawn and dusk.
     *
     * <p>Server-side twin of {@code Weather::NightLevel()} in {@code client/weather.cpp}.
     * The client owns the curve that is actually rendered; this copy exists so server
     * logic (seasonal rolls, future gameplay hooks) can ask "is it night" without
     * guessing. <b>Keep the two in step.</b>
     */
    public static float nightLevel() {
        int m = minuteOfDay();
        if (m < DAWN_START || m >= DUSK_END) {
            return 1.0f;
        }
        if (m < DAWN_END) {
            return 1.0f - (float) (m - DAWN_START) / (DAWN_END - DAWN_START);
        }
        if (m < DUSK_START) {
            return 0.0f;
        }
        return (float) (m - DUSK_START) / (DUSK_END - DUSK_START);
    }

    /** 05:00 to 07:00 is dawn, 17:00 to 19:00 is dusk. Mirrored in weather.cpp. */
    public static final int DAWN_START = 5 * 60;
    public static final int DAWN_END = 7 * 60;
    public static final int DUSK_START = 17 * 60;
    public static final int DUSK_END = 19 * 60;

    public static boolean isNight() {
        return nightLevel() > 0.5f;
    }

    // ---------------------------------------------------------------- the sky

    /**
     * Wire values. Kept as aliases of the first three {@link WeatherProfile} ids so the
     * packet format did not change when profiles landed; new skies are added to that
     * enum, not here.
     */
    public static final byte SKY_CLEAR = 0;
    public static final byte SKY_RAIN = 1;
    public static final byte SKY_SNOW = 2;

    /** Set by the client on map entry so the sky does not visibly fade in on arrival. */
    public static final byte FLAG_SNAP = 0x01;

    /**
     * The clock is frozen by a GM override: the client must NOT advance its own copy.
     * Without this the client would creep forward between packets and be snapped back
     * on every broadcast, which reads as a stutter rather than as a held time.
     */
    public static final byte FLAG_FROZEN = 0x02;

    /**
     * Testing: hide the map's own sky and show only the moon and the starfields.
     *
     * <p>Purely a rendering mode. It carries no time of its own, so {@code !weather
     * midnight} sets the clock to 00:00 as well; the flag alone at noon would give a
     * black sky with a moon in it, which is a different bug to look at.
     */
    public static final byte FLAG_BARESKY = 0x04;

    private static volatile boolean bareSky = false;

    /**
     * Gated on the time override it is a preview of, so it inherits that override's
     * finite hold. On its own this flag had no deadline at all: every caller sets it in
     * the same breath as a {@code setTime}, so a GM who ran {@code !weather midnight} for
     * a screenshot and logged off left the clock to self-heal one hour later while the
     * bare-sky bit kept broadcasting forever, holding every weather map's own sky at
     * {@code visible = 0} under a running daytime clock. Worse, once the freeze lapsed
     * {@code report()} stopped printing the "!weather auto" hint, so nothing on the
     * server said the world was still in a testing render mode.
     */
    public static boolean isBareSky() {
        return bareSky && isTimeOverridden();
    }

    public static void setBareSky(boolean on) {
        bareSky = on;
    }

    // One rolled sky PER REGION. Regions with a forced profile never consult this.
    //
    // Rolled per region rather than derived from one global value, because a derivation
    // would have to be random to be interesting and a random derivation evaluated per
    // packet would make the sky flicker. A stored roll per region is stable between
    // rolls, which is what the client needs.
    private static final java.util.concurrent.ConcurrentHashMap<WeatherRegion, Byte> skyByRegion =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Wall time each region's CURRENT sky began, so the client can seed its ground
     * accumulation instead of starting bare.
     *
     * <p>Without this, walking into a map that has been snowing for ten minutes shows
     * clean ground and the drifts build from zero for that player alone. Sending the
     * elapsed time is enough: the client picks its own deposit POSITIONS, so two players
     * see different arrangements at the same density, which nobody can tell apart in
     * play and which costs one int instead of a per-map deposit list on the wire.
     *
     * <p>A region absent from this map has never rolled, so its sky is as old as the
     * process. {@link #skyElapsedSecForMap} reports the uptime in that case, which is the
     * right answer for the forced regions (El Nath has always been snowing) and harmless
     * for the rest, whose sky is CLEAR and accumulates nothing.
     */
    private static final java.util.concurrent.ConcurrentHashMap<WeatherRegion, Long> skySinceByRegion =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Process start, the fallback age for a sky that has never been re-rolled. */
    private static final long startedAt = System.currentTimeMillis();

    /**
     * Wall time each region's rainbow expires, from a wet sky clearing.
     *
     * <p>Server side ON PURPOSE, and this is the whole reason it is not a client effect.
     * A rainbow that each client started for itself would only be seen by players who
     * happened to be standing in the region when the rain stopped. Holding the deadline
     * here and sending the REMAINING time means a player who arrives ninety seconds later
     * sees the last ninety seconds of the same rainbow, on the same schedule as everyone
     * else in the region.
     *
     * <p>A region absent from this map has no rainbow, which is the normal state.
     */
    private static final java.util.concurrent.ConcurrentHashMap<WeatherRegion, Long> rainbowUntilByRegion =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How long a rainbow lasts once the rain stops. Real time, not game time. */
    public static final long RAINBOW_MS = 180L * 1000L;

    /** Which skies leave a rainbow behind them when they clear. */
    private static boolean isWet(byte s) {
        return s == WeatherProfile.RAIN.id() || s == WeatherProfile.STORM.id();
    }

    /**
     * Record a sky change for {@code region} and start a rainbow if it just stopped
     * raining. Called from both places a sky can change: the periodic roll and a GM
     * override.
     *
     * <p>The DAY gate is deliberately NOT here. The client already owns the clock and
     * knows whether the sun is up, so gating server side would need this to duplicate
     * the day/night curve, and a rainbow that began in daylight would be cut off at dusk
     * mid-broadcast rather than fading with the light.
     */
    private static void onSkyChanged(WeatherRegion region, byte previous, byte next, long now) {
        skySinceByRegion.put(region, now);
        if (isWet(previous) && next == WeatherProfile.CLEAR.id()) {
            rainbowUntilByRegion.put(region, now + RAINBOW_MS);
        } else {
            // Any other change ends one early. Rain starting again during a rainbow, or
            // snow arriving, should not leave it hanging in the sky.
            rainbowUntilByRegion.remove(region);
        }
    }

    /**
     * Seconds of rainbow left over {@code mapId}, 0 for none.
     *
     * <p>Capped at a short, so it fits the two bytes it is sent in. RAINBOW_MS is far
     * below that ceiling; the clamp is there so a future longer rainbow cannot silently
     * wrap on the wire.
     */
    public static int rainbowSecsLeftForMap(int mapId) {
        // A sky override is global, but the transition into it is still regional: a
        // region that was raining may leave a rainbow while another was already clear.
        final WeatherRegion region = WeatherRegion.forMap(mapId);
        final Long until = rainbowUntilByRegion.get(region);
        if (until == null) {
            return 0;
        }
        final long left = (until - System.currentTimeMillis()) / 1000L;
        if (left <= 0L) {
            rainbowUntilByRegion.remove(region);
            return 0;
        }
        return left > Short.MAX_VALUE ? Short.MAX_VALUE : (int) left;
    }

    /** The GM override, which forces every region at once. Null when not held. */
    private static final AtomicInteger sky = new AtomicInteger(SKY_CLEAR);

    /**
     * When a GM override expires, as wall time. Zero means no override, and the periodic
     * roll owns the sky. A GM setting the weather freezes the roll for a while rather
     * than fighting it on the next tick.
     */
    private static final AtomicLong overrideUntil = new AtomicLong(0L);

    /** Real milliseconds between automatic weather re-rolls. */
    public static final long ROLL_INTERVAL_MS = 15L * 60L * 1000L;

    private static final AtomicLong nextRollAt = new AtomicLong(0L);

    /** The world's baseline sky: the GM override if held, else the DEFAULT region. */
    public static byte currentSky() {
        if (isOverridden()) {
            return (byte) sky.get();
        }
        return skyByRegion.getOrDefault(WeatherRegion.DEFAULT, (byte) sky.get());
    }

    /**
     * The sky over a particular map.
     *
     * <p>A GM override wins everywhere, so {@code !weather rain} still means rain in El
     * Nath. Otherwise a region with a forced profile always reports it, and everything
     * else reports its own rolled value.
     */
    public static byte skyForMap(int mapId) {
        if (isOverridden()) {
            return (byte) sky.get();
        }
        final WeatherRegion region = WeatherRegion.forMap(mapId);
        final WeatherProfile forced = region.forcedProfile();
        if (forced != null) {
            return forced.id();
        }
        return skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
    }

    /**
     * How long the sky over {@code mapId} has held, in seconds, for ground accumulation.
     *
     * <p><b>LEGACY, and no current client reads it.</b> The DLL stores this field in an
     * atomic it never loads: {@code Weather::SkyElapsedSec()} derives its seconds from the
     * UNCAPPED {@code skyElapsedMillis} field appended later in the same packet, so
     * {@link #ELAPSED_CAP_SEC} bounds nothing that is actually used. It is still sent
     * because the packet is POSITIONAL - removing it has to be done in lockstep with the
     * DLL decoder or the palette, elapsedMs and token reads all shift by four bytes. If a
     * bound is ever wanted, it belongs on {@link #skyElapsedMillisForMap} instead.
     */
    public static int skyElapsedSecForMap(int mapId) {
        final long now = System.currentTimeMillis();
        final long since = skySinceByRegion.getOrDefault(WeatherRegion.forMap(mapId), startedAt);
        final long secs = (now - since) / 1000L;
        if (secs < 0L) {
            return 0;
        }
        return secs > ELAPSED_CAP_SEC ? ELAPSED_CAP_SEC : (int) secs;
    }

    /** Beyond this the client is saturated anyway. */
    public static final int ELAPSED_CAP_SEC = 3600;

    /**
     * The night tint over {@code mapId}, 0xRRGGBB.
     *
     * <p>A GM override does NOT change this. The override forces a SKY, not a place: El
     * Nath is still El Nath while a GM is making it rain, so it should still have El
     * Nath's night colour.
     */
    public static int tintForMap(int mapId) {
        return WeatherRegion.forMap(mapId).tint();
    }

    /**
     * The client-owned dusk/night palette over {@code mapId}. The returned byte has no
     * colour meaning on the server; it indexes client/weather_palettes.inc.
     */
    public static byte paletteForMap(int mapId) {
        return WeatherPalette.forRegion(WeatherRegion.forMap(mapId)).id();
    }

    /**
     * Exact age of this region's sky, for synchronized cosmetic events such as lightning.
     *
     * <p>The older seconds field remains the compact ground-accumulation value. This is
     * intentionally uncapped (but int-clamped): a flash is timed in milliseconds, so
     * throwing away the sub-second part would make clients entering the same storm flash
     * up to a second apart.
     */
    public static int skyElapsedMillisForMap(int mapId) {
        final long now = System.currentTimeMillis();
        final long since = skySinceForMap(mapId);
        final long elapsed = now - since;
        if (elapsed <= 0L) {
            return 0;
        }
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    /** A stable, per-sky token. It seeds cosmetic events but is not gameplay state. */
    public static int skyTokenForMap(int mapId) {
        final long since = skySinceForMap(mapId);
        return (int) (since ^ (since >>> 32));
    }

    private static long skySinceForMap(int mapId) {
        return skySinceByRegion.getOrDefault(WeatherRegion.forMap(mapId), startedAt);
    }

    /**
     * Guards the compound read-decide-write on {@code sky} + {@code overrideUntil}.
     *
     * <p>The three fields are individually atomic, which is enough for readers but not
     * for the two mutators, because both of them make a DECISION from one field and then
     * write the other. An earlier attempt to fix this by ordering the two writes inside
     * {@link #setSky} did not work and the comment claiming it did was wrong: the
     * unprotected span is not in {@code setSky} at all, it is inside {@link #rollIfDue},
     * between reading {@code overrideUntil} and writing {@code sky}. No write ordering in
     * the other method can close a window that lives entirely in this one.
     *
     * <p>The concrete race it does close: a timer thread evaluates {@code isOverridden()}
     * as false and is descheduled; a GM runs {@code !weather rain}, arming the override and
     * setting the sky; the timer resumes, wins the deadline CAS and overwrites the GM's
     * sky. In game that reads as the command being silently ignored.
     *
     * <p>A lock is affordable here in a way it would not be in a hot path: these run once
     * per 60 s per world and once per GM command, and nothing inside the critical section
     * blocks, allocates or sends.
     */
    private static final Object skyLock = new Object();

    /** Force the sky and suspend the automatic roll for {@code holdMs}. */
    public static void setSky(byte value, long holdMs) {
        synchronized (skyLock) {
            final long now = System.currentTimeMillis();
            if (holdMs <= 0L) {
                clearSkyOverrideLocked(now);
                return;
            }

            final byte next = clampSky(value);
            final boolean wasOverridden = overrideUntil.get() > now;
            final byte forcedPrevious = (byte) sky.get();
            overrideUntil.set(now + holdMs);
            sky.set(next);

            for (WeatherRegion region : WeatherRegion.values()) {
                final byte previous = wasOverridden ? forcedPrevious : automaticSkyForRegion(region);
                if (next != previous) {
                    onSkyChanged(region, previous, next, now);
                }
            }
        }
    }

    /** Hand the sky back to each region's automatic (or forced regional) profile. */
    public static void clearSkyOverride() {
        synchronized (skyLock) {
            clearSkyOverrideLocked(System.currentTimeMillis());
        }
    }

    /** Caller holds {@link #skyLock}. Returns whether any region visibly changed. */
    private static boolean clearSkyOverrideLocked(long now) {
        final long heldUntil = overrideUntil.get();
        if (heldUntil == 0L) {
            return false;
        }

        final byte previous = (byte) sky.get();
        boolean changed = false;
        for (WeatherRegion region : WeatherRegion.values()) {
            final byte next = automaticSkyForRegion(region);
            if (next != previous) {
                onSkyChanged(region, previous, next, now);
                changed = true;
            }
        }
        overrideUntil.set(0L);
        sky.set(automaticSkyForRegion(WeatherRegion.DEFAULT));
        return changed;
    }

    private static byte automaticSkyForRegion(WeatherRegion region) {
        final WeatherProfile forced = region.forcedProfile();
        return forced != null ? forced.id()
                              : skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
    }

    /**
     * Whether a GM sky override is still in force.
     *
     * <p>The lapse is made a SINGLE event here rather than two independently observed
     * ones. A bare {@code overrideUntil.get() > now} test hands each region its automatic
     * sky back the instant the deadline passes, but the bookkeeping for that transition
     * ({@link #onSkyChanged}, which restamps {@code skySinceByRegion} and arms the
     * rainbow) lives in {@link #clearSkyOverrideLocked}, whose only automatic caller is
     * {@code rollIfDue} up to {@code WeatherTask.INTERVAL_MS} later. For that whole window
     * the restored sky was reported paired with the OVERRIDE's age, so a client entering
     * a map seeded its drifts from the wrong elapsed time, and a rain-to-clear lapse
     * raised no rainbow at all.
     *
     * <p>The lock is taken only on the one transition where a held override has actually
     * expired; the common no-override case is a single atomic read and no lock.
     */
    public static boolean isOverridden() {
        if (overrideUntil.get() == 0L) {
            return false;
        }
        lapseSkyOverrideIfDue();
        return overrideUntil.get() != 0L;
    }

    /**
     * {@link #isOverridden()}, additionally reporting whether THIS call was the one that
     * observed the lapse and did its bookkeeping. Only {@code rollIfDue} needs the second
     * answer, and only so it can still report a lapse as a change.
     */
    private static boolean lapseAndReportChange() {
        if (overrideUntil.get() == 0L) {
            return false;
        }
        return lapseSkyOverrideIfDue();
    }

    /**
     * Runs the lapse bookkeeping the instant the deadline passes, not at the next tick.
     *
     * @return whether this call performed the clear AND a region's sky visibly changed as
     *         a result. {@code rollIfDue} needs that answer: it used to obtain it from its
     *         own {@code clearSkyOverrideLocked} call, which this method now always beats
     *         to the work, leaving that call a guaranteed no-op and silently breaking
     *         {@code rollIfDue}'s documented "true when the sky actually changed" contract.
     */
    private static boolean lapseSkyOverrideIfDue() {
        final long heldUntil = overrideUntil.get();
        if (heldUntil == 0L || heldUntil > System.currentTimeMillis()) {
            return false;
        }
        synchronized (skyLock) {
            // Re-tested under the lock: another thread may have cleared it already.
            final long stillHeld = overrideUntil.get();
            if (stillHeld != 0L && stillHeld <= System.currentTimeMillis()) {
                return clearSkyOverrideLocked(System.currentTimeMillis());
            }
        }
        return false;
    }

    /**
     * Advance the weather if it is due. Returns true when the sky actually changed, so
     * the caller can decide whether this tick is a change or just a keep-alive.
     *
     * <p>Called from EVERY world's timer thread, concurrently, because the state is
     * server-wide, and interleaved with {@link #setSky} from any GM's command thread.
     * The whole decision is taken under {@link #skyLock} so that neither a second world
     * nor a GM can slip between the override check and the write. The deadline CAS is
     * kept as well: it is what makes "exactly one world per interval performs the roll"
     * true, and it stays correct on its own if the lock is ever removed.
     */
    public static boolean rollIfDue() {
        synchronized (skyLock) {
            long now = System.currentTimeMillis();
            // Capture whether the lapse happened on THIS call before testing the flag:
            // isOverridden() performs the lapse as a side effect, so by the time it has
            // answered false the bookkeeping is already done and a plain
            // clearSkyOverrideLocked() here would be a guaranteed no-op.
            boolean changed = lapseAndReportChange();
            if (overrideUntil.get() != 0L) {
                return false;   // still held
            }
            long due = nextRollAt.get();
            if (now < due || !nextRollAt.compareAndSet(due, now + ROLL_INTERVAL_MS)) {
                return changed;   // not due, or another world already claimed this interval
            }

            for (WeatherRegion region : WeatherRegion.values()) {
                if (region.forcedProfile() != null) {
                    continue;   // never rolls
                }
                byte previous = skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
                byte next = clampSky(pick(region));
                skyByRegion.put(region, next);
                if (next != previous) {
                    changed = true;
                    // Only on a CHANGE. Re-rolling the same sky must not reset a drift
                    // that has been building for half an hour.
                    onSkyChanged(region, previous, next, now);
                }
            }
            sky.set(skyByRegion.getOrDefault(WeatherRegion.DEFAULT, WeatherProfile.CLEAR.id()));
            return changed;
        }
    }

    // ------------------------------------------------------------------ seasons
    //
    // The season is derived from the real-world date, for the same reason the day clock
    // is derived from wall time: it is a pure function, so it needs no state, survives a
    // reboot, and every world agrees without synchronising. A compressed in-game year
    // would be more game-like but needs a second clock and something to persist it.
    //
    // Deliberately weather ONLY. Seasonal GROUND art (snow lying on Henesys grass) means
    // swapping map tiles rather than tinting layers, which is a different and much larger
    // feature. See IMPLEMENTATION.md.

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static Season currentSeason() {
        switch (java.time.LocalDate.now().getMonth()) {
            case DECEMBER:
            case JANUARY:
            case FEBRUARY:
                return Season.WINTER;
            case MARCH:
            case APRIL:
            case MAY:
                return Season.SPRING;
            case JUNE:
            case JULY:
            case AUGUST:
                return Season.SUMMER;
            default:
                return Season.AUTUMN;
        }
    }

    /**
     * Per-season roll weights, in {@link WeatherProfile} order:
     * clear, rain, snow, overcast, storm, blizzard, leaves, blossom, sandstorm.
     *
     * <p>Rows sum to 100 by convention, but {@link #pick(WeatherRegion)} does not require it: it
     * totals the row and rolls against that, so a row can be edited without rebalancing
     * the rest of it.
     *
     * <p>Snow is winter-only and leaves are autumn-only on purpose. A profile that can
     * turn up in any season stops reading as a season.
     */
    private static final int[][] SEASON_WEIGHTS = {
            //         clear rain snow over storm bliz leaf blos sand
            /*SPRING*/ {  48,  22,   0,  12,    6,   0,   0,  12,   6 },
            /*SUMMER*/ {  62,  14,   0,   8,   16,   0,   0,   0,  10 },
            /*AUTUMN*/ {  40,  20,   0,  18,    4,   0,  18,   0,   6 },
            /*WINTER*/ {  36,   4,  30,  14,    2,  14,   0,   0,   3 },
    };

    // SEASON_WEIGHTS is indexed by WeatherProfile ORDINAL and pick() hands the winning
    // column straight back as a profile id, so two invariants have to hold: ordinal must
    // equal id, and every row must be exactly as wide as the enum.
    //
    // Java enforces neither. int[][] may be ragged, so a half-finished additive edit
    // still compiles, and the enum is deliberately built to tolerate a hole in the id
    // space (byId answers CLEAR for an empty slot), which is what would make a
    // divergence SILENT rather than loud. The client already turns the same mistake into
    // a compile error, via the name-anchor static_asserts in weather.cpp; this is the
    // server-side equivalent, and it fails at class init rather than at some later roll.
    static {
        WeatherProfile[] all = WeatherProfile.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].id() != i) {
                throw new IllegalStateException("WeatherProfile " + all[i] + " has id "
                        + all[i].id() + " but ordinal " + i
                        + "; SEASON_WEIGHTS is indexed by ordinal");
            }
        }
        for (int s = 0; s < SEASON_WEIGHTS.length; s++) {
            if (SEASON_WEIGHTS[s].length != all.length) {
                throw new IllegalStateException("SEASON_WEIGHTS row " + s + " has "
                        + SEASON_WEIGHTS[s].length + " columns, expected " + all.length);
            }
        }
    }

    /**
     * Roll a sky for the current season.
     *
     * <p>This is the only place the weather table lives. It returns a
     * {@link WeatherProfile} id, which is the same byte the wire has always carried.
     */
    private static byte pick(WeatherRegion region) {
        final int[] season = SEASON_WEIGHTS[currentSeason().ordinal()];
        final double[] bias = region.weightBias();

        // The region multiplies the season, it does not replace it. That ordering is the
        // point: Ellinia's rain bias still yields no snow in July, because the seasonal
        // weight it multiplies is zero. A region can make something likelier or rarer,
        // never in season when it is not.
        // SANDSTORM IS OPT IN. It carries a real seasonal weight because a desert's bias
        // has to have something to multiply, but a region that has not asked for one must
        // never get one, and a null bias means "no opinion" rather than "yes to
        // everything". Without this line, every region added in future without a bias
        // would quietly start having sandstorms.
        // BLOSSOM IS OPT IN FOR THE SAME REASON, and the reason is the ART. Petals should
        // fall where there is something to shed them, and there is exactly one such place:
        // the only cherry trees in the game are globalJP/flowerViewing/nature/0..4, and
        // find_blossom_trees.py finds them placed in Mushroom Shrine and nowhere
        // else. (It also finds nine pink objects that are NOT trees -- Aquarium windows,
        // coral, a pink ladder in Amoria, a Ludibrium toy -- which is why the answer had to
        // be measured per sprite rather than taken per region.)
        //
        // Mushroom Shrine FORCES blossom, so it never reaches this roll. The practical
        // effect is that petals now fall only there, and a region that wants them has to
        // say so in its bias -- after checking it has trees.
        final int sandId = WeatherProfile.SANDSTORM.id();
        final int blossomId = WeatherProfile.BLOSSOM.id();

        final int[] w = new int[season.length];
        int total = 0;
        for (int i = 0; i < season.length; i++) {
            double v = season[i];
            if (bias != null) {
                v *= bias[i];
            } else if (i == sandId || i == blossomId) {
                v = 0.0;
            }
            w[i] = (int) Math.round(v * 100.0);   // scaled so a 0.1 bias does not floor to 0
            total += w[i];
        }
        if (total <= 0) {
            return WeatherProfile.CLEAR.id();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) {
                // The profile's own id, not the bare column index. Identical today
                // (the static block above proves ordinal == id) but it keeps working
                // if the ids ever stop being contiguous.
                return WeatherProfile.values()[i].id();
            }
        }
        return WeatherProfile.CLEAR.id();
    }

    private static byte clampSky(byte value) {
        // byId falls back to CLEAR for anything unrecognised, so this cannot leave the
        // sky undefined even if a future wire value arrives from an older or newer peer.
        return WeatherProfile.byId(value).id();
    }

    /** Human-readable, for GM commands and logging. */
    public static String skyName(byte value) {
        return WeatherProfile.byId(value).profileName();
    }

    /** "13:45", for GM commands. */
    public static String clockString() {
        int m = minuteOfDay();
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
