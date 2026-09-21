package server.living.population;

import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.nio.file.Path;

/**
 * Command line validator for population content: {@code tools\population-lint.bat [population-dir] [living-dir]}.
 * Prints the census and every finding; exit code 1 on parse errors or ERROR findings.
 */
public final class PopulationLintMain {
    private PopulationLintMain() {
    }

    public static void main(String[] args) {
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
        boolean errors = false;
        for (String finding : content.warnings()) {
            System.out.println("  " + finding);
            errors |= finding.startsWith("ERROR");
        }
        System.out.println(errors ? "FAILED" : "OK (" + content.warnings().size() + " warnings)");
        System.exit(errors ? 1 : 0);
    }
}
