package server.agents.capabilities.partyquest.opq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * 2026-10-17: the sealed-room splits this server's OPQ runs have solved, so the search ({@link AgentOpqSealedSearch})
 * checks first the ones Eak draws most: a split's weight is one plus the times it was solved. Kept in
 * .runtime/agents/opq-sealed-splits.json (read once on first use, written atomically after each solve); a file that
 * cannot be read or written is logged and the counts stay in memory, so a run never waits on it.
 */
final class AgentOpqSealedSplitMemory {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqSealedSplitMemory.class);
    private static final Path DEFAULT_FILE = Path.of(".runtime", "agents", "opq-sealed-splits.json");
    private static volatile AgentOpqSealedSplitMemory current = new AgentOpqSealedSplitMemory(DEFAULT_FILE);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> solved = new TreeMap<>();
    private boolean loaded;

    /** Counts kept in {@code file}; null keeps them in memory only. */
    AgentOpqSealedSplitMemory(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    /** The memory the runs use. */
    static AgentOpqSealedSplitMemory current() {
        return current;
    }

    /** Replaces the memory the runs use (tests); null restores the file-backed default. */
    static void install(AgentOpqSealedSplitMemory memory) {
        current = memory != null ? memory : new AgentOpqSealedSplitMemory(DEFAULT_FILE);
    }

    /** One plus the times {@code split} was solved. */
    synchronized long weight(AgentOpqSealedSearch.Split split) {
        load();
        return 1L + solved.getOrDefault(split.key(), 0);
    }

    /** The times {@code split} was solved. */
    synchronized int solvedCount(AgentOpqSealedSearch.Split split) {
        load();
        return solved.getOrDefault(split.key(), 0);
    }

    /** One more solve of {@code split}, written to the file at once. */
    synchronized void recordSolved(AgentOpqSealedSearch.Split split) {
        if (split == null) return;
        load();
        solved.merge(split.key(), 1, Integer::sum);
        save();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Map<String, Integer> read = mapper.readValue(file.toFile(), new TypeReference<Map<String, Integer>>() { });
            read.forEach((key, count) -> {
                if (AgentOpqSealedSearch.Split.parse(key) != null && count != null && count > 0) solved.put(key, count);
            });
        } catch (IOException | RuntimeException failure) {
            log.warn("OPQ sealed splits not read: file={} ({})", file, failure.toString());
        }
    }

    private void save() {
        if (file == null) return;
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(temporary.toFile(), solved);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("OPQ sealed splits not written: file={} ({})", file, failure.toString());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // the next write replaces it
            }
        }
    }
}
