package server.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The fingerprint does not see line endings: the live checkout keeps some WZ files with CRLF working copies
 * (git normalises them to LF, the content is the same), which must not read as "changed since export".
 */
class KnowledgeSourcesTest {
    @TempDir
    Path dir;

    @Test
    void lfBytesIgnoreCarriageReturns() throws IOException {
        Path lf = Files.writeString(dir.resolve("a.img.xml"), "<imgdir name=\"1\">\n  <int/>\n</imgdir>\n", StandardCharsets.US_ASCII);
        Path crlf = Files.writeString(dir.resolve("b.img.xml"), "<imgdir name=\"1\">\r\n  <int/>\r\n</imgdir>\r\n", StandardCharsets.US_ASCII);

        assertNotEquals(Files.size(lf), Files.size(crlf));
        assertEquals(KnowledgeSources.lfBytes(List.of(lf)), KnowledgeSources.lfBytes(List.of(crlf)));
        assertEquals(Files.size(lf), KnowledgeSources.lfBytes(List.of(lf)));
    }
}
