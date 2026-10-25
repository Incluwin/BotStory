package server.living.population;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything under the population content directory, indexed. Immutable once built. */
public final class PopulationContent {
    public record Cohort(String id, ZoneId zone, int share, int peakOnlinePercent, int[] weekday, int[] weekend,
                         int sessionMin, int sessionMax, Map<String, Integer> activities) {
        /** Relative online weight 0..10 for this wall-clock hour in the cohort's zone. */
        public int weight(java.time.ZonedDateTime now) {
            java.time.ZonedDateTime local = now.withZoneSameInstant(zone);
            boolean weekendDay = local.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || local.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            int[] curve = weekendDay ? weekend : weekday;
            return curve[local.getHour()];
        }
    }

    public record LoadoutBand(int maxLevel, String loadout) { }

    public record Career(String id, String job, int popularity, int levelCap, List<LoadoutBand> loadouts) {
        public String loadoutFor(int level) {
            for (LoadoutBand band : loadouts) {
                if (level <= band.maxLevel()) {
                    return band.loadout();
                }
            }
            return loadouts.isEmpty() ? null : loadouts.get(loadouts.size() - 1).loadout();
        }
    }

    public record NamePattern(String pattern, int weight) { }

    public record Names(List<NamePattern> patterns, Map<String, List<String>> lists,
                        List<String> start, List<String> middle, List<String> end) { }

    public record Voice(String id, String style, int share, double lowercase, double typoRate, double abbreviationRate,
                        Map<String, String> abbreviations, List<String> emoticons, double emoticonRate,
                        List<String> petPhrases, double petPhraseRate, int delayMsPerChar, double ignoreRate) { }

    public record GuildNames(List<NamePattern> patterns, Map<String, List<String>> lists, List<String> mottos,
                             Emblems emblems) { }

    /**
     * Bot guild emblems (guild-names.yaml {@code emblems}, read by GuildEmblems): the 16 colours with their sampled
     * background fill and mark ink (0xRRGGBB), the background shapes to choose from, the mark colours for a name with no
     * colour word, and per word (keys lower case) its marks, colours and shape.
     */
    public record Emblems(double minContrast, Map<Integer, EmblemColor> colors, List<Integer> backgrounds,
                          List<String> defaultColors, Map<String, EmblemWord> words) {
        public static Emblems none() {
            return new Emblems(0.0d, Map.of(), List.of(), List.of(), Map.of());
        }

        public EmblemColor color(String name) {
            for (EmblemColor color : colors.values()) {
                if (color.name().equalsIgnoreCase(name)) {
                    return color;
                }
            }
            return null;
        }
    }

    public record EmblemColor(int id, String name, int backgroundRgb, int markRgb) { }

    /** {@code background} 0 = the word picks no shape. */
    public record EmblemWord(List<Integer> marks, List<String> colors, int background) { }

    public record Lobby(String id, int mapId, int levelMin, int levelMax, String name) { }

    /**
     * {@code townWeights} is parallel to {@code towns} (empty = uniform): hub towns draw more of the region's town life.
     * {@code line} (null for none) makes the region a job line's home, Ereve the Cygnus Knights' and Rien the Aran's:
     * while their careers are on only that line's citizens live there, and a new one below its first job lives nowhere
     * else (CitizenLines).
     */
    public record Region(String id, int levelMin, int levelMax, List<Integer> towns, List<Integer> townWeights,
                         List<Lobby> lobbies, int fm, int weight, String line) {
        public Region {
            if (!townWeights.isEmpty() && townWeights.size() != towns.size()) {
                throw new IllegalArgumentException("region " + id + ": townWeights must have one entry per town");
            }
        }

        public Region(String id, int levelMin, int levelMax, List<Integer> towns, List<Integer> townWeights,
                      List<Lobby> lobbies, int fm, int weight) {
            this(id, levelMin, levelMax, towns, townWeights, lobbies, fm, weight, null);
        }

        /** This region with its band starting no lower than {@code level}. */
        public Region from(int level) {
            return level <= levelMin ? this
                    : new Region(id, level, levelMax, towns, townWeights, lobbies, fm, weight, line);
        }

        public boolean fits(int level) {
            return level >= levelMin && level <= levelMax;
        }

        /** Town for a stable seed: weighted by townWeights when present, uniform otherwise. */
        public int townFor(int seed) {
            if (towns.isEmpty()) {
                return 100000000;
            }
            if (townWeights.isEmpty()) {
                return towns.get(Math.floorMod(seed, towns.size()));
            }
            int total = 0;
            for (int weight : townWeights) {
                total += Math.max(0, weight);
            }
            if (total <= 0) {
                return towns.get(Math.floorMod(seed, towns.size()));
            }
            int roll = Math.floorMod(seed, total);
            for (int i = 0; i < towns.size(); i++) {
                roll -= Math.max(0, townWeights.get(i));
                if (roll < 0) {
                    return towns.get(i);
                }
            }
            return towns.get(towns.size() - 1);
        }
    }

    public record Field(int mapId, String name, String street, String region, boolean town, int mobs, int mobLevel, int capacity) { }

    /** A market-basket entry: an item citizens trade and its seed price. */
    public record Good(int itemId, long price) { }

    /** A chatter template group: when it fires, where, on which channel, with what lines; {@code maxLevel} 0 for any level. */
    public record Intent(String id, String trigger, String where, List<Integer> mapIds, String channel, int weight,
                         long cooldownMs, List<String> lines, Map<String, List<String>> styles, String file, int maxLevel) {
        public List<String> linesFor(String style) {
            List<String> styled = style == null ? null : styles.get(style);
            return styled == null || styled.isEmpty() ? lines : styled;
        }
    }

    private final Map<String, Cohort> cohorts;
    private final Map<String, Career> careers;
    private final Names names;
    private final Map<String, Voice> voices;
    private final GuildNames guildNames;
    private final Map<String, Region> regions;
    private final Map<Integer, Field> fields;
    private final List<Intent> intents;
    private final List<Good> basket;
    private final int permitItemId;
    private final List<String> warnings;

    PopulationContent(Map<String, Cohort> cohorts, Map<String, Career> careers, Names names, Map<String, Voice> voices,
                      GuildNames guildNames, Map<String, Region> regions, Map<Integer, Field> fields, List<Intent> intents,
                      List<Good> basket, int permitItemId, List<String> warnings) {
        this.cohorts = Collections.unmodifiableMap(new LinkedHashMap<>(cohorts));
        this.careers = Collections.unmodifiableMap(new LinkedHashMap<>(careers));
        this.names = names;
        this.voices = Collections.unmodifiableMap(new LinkedHashMap<>(voices));
        this.guildNames = guildNames;
        this.regions = Collections.unmodifiableMap(new LinkedHashMap<>(regions));
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.intents = List.copyOf(intents);
        this.basket = List.copyOf(basket);
        this.permitItemId = permitItemId;
        this.warnings = List.copyOf(warnings);
    }

    public static PopulationContent empty() {
        return new PopulationContent(Map.of(), Map.of(), new Names(List.of(), Map.of(), List.of(), List.of(), List.of()),
                Map.of(), new GuildNames(List.of(), Map.of(), List.of(), Emblems.none()),
                Map.of(), Map.of(), List.of(), List.of(), 5140000, List.of());
    }

    public List<Good> basket() {
        return basket;
    }

    public int permitItemId() {
        return permitItemId;
    }

    public Map<String, Cohort> cohorts() {
        return cohorts;
    }

    public Cohort cohort(String id) {
        return id == null ? null : cohorts.get(id);
    }

    public Map<String, Career> careers() {
        return careers;
    }

    public Career career(String id) {
        return id == null ? null : careers.get(id);
    }

    public Names names() {
        return names;
    }

    public Map<String, Voice> voices() {
        return voices;
    }

    public Voice voice(String id) {
        return id == null ? null : voices.get(id);
    }

    public GuildNames guildNames() {
        return guildNames;
    }

    public Map<String, Region> regions() {
        return regions;
    }

    public Region region(String id) {
        return id == null ? null : regions.get(id);
    }

    public Map<Integer, Field> fields() {
        return fields;
    }

    public Field field(int mapId) {
        return fields.get(mapId);
    }

    public List<Intent> intents() {
        return intents;
    }

    public List<Intent> intents(String trigger) {
        List<Intent> out = new ArrayList<>();
        for (Intent intent : intents) {
            if (intent.trigger().equals(trigger)) {
                out.add(intent);
            }
        }
        return out;
    }

    public List<String> warnings() {
        return warnings;
    }

    public String census() {
        int fieldMaps = 0;
        for (Field field : fields.values()) {
            if (!field.town()) {
                fieldMaps++;
            }
        }
        return "cohorts=" + cohorts.size() + " careers=" + careers.size() + " voices=" + voices.size()
                + " regions=" + regions.size() + " fields=" + fieldMaps + " intents=" + intents.size();
    }
}
