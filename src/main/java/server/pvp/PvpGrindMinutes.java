package server.pvp;

import constants.game.ExpTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The grind-minute (GMin) table, the unit PvP rewards are counted in (docs/PVP_PLAN.md section 2 "Rewards"): what a
 * citizen of a level earns per field-minute, in EXP and in mesos. Read from {@link #FILE} at first use; a missing file
 * leaves every value 0, so a reward counted in GMin pays nothing rather than a guess.
 */
public final class PvpGrindMinutes {
    public static final Path FILE = Path.of("scripts", "config", "pvp", "grind-minutes.yaml");

    private static volatile PvpGrindMinutes current;

    private final TreeMap<Integer, Long> expByBand;
    private final TreeMap<Integer, Long> mobMesoByBand;
    private final double mesoMultiplier;
    private final double mesoFloorPerLevelHour;

    PvpGrindMinutes(Map<Integer, Long> expByBand, Map<Integer, Long> mobMesoByBand, double mesoMultiplier,
                    double mesoFloorPerLevelHour) {
        this.expByBand = new TreeMap<>(expByBand);
        this.mobMesoByBand = new TreeMap<>(mobMesoByBand);
        this.mesoMultiplier = mesoMultiplier;
        this.mesoFloorPerLevelHour = mesoFloorPerLevelHour;
    }

    public static PvpGrindMinutes get() {
        PvpGrindMinutes loaded = current;
        if (loaded == null) {
            loaded = load(FILE);
            current = loaded;
        }
        return loaded;
    }

    static PvpGrindMinutes load(Path file) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException missing) {
            return parse(List.of());
        }
    }

    /** The same small reader as {@link PvpReference#parse}: top-level section names, then indented "key: value". */
    static PvpGrindMinutes parse(List<String> lines) {
        Map<Integer, Long> exp = new TreeMap<>();
        Map<Integer, Long> meso = new TreeMap<>();
        double multiplier = 0d;
        double floor = 0d;
        String section = "";
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            if (line.isBlank()) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                section = line.trim().replace(":", "");
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            try {
                switch (section) {
                    case "exp_per_minute" -> exp.put(Integer.parseInt(key) / 10, Long.parseLong(value));
                    case "mob_meso_per_hour" -> meso.put(Integer.parseInt(key) / 10, Long.parseLong(value));
                    case "meso_rule" -> {
                        if (key.equals("measured_multiplier")) {
                            multiplier = Double.parseDouble(value);
                        } else if (key.equals("floor_per_level_hour")) {
                            floor = Double.parseDouble(value);
                        }
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // a hand-edited line that is not a number is skipped
            }
        }
        return new PvpGrindMinutes(exp, meso, multiplier, floor);
    }

    /** One EXP grind-minute at {@code level}. */
    public double expPerMinute(int level) {
        if (expByBand.isEmpty()) {
            return 0d;
        }
        int band = level / 10;
        int lowest = expByBand.firstKey();
        if (band < lowest) {
            int first = lowest * 10;
            return expByBand.get(lowest) * ExpTable.getExpNeededForLevel(Math.max(1, level))
                    / (double) Math.max(1, ExpTable.getExpNeededForLevel(first));
        }
        return expByBand.floorEntry(band).getValue();
    }

    /** One meso grind-minute at {@code level}: max(multiplier x measured, floor x level) an hour, over 60. */
    public double mesoPerMinute(int level) {
        var measured = mobMesoByBand.isEmpty() ? null : mobMesoByBand.floorEntry(Math.max(level / 10,
                mobMesoByBand.firstKey()));
        double perHour = Math.max(measured == null ? 0d : mesoMultiplier * measured.getValue(),
                mesoFloorPerLevelHour * Math.max(1, level));
        return perHour / 60d;
    }
}
