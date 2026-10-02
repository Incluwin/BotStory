package server.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The committed knowledge file, travel.yaml, aliases.yaml and population/fields.yaml, read once per test JVM
 * (tests run from the server directory).
 */
final class KnowledgeFixtures {
    static final Path DIR = Path.of("scripts/config/knowledge");
    static final Path FIELDS = Path.of("scripts/config/population/fields.yaml");

    private static KnowledgeFile file;
    private static List<TravelLeg> travel;
    private static AliasCatalog aliases;
    private static Map<Integer, Integer> fields;

    private KnowledgeFixtures() {
    }

    static synchronized KnowledgeFile file() {
        if (file == null) {
            try {
                file = KnowledgeFile.read(DIR.resolve(KnowledgeSources.FILE_NAME));
                travel = TravelCatalog.load(DIR.resolve("travel.yaml"), file);
                aliases = AliasCatalog.load(DIR.resolve("aliases.yaml"), file);
                fields = KnowledgeLoader.readFields(FIELDS);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return file;
    }

    static synchronized List<TravelLeg> travel() {
        file();
        return travel;
    }

    static synchronized AliasCatalog aliases() {
        file();
        return aliases;
    }

    static synchronized Map<Integer, Integer> fields() {
        file();
        return fields;
    }

    /** The real file with only the given overlay rows. */
    static WorldKnowledge with(KnowledgeOverlay overlay) {
        return WorldKnowledge.build(file(), travel(), aliases(), fields(), overlay, 1);
    }
}
