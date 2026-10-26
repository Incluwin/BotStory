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

/**
 * A named sky. Replaces the three independent scalars the first cut used.
 *
 * <p>The old model was a night level, a cloud alpha and a rain alpha, plus a snow toggle.
 * That cannot express a COHERENT sky: a storm is not just rain, it is rain plus heavy
 * cloud plus a colder tint plus a slower fade, and there was nowhere to say so. Every
 * later feature is a table keyed on a profile, so this had to come first.
 *
 * <p><b>Only the ID and the name live here.</b> The server has no opinion about what a
 * storm LOOKS like: the render parameters (tint, cloud and rain alpha, native particle
 * effect, fade rate, sound) are the client's, in
 * {@code client/weather_profiles.inc}. What the two ends must agree on is this
 * numbering, and nothing but this comment enforces that.
 *
 * <p>0, 1 and 2 are deliberately the old {@code SKY_CLEAR}, {@code SKY_RAIN} and
 * {@code SKY_SNOW} values, so the wire format did not change and no saved state or
 * in-flight packet was invalidated by the refactor.
 */
public enum WeatherProfile {
    /** Nothing falling, no tint of its own. */
    CLEAR(0, "clear"),
    /** Rain sheets plus cloud. */
    RAIN(1, "rain"),
    /** The engine's own snow, plus cloud. */
    SNOW(2, "snow"),
    /** Heavy cloud and a flat grey darkening, but nothing falling. */
    OVERCAST(3, "overcast"),
    /** Rain, full cloud, a colder and much darker tint, and a slower roll-in. */
    STORM(4, "storm"),
    /** Snow at full cloud with a cold bright tint. */
    BLIZZARD(5, "blizzard"),
    /** Autumn. Native "Sprinkled Maple Leaves". */
    LEAVES(6, "leaves"),
    /** Spring. Native "Sprinkled Flowers". */
    BLOSSOM(7, "blossom"),
    /**
     * Desert. Blown sand, a dust choked sky and poor visibility.
     *
     * <p>Appended, never inserted. The id IS the wire value and it indexes both
     * SEASON_WEIGHTS and every region bias row, so putting this anywhere but the end
     * would silently renumber every sky above it.
     */
    SANDSTORM(8, "sandstorm");

    private final byte id;
    private final String name;

    WeatherProfile(int id, String name) {
        this.id = (byte) id;
        this.name = name;
    }

    public byte id() {
        return id;
    }

    public String profileName() {
        return name;
    }

    private static final WeatherProfile[] BY_ID = buildById();

    private static WeatherProfile[] buildById() {
        int max = 0;
        for (WeatherProfile p : values()) {
            max = Math.max(max, p.id);
        }
        WeatherProfile[] out = new WeatherProfile[max + 1];
        for (WeatherProfile p : values()) {
            out[p.id] = p;
        }
        return out;
    }

    /** CLEAR for anything unrecognised, so a bad id can never leave the sky undefined. */
    public static WeatherProfile byId(byte id) {
        int i = id & 0xFF;
        if (i >= BY_ID.length || BY_ID[i] == null) {
            return CLEAR;
        }
        return BY_ID[i];
    }

    /** Match a GM's word, or null. */
    public static WeatherProfile byName(String s) {
        for (WeatherProfile p : values()) {
            if (p.name.equalsIgnoreCase(s)) {
                return p;
            }
        }
        return null;
    }

    /** Every profile name, for the usage line. */
    public static String namesForUsage() {
        StringBuilder sb = new StringBuilder();
        for (WeatherProfile p : values()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p.name);
        }
        return sb.toString();
    }
}
