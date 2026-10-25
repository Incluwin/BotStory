package server.knowledge;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Guide claims (plan docs/GUIDE_KNOWLEDGE_PLAN.md section 7): reads the distilled claims of the local guide corpus, keeps
 * the ones this server's data agrees with, and writes {@code guide-knowledge.json} plus a report of what was dropped.
 *
 * <p>A claim survives only when every map, monster, item, NPC, quest, party quest and region it names resolves through
 * {@link NameIndex} the way a player's exact words would (a quest also by its full name: the chat index knows quests
 * only through aliases), every skill it names exists in String.wz, it does not rest on
 * numbers (the guides' rates and skill values are not this server's), a training spot's monsters are near the
 * claim's levels here, and it does not repeat {@value #COPY_WORDS} words in a row of its guide (claims are reworded).
 */
public final class GuideImport {
    public static final String FILE_NAME = "guide-knowledge.json";

    static final Set<String> TOPICS = Set.of("training_spot", "party_quest", "boss", "quest", "job_advance", "build_ap",
            "build_sp", "gear", "item_value", "meso_making", "mechanic", "travel", "skill_use", "general_tip");
    /** A training map's monsters may sit this far under the claim's lowest level, or over its highest. */
    static final int SPOT_UNDER = 15;
    static final int SPOT_OVER = 20;
    static final int BAND = 10;
    static final int SAY_MAX = 100;
    /** Matches a player's exact words would get: not a subset or partial name. */
    private static final Set<String> TRUSTED = Set.of("alias", "exact", "scroll", "fuzzy");
    /** Words that give the guide's own server, the forum or the real world away; a bot never says them. */
    private static final Pattern LEAK = Pattern.compile("\\b(?:royals?|mapleroyals|server|donor|donat\\w*|vote|discord|wiki"
            + "|guide|forums?|gms|kms|msea|ems|jms|nexon|patch|update|@\\w+|https?|www)\\b", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> BRANCH_OF_LINE = Map.ofEntries(Map.entry("fighter-line", "warrior"),
            Map.entry("page-line", "warrior"), Map.entry("spearman-line", "warrior"), Map.entry("fp-line", "magician"),
            Map.entry("il-line", "magician"), Map.entry("cleric-line", "magician"), Map.entry("hunter-line", "bowman"),
            Map.entry("crossbow-line", "bowman"), Map.entry("assassin-line", "thief"), Map.entry("bandit-line", "thief"),
            Map.entry("brawler-line", "pirate"), Map.entry("gunslinger-line", "pirate"));
    private static final Pattern SKILL_NAME = Pattern.compile("<string name=\"name\" value=\"([^\"]+)\"");
    private static final Pattern SERIES = Pattern.compile("(?: (?:\\d+|[ivx]+))+$");
    /** A claim sharing this many consecutive words with its guide repeats the guide's sentence, not just its facts. */
    static final int COPY_WORDS = 10;
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    record Source(String key, String url, String title, String author, String date) {
    }

    /**
     * A kept claim. Refs are "KIND:id" for game ids and "KIND:name@first id" for aliases.yaml composites (their ids
     * shift). {@code sources}: every thread that gave exactly this advice (the server counts them as support).
     */
    record Claim(String topic, List<String> jobs, int min, int max, List<String> refs, List<String> skills, String text,
                 String say, String strength, Set<Integer> sources) {
    }

    record Result(List<Source> sources, List<Claim> claims, Map<String, Integer> dropped, Map<String, Integer> unresolved,
                  int threads, int usableThreads, int rawClaims) {
    }

    private GuideImport() {
    }

    // ---- offline entry ------------------------------------------------------------------------------------------

    /** The knowledge the importer checks against: the committed file, travel, aliases and the Liquibase seed rows. */
    static WorldKnowledge offline(Path knowledgeDir, Path dbRoot) throws IOException {
        KnowledgeFile file = KnowledgeFile.read(knowledgeDir.resolve(KnowledgeSources.FILE_NAME));
        List<TravelLeg> travel = TravelCatalog.load(knowledgeDir.resolve(KnowledgeLoader.TRAVEL_FILE), file);
        AliasCatalog aliases = AliasCatalog.load(knowledgeDir.resolve(KnowledgeLoader.ALIAS_FILE), file);
        KnowledgeOverlay overlay = Files.isDirectory(dbRoot) ? SeedData.overlay(SeedData.replay(dbRoot)) : KnowledgeOverlay.EMPTY;
        return WorldKnowledge.build(file, travel, aliases, Map.of(), overlay, 1);
    }

    /** Lowercased skill names from String.wz/Skill.img.xml; "Fire Charge: Sword" also counts as "fire charge". */
    static Set<String> skillNames(Path stringSkillXml) throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher m = SKILL_NAME.matcher(Files.readString(stringSkillXml, StandardCharsets.UTF_8));
        while (m.find()) {
            String name = m.group(1).replace("&apos;", "'");
            names.add(NameIndex.normalize(name));
            int colon = name.indexOf(':');
            if (colon > 0) {
                names.add(NameIndex.normalize(name.substring(0, colon)));
            }
        }
        return names;
    }

    /** Reads {@code <corpus>/claims/<source>/*.json}, imports, writes the knowledge file and the report. */
    public static String importCorpus(Path corpus, Path knowledgeDir, Path dbRoot, Path wzRoot, Path report) throws IOException {
        WorldKnowledge knowledge = offline(knowledgeDir, dbRoot);
        Set<String> skills = skillNames(wzRoot.resolve("String.wz").resolve("Skill.img.xml"));
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> threads = new ArrayList<>();
        Path claims = corpus.resolve("claims");
        try (Stream<Path> files = Files.walk(claims)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                threads.add(json.readTree(path.toFile()));
            }
        }
        Result result = run(knowledge, skills, threads, thread -> postsOf(corpus, thread));
        write(result, knowledgeDir.resolve(FILE_NAME));
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, report(result), StandardCharsets.UTF_8);
        return summary(result);
    }

    /** "map:land of wild boar i" -> how each name resolves (top candidates with score and match type), for alias work. */
    public static String probe(Path knowledgeDir, Path dbRoot, List<String> queries) throws IOException {
        WorldKnowledge k = offline(knowledgeDir, dbRoot);
        StringBuilder out = new StringBuilder();
        for (String query : queries) {
            int colon = query.indexOf(':');
            NameIndex.Resolution r = k.names().resolve(query.substring(colon + 1), kindsFor(query.substring(0, colon)));
            out.append(query).append(r.resolved() ? " -> " : r.tie() ? " -> TIE " : " -> unresolved ");
            r.candidates().stream().limit(3).forEach(c -> out.append(String.format(Locale.ROOT, "[%s %d %s %.2f %s] ",
                    c.entity().kind(), c.entity().id(), c.entity().name(), c.score(), c.how())));
            out.append('\n');
        }
        return out.toString();
    }

    // ---- import -------------------------------------------------------------------------------------------------

    /** The thread author's own posts in {@code <corpus>/threads/<source>/<id>.json}; "" when the page is not there. */
    static String postsOf(Path corpus, JsonNode thread) {
        Path page = corpus.resolve("threads").resolve(thread.path("source").asText("")).resolve(thread.path("id").asText("") + ".json");
        if (!Files.isRegularFile(page)) {
            return "";
        }
        try {
            StringBuilder text = new StringBuilder();
            for (JsonNode post : new ObjectMapper().readTree(page.toFile()).path("posts")) {
                if (post.path("is_op").asBoolean(false)) {
                    text.append(post.path("text").asText("")).append('\n');
                }
            }
            return text.toString();
        } catch (IOException unreadable) {
            return "";
        }
    }

    static Result run(WorldKnowledge k, Set<String> skills, List<JsonNode> threads) {
        return run(k, skills, threads, thread -> "");
    }

    /** {@code posts}: a thread's own text, so a claim that copies the guide's wording (not just its facts) is dropped. */
    static Result run(WorldKnowledge k, Set<String> skills, List<JsonNode> threads, Function<JsonNode, String> posts) {
        Map<String, Integer> quests = questNames(k);
        List<Source> sources = new ArrayList<>();
        List<Claim> kept = new ArrayList<>();
        Map<String, Integer> dropped = new TreeMap<>();
        Map<String, Integer> unresolved = new HashMap<>();
        Map<String, Claim> seen = new HashMap<>();
        int usable = 0;
        int raw = 0;
        for (JsonNode thread : threads) {
            int sourceIndex = -1;
            boolean any = false;
            Set<String> wording = runs(posts.apply(thread));
            for (JsonNode chunk : thread.path("chunks")) {
                if (!chunk.path("usable").asBoolean(false)) {
                    continue;
                }
                any = true;
                for (JsonNode node : chunk.path("claims")) {
                    raw++;
                    Verdict verdict = check(k, skills, quests, node, unresolved);
                    if (verdict.reason == null && (copies(node.path("claim").asText(""), wording)
                            || copies(node.path("say").asText(""), wording))) {
                        verdict.drop("copies the guide's wording");
                    }
                    if (verdict.reason != null) {
                        dropped.merge(verdict.reason, 1, Integer::sum);
                        continue;
                    }
                    if (sourceIndex < 0) {
                        sourceIndex = sources.size();
                        sources.add(new Source(text(thread, "source") + ":" + text(thread, "id"), text(thread, "url"),
                                text(thread, "title"), text(thread, "starter"), text(thread, "started")));
                    }
                    Claim same = seen.get(verdict.topic + "|" + verdict.refs + "|" + verdict.jobs + "|" + verdict.say);
                    if (same != null) { // the same advice again: kept once, the thread counts as support
                        same.sources.add(sourceIndex);
                        dropped.merge("duplicate", 1, Integer::sum);
                        continue;
                    }
                    Claim claim = new Claim(verdict.topic, verdict.jobs, verdict.min, verdict.max, verdict.refs, verdict.skills,
                            clean(node.path("claim").asText(""), 240), verdict.say, node.path("strength").asText("consensus"),
                            new TreeSet<>(Set.of(sourceIndex)));
                    seen.put(verdict.topic + "|" + verdict.refs + "|" + verdict.jobs + "|" + verdict.say, claim);
                    kept.add(claim);
                }
            }
            if (any) {
                usable++;
            }
        }
        return new Result(sources, kept, dropped, unresolved, threads.size(), usable, raw);
    }

    private static final class Verdict {
        String reason;
        String topic;
        List<String> jobs = List.of();
        int min;
        int max;
        final List<String> refs = new ArrayList<>();
        final List<String> skills = new ArrayList<>();
        String say;

        Verdict drop(String why) {
            reason = why;
            return this;
        }
    }

    /** Every run of {@link #COPY_WORDS} consecutive words in {@code text} (lowercase letters and digits). */
    static Set<String> runs(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        Set<String> runs = new HashSet<>();
        for (int i = 0; i + COPY_WORDS <= words.size(); i++) {
            runs.add(String.join(" ", words.subList(i, i + COPY_WORDS)));
        }
        return runs;
    }

    static boolean copies(String text, Set<String> wording) {
        return !wording.isEmpty() && runs(text).stream().anyMatch(wording::contains);
    }

    /** Normalized full quest name (two words or more) -> its lowest id; never a part of a name, never one word. */
    static Map<String, Integer> questNames(WorldKnowledge k) {
        Map<String, Integer> names = new HashMap<>();
        for (QuestFact quest : k.quests()) {
            String name = quest.name() == null ? "" : NameIndex.normalize(quest.name());
            if (name.indexOf(' ') > 0) {
                names.merge(name, quest.id(), Math::min);
            }
        }
        return names;
    }

    private static Verdict check(WorldKnowledge k, Set<String> skills, Map<String, Integer> quests, JsonNode node,
                                 Map<String, Integer> unresolved) {
        Verdict v = new Verdict();
        v.topic = node.path("topic").asText("");
        if (!TOPICS.contains(v.topic)) {
            return v.drop("unknown topic");
        }
        if (node.path("depends_on_numbers").asBoolean(false)) {
            return v.drop("rests on numbers");
        }
        String text = node.path("claim").asText("");
        v.say = say(node.path("say").asText(""));
        if (v.say == null) {
            return v.drop("no usable say line");
        }
        if (LEAK.matcher(v.say).find() || LEAK.matcher(text).find()) {
            return v.drop("names the guide's server, the forum or the real world");
        }
        Set<String> named = new LinkedHashSet<>();
        for (JsonNode job : node.path("jobs")) {
            if (GuideKnowledge.JOB_KEYS.contains(job.asText())) {
                named.add(job.asText());
            }
        }
        List<String> jobs = new ArrayList<>();
        for (String job : named) {
            String branch = BRANCH_OF_LINE.get(job);
            if (branch == null || !named.contains(branch)) { // "warrior" already covers "fighter-line"
                jobs.add(job);
            }
        }
        v.jobs = List.copyOf(jobs);
        int min = Math.max(0, node.path("level_min").asInt(0));
        int max = Math.max(0, node.path("level_max").asInt(0));
        v.min = max > 0 && min > max ? max : min;
        v.max = max > 0 && min > max ? min : max;
        boolean places = false; // a training spot names a map, region or monster
        for (JsonNode entity : node.path("entities")) {
            String kind = entity.path("kind").asText("");
            String name = entity.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (kind.equals("skill")) {
                String skill = wzSkill(skills, name);
                if (skill == null) {
                    unresolved.merge("skill: " + NameIndex.normalize(name), 1, Integer::sum);
                    return v.drop("names a skill this server does not have");
                }
                v.skills.add(skill);
                continue;
            }
            List<EntityKind> kinds = kindsFor(kind);
            if (kinds.isEmpty()) {
                return v.drop("unknown entity kind");
            }
            NameIndex.Resolution resolution = k.names().resolve(name, kinds);
            Entity hit = resolution.resolved() && trusted(resolution.best().get(), resolution.text())
                    ? resolution.best().get().entity() : null;
            if (hit == null && kind.equals("quest")) {
                Integer quest = quests.get(NameIndex.normalize(name));
                hit = quest == null ? null : k.names().entity(EntityKind.QUEST, quest).orElse(null);
            }
            if (hit == null && (kind.equals("region") || kind.equals("map"))) { // "Mu Lung Dojo" is a party quest here
                NameIndex.Resolution pq = k.names().resolve(name, List.of(EntityKind.PQ));
                hit = pq.resolved() && TRUSTED.contains(pq.best().get().how()) ? pq.best().get().entity() : null;
            }
            if (hit == null) {
                unresolved.merge(kind + ": " + NameIndex.normalize(name), 1, Integer::sum);
                return v.drop("names something that does not resolve here");
            }
            String ref = ref(hit);
            if (!v.refs.contains(ref)) {
                v.refs.add(ref);
            }
            if (v.topic.equals("training_spot")) {
                List<Integer> maps = GuideKnowledge.spotMaps(k, hit, v.min, v.max);
                if (maps != null && maps.isEmpty()) {
                    return v.drop("training spot far from the claim's levels here");
                }
                places |= maps != null;
            }
        }
        if (v.topic.equals("training_spot") && !places) {
            return v.drop("training spot names no map or monster");
        }
        return v;
    }

    /**
     * A match a player's exact words would get. A place may also match as a subset of a multi-word name ("Southern Forest
     * I" is WZ's "Dungeon, Southern Forest I"): series numerals do not count in names, so a spot is right to the series.
     */
    private static boolean trusted(NameIndex.Candidate hit, String text) {
        EntityKind kind = hit.entity().kind();
        return TRUSTED.contains(hit.how()) || hit.how().equals("subset") && (text.indexOf(' ') > 0
                && (kind == EntityKind.MAP || kind == EntityKind.REGION) || sameName(hit.entity().name(), text));
    }

    /** "The Magic Rock" is "magic rock" and "Jr. Boogie 1" is "jr boogie": a leading "the" and series numerals do not count. */
    static boolean sameName(String wzName, String text) {
        String core = core(text);
        return core.chars().anyMatch(Character::isLetter) && core.equals(core(wzName));
    }

    private static String core(String name) {
        return SERIES.matcher(NameIndex.normalize(name).replaceFirst("^the ", "")).replaceFirst("");
    }

    /** A skill as String.wz spells it: guides write "Improving Max HP Increase" for "Improved MaxHP Increase". */
    static String wzSkill(Set<String> skills, String name) {
        String normal = NameIndex.normalize(name);
        String spelled = normal.replace("improving ", "improved ").replace("max hp", "maxhp").replace("max mp", "maxmp");
        return skills.contains(normal) ? normal : skills.contains(spelled) ? spelled : null;
    }

    static List<EntityKind> kindsFor(String kind) {
        return switch (kind) {
            case "map" -> List.of(EntityKind.MAP, EntityKind.REGION);
            case "region" -> List.of(EntityKind.REGION, EntityKind.MAP);
            case "mob" -> List.of(EntityKind.MOB);
            case "boss" -> List.of(EntityKind.MOB, EntityKind.PQ);
            case "item" -> List.of(EntityKind.ITEM);
            case "npc" -> List.of(EntityKind.NPC, EntityKind.ROLE);
            case "quest" -> List.of(EntityKind.QUEST);
            case "pq" -> List.of(EntityKind.PQ);
            default -> List.of();
        };
    }

    static String ref(Entity e) {
        return switch (e.kind()) {
            case REGION, PQ, ROLE -> e.kind().name() + ":" + e.name() + "@" + (e.ids().isEmpty() ? 0 : e.ids().getInt(0));
            default -> e.kind().name() + ":" + e.id();
        };
    }


    /** A chat line: ASCII, lowercase, single-spaced, no surrounding quotes or final period; null when unusable. */
    static String say(String raw) {
        String text = clean(raw, SAY_MAX + 20).toLowerCase(Locale.ROOT);
        text = text.replaceAll("^[\"'\\s]+|[\"'\\s]+$", "").replaceAll("\\.+$", "").trim();
        return text.isEmpty() || text.length() > SAY_MAX ? null : text;
    }

    /** ASCII-folded, single-spaced text cut to {@code max} chars (the knowledge files are US-ASCII). */
    static String clean(String raw, int max) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2026', '.')
                .replaceAll("[^\\x20-\\x7e]", "").replaceAll("\\s+", " ").trim();
        return text.length() > max ? text.substring(0, max).trim() : text;
    }

    private static String text(JsonNode node, String field) {
        return clean(node.path(field).asText(""), 200);
    }

    // ---- output -------------------------------------------------------------------------------------------------

    static void write(Result result, Path target) throws IOException {
        ObjectMapper json = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        ObjectNode root = json.createObjectNode();
        root.put("version", 1);
        root.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        root.put("note", "Claims distilled from player-written guides, reworded; each cites its source thread. "
                + "Generated by tools/guide-import.bat; do not edit by hand.");
        ArrayNode sources = root.putArray("sources");
        for (Source s : result.sources) {
            sources.addObject().put("key", s.key).put("url", s.url).put("title", s.title).put("author", s.author)
                    .put("date", s.date);
        }
        ArrayNode claims = root.putArray("claims");
        for (Claim c : result.claims) {
            ObjectNode row = claims.addObject().put("topic", c.topic);
            c.jobs.forEach(row.putArray("jobs")::add);
            row.put("min", c.min).put("max", c.max);
            c.refs.forEach(row.putArray("refs")::add);
            c.skills.forEach(row.putArray("skills")::add);
            row.put("text", c.text).put("say", c.say).put("strength", c.strength);
            c.sources.forEach(row.putArray("src")::add);
        }
        Files.writeString(target, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.US_ASCII);
    }

    static String summary(Result r) {
        int dropped = r.dropped.values().stream().mapToInt(Integer::intValue).sum();
        return r.threads + " threads (" + r.usableThreads + " usable), " + r.rawClaims + " claims: " + r.claims.size()
                + " kept from " + r.sources.size() + " sources (" + r.claims.stream().filter(c -> c.topic.equals("training_spot")).count()
                + " training spots), " + dropped + " dropped";
    }

    static String report(Result r) {
        StringBuilder out = new StringBuilder("# Guide import report\n\n").append(summary(r)).append("\n\n## Dropped\n\n");
        r.dropped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> out.append("- ").append(e.getValue()).append(" ").append(e.getKey()).append('\n'));
        out.append("\n## Kept by topic\n\n");
        Map<String, Integer> topics = new TreeMap<>();
        r.claims.forEach(c -> topics.merge(c.topic, 1, Integer::sum));
        topics.forEach((topic, n) -> out.append("- ").append(topic).append(": ").append(n).append('\n'));
        out.append("\n## Most frequent unresolved names (alias candidates)\n\n");
        r.unresolved.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(150).forEach(e -> out.append("- ").append(e.getValue()).append(" ").append(e.getKey()).append('\n'));
        return out.toString();
    }
}
