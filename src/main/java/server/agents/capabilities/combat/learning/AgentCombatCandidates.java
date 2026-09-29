package server.agents.capabilities.combat.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * What Agents learned where no player could see them (AgentCombatLearnGuard): one candidate table per job, which all
 * its Agents learn into. A situation's key already names its scenario (its target count and boss mark, see
 * AgentCombatLearningContext), so one table keeps the learning going as a fight moves between one target and a pack;
 * it is written split by scenario, as the arena's policy files are. Candidates are kept under
 * {@code combat-policy/candidates/}, a directory the live policy
 * ({@link AgentCombatPolicyRepository#defaultRepository()}, {@code combat-policy/<jobId>.json}) never reads: a
 * candidate reaches live play only when the combat arena's {@code policy=candidate} check finds it not worse than what
 * live play has and writes it into the job's policy file as adopted. Every scenario written here is marked not adopted.
 *
 * <p>The learner only ever asks for a table in memory ({@link #table}); the disk is read ({@link #loadAll}) and written
 * ({@link #flush}) by the host off the Agent tick.</p>
 */
public final class AgentCombatCandidates {
    private static final Logger log = LoggerFactory.getLogger(AgentCombatCandidates.class);
    public static final Path DIRECTORY = AgentCombatPolicyRepository.DIRECTORY.resolve("candidates");
    private static final AgentCombatCandidates DEFAULT = new AgentCombatCandidates(DIRECTORY);

    private final Path directory;
    private final AgentCombatPolicyRepository repository;
    /** job id -> the table its Agents learn into. */
    private final Map<Integer, AgentCombatPolicyTable> tables = new ConcurrentHashMap<>();
    /** job id -> what was last read or written for it, so an unchanged job is not written again. */
    private final Map<Integer, Map<String, Map<Integer, AgentCombatPolicyTable.Cell>>> written = new ConcurrentHashMap<>();

    AgentCombatCandidates(Path directory) {
        this.directory = directory;
        this.repository = new AgentCombatPolicyRepository(directory);
    }

    public static AgentCombatCandidates defaultCandidates() {
        return DEFAULT;
    }

    /** The table Agents of {@code jobId} learn into: in memory only, created empty the first time. */
    AgentCombatPolicyTable table(int jobId) {
        return tables.computeIfAbsent(jobId, ignored -> new AgentCombatPolicyTable());
    }

    /** The arena scenario a situation key belongs to ({@link AgentCombatPolicy#scenarioFor}). */
    static String scenarioOf(String context) {
        return AgentCombatPolicy.scenarioFor(context.contains("|boss"), context.startsWith("1 target") ? 1 : 2);
    }

    /**
     * Reads every candidate file; a job already learning in memory keeps its table. Off the Agent tick (the host calls
     * it at boot). Returns the jobs read.
     */
    public int loadAll() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        List<Integer> jobs;
        try (Stream<Path> files = Files.list(directory)) {
            jobs = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("\\d+\\.json"))
                    .map(name -> Integer.parseInt(name.substring(0, name.length() - ".json".length())))
                    .toList();
        } catch (IOException failure) {
            log.warn("combat candidates in {} unreadable", directory, failure);
            return 0;
        }
        int read = 0;
        for (int jobId : jobs) {
            Optional<AgentCombatPolicyRepository.Loaded> loaded = repository.find(jobId);
            if (loaded.isEmpty() || tables.containsKey(jobId)) {
                continue;
            }
            Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows = new TreeMap<>();
            loaded.get().policy().scenarios().values().forEach(scenario -> rows.putAll(scenario.table()));
            if (tables.putIfAbsent(jobId, AgentCombatPolicyTable.of(rows)) == null) {
                written.put(jobId, rows);
                read++;
            }
        }
        return read;
    }

    /**
     * Writes each job whose table changed since it was last read or written, split by scenario and every scenario
     * marked not adopted. Off the Agent tick. Returns the files written.
     */
    public int flush() {
        int saved = 0;
        for (Map.Entry<Integer, AgentCombatPolicyTable> job : tables.entrySet()) {
            Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> now = job.getValue().snapshot();
            if (now.isEmpty() || now.equals(written.get(job.getKey()))) {
                continue;
            }
            Map<String, AgentCombatPolicy.Scenario> scenarios = new TreeMap<>();
            for (String scenario : List.of("single", "multi", "boss")) {
                Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows = rowsOf(now, scenario);
                if (!rows.isEmpty()) {
                    scenarios.put(scenario, new AgentCombatPolicy.Scenario(false, 0, 0, visits(rows), rows));
                }
            }
            try {
                repository.save(new AgentCombatPolicy(job.getKey(), jobName(job.getKey()), 0, "bench",
                        Instant.now().toString(), AgentCombatLearner.gammaPerSecond(), false, Map.of(), scenarios));
                written.put(job.getKey(), now);
                saved++;
            } catch (IOException failure) {
                log.warn("combat candidate for job {} not saved", job.getKey(), failure);
            }
        }
        return saved;
    }

    /**
     * A copy of the candidate's situations of {@code scenario} for {@code jobId}, to check in the arena without touching
     * what is still being learned: from the table in memory, else the file; empty when nothing was learned there.
     */
    public Optional<AgentCombatPolicyTable> copy(int jobId, String scenario) {
        AgentCombatPolicyTable table = tables.get(jobId);
        Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows;
        if (table != null) {
            rows = rowsOf(table.snapshot(), scenario);
        } else {
            rows = repository.find(jobId).map(loaded -> loaded.policy().scenarios().get(scenario))
                    .map(AgentCombatPolicy.Scenario::table).orElse(Map.of());
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(AgentCombatPolicyTable.of(rows));
    }

    /** Visits recorded over a table's cells (priors alone count none). */
    public static long visits(Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows) {
        long visits = 0;
        for (Map<Integer, AgentCombatPolicyTable.Cell> row : rows.values()) {
            for (AgentCombatPolicyTable.Cell cell : row.values()) {
                visits += cell.visits();
            }
        }
        return visits;
    }

    private static Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rowsOf(
            Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> rows, String scenario) {
        Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> out = new TreeMap<>();
        rows.forEach((context, row) -> {
            if (scenarioOf(context).equals(scenario)) {
                out.put(context, row);
            }
        });
        return out;
    }

    private static String jobName(int jobId) {
        client.Job job = client.Job.getById(jobId);
        return job == null ? Integer.toString(jobId) : job.name();
    }
}
