package server.agents.capabilities.combat.rotation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reads and writes {@link AgentCombatRotationProfile} files; cached per job, re-read when the file changes. */
public final class AgentCombatRotationProfileRepository {
    private static final Logger log = LoggerFactory.getLogger(AgentCombatRotationProfileRepository.class);
    public static final Path DIRECTORY = Path.of("scripts", "config", "agents", "combat-profiles");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final AgentCombatRotationProfileRepository DEFAULT = new AgentCombatRotationProfileRepository(DIRECTORY);

    private record Cached(long modifiedMs, AgentCombatRotationProfile profile) {
    }

    private final Path directory;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public AgentCombatRotationProfileRepository(Path directory) {
        this.directory = directory;
    }

    public static AgentCombatRotationProfileRepository defaultRepository() {
        return DEFAULT;
    }

    public Path pathFor(int jobId) {
        return directory.resolve(jobId + ".json");
    }

    public Optional<AgentCombatRotationProfile> find(int jobId) {
        Path path = pathFor(jobId);
        if (!Files.isRegularFile(path)) {
            cache.remove(jobId);
            return Optional.empty();
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            Cached cached = cache.get(jobId);
            if (cached != null && cached.modifiedMs() == modified) {
                return Optional.of(cached.profile());
            }
            AgentCombatRotationProfile profile = MAPPER.readValue(path.toFile(), AgentCombatRotationProfile.class);
            cache.put(jobId, new Cached(modified, profile));
            return Optional.of(profile);
        } catch (IOException failure) {
            log.warn("combat rotation profile {} unreadable", path, failure);
            return Optional.empty();
        }
    }

    public Path save(AgentCombatRotationProfile profile) throws IOException {
        Files.createDirectories(directory);
        Path path = pathFor(profile.jobId());
        MAPPER.writeValue(path.toFile(), profile);
        cache.remove(profile.jobId());
        return path;
    }

    public void delete(int jobId) throws IOException {
        Files.deleteIfExists(pathFor(jobId));
        cache.remove(jobId);
    }
}
