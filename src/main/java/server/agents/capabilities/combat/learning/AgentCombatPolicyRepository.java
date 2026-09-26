package server.agents.capabilities.combat.learning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and writes {@link AgentCombatPolicy} files. The live combat loop asks for a job's policy
 * on every plan, so the file is only re-checked every {@code RECHECK_MS} and the parsed tables
 * are kept alongside it.
 */
public final class AgentCombatPolicyRepository {
    private static final Logger log = LoggerFactory.getLogger(AgentCombatPolicyRepository.class);
    public static final Path DIRECTORY = Path.of("scripts", "config", "agents", "combat-policy");
    private static final long RECHECK_MS = config.AgentTuning.longValue("server.agents.capabilities.combat.learning.AgentCombatPolicyRepository.RECHECK_MS");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final AgentCombatPolicyRepository DEFAULT = new AgentCombatPolicyRepository(DIRECTORY);

    /** A policy file plus one parsed table per scenario. */
    public record Loaded(AgentCombatPolicy policy, Map<String, AgentCombatPolicyTable> tables) {
    }

    private record Cached(long modifiedMs, long checkedAtMs, Loaded loaded) {
    }

    private final Path directory;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public AgentCombatPolicyRepository(Path directory) {
        this.directory = directory;
    }

    public static AgentCombatPolicyRepository defaultRepository() {
        return DEFAULT;
    }

    public Path pathFor(int jobId) {
        return directory.resolve(jobId + ".json");
    }

    public Optional<Loaded> find(int jobId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(jobId);
        if (cached != null && now - cached.checkedAtMs() < RECHECK_MS) {
            return Optional.ofNullable(cached.loaded());
        }
        Path path = pathFor(jobId);
        if (!Files.isRegularFile(path)) {
            cache.put(jobId, new Cached(-1, now, null));
            return Optional.empty();
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (cached != null && cached.modifiedMs() == modified) {
                cache.put(jobId, new Cached(modified, now, cached.loaded()));
                return Optional.ofNullable(cached.loaded());
            }
            AgentCombatPolicy policy = MAPPER.readValue(path.toFile(), AgentCombatPolicy.class);
            Map<String, AgentCombatPolicyTable> tables = new LinkedHashMap<>();
            policy.scenarios().forEach((scenario, learned) ->
                    tables.put(scenario, AgentCombatPolicyTable.of(learned.table())));
            Loaded loaded = new Loaded(policy, tables);
            cache.put(jobId, new Cached(modified, now, loaded));
            return Optional.of(loaded);
        } catch (IOException failure) {
            log.warn("combat policy {} unreadable", path, failure);
            cache.put(jobId, new Cached(-1, now, null));
            return Optional.empty();
        }
    }

    public Path save(AgentCombatPolicy policy) throws IOException {
        Files.createDirectories(directory);
        Path path = pathFor(policy.jobId());
        MAPPER.writeValue(path.toFile(), policy);
        cache.remove(policy.jobId());
        return path;
    }
}
