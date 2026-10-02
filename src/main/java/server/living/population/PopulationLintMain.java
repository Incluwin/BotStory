package server.living.population;

import config.KnowledgeConfig;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import server.knowledge.KnowledgeLint;
import server.knowledge.SeedData;
import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;
import java.util.List;

/**
 * Command line validator for population content: {@code tools\population-lint.bat [population-dir] [living-dir]}.
 * Prints the census and every finding; exit code 1 on parse errors or ERROR findings. Ids are checked against the
 * knowledge file and the Liquibase seed rows: always the default {@code scripts/config/knowledge}, whatever config.yaml's
 * {@code knowledge.CONTENT_DIR} and {@code ENABLED} say (it reads no config.yaml), since an id the server WZ lacks is a
 * content error with the feature on or off.
 */
public final class PopulationLintMain {
    private PopulationLintMain() {
    }

    public static void main(String[] args) {
        Configurator.initialize(new DefaultConfiguration()); // console at ERROR only: never the server's log files
        List<String> notes = KnowledgeLint.installOffline(Path.of(new KnowledgeConfig().CONTENT_DIR), Path.of(SeedData.DEFAULT_ROOT));
        Path root = Path.of(args.length > 0 ? args[0] : "scripts/config/population");
        Path livingRoot = Path.of(args.length > 1 ? args[1] : "scripts/config/living-world");
        PopulationContent content;
        try {
            LivingContent living = ContentLoader.load(livingRoot);
            content = PopulationLoader.load(root, living);
        } catch (ContentException failure) {
            System.out.println("ERROR " + failure.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("Population content at " + root.toAbsolutePath());
        System.out.println(content.census());
        for (String note : notes) {
            System.out.println("  WARN knowledge: " + note);
        }
        boolean errors = false;
        for (String finding : content.warnings()) {
            System.out.println("  " + finding);
            errors |= finding.startsWith("ERROR");
        }
        System.out.println(errors ? "FAILED" : "OK (" + content.warnings().size() + " warnings)");
        System.exit(errors ? 1 : 0);
    }
}
