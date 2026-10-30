package server.living.crew;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The crew banter catalog (scripts/config/living-world/banter.yaml, docs/BOT_CONCEPT_PLAN.md F6.4): the tie seeds, the
 * mute answers and the exchanges, each a call and an answer in one theme. Templated lines only, no model call. The
 * lint ({@link #lint}) keeps every number a slot: a line with a digit in it is an error, so rival banter can only ever
 * quote a real record.
 */
public final class BanterCatalog {
    private static final Logger log = LoggerFactory.getLogger(BanterCatalog.class);
    public static final String FILE = "banter.yaml";
    static final String MUTE_OK = "mute.ok";
    static final String UNMUTE_OK = "unmute.ok";
    private static final Pattern SLOT = Pattern.compile("\\{([a-z_]+)}");
    private static final Set<String> NAME_SLOTS = Set.of("caller", "answerer", "owner");
    private static final Set<String> CLEAR_SLOTS = Set.of("quest", "time");
    private static final Set<String> NUMBER_SLOTS = Set.of("lead", "trail", "gap");

    public enum Moment {
        TOWN, WALK, CLEAR;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A rival number the exchange quotes, and the slots it fills. */
    public enum Stat {
        LEVEL(Set.of("lead", "trail", "gap")),
        RECORD(Set.of("act", "lead", "trail")),
        DEATHS(Set.of("act", "lead", "trail", "gap")),
        BEST_TIME(Set.of("act", "lead", "trail")),
        DAMAGE(Set.of("act", "lead", "trail"));

        final Set<String> slots;

        Stat(Set<String> slots) {
            this.slots = slots;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Stat fromKey(String key) {
            for (Stat stat : values()) {
                if (stat.key().equals(key)) {
                    return stat;
                }
            }
            return null;
        }
    }

    /** One exchange: who may say it (ties from the caller's side, or a fixed caller/answerer pair) and when. */
    public record Exchange(String id, Set<TieKind> ties, List<String> pair, Set<Moment> moments, Stat stat,
                           int minAffinity, int maxAffinity, int weight, List<String> call, List<String> answer,
                           Map<String, List<String>> styleCalls, Map<String, List<String>> styleAnswers) {
        public boolean paired() {
            return pair.size() == 2;
        }

        /** The call lines in {@code style} (a hire's voice) where it has its own, else the plain ones. */
        List<String> calls(String style) {
            List<String> own = style == null ? null : styleCalls.get(style);
            return own == null || own.isEmpty() ? call : own;
        }

        List<String> answers(String style) {
            List<String> own = style == null ? null : styleAnswers.get(style);
            return own == null || own.isEmpty() ? answer : own;
        }
    }

    private final Map<TieKind, Integer> seeds;
    private final Map<String, List<String>> lines;
    private final List<Exchange> exchanges;
    /** Problems found while reading (unknown keys and the like): the lint reports them as errors. */
    private final List<String> problems;

    BanterCatalog(Map<TieKind, Integer> seeds, Map<String, List<String>> lines, List<Exchange> exchanges,
                  List<String> problems) {
        this.seeds = Map.copyOf(seeds);
        this.lines = Map.copyOf(lines);
        this.exchanges = List.copyOf(exchanges);
        this.problems = List.copyOf(problems);
    }

    /** The default seeds when the catalog gives none. */
    static Map<TieKind, Integer> defaultSeeds() {
        Map<TieKind, Integer> seeds = new EnumMap<>(TieKind.class);
        seeds.put(TieKind.SPOUSE, 60);
        seeds.put(TieKind.FRIEND, 40);
        for (TieKind kind : List.of(TieKind.RIVAL, TieKind.OWES, TieKind.OWED, TieKind.BLAMES, TieKind.BLAMED)) {
            seeds.put(kind, 30);
        }
        seeds.put(TieKind.CREWMATE, 0);
        return seeds;
    }

    /** The catalog in {@code contentDir}; empty (no exchange: the crew says nothing) when it cannot be read. */
    public static BanterCatalog load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        if (!Files.exists(file)) {
            log.warn("{} not found: crews trade no banter", file);
            return new BanterCatalog(defaultSeeds(), Map.of(), List.of(), List.of());
        }
        try {
            return parse(ContentLoader.read(file));
        } catch (RuntimeException failure) {
            log.warn("{} could not be read: crews trade no banter", file, failure);
            return new BanterCatalog(defaultSeeds(), Map.of(), List.of(), List.of("cannot read: " + failure.getMessage()));
        }
    }

    static BanterCatalog parse(Node root) {
        List<String> problems = new ArrayList<>();
        Map<TieKind, Integer> seeds = defaultSeeds();
        Node seedNode = root.get("ties").get("seeds");
        if (!seedNode.isMissing()) {
            for (Map.Entry<String, Node> seed : seedNode.entries().entrySet()) {
                TieKind kind = TieKind.fromKey(seed.getKey());
                int value = seed.getValue().integer(-1);
                if (kind == null || value < 0 || value > CrewTies.MAX_AFFINITY) {
                    problems.add("ties.seeds." + seed.getKey() + ": not a tie with a value 0-100");
                } else {
                    seeds.put(kind, value);
                }
            }
        }
        Map<String, List<String>> lines = new HashMap<>();
        lines.put(MUTE_OK, List.copyOf(root.get("mute").get("ok").strings()));
        lines.put(UNMUTE_OK, List.copyOf(root.get("mute").get("unmute").strings()));
        List<Exchange> exchanges = new ArrayList<>();
        for (Node node : root.get("exchanges").items()) {
            String id = node.get("id").string("");
            Set<TieKind> ties = EnumSet.noneOf(TieKind.class);
            for (String key : node.get("ties").strings()) {
                TieKind kind = TieKind.fromKey(key);
                if (kind == null) {
                    problems.add("exchange " + id + ": unknown tie '" + key + "'");
                } else {
                    ties.add(kind);
                }
            }
            Set<Moment> moments = EnumSet.noneOf(Moment.class);
            for (String key : node.get("moments").strings()) {
                Moment moment = moment(key);
                if (moment == null) {
                    problems.add("exchange " + id + ": unknown moment '" + key + "'");
                } else {
                    moments.add(moment);
                }
            }
            String statKey = node.get("stat").string(null);
            Stat stat = statKey == null ? null : Stat.fromKey(statKey);
            if (statKey != null && stat == null) {
                problems.add("exchange " + id + ": unknown stat '" + statKey + "'");
            }
            Map<String, List<String>> styleCalls = new LinkedHashMap<>();
            Map<String, List<String>> styleAnswers = new LinkedHashMap<>();
            Node styles = node.get("styles");
            if (!styles.isMissing()) {
                for (Map.Entry<String, Node> style : styles.entries().entrySet()) {
                    styleCalls.put(style.getKey(), List.copyOf(style.getValue().get("call").strings()));
                    styleAnswers.put(style.getKey(), List.copyOf(style.getValue().get("answer").strings()));
                }
            }
            exchanges.add(new Exchange(id, ties, List.copyOf(node.get("pair").strings()), moments, stat,
                    node.get("minAffinity").integer(0), node.get("maxAffinity").integer(CrewTies.MAX_AFFINITY),
                    node.get("weight").integer(10), List.copyOf(node.get("call").strings()),
                    List.copyOf(node.get("answer").strings()), styleCalls, styleAnswers));
        }
        return new BanterCatalog(seeds, lines, exchanges, problems);
    }

    private static Moment moment(String key) {
        for (Moment moment : Moment.values()) {
            if (moment.key().equals(key)) {
                return moment;
            }
        }
        return null;
    }

    public Map<TieKind, Integer> seeds() {
        return seeds;
    }

    public List<Exchange> exchanges() {
        return exchanges;
    }

    /** The lines of a single-line key ({@link #MUTE_OK}, {@link #UNMUTE_OK}); empty for none. */
    List<String> lines(String key) {
        List<String> found = lines.get(key);
        return found == null ? List.of() : found;
    }

    /**
     * What is wrong with the catalog against {@code personas} (every persona, recruitable or not), as "ERROR ..." and
     * "WARN ..." lines: unknown keys, an exchange with no call or answer, a digit in any line, a slot its exchange does
     * not fill, a stat exchange whose calls quote no number, an unknown or unrecruitable pair; and, as warnings, a
     * relationship kind between two recruitables that {@link TieKind#of} reads as FRIEND by default only.
     */
    public List<String> lint(Collection<Persona> personas) {
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            out.add("ERROR " + FILE + ": " + problem);
        }
        lintLines(out, "mute.ok", lines(MUTE_OK), Set.of("owner"));
        lintLines(out, "mute.unmute", lines(UNMUTE_OK), Set.of("owner"));
        Map<String, Persona> byId = new HashMap<>();
        for (Persona persona : personas) {
            byId.put(persona.id(), persona);
        }
        Set<String> ids = new HashSet<>();
        for (Exchange exchange : exchanges) {
            String where = "exchange " + (exchange.id().isEmpty() ? "(no id)" : exchange.id());
            if (exchange.id().isEmpty() || !ids.add(exchange.id())) {
                out.add("ERROR " + FILE + " " + where + ": missing or repeated id");
            }
            if (exchange.moments().isEmpty()) {
                out.add("ERROR " + FILE + " " + where + ": no moments");
            }
            if (exchange.call().isEmpty() || exchange.answer().isEmpty()) {
                out.add("ERROR " + FILE + " " + where + ": needs call and answer lines");
            }
            if (exchange.paired()) {
                for (String personaId : exchange.pair()) {
                    Persona persona = byId.get(personaId);
                    if (persona == null || !persona.recruitable()) {
                        out.add("ERROR " + FILE + " " + where + ": pair persona '" + personaId + "' is no recruitable");
                    }
                }
            } else if (!exchange.pair().isEmpty()) {
                out.add("ERROR " + FILE + " " + where + ": a pair names exactly two personas");
            } else if (exchange.ties().isEmpty()) {
                out.add("ERROR " + FILE + " " + where + ": needs ties or a pair");
            }
            if (exchange.minAffinity() < 0 || exchange.maxAffinity() > CrewTies.MAX_AFFINITY
                    || exchange.minAffinity() > exchange.maxAffinity() || exchange.weight() < 1) {
                out.add("ERROR " + FILE + " " + where + ": affinity range within 0..100 and weight >= 1");
            }
            Set<String> allowed = new HashSet<>(NAME_SLOTS);
            if (exchange.moments().equals(EnumSet.of(Moment.CLEAR))) {
                allowed.addAll(CLEAR_SLOTS);
            }
            if (exchange.stat() != null) {
                allowed.addAll(exchange.stat().slots);
            }
            List<String> calls = new ArrayList<>(exchange.call());
            exchange.styleCalls().values().forEach(calls::addAll);
            List<String> answers = new ArrayList<>(exchange.answer());
            exchange.styleAnswers().values().forEach(answers::addAll);
            lintLines(out, where + " call", calls, allowed);
            lintLines(out, where + " answer", answers, allowed);
            if (exchange.stat() != null) {
                for (String line : calls) {
                    if (slots(line).stream().noneMatch(NUMBER_SLOTS::contains)) {
                        out.add("ERROR " + FILE + " " + where + ": a " + exchange.stat().key()
                                + " call quotes no number: \"" + line + "\"");
                    }
                }
            }
        }
        Set<String> unnamed = new TreeSet<>();
        for (Persona persona : personas) {
            if (!persona.recruitable() || persona.relationships() == null) {
                continue;
            }
            for (Relationship relationship : persona.relationships()) {
                Persona other = byId.get(relationship.persona());
                if (other != null && other.recruitable() && !TieKind.named(relationship.kind())) {
                    unnamed.add(relationship.kind() + " (" + persona.id() + " -> " + other.id() + ")");
                }
            }
        }
        for (String kind : unnamed) {
            out.add("WARN " + FILE + ": relationship kind " + kind + " between recruitables is read as FRIEND by default;"
                    + " name it in TieKind");
        }
        return out;
    }

    private static void lintLines(List<String> out, String where, List<String> lines, Set<String> allowed) {
        for (String line : lines) {
            if (line.chars().anyMatch(java.lang.Character::isDigit)) {
                out.add("ERROR " + FILE + " " + where + ": a digit in \"" + line + "\" (numbers come from slots only)");
            }
            for (String slot : slots(line)) {
                if (!allowed.contains(slot)) {
                    out.add("ERROR " + FILE + " " + where + ": slot {" + slot + "} is not filled here: \"" + line + "\"");
                }
            }
        }
    }

    static Set<String> slots(String line) {
        Set<String> out = new HashSet<>();
        Matcher matcher = SLOT.matcher(line);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
