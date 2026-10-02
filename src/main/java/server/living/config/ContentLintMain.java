package server.living.config;

import config.KnowledgeConfig;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import server.knowledge.KnowledgeLint;
import server.knowledge.SeedData;

import java.nio.file.Path;
import java.util.List;

/**
 * Command line validator: {@code tools\living-world-lint.bat [content-dir]}. Prints the census
 * and every finding; exit code 1 on parse errors or ERROR findings. Ids are checked against the
 * knowledge file and the Liquibase seed rows: always the default {@code scripts/config/knowledge}, whatever config.yaml's
 * {@code knowledge.CONTENT_DIR} and {@code ENABLED} say (it reads no config.yaml), since an id the server WZ lacks is a
 * content error with the feature on or off.
 */
public final class ContentLintMain {
    private ContentLintMain() {
    }

    public static void main(String[] args) {
        Configurator.initialize(new DefaultConfiguration()); // console at ERROR only: never the server's log files
        List<String> notes = KnowledgeLint.installOffline(Path.of(new KnowledgeConfig().CONTENT_DIR), Path.of(SeedData.DEFAULT_ROOT));
        Path root = Path.of(args.length > 0 ? args[0] : "scripts/config/living-world");
        LivingContent content;
        try {
            content = ContentLoader.load(root);
        } catch (ContentException failure) {
            System.out.println("ERROR " + failure.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("Living world content at " + root.toAbsolutePath());
        System.out.println(content.census());
        for (String note : notes) {
            System.out.println("  WARN knowledge: " + note);
        }
        for (String finding : content.warnings()) {
            System.out.println("  " + finding);
        }
        boolean errors = ContentLint.hasErrors(content.warnings());
        System.out.println(errors ? "FAILED" : "OK (" + content.warnings().size() + " warnings)");
        System.exit(errors ? 1 : 0);
    }
}
