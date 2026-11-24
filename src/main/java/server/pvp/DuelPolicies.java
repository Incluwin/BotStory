package server.pvp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What self-play learned per job and whether it may be used (docs/PVP_PLAN.md V2.3, docs/WORLD_SYSTEMS_PLAN.md I1.2). A
 * job learns until it has its target of decisions (DUEL_LEARN_DECISIONS at first), then plays its measuring pairs: each
 * is one bout with the learned table and one with the prior brain by the same two fighters, over the fixed set of
 * matchups (the five classes by three level bands, as many of each as the others allow). Once DUEL_EVAL_PAIRS are in,
 * {@link Verdict} says whether the learned brain beat the prior brain; if not, the job learns on (half as much again)
 * and is measured anew. Only a LEARNED_WON job plays its table in a real duel, and only while DUEL_LEARNING_ENABLED.
 * Kept in {@link #DIRECTORY}, beside the other learned PvP numbers.
 */
public final class DuelPolicies {
    private static final Logger log = LoggerFactory.getLogger(DuelPolicies.class);
    static final Path DIRECTORY = Path.of("logs", "pvp", "duel-policies");
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** The foe classes a verdict must have met at least. */
    static final int MIN_FOE_CLASSES = 3;
    /** One-sided 99 %: a job is judged once per learning phase. */
    static final double Z = 2.33d;

    public enum Verdict { LEARNING, MEASURING, LEARNED_WON, PRIOR_WON }

    /** One measuring pair: the subject's margin with its table and with the prior brain against the same fighter. */
    public record Pair(int foeClass, int band, double learned, double prior) {
    }

    /** One job's file. {@code target}: the decisions it learns to before it is measured. */
    public record Policy(int jobId, long decisions, long target, List<Pair> pairs,
                         Map<String, Map<Integer, AgentCombatPolicyTable.Cell>> table) {
        public Policy {
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
            table = table == null ? Map.of() : new TreeMap<>(table);
        }
    }

    private static final class Entry {
        final int jobId;
        long decisions;
        long target;
        final List<Pair> pairs = new ArrayList<>();
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();

        Entry(int jobId, long target) {
            this.jobId = jobId;
            this.target = target;
        }
    }

    private static volatile DuelPolicies current = new DuelPolicies(DIRECTORY);

    private final Path directory;
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    DuelPolicies(Path directory) {
        this.directory = directory;
    }

    public static DuelPolicies get() {
        return current;
    }

    /** For tests: another store in place of the server's; the one it replaced. */
    static DuelPolicies use(DuelPolicies other) {
        DuelPolicies before = current;
        current = other;
        return before;
    }

    static Verdict verdict(long decisions, long target, List<Pair> pairs, int evalPairs) {
        if (decisions < target) {
            return Verdict.LEARNING;
        }
        Set<Integer> classes = new HashSet<>();
        pairs.forEach(pair -> classes.add(pair.foeClass()));
        if (pairs.size() < Math.max(2, evalPairs) || classes.size() < MIN_FOE_CLASSES) {
            return Verdict.MEASURING;
        }
        double mean = pairs.stream().mapToDouble(p -> p.learned() - p.prior()).average().orElse(0d);
        double squares = pairs.stream().mapToDouble(p -> Math.pow(p.learned() - p.prior() - mean, 2)).sum();
        double error = Math.sqrt(squares / (pairs.size() - 1) / pairs.size());
        return mean - Z * error > 0d ? Verdict.LEARNED_WON : Verdict.PRIOR_WON;
    }

    private static Verdict verdict(Entry entry) {
        return verdict(entry.decisions, entry.target, entry.pairs, YamlConfig.config.pvp.DUEL_EVAL_PAIRS);
    }

    private Entry entry(int jobId) {
        return entries.computeIfAbsent(jobId, id -> {
            Entry entry = new Entry(id, YamlConfig.config.pvp.DUEL_LEARN_DECISIONS);
            Path file = directory.resolve(id + ".json");
            if (Files.isRegularFile(file)) {
                try {
                    Policy read = MAPPER.readValue(file.toFile(), Policy.class);
                    entry.decisions = read.decisions();
                    entry.target = read.target();
                    entry.pairs.addAll(read.pairs());
                    entry.table = AgentCombatPolicyTable.of(read.table());
                } catch (IOException failure) {
                    log.warn("duel policy {} unreadable; the job starts over", file, failure);
                }
            }
            return entry;
        });
    }

    public Verdict verdict(int jobId) {
        Entry entry = entry(jobId);
        synchronized (entry) {
            return verdict(entry);
        }
    }

    /** The seat a fighter of {@code jobId} takes in a real duel: its table where it beat the prior brain; else null. */
    DuelLearner.Seat liveSeat(int jobId) {
        if (!YamlConfig.config.pvp.DUEL_LEARNING_ENABLED || verdict(jobId) != Verdict.LEARNED_WON) {
            return null;
        }
        return new DuelLearner.Seat(DuelLearner.Mode.LIVE, entry(jobId).table, 0d);
    }

    DuelLearner.Seat learningSeat(int jobId) {
        return new DuelLearner.Seat(DuelLearner.Mode.LEARN, entry(jobId).table, YamlConfig.config.pvp.DUEL_LEARN_EPSILON);
    }

    DuelLearner.Seat frozenSeat(int jobId) {
        return new DuelLearner.Seat(DuelLearner.Mode.FROZEN, entry(jobId).table, 0d);
    }

    /** A learning round of {@code jobId} ended after {@code decisions}. */
    public void learned(int jobId, long decisions) {
        Entry entry = entry(jobId);
        synchronized (entry) {
            entry.decisions += decisions;
            save(entry);
        }
    }

    /** The measuring pairs {@code jobId} has against this class and band: the fixed set is filled evenly. */
    public int pairsAgainst(int jobId, int foeClass, int band) {
        Entry entry = entry(jobId);
        synchronized (entry) {
            return (int) entry.pairs.stream().filter(p -> p.foeClass() == foeClass && p.band() == band).count();
        }
    }

    /** A measuring pair of {@code jobId} is in; a job the prior brain beat learns on. */
    public void measured(int jobId, Pair pair) {
        Entry entry = entry(jobId);
        synchronized (entry) {
            entry.pairs.add(pair);
            Verdict verdict = verdict(entry);
            if (verdict == Verdict.PRIOR_WON || verdict == Verdict.LEARNED_WON) {
                log.info("duel policy result: {}", line(entry));
            }
            if (verdict == Verdict.PRIOR_WON) {
                entry.pairs.clear();
                entry.target += Math.max(1, entry.target / 2);
            }
            save(entry);
        }
    }

    /** One line per job with anything learned, for {@code !pvp selfplay status}. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Entry entry : new TreeMap<>(entries).values()) {
            synchronized (entry) {
                lines.add(line(entry));
            }
        }
        return lines;
    }

    private static String line(Entry entry) {
        double diff = entry.pairs.stream().mapToDouble(p -> p.learned() - p.prior()).average().orElse(0d);
        return String.format(Locale.ROOT, "job %d: %s, %d of %d decisions, %d pairs, learned %+.3f over the prior brain",
                entry.jobId, verdict(entry), entry.decisions, entry.target, entry.pairs.size(), diff);
    }

    private void save(Entry entry) {
        try {
            Files.createDirectories(directory);
            MAPPER.writeValue(directory.resolve(entry.jobId + ".json").toFile(),
                    new Policy(entry.jobId, entry.decisions, entry.target, entry.pairs, entry.table.snapshot()));
        } catch (IOException failure) {
            log.warn("duel policy {} not saved", entry.jobId, failure);
        }
    }
}
