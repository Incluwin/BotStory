package server.living.population;

import server.living.population.PopulationContent.NamePattern;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Era-flavoured name generator driven by names.yaml / guild-names.yaml patterns: {@code {Slot}}
 * draws from a list, {@code {Syl2}}/{@code {Syl3}} build a pronounceable made-up name,
 * {@code {digits}} appends 1-3 digits. Pure and seedable so the same seed yields the same names.
 */
public final class NameGenerator {
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9]+)}");

    private final List<NamePattern> patterns;
    private final Map<String, List<String>> lists;
    private final List<String> start;
    private final List<String> middle;
    private final List<String> end;
    private final int totalWeight;

    public NameGenerator(List<NamePattern> patterns, Map<String, List<String>> lists,
                         List<String> start, List<String> middle, List<String> end) {
        this.patterns = patterns;
        this.lists = lists;
        this.start = start;
        this.middle = middle;
        this.end = end;
        int total = 0;
        for (NamePattern pattern : patterns) {
            total += Math.max(0, pattern.weight());
        }
        this.totalWeight = total;
    }

    public String next(Random random) {
        if (patterns.isEmpty() || totalWeight <= 0) {
            return syllables(random, 2);
        }
        int roll = random.nextInt(totalWeight);
        String pattern = patterns.get(patterns.size() - 1).pattern();
        for (NamePattern candidate : patterns) {
            roll -= Math.max(0, candidate.weight());
            if (roll < 0) {
                pattern = candidate.pattern();
                break;
            }
        }
        Matcher m = SLOT.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String slot = m.group(1);
            String value;
            switch (slot) {
                case "Syl2" -> value = syllables(random, 2);
                case "Syl3" -> value = syllables(random, 3);
                case "syl2" -> value = syllables(random, 2).toLowerCase(Locale.ROOT);
                case "digits" -> value = String.valueOf(1 + random.nextInt(999));
                default -> {
                    List<String> list = lists.get(slot);
                    value = list == null || list.isEmpty() ? "" : list.get(random.nextInt(list.size()));
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String syllables(Random random, int count) {
        if (start.isEmpty() || end.isEmpty()) {
            return "Maple" + (1 + random.nextInt(99));
        }
        StringBuilder name = new StringBuilder(start.get(random.nextInt(start.size())));
        for (int i = 0; i < count - 2 && !middle.isEmpty(); i++) {
            name.append(middle.get(random.nextInt(middle.size())));
        }
        name.append(end.get(random.nextInt(end.size())));
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
