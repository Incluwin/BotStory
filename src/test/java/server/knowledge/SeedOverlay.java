package server.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * A realistic fixture overlay: the drop, global-drop and shop rows a fresh DB holds after Liquibase, from the
 * Liquibase seed replay (SeedData). No DB is touched.
 */
final class SeedOverlay {
    private static KnowledgeOverlay overlay;
    private static WorldKnowledge knowledge;

    private SeedOverlay() {
    }

    static synchronized KnowledgeOverlay overlay() {
        if (overlay == null) {
            try {
                overlay = read();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return overlay;
    }

    /** The real knowledge file + authored files + this overlay, built once per test JVM. */
    static synchronized WorldKnowledge knowledge() {
        if (knowledge == null) {
            knowledge = KnowledgeFixtures.with(overlay());
        }
        return knowledge;
    }

    private static KnowledgeOverlay read() throws IOException {
        return SeedData.load(Path.of(SeedData.DEFAULT_ROOT));
    }
}
