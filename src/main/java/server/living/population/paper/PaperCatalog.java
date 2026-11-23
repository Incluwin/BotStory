package server.living.population.paper;

import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The paper's words ({@code paper.yaml} in the population content folder): its sections in print order, and for each
 * story key several voices of headline, body and gist (the gist is what a citizen says of it in town). A template's
 * {@code {slot}} takes the story's slot; a voice with a slot the story lacks is passed over. The voice is chosen from
 * the week and the story, so composing the same week twice reads the same.
 */
public final class PaperCatalog {
    public static final String FILE = "paper.yaml";
    static final String DEFAULT_TITLE = "The Town Paper";
    static final String DEFAULT_SENDER = "Paperboy";
    static final int LETTER_MAX = 200;

    public record SectionInfo(String id, String title) {
    }

    record Voices(List<String> headlines, List<String> bodies, List<String> gists) {
    }

    /** One story's words, filled. */
    public record Words(String headline, String body, String gist) {
    }

    private final List<SectionInfo> sections;
    private final Map<String, Voices> stories;
    private final List<String> quiet;
    private final String title;
    private String sender = DEFAULT_SENDER;
    private List<String> letters = List.of();

    PaperCatalog(String title, List<SectionInfo> sections, Map<String, Voices> stories, List<String> quiet) {
        this.title = title;
        this.sections = List.copyOf(sections);
        this.stories = Map.copyOf(stories);
        this.quiet = List.copyOf(quiet);
    }

    /** {@code paper.yaml} in {@code contentDir}; an empty catalog (no sections, nothing printed) when it is missing. */
    public static PaperCatalog load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        List<SectionInfo> sections = new ArrayList<>();
        Map<String, Voices> stories = new LinkedHashMap<>();
        List<String> quiet = new ArrayList<>();
        String title = DEFAULT_TITLE;
        String sender = DEFAULT_SENDER;
        List<String> letters = new ArrayList<>();
        if (Files.exists(file)) {
            Node root = ContentLoader.read(file);
            title = root.get("title").string(DEFAULT_TITLE);
            sender = root.get("sender").string(DEFAULT_SENDER);
            if (root.has("letters")) {
                letters.addAll(root.get("letters").strings());
            }
            for (Node node : root.get("sections").items()) {
                sections.add(new SectionInfo(node.get("id").string(), node.get("title").string()));
            }
            for (Node node : root.get("stories").items()) {
                stories.put(node.get("key").string(), new Voices(node.get("headlines").strings(),
                        node.get("bodies").strings(), node.get("gists").strings()));
            }
            if (root.has("quiet")) {
                quiet.addAll(root.get("quiet").strings());
            }
        }
        PaperCatalog catalog = new PaperCatalog(title, sections, stories, quiet);
        catalog.sender = sender.length() > 13 ? sender.substring(0, 13) : sender;
        catalog.letters = List.copyOf(letters);
        return catalog;
    }

    /** Who the "paper is out" note is from. */
    public String sender() {
        return sender;
    }

    /** The "paper is out" note for {@code issue}, at most {@link #LETTER_MAX} characters. */
    public String letter(PaperIssue issue) {
        List<PaperIssue.Story> top = issue.headlines(1);
        String headline = top.isEmpty() ? "a quiet week" : top.get(0).headline();
        String filled = pick(letters, issue.weekOf(), Map.of("title", title, "headline", headline));
        if (filled == null || filled.length() > LETTER_MAX) {
            filled = title + " is out. Read it at any Bulletin Board.";
        }
        return filled.length() > LETTER_MAX ? filled.substring(0, LETTER_MAX) : filled;
    }

    /** The paper's name on its masthead. */
    public String title() {
        return title;
    }

    public List<SectionInfo> sections() {
        return sections;
    }

    public boolean knows(String key) {
        return stories.containsKey(key);
    }

    Voices voices(String key) {
        return stories.get(key);
    }

    /** The line printed for an issue with nothing in it. */
    public String quiet(int weekOf) {
        return quiet.isEmpty() ? "A quiet week. Nothing to report." : quiet.get(Math.floorMod(weekOf, quiet.size()));
    }

    /** A story's words in the voice the week picks; null when the key is unknown or no voice fits its slots. */
    public Words words(int weekOf, String key, Map<String, String> slots) {
        Voices voices = stories.get(key);
        if (voices == null) {
            return null;
        }
        int seed = (weekOf * 31 + key.hashCode()) * 31 + new TreeMap<>(slots).hashCode();
        String headline = pick(voices.headlines(), seed, slots);
        String body = pick(voices.bodies(), seed, slots);
        String gist = pick(voices.gists(), seed, slots);
        return headline == null || body == null || gist == null ? null : new Words(headline, body, gist);
    }

    /** The voice at the seed, or the next that every slot fills. */
    static String pick(List<String> voices, int seed, Map<String, String> slots) {
        for (int i = 0; i < voices.size(); i++) {
            String filled = fill(voices.get(Math.floorMod(seed + i, voices.size())), slots);
            if (filled != null) {
                return filled;
            }
        }
        return null;
    }

    static String fill(String template, Map<String, String> slots) {
        String out = template;
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            out = out.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        return out.contains("{") ? null : out;
    }
}
