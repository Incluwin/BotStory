package server.knowledge;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code aliases.yaml}: player names for entities, NPC roles and the class words of each job branch.
 * Every id is checked against the knowledge file; an entry naming an unknown id is dropped with a warning
 * (AliasCatalogTest keeps the committed file clean), so a stale alias can never answer with a wrong thing.
 */
final class AliasCatalog {
    private static final Logger log = LoggerFactory.getLogger(AliasCatalog.class);
    static final AliasCatalog EMPTY = new AliasCatalog(List.of(), List.of(), List.of());

    /**
     * One aliases.yaml entry. ROLE entries name no ids; the NPCs are found by {@code funcs} (exact String.wz
     * function text), {@code nameHas} (a token of the NPC's name) or {@code sells} (an item its shop sells).
     */
    record Alias(List<String> names, EntityKind kind, IntList ids, String say, int npc, int minLevel, int maxLevel,
                 List<String> funcs, List<String> nameHas, int sells) {
    }

    /** A job branch: the class words players use and the advancement instructors (1st/2nd job, 3rd, 4th). */
    record JobBranch(String branch, List<String> words, int first, int third, int fourth) {
    }

    final List<Alias> aliases;
    final List<JobBranch> jobs;
    /** Entries dropped at load, for the test and {@code !knowledge status}. */
    final List<String> dropped;

    private AliasCatalog(List<Alias> aliases, List<JobBranch> jobs, List<String> dropped) {
        this.aliases = aliases;
        this.jobs = jobs;
        this.dropped = dropped;
    }

    static AliasCatalog load(Path yaml, KnowledgeFile file) throws IOException {
        if (!Files.exists(yaml)) {
            log.warn("[knowledge] {} missing: names resolve from String.wz only", yaml);
            return EMPTY;
        }
        Object document;
        try (Reader reader = Files.newBufferedReader(yaml, StandardCharsets.US_ASCII)) {
            document = new YamlReader(reader).read();
        }
        if (!(document instanceof Map<?, ?> root)) {
            log.warn("[knowledge] {} is not a mapping", yaml);
            return EMPTY;
        }
        List<Alias> aliases = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Object row : list(root.get("aliases"))) {
            if (!(row instanceof Map<?, ?> entry)) {
                continue;
            }
            List<String> names = strings(entry.get("alias"));
            EntityKind kind;
            try {
                kind = EntityKind.valueOf(String.valueOf(entry.get("kind")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                dropped.add(names + ": unknown kind " + entry.get("kind"));
                continue;
            }
            IntList ids = IntList.copyOf(integers(entry.get("ids")));
            int npc = integer(entry.get("npc"));
            String unknown = unknownId(file, kind, ids, npc);
            if (names.isEmpty() || unknown != null || kind != EntityKind.ROLE && ids.isEmpty()) {
                dropped.add(names + ": " + (unknown == null ? "no alias or ids" : unknown));
                continue;
            }
            List<Integer> levels = integers(entry.get("levels"));
            Object say = entry.get("say");
            aliases.add(new Alias(names, kind, ids, say == null ? names.get(0) : say.toString(), npc,
                    levels.isEmpty() ? 0 : levels.get(0), levels.size() < 2 ? 0 : levels.get(1),
                    strings(entry.get("funcs")), strings(entry.get("nameHas")), integer(entry.get("sells"))));
        }
        List<JobBranch> jobs = new ArrayList<>();
        for (Object row : list(root.get("jobs"))) {
            if (row instanceof Map<?, ?> entry) {
                JobBranch job = new JobBranch(String.valueOf(entry.get("branch")), strings(entry.get("words")),
                        integer(entry.get("first")), integer(entry.get("third")), integer(entry.get("fourth")));
                if (file.npcs.get(job.first()) == null || file.npcs.get(job.third()) == null || file.npcs.get(job.fourth()) == null) {
                    dropped.add("job " + job.branch() + ": unknown instructor");
                    continue;
                }
                jobs.add(job);
            }
        }
        for (String drop : dropped) {
            log.warn("[knowledge] aliases.yaml: dropped {}", drop);
        }
        return new AliasCatalog(List.copyOf(aliases), List.copyOf(jobs), List.copyOf(dropped));
    }

    /** Null when every id exists for the entry's kind, else what is missing. */
    private static String unknownId(KnowledgeFile file, EntityKind kind, IntList ids, int npc) {
        for (int id : ids) {
            boolean known = switch (kind) {
                case ITEM -> file.items.get(id) != null;
                case MOB -> file.mobs.get(id) != null;
                case NPC -> file.npcs.get(id) != null;
                case MAP, REGION, PQ -> file.maps.get(id) != null;
                case QUEST -> file.quests.get(id) != null;
                case ROLE -> true;
            };
            if (!known) {
                return "unknown " + kind.name().toLowerCase(Locale.ROOT) + " id " + id;
            }
        }
        return npc != 0 && file.npcs.get(npc) == null ? "unknown npc " + npc : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : value instanceof List<?> list ? list : List.of(value)) {
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> integers(Object value) {
        List<Integer> out = new ArrayList<>();
        for (String text : strings(value)) {
            try {
                out.add(Integer.parseInt(text));
            } catch (NumberFormatException notNumber) {
                out.add(-1);
            }
        }
        return out;
    }

    private static int integer(Object value) {
        List<Integer> values = integers(value);
        return values.isEmpty() ? 0 : values.get(0);
    }
}
