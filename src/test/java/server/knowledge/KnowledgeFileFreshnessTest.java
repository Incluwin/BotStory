package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.export.KnowledgeExportMain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The committed server-knowledge.json says what the WZ and scripts say: a full in-memory re-export (about 5 s of
 * streaming XML) must match it, ignoring {@code generatedAt} and the {@code sources} fingerprint. The fingerprint
 * changes on any byte of any input (a one-line NPC script edit, a CRLF checkout) and only feeds the boot WARN, so
 * this fails only when what bots would know changed. A failure means: run tools\knowledge-export.bat.
 */
class KnowledgeFileFreshnessTest {
    private static final Path FILE = KnowledgeFixtures.DIR.resolve(KnowledgeSources.FILE_NAME);
    private static final Pattern GENERATED_AT = Pattern.compile("\"generatedAt\": \"([^\"]*)\"");
    private static final Pattern SOURCES = Pattern.compile("\"sources\": \\[.*?\\],\n", Pattern.DOTALL);

    @Test
    void reExportMatchesTheCommittedFile() throws IOException {
        String committed = Files.readString(FILE, StandardCharsets.US_ASCII);
        Matcher generatedAt = GENERATED_AT.matcher(committed);
        assertTrue(generatedAt.find(), "committed file has generatedAt");
        long started = System.nanoTime();
        String exported = KnowledgeExportMain.export(Path.of("."), generatedAt.group(1));
        System.out.printf("re-export: %d bytes in %d ms%n", exported.length(), (System.nanoTime() - started) / 1_000_000);
        String want = withoutSources(committed);
        String got = withoutSources(exported);
        if (!got.equals(want)) {
            String[] wantLines = want.split("\n", -1);
            String[] gotLines = got.split("\n", -1);
            for (int i = 0; i < Math.min(wantLines.length, gotLines.length); i++) {
                if (!wantLines[i].equals(gotLines[i])) {
                    fail("line " + (i + 1) + " differs, run tools\\knowledge-export.bat\n committed: " + clip(wantLines[i])
                            + "\n exported:  " + clip(gotLines[i]));
                }
            }
            fail("line count differs (" + wantLines.length + " vs " + gotLines.length + "), run tools\\knowledge-export.bat");
        }
    }

    private static String withoutSources(String json) {
        Matcher sources = SOURCES.matcher(json);
        assertTrue(sources.find(), "file has a sources block");
        return sources.replaceFirst("");
    }

    private static String clip(String line) {
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}
