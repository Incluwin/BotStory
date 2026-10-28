package server.living.mentor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mentors' words (scripts/config/living-world/mentor.yaml, docs/BOT_CONCEPT_PLAN.md F4): {@code lines} is every
 * key's default, {@code styles} the authored mentor voices (3-5), each overriding some keys. A mentor's voice is drawn
 * once by its newcomer's id ({@link #style}), so the offer window and the mentor born for it speak alike. Templated
 * lines only, no model call; placeholders are filled from the values a caller passes ("{name}" the newcomer,
 * "{me}" the mentor, "{fact}" a ContentAnswers reply, "{branch}", "{town}", "{npc}", "{level}").
 */
public final class MentorLines {
    private static final Logger log = LoggerFactory.getLogger(MentorLines.class);
    public static final String FILE = "mentor.yaml";

    private final Map<String, List<String>> base;
    private final Map<String, Map<String, List<String>>> styles;

    MentorLines(Map<String, List<String>> base, Map<String, Map<String, List<String>>> styles) {
        this.base = base;
        this.styles = styles;
    }

    /** The catalog in {@code contentDir}; empty (no line: the mentor says nothing) when it cannot be read. */
    public static MentorLines load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        Map<String, List<String>> base = new HashMap<>();
        Map<String, Map<String, List<String>>> styles = new TreeMap<>();
        if (!Files.exists(file)) {
            log.warn("{} not found: mentors say nothing", file);
            return new MentorLines(base, styles);
        }
        try {
            Node root = ContentLoader.read(file);
            base.putAll(lines(root.get("lines")));
            Node voices = root.get("styles");
            if (!voices.isMissing()) {
                for (Map.Entry<String, Node> style : voices.entries().entrySet()) {
                    styles.put(style.getKey(), lines(style.getValue()));
                }
            }
        } catch (RuntimeException failure) {
            log.warn("{} could not be read: mentors say nothing", file, failure);
        }
        return new MentorLines(base, styles);
    }

    private static Map<String, List<String>> lines(Node node) {
        Map<String, List<String>> out = new HashMap<>();
        if (node == null || node.isMissing()) {
            return out;
        }
        for (Map.Entry<String, Node> entry : node.entries().entrySet()) {
            out.put(entry.getKey(), List.copyOf(entry.getValue().strings()));
        }
        return out;
    }

    /** The voice for a newcomer: one of the styles, stable for {@code seed}; null when the catalog has none. */
    public String style(int seed) {
        if (styles.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>(styles.keySet());
        return ids.get(Math.floorMod(seed * 31 + 7, ids.size()));
    }

    /** The lines of {@code key} in {@code style} (its own, else the default's); empty for none. */
    public List<String> lines(String style, String key) {
        Map<String, List<String>> own = style == null ? null : styles.get(style);
        List<String> lines = own == null ? null : own.get(key);
        if (lines == null || lines.isEmpty()) {
            lines = base.get(key);
        }
        return lines == null ? List.of() : lines;
    }

    /** One line of {@code key}, picked by {@code seed} and filled from {@code values}; null when there is none. */
    public String line(String style, String key, Map<String, String> values, int seed) {
        List<String> options = lines(style, key);
        if (options.isEmpty()) {
            return null;
        }
        return fill(options.get(Math.floorMod(seed, options.size())), values);
    }

    static String fill(String line, Map<String, String> values) {
        String out = line;
        for (Map.Entry<String, String> value : values.entrySet()) {
            out = out.replace("{" + value.getKey() + "}", value.getValue() == null ? "" : value.getValue());
        }
        return out;
    }

    /** Every key the default lines have (the content lint checks the styles against them). */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(base.keySet());
    }

    /** The style ids. */
    public java.util.Set<String> styleIds() {
        return java.util.Collections.unmodifiableSet(styles.keySet());
    }
}
