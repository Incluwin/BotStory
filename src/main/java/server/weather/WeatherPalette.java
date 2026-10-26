/*
    This file is part of a Maple Story Server and is redistributed under the
    licence below
*/
package server.weather;

/**
 * Identifies a regional sky palette without putting any colours on the server.
 *
 * <p>The client owns the dusk and night RGB curves in
 * {@code client/weather_palettes.inc}; this enum is only the stable byte that says
 * which curve applies over a map. Keep entries appended and in the same order as that
 * table. A new palette is therefore a client art change plus one name here, never a
 * server-side colour balance change.
 */
public enum WeatherPalette {
    EL_NATH,
    RIEN,
    MUSHROOM_SHRINE,
    ELLINIA,
    PERION,
    KERNING_CITY,
    SHOWA,
    ORBIS,
    MU_LUNG,
    ARIANT,
    SLEEPYWOOD,
    AQUA_ROAD,
    LEAFRE,
    LUDIBRIUM,
    FLORINA,
    AMORIA,
    LITH_HARBOUR,
    MAGATIA,
    NAUTILUS,
    HENESYS,
    EREVE,
    TEMPLE_OF_TIME,
    ELLIN_FOREST,
    NEW_LEAF_CITY,
    FORMOSA,
    ZIPANGU,
    DEFAULT;

    public byte id() {
        return (byte) ordinal();
    }

    /** Every weather region has a like-named palette; fail fast if that contract drifts. */
    public static WeatherPalette forRegion(WeatherRegion region) {
        if (region == null) {
            return DEFAULT;
        }
        return valueOf(region.name());
    }

    static {
        for (WeatherRegion region : WeatherRegion.values()) {
            try {
                valueOf(region.name());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("WeatherRegion " + region
                        + " has no WeatherPalette", e);
            }
        }
    }
}
