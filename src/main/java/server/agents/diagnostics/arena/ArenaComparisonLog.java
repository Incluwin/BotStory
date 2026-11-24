package server.agents.diagnostics.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Every reading of an arena comparison ({@code policy=candidate adopt=off}) as one JSON line, so
 * {@code tools/combat-policy/compare_policies.py} can pool the runs of a job and judge them. Never read by the server.
 */
public final class ArenaComparisonLog {
    private static final Logger log = LoggerFactory.getLogger(ArenaComparisonLog.class);
    public static final Path FILE = Path.of("logs", "arena-comparisons.jsonl");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One scenario of one run: {@code candidateDps} and {@code liveDps} are paired by index. */
    public record Line(String at, int jobId, String job, int level, String agent, String scenario, int seconds,
                       long decisions, boolean capped, int deaths, List<Double> candidateDps, List<Double> liveDps,
                       String weapon) {
    }

    private ArenaComparisonLog() {
    }

    static void append(Line line) {
        append(FILE, line);
    }

    static synchronized void append(Path file, Line line) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writeValueAsString(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            log.warn("arena comparison of job {} not written", line.jobId(), failure);
        }
    }
}
