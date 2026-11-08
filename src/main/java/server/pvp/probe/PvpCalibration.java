package server.pvp.probe;

import client.Character;
import server.combat.CombatFormulaProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * V0.5 reference numbers for the PvP damage model (docs/PVP_PLAN.md section 2) from the characters online:
 * {@code H_ref} per level band, class-balanced (each class's median max HP, then their mean, so the citizen class mix
 * does not tilt it), and the median gear base per job path and band (the {@code G} denominator).
 */
public final class PvpCalibration {
    private PvpCalibration() {
    }

    /** One character: base max HP, and its weapon formula's max line (magic: the spell base). */
    public record Sample(int level, int jobId, int maxHp, long gearBase) {
        /** 1 warrior, 2 magician, 3 bowman, 4 thief, 5 pirate; 0 for beginners. Cygnus and Aran fold into these. */
        public int jobClass() {
            return jobId / 100 % 10;
        }
    }

    public record Band(int band, long hRef, Map<Integer, Long> classMedians, int samples) { }

    public static Sample of(Character chr) {
        int jobId = chr.getJob().getId();
        long gearBase = jobId / 100 % 10 == 2
                ? CombatFormulaProvider.getInstance().magicDamageBase(chr.getTotalMagic(), chr.getTotalInt())
                : chr.calculateMaxBaseDamage(chr.getTotalWatk());
        return new Sample(chr.getLevel(), jobId, chr.getMaxHp(), gearBase);
    }

    public static List<Band> referenceHp(List<Sample> samples) {
        Map<Integer, Map<Integer, List<Long>>> byBand = new TreeMap<>();
        for (Sample sample : samples) {
            if (sample.jobClass() < 1 || sample.jobClass() > 5) {
                continue;
            }
            byBand.computeIfAbsent(sample.level() / 10, b -> new TreeMap<>())
                    .computeIfAbsent(sample.jobClass(), c -> new ArrayList<>()).add((long) sample.maxHp());
        }
        List<Band> bands = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, List<Long>>> band : byBand.entrySet()) {
            Map<Integer, Long> medians = new TreeMap<>();
            int count = 0;
            long sum = 0;
            for (Map.Entry<Integer, List<Long>> jobClass : band.getValue().entrySet()) {
                List<Long> hp = jobClass.getValue();
                hp.sort(null);
                long median = GapStats.percentile(hp, 0.5);
                medians.put(jobClass.getKey(), median);
                sum += median;
                count += hp.size();
            }
            bands.add(new Band(band.getKey(), sum / medians.size(), medians, count));
        }
        return bands;
    }

    /** Median gear base per {@code "<jobId / 10>-<level / 10>"}. */
    public static Map<String, Long> gearMedians(List<Sample> samples) {
        Map<String, List<Long>> groups = new TreeMap<>();
        for (Sample sample : samples) {
            if (sample.jobClass() < 1 || sample.jobClass() > 5) {
                continue;
            }
            groups.computeIfAbsent((sample.jobId() / 10) + "-" + (sample.level() / 10), k -> new ArrayList<>())
                    .add(sample.gearBase());
        }
        Map<String, Long> medians = new TreeMap<>();
        groups.forEach((key, bases) -> {
            bases.sort(null);
            medians.put(key, GapStats.percentile(bases, 0.5));
        });
        return medians;
    }

    public static Path write(Path dir, List<Band> bands, Map<String, Long> gear, List<DamageMeter.Row> dps)
            throws IOException {
        StringBuilder yaml = new StringBuilder("# PvP V0.5 calibration, ").append(LocalDateTime.now()).append('\n');
        yaml.append("h_ref:            # level band -> class-balanced median max HP (classes 1-5: median)\n");
        for (Band band : bands) {
            yaml.append("  ").append(band.band() * 10).append(": ").append(band.hRef())
                    .append("   # n=").append(band.samples()).append(' ').append(band.classMedians()).append('\n');
        }
        yaml.append("gear_base:        # \"<jobId/10>-<band>\" -> median max line before skills\n");
        gear.forEach((key, base) -> yaml.append("  \"").append(key).append("\": ").append(base).append('\n'));
        yaml.append("sustained_dps:    # meter: job path, band, H=human/B=bot -> median single-target dps\n");
        for (DamageMeter.Row row : dps) {
            yaml.append("  \"").append(row.jobPath()).append('-').append(row.band()).append('-')
                    .append(row.human() ? 'H' : 'B').append("\": ").append(row.medianDps())
                    .append("   # n=").append(row.characters()).append(" p25=").append(row.p25Dps())
                    .append(" p75=").append(row.p75Dps()).append('\n');
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("reference-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                + ".yaml");
        Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
