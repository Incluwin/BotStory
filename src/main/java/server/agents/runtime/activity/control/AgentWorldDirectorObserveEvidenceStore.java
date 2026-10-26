package server.agents.runtime.activity.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * An Agent's Observe sample count, one small file per Agent beside the Director session files. Its own file rather than
 * the session record: the session file already has two writers (the Agent's tick and the Director bridge), so a
 * read-modify-write checkpoint there could undo an operator's mode change. Only the Agent's own tick writes this one.
 */
public final class AgentWorldDirectorObserveEvidenceStore {
    private final Path directory;

    public AgentWorldDirectorObserveEvidenceStore(Path directory) {
        if (directory == null) throw new IllegalArgumentException("evidence directory is required");
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static AgentWorldDirectorObserveEvidenceStore runtimeDefault() {
        return new AgentWorldDirectorObserveEvidenceStore(
                Path.of(".runtime", "agents", "world-director", "observe-samples"));
    }

    /** The stored sample count; 0 when none was stored or the file is unreadable. */
    public long load(int agentId) {
        Path source = path(agentId);
        if (!Files.isRegularFile(source)) return 0L;
        try {
            return Math.max(0L, Long.parseLong(Files.readString(source, StandardCharsets.US_ASCII).trim()));
        } catch (IOException | NumberFormatException unreadable) {
            return 0L;
        }
    }

    public void save(int agentId, long samples) {
        Path target = path(agentId);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(temporary, Long.toString(Math.max(0L, samples)), StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not persist Director Observe evidence", failure);
        }
    }

    private Path path(int agentId) {
        if (agentId <= 0) throw new IllegalArgumentException("positive Agent id is required");
        return directory.resolve(agentId + ".samples");
    }
}
