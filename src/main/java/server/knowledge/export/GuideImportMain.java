package server.knowledge.export;

import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import server.knowledge.GuideImport;
import server.knowledge.SeedData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Imports the distilled guide claims of the local corpus (tools/guide-corpus) into
 * scripts/config/knowledge/guide-knowledge.json, checking each against the server's data; writes a report of the drops.
 * Usage: GuideImportMain corpus-dir [knowledge-dir] [report.md], or GuideImportMain --resolve knowledge-dir kind:name...
 */
public final class GuideImportMain {
    private GuideImportMain() {
    }

    public static void main(String[] args) throws IOException {
        Configurator.initialize(new DefaultConfiguration()); // console at ERROR only: never the server's file appenders
        if (args.length < 1) {
            System.err.println("usage: GuideImportMain corpus-dir [knowledge-dir] [report.md]");
            System.exit(2);
        }
        if (args[0].equals("--resolve")) { // --resolve knowledge-dir kind:name...
            System.out.print(GuideImport.probe(Path.of(args[1]), Path.of(SeedData.DEFAULT_ROOT), List.of(args).subList(2, args.length)));
            return;
        }
        Path knowledgeDir = Path.of(args.length > 1 ? args[1] : "scripts/config/knowledge");
        Path report = Path.of(args.length > 2 ? args[2] : "tmp/guide-import-report.md");
        System.out.println(GuideImport.importCorpus(Path.of(args[0]), knowledgeDir, Path.of(SeedData.DEFAULT_ROOT), Path.of("wz"), report));
        System.out.println("report: " + report.toAbsolutePath());
    }
}
