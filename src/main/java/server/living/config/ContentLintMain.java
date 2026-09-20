package server.living.config;

import java.nio.file.Path;

/**
 * Command line validator: {@code tools\living-world-lint.bat [content-dir]}. Prints the census
 * and every finding; exit code 1 on parse errors or ERROR findings.
 */
public final class ContentLintMain {
    private ContentLintMain() {
    }

    public static void main(String[] args) {
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
        for (String finding : content.warnings()) {
            System.out.println("  " + finding);
        }
        boolean errors = ContentLint.hasErrors(content.warnings());
        System.out.println(errors ? "FAILED" : "OK (" + content.warnings().size() + " warnings)");
        System.exit(errors ? 1 : 0);
    }
}
