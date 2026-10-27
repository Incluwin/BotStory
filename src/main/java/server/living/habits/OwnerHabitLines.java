package server.living.habits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The owner-habit catalog (scripts/config/living-world/owner-habits.yaml, F2.4): what a companion says, once, when it
 * has learned one of its owner's habits (potion, loot, sell, camp, skill: templated lines, no model call), the name of
 * each loot category in those lines, and the categories never learned as a skip (scrolls, books, quest items).
 */
public final class OwnerHabitLines {
    private static final Logger log = LoggerFactory.getLogger(OwnerHabitLines.class);
    public static final String FILE = "owner-habits.yaml";

    private final Map<String, List<String>> lines;
    private final Map<Integer, String> categories;
    private final Set<Integer> neverSkip;

    OwnerHabitLines(Map<String, List<String>> lines, Map<Integer, String> categories, Set<Integer> neverSkip) {
        this.lines = lines;
        this.categories = categories;
        this.neverSkip = neverSkip;
    }

    /** The catalog in {@code contentDir}; empty (no lines, nothing named, nothing never skipped) when it cannot be read. */
    public static OwnerHabitLines load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        Map<String, List<String>> lines = new HashMap<>();
        Map<Integer, String> categories = new HashMap<>();
        Set<Integer> neverSkip = new HashSet<>();
        if (!Files.exists(file)) {
            log.warn("{} not found: companions mention no habit", file);
            return new OwnerHabitLines(lines, categories, neverSkip);
        }
        try {
            Node root = ContentLoader.read(file);
            for (Map.Entry<String, Node> entry : root.get("lines").entries().entrySet()) {
                lines.put(entry.getKey(), List.copyOf(entry.getValue().strings()));
            }
            for (Map.Entry<String, Node> entry : root.get("categories").entries().entrySet()) {
                categories.put(Integer.parseInt(entry.getKey().trim()), entry.getValue().string());
            }
            neverSkip.addAll(root.get("never_skip").integers());
        } catch (RuntimeException failure) {
            log.warn("{} could not be read: companions mention no habit", file, failure);
        }
        return new OwnerHabitLines(lines, categories, neverSkip);
    }

    /** A line for {@code habit} with each "{key}" filled from {@code values}; null when the catalog has none. */
    public String line(String habit, Map<String, String> values, Random random) {
        List<String> options = lines.get(habit);
        if (options == null || options.isEmpty()) {
            return null;
        }
        String line = options.get(random.nextInt(options.size()));
        for (Map.Entry<String, String> value : values.entrySet()) {
            line = line.replace("{" + value.getKey() + "}", value.getValue());
        }
        return line;
    }

    /** The category's name in a line ("arrows"), or null when the catalog names none. */
    public String category(int category) {
        return categories.get(category);
    }

    /** True for a category never learned as a skip, whatever the owner does. */
    public boolean neverSkips(int category) {
        return neverSkip.contains(category);
    }
}
