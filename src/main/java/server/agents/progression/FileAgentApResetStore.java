package server.agents.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** One JSON marker per Agent owed (or already given) a one-time AP reset; see AgentApReset. */
public final class FileAgentApResetStore {
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public FileAgentApResetStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static FileAgentApResetStore runtimeDefault() {
        return new FileAgentApResetStore(Path.of(".runtime", "agents", "progression", "ap-resets"));
    }

    public synchronized Optional<AgentApReset> load(int characterId) throws IOException {
        Path path = path(characterId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        AgentApReset reset = mapper.readValue(path.toFile(), AgentApReset.class);
        if (reset.characterId() != characterId) {
            throw new IOException("AP reset identity does not match its file name");
        }
        return Optional.of(reset);
    }

    public synchronized void save(AgentApReset reset) throws IOException {
        Files.createDirectories(directory);
        Path path = path(reset.characterId());
        Path temp = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(temp.toFile(), reset);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path path(int characterId) {
        if (characterId <= 0) {
            throw new IllegalArgumentException("positive character id is required");
        }
        return directory.resolve(characterId + ".json");
    }
}
