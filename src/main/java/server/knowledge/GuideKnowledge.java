package server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What players know from the guides (plan docs/GUIDE_KNOWLEDGE_PLAN.md section 8): the claims {@link GuideImport} kept,
 * indexed by entity and by topic, and the training spots guides recommend by class and level band. Immutable; loaded
 * with each new {@link WorldKnowledge} (its composite refs resolve against that snapshot's {@link NameIndex}).
 */
public final class GuideKnowledge {
    public static final String ANY_JOB = "any";
    /** The distiller's class keys (tools/guide-corpus/claims-schema.json). */
    static final Set<String> JOB_KEYS = Set.of("beginner", "warrior", "fighter-line", "page-line", "spearman-line", "magician",
            "fp-line", "il-line", "cleric-line", "bowman", "hunter-line", "crossbow-line", "thief", "assassin-line",
            "bandit-line", "pirate", "brawler-line", "gunslinger-line", "dawn-warrior", "blaze-wizard", "wind-archer",
            "night-walker", "thunder-breaker", "aran");
    static final GuideKnowledge EMPTY = new GuideKnowledge(List.of(), List.of(), Map.of(), 0);

    private static volatile GuideKnowledge current = EMPTY;

    public record Source(String url, String title, String author, String date) {
    }

    /** A claim; {@code refs} are entity keys ({@link Entity#key()}). */
    public record Claim(String topic, List<String> jobs, int min, int max, long[] refs, List<String> skills, String text,
                        String say, String strength, int[] sources) {
        /** For a character of this job and level: its class (or any) and, when the claim gives levels, within them. */
        public boolean fits(int jobId, int level) {
            return (jobs.isEmpty() || jobs.stream().anyMatch(job -> matches(job, jobId)))
                    && (level <= 0 || (min == 0 || level >= min) && (max == 0 || level <= max));
        }
    }

    private final List<Claim> claims;
    private final List<Source> sources;
    private final Map<Long, int[]> byRef;
    /** Level band -> the spots guides give for it. */
    private final Map<Integer, List<Spot>> spots;
    private final int droppedAtLoad;

    /** A training map guides recommend for a class key (or {@link #ANY_JOB}) in a level band, and which guides do. */
    record Spot(int map, String job, int[] sources) {
    }

    private GuideKnowledge(List<Claim> claims, List<Source> sources, Map<Integer, List<Spot>> spots, int droppedAtLoad) {
        this.claims = claims;
        this.sources = sources;
        this.spots = spots;
        this.droppedAtLoad = droppedAtLoad;
        Map<Long, List<Integer>> refs = new HashMap<>();
        for (int i = 0; i < claims.size(); i++) {
            for (long ref : claims.get(i).refs) {
                refs.computeIfAbsent(ref, key -> new ArrayList<>()).add(i);
            }
        }
        Map<Long, int[]> index = new HashMap<>();
        refs.forEach((ref, list) -> index.put(ref, list.stream().mapToInt(Integer::intValue).toArray()));
        this.byRef = index;
    }

    public static GuideKnowledge current() {
        return current;
    }

    static void publish(GuideKnowledge next) {
        current = next;
    }

    /** The guide file resolved against {@code k}; EMPTY when the file is missing. Claims whose refs no longer resolve are dropped. */
    static GuideKnowledge load(Path file, WorldKnowledge k) throws IOException {
        if (!Files.exists(file)) {
            return EMPTY;
        }
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        List<Source> sources = new ArrayList<>();
        for (JsonNode s : root.path("sources")) {
            sources.add(new Source(s.path("url").asText(), s.path("title").asText(), s.path("author").asText(), s.path("date").asText()));
        }
        List<Claim> claims = new ArrayList<>();
        int dropped = 0;
        for (JsonNode c : root.path("claims")) {
            long[] refs = new long[c.path("refs").size()];
            boolean ok = true;
            for (int i = 0; i < refs.length && ok; i++) {
                Optional<Entity> entity = entity(k, c.path("refs").get(i).asText());
                ok = entity.isPresent();
                refs[i] = entity.map(Entity::key).orElse(0L);
            }
            if (!ok) {
                dropped++;
                continue;
            }
            claims.add(new Claim(c.path("topic").asText(), strings(c.path("jobs")), c.path("min").asInt(), c.path("max").asInt(),
                    refs, strings(c.path("skills")), c.path("text").asText(), c.path("say").asText(),
                    c.path("strength").asText(), ints(c.path("src"))));
        }
        return new GuideKnowledge(List.copyOf(claims), List.copyOf(sources), spots(k, claims), dropped);
    }

    /**
     * The training spots, by level band, from the training claims: each claim's maps (its maps, regions' maps and monsters'
     * spawn maps that suit its levels here) for each of its classes, over its levels (no levels given: the map's own).
     */
    private static Map<Integer, List<Spot>> spots(WorldKnowledge k, List<Claim> claims) {
        Map<Integer, List<Spot>> spots = new HashMap<>();
        for (Claim claim : claims) {
            if (!claim.topic.equals("training_spot")) {
                continue;
            }
            Set<Integer> maps = new LinkedHashSet<>();
            for (long ref : claim.refs) {
                k.names().entity(EntityKind.values()[(int) (ref >>> 32)], (int) ref).map(e -> spotMaps(k, e, claim.min, claim.max))
                        .ifPresent(maps::addAll);
            }
            for (int map : maps) {
                int low = claim.min;
                int high = claim.max;
                if (low == 0 && high == 0) {
                    low = high = k.map(map).map(MapFact::mobLevel).orElse(0);
                } else if (high == 0) {
                    high = low + GuideImport.BAND - 1;
                } else if (low == 0) {
                    low = Math.max(1, high - GuideImport.BAND + 1);
                }
                for (String job : claim.jobs.isEmpty() ? List.of(ANY_JOB) : claim.jobs) {
                    Spot spot = new Spot(map, job, claim.sources);
                    for (int band = low / GuideImport.BAND * GuideImport.BAND; high > 0 && band <= high; band += GuideImport.BAND) {
                        spots.computeIfAbsent(band, at -> new ArrayList<>()).add(spot);
                    }
                }
            }
        }
        return spots;
    }

    /**
     * The maps a training-spot entity stands for (a map, a region's maps, a monster's spawn maps) whose monsters suit
     * levels {@code min..max} here (0: unbounded); empty when none do, null when the entity is no place or monster.
     * The import keeps a training claim only when this is not empty.
     */
    static List<Integer> spotMaps(WorldKnowledge k, Entity e, int min, int max) {
        List<Integer> maps = new ArrayList<>();
        switch (e.kind()) {
            case MAP -> maps.add(e.id());
            case REGION -> maps.addAll(e.ids());
            case MOB -> maps.addAll(k.spawnMaps(e.id()));
            default -> {
                return null;
            }
        }
        List<Integer> fit = new ArrayList<>();
        for (int map : maps) {
            int level = e.kind() == EntityKind.MOB ? k.mob(e.id()).map(MobFact::level).orElse(0)
                    : k.map(map).map(MapFact::mobLevel).orElse(0);
            if (level > 0 && (min == 0 || level >= min - GuideImport.SPOT_UNDER) && (max == 0 || level <= max + GuideImport.SPOT_OVER)
                    && !fit.contains(map)) {
                fit.add(map);
            }
        }
        return fit;
    }

    /** "MAP:100000000" or "PQ:Kerning City@103000000" (composites by name and first id: their ids shift with aliases.yaml). */
    static Optional<Entity> entity(WorldKnowledge k, String ref) {
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        EntityKind kind;
        try {
            kind = EntityKind.valueOf(ref.substring(0, colon));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        String value = ref.substring(colon + 1);
        return switch (kind) {
            case REGION, PQ, ROLE -> {
                int at = value.lastIndexOf('@');
                try {
                    yield at < 0 ? Optional.empty() : k.names().composite(kind, value.substring(0, at), Integer.parseInt(value.substring(at + 1)));
                } catch (NumberFormatException bad) {
                    yield Optional.empty();
                }
            }
            default -> {
                try {
                    yield k.names().entity(kind, Integer.parseInt(value));
                } catch (NumberFormatException bad) {
                    yield Optional.empty();
                }
            }
        };
    }

    private static int[] ints(JsonNode array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asInt();
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return List.copyOf(out);
    }

    // ---- queries ------------------------------------------------------------------------------------------------

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    public int size() {
        return claims.size();
    }

    public int droppedAtLoad() {
        return droppedAtLoad;
    }

    /** The first thread that gave this claim. */
    public Source source(Claim claim) {
        return claim.sources.length > 0 && claim.sources[0] < sources.size() ? sources.get(claim.sources[0]) : null;
    }

    /**
     * The maps guides recommend training on at this level, with how many guides do: the class's own spots plus the spots
     * given for any class, in the level's band. A job id of -1 (class unknown) counts the any-class spots only.
     */
    public Map<Integer, Integer> spotsFor(int jobId, int level) {
        List<Spot> band = spots.get(level / GuideImport.BAND * GuideImport.BAND);
        if (band == null) {
            return Map.of();
        }
        Map<Integer, Set<Integer>> guides = new HashMap<>();
        for (Spot spot : band) {
            if (spot.job.equals(ANY_JOB) || jobId >= 0 && matches(spot.job, jobId)) {
                Set<Integer> of = guides.computeIfAbsent(spot.map, map -> new HashSet<>());
                for (int source : spot.sources) {
                    of.add(source);
                }
            }
        }
        Map<Integer, Integer> out = new HashMap<>();
        guides.forEach((map, of) -> out.put(map, of.size()));
        return out;
    }

    /** Claims about an entity that fit the job and level (level 0: any), the most specific first. */
    public List<Claim> about(long entityKey, int jobId, int level, Set<String> topics) {
        int[] hits = byRef.get(entityKey);
        if (hits == null) {
            return List.of();
        }
        List<Claim> out = new ArrayList<>();
        for (int i : hits) {
            Claim claim = claims.get(i);
            if ((topics.isEmpty() || topics.contains(claim.topic)) && claim.fits(jobId, level)) {
                out.add(claim);
            }
        }
        out.sort(SPECIFIC_FIRST);
        return out;
    }

    /** Claims of these topics with no entity required, for a job and level (builds). */
    public List<Claim> forTopics(Set<String> topics, int jobId, int level) {
        List<Claim> out = new ArrayList<>();
        for (Claim claim : claims) {
            if (topics.contains(claim.topic) && !claim.jobs.isEmpty() && claim.fits(jobId, level)) {
                out.add(claim);
            }
        }
        out.sort(SPECIFIC_FIRST);
        return out;
    }

    /**
     * Consensus before opinion, advice more guides give before advice from one, a class-specific claim before a general
     * one, a narrow level range before a wide one.
     */
    private static final Comparator<Claim> SPECIFIC_FIRST = Comparator
            .comparingInt((Claim c) -> c.strength.equals("consensus") ? 0 : c.strength.equals("author_opinion") ? 1 : 2)
            .thenComparingInt(c -> -c.sources.length)
            .thenComparingInt(c -> c.jobs.isEmpty() ? 1 : 0)
            .thenComparingInt(c -> c.max == 0 || c.min == 0 ? 200 : c.max - c.min);

    /**
     * Whether a class key covers a job id. A branch key covers the branch; a line key covers its line and the branch's
     * first job (an undecided level-25 warrior trains like a fighter or a page).
     */
    static boolean matches(String key, int jobId) {
        int branch = jobId / 100;
        int line = jobId / 10 % 10;
        return switch (key) {
            case "beginner" -> jobId == 0 || jobId == 1000 || jobId == 2000;
            case "warrior" -> branch == 1;
            case "magician" -> branch == 2;
            case "bowman" -> branch == 3;
            case "thief" -> branch == 4;
            case "pirate" -> branch == 5;
            case "fighter-line" -> branch == 1 && (line == 1 || jobId == 100);
            case "page-line" -> branch == 1 && (line == 2 || jobId == 100);
            case "spearman-line" -> branch == 1 && (line == 3 || jobId == 100);
            case "fp-line" -> branch == 2 && (line == 1 || jobId == 200);
            case "il-line" -> branch == 2 && (line == 2 || jobId == 200);
            case "cleric-line" -> branch == 2 && (line == 3 || jobId == 200);
            case "hunter-line" -> branch == 3 && (line == 1 || jobId == 300);
            case "crossbow-line" -> branch == 3 && (line == 2 || jobId == 300);
            case "assassin-line" -> branch == 4 && (line == 1 || jobId == 400);
            case "bandit-line" -> branch == 4 && (line == 2 || jobId == 400);
            case "brawler-line" -> branch == 5 && (line == 1 || jobId == 500);
            case "gunslinger-line" -> branch == 5 && (line == 2 || jobId == 500);
            case "dawn-warrior" -> branch == 11;
            case "blaze-wizard" -> branch == 12;
            case "wind-archer" -> branch == 13;
            case "night-walker" -> branch == 14;
            case "thunder-breaker" -> branch == 15;
            case "aran" -> branch == 21 || jobId == 2000;
            default -> false;
        };
    }

    /** One line for {@code !knowledge status}. */
    public String status() {
        return claims.isEmpty() ? "guides: none" : "guides: " + claims.size() + " claims from " + sources.size() + " threads, "
                + spots.values().stream().mapToInt(List::size).sum() + " training spots" + (droppedAtLoad > 0 ? ", " + droppedAtLoad + " no longer resolve" : "");
    }
}
