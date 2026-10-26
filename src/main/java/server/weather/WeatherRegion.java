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
 * Where in the world a map is, for the purpose of what falls out of its sky.
 *
 * <p>Weather used to be one value for the whole world. It is now rolled per region, so
 * El Nath can be snowing while Henesys is clear. <b>The client needed no change for
 * this</b>: it renders whatever sky byte it is handed, so "regional weather" is entirely
 * a question of which byte each player is sent, and players are told their own map's sky
 * on entry and on every broadcast.
 *
 * <h2>Keyed on the map id prefix</h2>
 *
 * Map ids are structured, and the leading digits are the area. Verified against
 * String.wz/Map.img:
 *
 * <pre>
 *   101  Ellinia          102  Perion           103  Kerning City
 *   104  Lith Harbour     105  Sleepywood       110  Florina Beach
 *   120  Nautilus         140  Rien             200  Orbis
 *   211  El Nath          220  Ludibrium        221  Ludibrium (Eos, Helios)
 *   222  Ludibrium (Omega Sector, Korean Folk Town)
 *   230  Aqua Road        240  Leafre           250  Mu Lung
 *   251  Herb Town        260  Ariant           261  Magatia
 *   680  Amoria           801  Showa
 *
 *   800000000  Mushroom Shrine, ONE map
 * </pre>
 *
 * Mushroom Shrine is the one entry that is an exact id rather than a prefix, because it
 * is a single map rather than an area.
 *
 * The client classifies maps by their background ART bank instead, which is a better key.
 * That table does not exist server side, and porting it here would be a lot of machinery
 * for no gain, so the id prefix is used. Where two prefixes are visually one place, as
 * with Ludibrium's three, they simply share a row.
 */
public enum WeatherRegion {

    /** Permanently snowbound. Nights COLDER than the world default, because snow is blue. */
    EL_NATH(WeatherProfile.SNOW, 0x41508E),
    /** Coldest of all, with a little violet so it does not read the same as El Nath. */
    RIEN(WeatherProfile.SNOW, 0x485294),

    /**
     * Permanently in blossom. ONE map, 800000000, the sakura shrine town.
     *
     * <p>This was originally the whole of Zipangu, which was wrong: it put cherry
     * blossom over Showa Town's neon streets, the Crow Forest cemetery and Ninja Castle.
     * The shrine is the only place in the region the effect suits.
     */
    MUSHROOM_SHRINE(WeatherProfile.BLOSSOM, 0x605492),

    /** Wet. Rain and storms roughly three times as likely as the world at large. */
    ELLINIA(new double[]{1.0, 3.0, 1.0, 1.5, 2.5, 1.0, 1.0, 0.0, 0.0}, 0x3D666C),

    /** Arid. Rain is a rarity here. */
    PERION(new double[]{1.0, 0.1, 0.5, 0.6, 0.1, 0.5, 1.0, 0.0, 0.0}, 0x635582),

    // ------------------------------------------------------------------ the regions
    //
    // Each row is a night colour and, for most, a WEATHER BIAS: nine multipliers indexed
    // by WeatherProfile ordinal, in the order
    //
    //     { clear, rain, snow, overcast, storm, blizzard, leaves, blossom, sandstorm }
    //
    // The bias MULTIPLIES the seasonal table rather than replacing it, so a region can
    // make a sky likelier or rarer but can never put one in a season it does not belong
    // to. Ellinia's tripled rain still yields no snow in July, because the seasonal weight
    // it multiplies is zero.
    //
    // A ZERO is therefore the only way to say "never here, in any season", which is what
    // Ariant's snow and Florina's winter use it for.
    //
    // BLOSSOM (the eighth column) IS OPT IN TOO, and AMORIA is the only row that opts in.
    //
    // Petals should fall where there is something to shed them. Measured rather than
    // assumed: find_blossom_trees.py decodes every object a map actually places and
    // scores its canopy, and the only cherry trees in the game are
    // globalJP/flowerViewing/nature/0..4 -- placed in Mushroom Shrine and nowhere else.
    // Mushroom Shrine forces BLOSSOM outright, so it does not need a weight here.
    //
    // Nine other objects come back predominantly pink and none of them is a tree: three
    // Aquarium windows, two Aqua Road corals, a wedding-town ladder and a Ludibrium toy.
    // So the tree test does NOT justify blossom anywhere except the shrine, and Amoria
    // keeps it as a deliberate exception rather than because it passed -- see its row.
    //
    // SANDSTORM is the ninth column and it is OPT IN: pick() zeroes it for any region
    // with no bias at all, and ARIANT is now the only region that gives it a non-zero
    // weight. Magatia used to carry 2.5. It is a desert town too, so that was defensible,
    // but a sandstorm is the loudest thing the sky does here and having it in two places
    // costs Ariant the one weather event that is its own.

    /**
     * Smoggy, grimy and overcast far more often than it is bright. Rain suits the place;
     * what it really wants is a low grey lid.
     */
    KERNING_CITY(new double[]{0.7, 1.4, 1.0, 2.0, 1.0, 1.0, 0.8, 0.0, 0.0}, 0x6E4A7E),
    /**
     * The same red sunset as Kerning and, deliberately, the same kind of weather: neon
     * reads best wet, so Showa is the rainiest city in the world.
     */
    SHOWA(new double[]{0.7, 1.9, 0.8, 1.7, 1.0, 0.6, 0.8, 0.0, 0.0}, 0x74497A),

    /**
     * A city built in the clouds, so it gets the weather the clouds are making: storms
     * well above anywhere else, and heavy rain behind them.
     *
     * <p>The rain is the point as much as the storms are. A rainbow is raised by a WET
     * sky clearing, so the region that rains most is also the region that sees the most
     * rainbows, without rainbows needing a weight of their own.
     */
    ORBIS(new double[]{0.6, 1.8, 1.2, 1.6, 2.6, 1.0, 0.6, 0.0, 0.0}, 0x6A5090),
    /** A mountain temple in permanent mist: overcast far more than anything else. */
    MU_LUNG(new double[]{0.8, 1.3, 1.0, 2.0, 0.6, 0.8, 1.2, 0.0, 0.0}, 0x6B4E84),
    /**
     * Desert. Drier than Perion, which is merely arid: rain here is close to unheard of
     * and it never, ever snows.
     *
     * <p>The zeroes are absolute. A region bias MULTIPLIES the seasonal weight, so a zero
     * is the one way to say "not here, not in any season" rather than "rarely".
     *
     * <p>The last column is the sandstorm, and this is one of only two regions that has
     * one. It is what fills the gap left by taking the rain away: without it Ariant is
     * simply clear nine days in ten, which is accurate and dull.
     */
    ARIANT(new double[]{1.6, 0.05, 0.0, 0.3, 0.05, 0.0, 0.2, 0.0, 5.0}, 0x6E5078),

    // Sleepywood and Aqua Road take a DARKER night rather than a differently coloured
    // one: a forest floor and the sea bed do not want a hue, they want less light. Both
    // tints are TINT_NEUTRAL scaled toward black, by 0.62 and 0.72. Because the client
    // combines region and profile multiplicatively, a storm in either is darker still.
    /**
     * Deep forest and the caves under it. Still, close air: a lot of grey, very little
     * violence in the sky.
     */
    SLEEPYWOOD(new double[]{0.9, 1.2, 0.8, 1.8, 0.4, 0.5, 1.2, 0.0, 0.0}, 0x2E3857),
    /**
     * NO WEATHER. Aqua Road is under the sea, so there is no sky over it for anything to
     * fall out of.
     *
     * <p>Expressed as a FORCED profile rather than as a bias of all zeroes. A zeroed bias
     * would leave pick() with nothing to choose and fall back to CLEAR by accident;
     * forcing CLEAR says so on purpose and skips the roll entirely. It still gets a night,
     * because the sea does get darker after dark.
     *
     * <p>A GM override still reaches it, deliberately: the override forces a sky
     * everywhere and being underwater is not a reason to make it untestable.
     */
    AQUA_ROAD(WeatherProfile.CLEAR, 0x354165),

    /** A jungle plateau. Wet, stormy, and thick with falling leaves. */
    LEAFRE(new double[]{0.8, 1.7, 0.6, 1.3, 1.6, 0.4, 1.5, 0.0, 0.0}, 0x3A5E58),
    /** A toy world inside a clock tower. Mild and stable: very little happens to it. */
    LUDIBRIUM(new double[]{1.5, 0.7, 1.0, 0.8, 0.3, 0.8, 0.7, 0.0, 0.0}, 0x574C90),
    /**
     * A tropical beach: bright most of the time, with sudden heavy showers. Snow and
     * blizzard are zeroed, because a tropical beach does not have a winter.
     */
    FLORINA(new double[]{1.4, 1.3, 0.0, 0.5, 1.2, 0.0, 0.3, 0.0, 0.0}, 0x40646E),
    /**
     * A town that exists to hold weddings. It is pleasant here, on purpose.
     *
     * <p>THE ONE BLOSSOM EXCEPTION, and knowingly so. Every other region was zeroed on the
     * rule that petals need trees to fall from, and Amoria has none: its only pink object
     * is WeddingGL/town/ladder/0, a ladder. It keeps blossom anyway because the sky here
     * is set dressing for a wedding rather than weather, and it was the town's defining
     * look before the rule existed. If Amoria ever gets cherry trees this stops being an
     * exception and starts being ordinary.
     */
    AMORIA(new double[]{1.6, 0.5, 0.6, 0.4, 0.2, 0.4, 0.6, 2.0, 0.0}, 0x5E5490),
    /** An exposed coastal port. Wet and blowy, and it gets the sea's storms first. */
    LITH_HARBOUR(new double[]{0.7, 1.6, 1.0, 1.6, 1.8, 1.0, 0.7, 0.0, 0.0}, 0x44607E),

    /**
     * The other desert town. Dry like Ariant, but NO sandstorm: that belongs to Ariant
     * alone. Magatia sits at the EDGE of the desert rather than in it, and what it has
     * instead of blown sand is the alchemy, so its smog takes the weight the sand used to
     * carry -- overcast 1.6, up from 0.7.
     *
     * <p>Without that swap the row would be almost nothing but clear sky: rain 0.1, storm
     * 0.1, snow and blizzard a flat zero, so removing the 2.5 sandstorm leaves hardly
     * anything to roll.
     */
    MAGATIA(new double[]{1.4, 0.1, 0.0, 1.6, 0.1, 0.0, 0.3, 0.0, 0.0}, 0x465A85),
    /** A ship. Everything the open sea throws at one. */
    NAUTILUS(new double[]{0.6, 1.8, 0.6, 1.8, 2.2, 0.5, 0.4, 0.0, 0.0}, 0x46587F),

    // ---------------------------------------------------------------- outdoor regions
    //
    // These prefixes were falling through to DEFAULT, which is not wrong so much as
    // characterless: every one of them rolled the plain seasonal table under the plain
    // neutral night. Henesys is the one that mattered most -- 152 maps, the starting town,
    // and the map every one of these effects was developed against.
    //
    // Only OUTDOOR places are listed. The uncovered prefixes are overwhelmingly dungeons,
    // party-quest interiors, jails and event instances (970 alone is 845 maps), and the
    // client will not draw weather in any of them regardless: it decides whether a map has
    // sky from its backmost background canvas, so an interior on DEFAULT renders exactly
    // the same as an interior with a bias. Adding rows for them would be table entries
    // that can never be observed.

    /**
     * Farmland. The gentlest weather in the world: mild, bright, and what it does get is
     * things blowing about rather than things falling hard.
     *
     * <p>Leaves and blossom lead because Henesys is hedgerows, orchards and mushroom
     * fields, and those are the two skies that read as a breeze rather than as bad
     * weather. Storm is held well down -- a farm town in a thunderstorm is Perion's
     * register, not this one.
     */
    HENESYS(new double[]{1.3, 0.9, 0.7, 0.7, 0.4, 0.5, 1.8, 0.0, 0.0}, 0x4C5C88),

    /**
     * An island in the sky over open sea, and the empress' garden. Clear and bright by
     * design, with sea wind: blossom for the garden, and enough rain to earn the green.
     */
    EREVE(new double[]{1.5, 1.1, 0.4, 0.8, 0.6, 0.3, 0.9, 0.0, 0.0}, 0x4E5A96),

    /**
     * Outside time. Overcast almost to the exclusion of everything else, because a place
     * with no clock should not have visible weather either -- but flat CLEAR would read as
     * unfinished, and a permanent still grey reads as deliberate.
     */
    TEMPLE_OF_TIME(new double[]{0.8, 0.4, 0.3, 2.6, 0.2, 0.2, 0.4, 0.0, 0.0}, 0x554A8C),

    /**
     * Primeval jungle, restored. Wetter than Leafre and thicker with falling green: this
     * is the wettest region in the world after Ellinia.
     */
    ELLIN_FOREST(new double[]{0.7, 2.2, 0.4, 1.6, 1.4, 0.2, 1.7, 0.0, 0.0}, 0x3C5E5C),

    /**
     * New Leaf City. Kerning's weather with the smog turned up: it is the same kind of
     * place -- a night city that reads best wet -- and it shares Kerning's sunset.
     */
    NEW_LEAF_CITY(new double[]{0.7, 1.6, 0.9, 1.9, 1.1, 0.7, 0.7, 0.0, 0.0}, 0x6E4A7E),

    /**
     * Formosa. Subtropical: hot, bright, and when it rains it rains hard and briefly.
     * Blossom over the temple districts, and no winter to speak of.
     *
     * <p>See the Formosa buildout -- these are the 742 maps that were imported and
     * scripted, so they are genuinely lived in rather than a stub area.
     */
    FORMOSA(new double[]{1.2, 1.6, 0.1, 0.9, 1.5, 0.0, 0.8, 0.0, 0.0}, 0x445C7E),

    /**
     * Zipangu at large -- Showa's 801 covers the town, this covers the rest: Mushroom
     * Forest, Crow Forest, Ninja Castle. Cool, damp and still.
     *
     * <p>NOT blossom-forced. That is Mushroom Shrine's alone and the reason it is keyed on
     * an exact map id: putting sakura over the Crow Forest cemetery is the mistake that
     * entry exists to record.
     */
    ZIPANGU(new double[]{0.9, 1.4, 0.9, 1.7, 0.7, 0.6, 1.3, 0.0, 0.0}, 0x4A5490),

    /**
     * Everywhere else: the plain seasonal roll, and the reference night colour.
     *
     * <p>The literal rather than TINT_NEUTRAL, because an enum constant cannot forward
     * reference a static field declared below it. The static block asserts the two agree.
     */
    DEFAULT(null, null, 0x4A5A8C);

    /**
     * The night colour of the world at large, and the value every profile's own tint is
     * expressed relative to. Must equal row 0 of client/weather_profiles.inc.
     */
    public static final int TINT_NEUTRAL = 0x4A5A8C;

    /** Non-null when this region ignores the roll entirely. */
    private final WeatherProfile forced;

    /**
     * The night tint over this region, 0xRRGGBB, as a MULTIPLY against the map's own art.
     *
     * <p>The client combines it with the active profile's tint as
     * {@code region * profile / TINT_NEUTRAL}, so the profile keeps deciding how dark and
     * how cold a storm is while the region decides what colour the night is. A region at
     * TINT_NEUTRAL therefore behaves exactly as before this existed.
     *
     * <p><b>A CURRENT CLIENT NEVER USES THESE, so do not tune colour here.</b> The three
     * tint bytes are read by the DLL only when no palette id has arrived, and the palette
     * id is sent on every packet and latched on the client for the session. From packet
     * one the colour comes from {@code client/weather_palettes.inc} instead, which holds
     * its own 27 hand-picked triples. Editing a value here changes nothing anyone sees;
     * the matching row of that .inc is what to edit.
     *
     * <p>They are still sent, and still have to be right, because the field is POSITIONAL:
     * the DLL reads these three bytes BEFORE the palette byte, so removing them on one side
     * alone shifts the palette, elapsedMs and token reads. They are also the fallback a
     * client older than the palette table would use.
     *
     * <p>These are HAND PICKED, not derived. Three attempts to compute them from the art
     * all failed the same way: a hue average cannot see artistic intent. Measuring the
     * backdrop returns "sky blue" for almost every region, because that is what a backdrop
     * mostly is. Measuring the ground tiles instead returns warm for El Nath and Rien,
     * whose rock and road tiles are warm, and would give them warm nights when the whole
     * point of a snow region is that its night is cold. It also returns green for
     * Mushroom Shrine, off its grass, which turns the sakura olive.
     */
    private final int tint;

    /**
     * Per-profile weight multipliers applied on top of the seasonal table, indexed by
     * {@link WeatherProfile} ordinal. Null means no bias.
     */
    private final double[] bias;

    WeatherRegion(WeatherProfile forced, int tint) {
        this.forced = forced;
        this.bias = null;
        this.tint = tint;
    }

    WeatherRegion(double[] bias, int tint) {
        this.forced = null;
        this.bias = bias;
        this.tint = tint;
    }

    WeatherRegion(WeatherProfile forced, double[] bias, int tint) {
        this.forced = forced;
        this.bias = bias;
        this.tint = tint;
    }

    public WeatherProfile forcedProfile() {
        return forced;
    }

    public double[] weightBias() {
        return bias;
    }

    /** This region's night tint, 0xRRGGBB. */
    public int tint() {
        return tint;
    }

    static {
        if (DEFAULT.tint != TINT_NEUTRAL) {
            throw new AssertionError("DEFAULT's tint must be TINT_NEUTRAL: it is the value "
                    + "every other region's tint was chosen against, and the value the "
                    + "client divides the profile tint by.");
        }
        // Same invariant as SEASON_WEIGHTS: a bias row is indexed by profile ordinal, so
        // it has to be exactly as wide as the enum. Java will not check a bare double[].
        int n = WeatherProfile.values().length;
        for (WeatherRegion r : values()) {
            if (r.bias != null && r.bias.length != n) {
                throw new IllegalStateException("WeatherRegion " + r + " bias has "
                        + r.bias.length + " entries, expected " + n);
            }
        }
    }

    /**
     * Which region a map belongs to.
     *
     * <p>Cheap by construction: integer division, no allocation, no lookup. It is called
     * once per player per broadcast and once per map entry.
     */
    /** Mushroom Shrine, the sakura town in Zipangu. String.wz/Map.img, verified. */
    private static final int MUSHROOM_SHRINE_MAP = 800000000;

    public static WeatherRegion forMap(int mapId) {
        if (mapId < 0) {
            return DEFAULT;
        }
        final int area = mapId / 1000000;      // the leading three digits
        switch (area) {
            case 100: return HENESYS;
            case 101: return ELLINIA;
            case 102: return PERION;
            case 103: return KERNING_CITY;
            case 104: return LITH_HARBOUR;
            case 105: return SLEEPYWOOD;
            case 110: return FLORINA;
            case 120: return NAUTILUS;
            case 130: return EREVE;
            case 140: return RIEN;
            case 200: return ORBIS;
            case 211: return EL_NATH;
            case 220: case 221: case 222: return LUDIBRIUM;
            case 230: return AQUA_ROAD;
            case 240: return LEAFRE;
            case 250: case 251: return MU_LUNG;   // Mu Lung and Herb Town
            case 260: return ARIANT;
            case 270: return TEMPLE_OF_TIME;
            case 300: return ELLIN_FOREST;
            case 261: return MAGATIA;
            case 600: case 610: return NEW_LEAF_CITY;   // Masteria
            case 680: return AMORIA;
            case 740: case 741: case 742: return FORMOSA;
            // Mushroom Shrine is an EXACT id, not a prefix: its neighbours (Mushroom
            // Forest, Crow Forest, Hall of Mushroom) are ordinary places that should roll
            // their own weather like anywhere else. It has to be tested INSIDE this case
            // rather than after the switch, because the tail below is only reached by ids
            // that matched no case at all, and 800000000 matches this one.
            //
            // That is the general rule for this method: an exact-id region must be tested
            // within the case for its own area prefix.
            case 800: return (mapId == MUSHROOM_SHRINE_MAP) ? MUSHROOM_SHRINE : ZIPANGU;
            case 801: return SHOWA;
            default:
                break;
        }
        return DEFAULT;
    }
}
