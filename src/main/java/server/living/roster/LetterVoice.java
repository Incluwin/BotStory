package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.social.personality.DialogueStyleProfileRepository;
import server.living.config.Content.Persona;
import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The words of the letters (docs/BOT_CONCEPT_PLAN.md F3): {@code letters.yaml} in the content directory, per part of a
 * letter its lines and each dialogue style's own. A part a style leaves out uses the common lines; a part with none is
 * left out of the letter. Templated like the chatter catalogs: nothing here calls a model.
 */
final class LetterVoice {
    private static final Logger log = LoggerFactory.getLogger(LetterVoice.class);
    static final String FILE = "letters.yaml";
    /** The common lines' key among a part's styles. */
    private static final String COMMON = "";

    /** part -> style (COMMON for the lines every style shares) -> lines. */
    private final Map<String, Map<String, List<String>>> parts;

    LetterVoice(Map<String, Map<String, List<String>>> parts) {
        this.parts = parts;
    }

    /** The catalog in {@code file}; none (every part empty) when it is missing or does not read. */
    static LetterVoice load(Path file) {
        Map<String, Map<String, List<String>>> parts = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            log.warn("letters: no {}, the letters have no words", file);
            return new LetterVoice(parts);
        }
        try {
            for (Map.Entry<String, Node> part : ContentLoader.read(file).get("parts").entries().entrySet()) {
                Map<String, List<String>> styles = new HashMap<>();
                styles.put(COMMON, part.getValue().get("lines").strings());
                part.getValue().get("styles").entries().forEach((style, lines) -> styles.put(style, lines.strings()));
                parts.put(part.getKey(), styles);
            }
        } catch (ContentException failure) {
            log.warn("letters: {} does not read, the letters have no words", file, failure);
            parts.clear();
        }
        return new LetterVoice(parts);
    }

    /** One of {@code part}'s lines in {@code style}'s words, or its common ones; null when the part has none. */
    String line(String part, String style, Random random) {
        Map<String, List<String>> styles = parts.get(part);
        if (styles == null) {
            return null;
        }
        List<String> lines = styles.getOrDefault(style, List.of());
        if (lines.isEmpty()) {
            lines = styles.getOrDefault(COMMON, List.of());
        }
        return lines.isEmpty() ? null : lines.get(random.nextInt(lines.size()));
    }

    /** The dialogue style a persona writes in: its own, else the one its personality resolves to. */
    static String styleOf(Persona persona) {
        if (persona.dialogueStyle() != null && !persona.dialogueStyle().isBlank()) {
            return persona.dialogueStyle();
        }
        return DialogueStyleProfileRepository.defaultRepository().resolve(persona.personality()).styleId();
    }
}
