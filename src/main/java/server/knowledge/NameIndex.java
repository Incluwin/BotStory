package server.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player words to entities (docs/SERVER_KNOWLEDGE_PLAN.md 5.2): aliases.yaml, every String.wz name, the
 * scroll grammar ("glove att 60") and numbered map series ("ant tunnel"), all keyed by the same
 * {@link #normalize entity normalization}. Built once per {@link WorldKnowledge} snapshot because the
 * canonical preference among same-named ids reads the overlay (an item a shop sells or a mob drops beats
 * an unobtainable copy).
 *
 * <p>Match order and scores: alias 1.0, exact name 0.95, scroll grammar 1.0 (0.9 per % when the % is left
 * out), token subset/prefix 0.6 + 0.3 x coverage, one-edit typo 0.5 (0.4 for a subset). Cash items score x0.7
 * and unplaced event copies of mobs x0.8, so "snail" is the mob and never the cash pet. Two distinct
 * entities within 0.1 of each other are a tie: the bot asks back instead of guessing.
 *
 * <p>Lookups are hash and binary-search work over normalized strings (a few microseconds per span).
 */
public final class NameIndex {
    static final double ALIAS = 1.0;
    static final double EXACT = 0.95;
    static final double SCROLL_ANY_PCT = 0.9;
    static final double SUBSET_BASE = 0.6;
    static final double SUBSET_SPAN = 0.3;
    static final double FUZZY = 0.5;
    static final double FUZZY_SUBSET = 0.4;
    static final double TIE = 0.1;
    /** A word-subset match covering less of the name than this is partial ("leader" in "Leader A"). */
    private static final double PARTIAL = 2d / 3d;
    private static final double CASH = 0.7;
    private static final double EVENT_COPY = 0.8;
    private static final double KIND_STEP = 0.01;
    private static final double EPSILON = 1e-9;
    private static final int EVENT_MOB_ID = 9_000_000;
    private static final int FIELD_MAP_LIMIT = 900_000_000;
    private static final int CARD_FIRST = 2_380_000;
    private static final int CARD_LAST = 2_389_999;
    private static final int SCROLL_FIRST = 2_040_000;
    private static final int SCROLL_LAST = 2_049_999;
    /** A one-word subset query only expands when the word is this rare ("shells", not "helm"). */
    private static final int SUBSET_SINGLE_MAX = 8;
    private static final int SUBSET_SCAN_MAX = 4_000;
    private static final int MAX_CANDIDATES = 6;
    private static final int FUZZY_MIN_LENGTH = 5;
    private static final int MENTION_MAX_TOKENS = 9;
    private static final String MISSING_NAME = "missing name";
    private static final int STRING_BYTES = 56;
    private static final int ARRAY_BYTES = 16;
    private static final int ENTRY_BYTES = 64;

    /** Never a match on their own, even inside a question slot (grammar, chat and category words). */
    static final Set<String> GRAMMAR = Set.of("the", "a", "an", "one", "it", "is", "i", "you", "u", "me", "go", "get",
            "any", "some", "all", "here", "there", "town", "shop", "npc", "mob", "monster", "map", "item", "drop", "pot",
            "potion", "scroll", "hp", "mp", "exp", "xp", "ap", "sp", "pt", "cc", "ks", "lf", "gm", "pc", "that", "this",
            "these", "those", "them", "they", "everyone", "everybody", "someone", "anyone", "people", "ppl", "guy", "stuff",
            "thing", "good", "best", "my", "your", "ur", "his", "her", "their", "our", "level", "lvl", "job", "party",
            "quest", "boss", "money", "meso", "nx", "rich", "where", "what", "who", "how", "which", "way", "place", "spot",
            "area", "room", "channel", "ch", "now", "today", "lol", "ok", "yes", "no", "gg", "please", "pls", "plz",
            "equip", "gear", "weapon", "set", "card", "mount", "chair", "event", "new", "old", "big", "small",
            "exit", "pass", "leader", "other", "bonus", "first", "rope", "platform", "fun");
    /** Real one-word names that are also chat words: only resolve in a typed slot, never in free text. */
    static final Set<String> COLLIDES = Set.of("egg", "key", "note", "moon", "leaf", "wood", "shield", "sword", "spear",
            "axe", "orange", "apple", "meat", "red", "green", "blue", "white", "black", "rain", "pi", "silver", "bronze",
            "gold", "mia", "ria", "tia", "mai", "jane", "harry", "cody", "bush", "moose", "ice", "fire", "snow", "star",
            "happy", "chaos", "ht", "cs", "pe", "fm", "ws", "isa", "dat", "pac", "kc", "lh", "am", "bm", "mm", "nl", "gs",
            "pig", "pigs", "snail", "stump", "drake", "wraith", "necki", "pots", "potions", "storage", "boat", "ship",
            "ticket", "station", "taxi", "cab", "weapon", "weapons", "armor", "hair", "bank", "pitch", "coupon", "stars",
            "arrows", "arrow", "bolts", "bullets", "screw", "leather", "banana", "bananas", "onyx", "cider");
    private static final Map<String, String> IRREGULAR = Map.ofEntries(Map.entry("knives", "knife"),
            Map.entry("leaves", "leaf"), Map.entry("octopi", "octopus"), Map.entry("mushies", "mushroom"),
            Map.entry("mushy", "mushroom"), Map.entry("mushie", "mushroom"), Map.entry("shrooms", "mushroom"),
            Map.entry("shroom", "mushroom"), Map.entry("mush", "mushroom"), Map.entry("teeth", "tooth"),
            Map.entry("feet", "foot"), Map.entry("bowmen", "bowman"), Map.entry("junior", "jr"),
            Map.entry("percent", "%"), Map.entry("mushrooms", "mushroom"));
    private static final Set<String> ROMAN = Set.of("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii");
    private static final Pattern BRACKETS = Pattern.compile("<[^>]*>|\\[[^\\]]*]|\\([^)]*\\)");
    private static final Pattern PERCENT_TOKEN = Pattern.compile("(\\d{1,3})(?:%|p)");
    private static final Pattern SCROLL_NAME = Pattern.compile("^(?:dark )?scroll for (.+?) for (.+?)(?: (\\d{1,3})%)?$");
    /** Map names that say nothing on their own ("Line 1 <Area 1>", "B1 <Area 1>"): the street is said instead. */
    private static final Set<String> GENERIC_PLACE = Set.of("line", "area", "stage", "floor", "room", "cabin");
    /** A name of nothing but those words, ordinals, numbers, basement codes and single letters ("1st floor 2nd floor area b"). */
    private static final Pattern GENERIC_NAME = Pattern.compile(
            "^(?:(?:\\d+(?:st|nd|rd|th)?|b\\d+|[a-z]|line|area|stage|floor|room|cabin)(?: |$))+$");

    private static final Map<String, String> SCROLL_SLOTS = phraseMap(
            "glove", "glove", "gloves", "glove", "g", "glove", "glv", "glove", "gl", "glove",
            "helmet", "helmet", "helm", "helmet", "hat", "helmet", "h", "helmet",
            "shield", "shield", "cape", "cape", "earring", "earring", "ear", "earring", "earing", "earring",
            "overall", "overall", "overall armor", "overall", "ova", "overall",
            "top", "top", "topwear", "top", "shirt", "top", "bottom", "bottom", "bottomwear", "bottom", "pant", "bottom",
            "shoe", "shoe", "boot", "shoe", "ring", "ring", "belt", "belt", "pet equip", "pet", "pet", "pet",
            "face accessory", "face", "face", "face", "eye accessory", "eye", "eye", "eye",
            "one handed sword", "1h sword", "1h sword", "1h sword", "ohs", "1h sword", "1hs", "1h sword",
            "two handed sword", "2h sword", "2h sword", "2h sword", "ths", "2h sword", "2hs", "2h sword",
            "one handed axe", "1h axe", "1h axe", "1h axe", "oha", "1h axe",
            "two handed axe", "2h axe", "2h axe", "2h axe", "tha", "2h axe",
            "one handed bw", "1h bw", "1h bw", "1h bw", "ohbw", "1h bw", "1h mace", "1h bw",
            "two handed bw", "2h bw", "2h bw", "2h bw", "thbw", "2h bw", "2h mace", "2h bw",
            "spear", "spear", "pole arm", "pole arm", "polearm", "pole arm", "pa", "pole arm",
            "bow", "bow", "crossbow", "xbow", "xbow", "xbow", "claw", "claw", "dagger", "dagger", "wand", "wand",
            "staff", "staff", "knuckler", "knuckle", "knuckle", "knuckle", "knux", "knuckle", "gun", "gun");
    private static final Map<String, String> SCROLL_STATS = phraseMap(
            "att", "att", "attack", "att", "atk", "att", "watk", "att", "wa", "att", "w att", "att", "weapon att", "att",
            "magic att", "matt", "magic attack", "matt", "matt", "matt", "ma", "matt", "m att", "matt",
            "def", "def", "defense", "def", "wdef", "def", "weapon def", "def", "magic def", "mdef", "mdef", "mdef",
            "accuracy", "acc", "acc", "acc", "dex", "dex", "str", "str", "int", "int", "luk", "luk", "luck", "luk",
            "hp", "hp", "maxhp", "hp", "mp", "mp", "jump", "jump", "speed", "speed", "spd", "speed",
            "avoidability", "avoid", "avoid", "avoid");
    private static final Map<String, String[]> SCROLL_FUSED = Map.of("gatt", new String[]{"glove", "att"},
            "gdex", new String[]{"glove", "dex"}, "hdef", new String[]{"helmet", "def"});
    private static final Map<String, String> SLOT_SAY = Map.of("helmet", "helm");
    private static final Set<String> SCROLL_FILLERS = Set.of("scroll", "for", "a", "the", "of", "%");

    /** A match: the entity, its score and how it was found (alias, exact, scroll, subset, fuzzy). */
    public record Candidate(Entity entity, double score, String how) {
    }

    /**
     * Candidates best first. {@code tie} is true when the top two are distinct entities of the same kind within
     * {@link #TIE}; then the bot asks back ("blue or red snail?") instead of answering. Between kinds the question's
     * kind order decides (an NPC named like a role, "ht" the boss or the necklace).
     */
    public record Resolution(String text, List<Candidate> candidates, boolean tie) {
        static final Resolution NONE = new Resolution("", List.of(), false);

        public Optional<Candidate> best() {
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
        }

        public boolean resolved() {
            return !candidates.isEmpty() && !tie;
        }
    }

    /** A name found in free text, at token offsets {@code [start, end)} of the normalized text. */
    public record Mention(int start, int end, Candidate candidate) {
    }

    private final WorldKnowledge knowledge;
    private final Map<String, List<Entity>> aliases = new HashMap<>();
    private final List<Entity> composites = new ArrayList<>();
    /** Scroll grammar: "slot|stat|pct" -> item ids, canonical first; "slot|stat" -> the % on offer. */
    private final Map<String, int[]> scrolls = new HashMap<>();
    private final Map<String, int[]> scrollPcts = new HashMap<>();
    private final Map<Integer, String> scrollSay = new HashMap<>();
    private final Map<String, AliasCatalog.JobBranch> jobWords = new HashMap<>();
    private final List<AliasCatalog.JobBranch> jobs;
    /** Spoken names from aliases.yaml, by entity key. */
    private final Map<Long, String> says = new HashMap<>();
    /** Spoken names derived from WZ names, filled on first use (only entities bots actually talk about). */
    private final Map<Long, String> derivedSays = new java.util.concurrent.ConcurrentHashMap<>();
    /** Map id -> the REGION composite (authored area or numbered series) it belongs to. */
    private final Map<Integer, Entity> seriesByMap = new HashMap<>();
    /** Stage map id -> the party quest it belongs to (lobby towns excluded). */
    private final Map<Integer, Entity> pqByMap = new HashMap<>();
    /** Lobby map id (aliases.yaml pq ids[0]) -> its party quest. */
    private final Map<Integer, Entity> pqByLobby = new HashMap<>();
    /** Every map of a party quest's instance: a stage, or a map sharing a stage's forced return (other stages, the exit). */
    private final Map<Integer, Entity> pqInstances = new HashMap<>();
    /**
     * Every normalized name, sorted, with its refs ({@code kind << 32 | id}, each kind's canonical id first).
     * Token ids per name and name ids per token are flat arrays with start offsets: the index stays a couple of
     * MB instead of a HashMap of small arrays.
     */
    private final String[] keys;
    private final long[][] keyRefs;
    private final int[] keyTokenStart;
    private final int[] keyTokenIds;
    private final String[] vocab;
    private final int[] postingStart;
    private final int[] postingIds;
    private final String[][] vocabByLength;
    private final long[] prefixes;

    NameIndex(WorldKnowledge knowledge, AliasCatalog catalog, Map<Integer, Integer> fields) {
        this.knowledge = knowledge;
        this.jobs = catalog.jobs;
        Map<String, List<long[]>> refs = new HashMap<>();
        for (ItemFact item : knowledge.items()) {
            if (!item.unnamed() && (item.id() < CARD_FIRST || item.id() > CARD_LAST)) {
                addName(refs, item.name(), EntityKind.ITEM, item.id(), itemRank(item));
            }
        }
        for (MobFact mob : knowledge.mobs()) {
            boolean spawns = !knowledge.spawnMaps(mob.id()).isEmpty();
            addName(refs, mob.name(), EntityKind.MOB, mob.id(), (mob.id() < EVENT_MOB_ID && spawns ? 0 : spawns ? 1
                    : mob.id() < EVENT_MOB_ID ? 2 : 3));
        }
        for (NpcFact npc : knowledge.npcs()) {
            List<Integer> placed = knowledge.npcMaps(npc.id());
            addName(refs, npc.name(), EntityKind.NPC, npc.id(), placed.isEmpty() ? 2
                    : placed.stream().anyMatch(map -> map < FIELD_MAP_LIMIT) ? 0 : 1);
        }
        Map<String, TreeSet<Integer>> series = new TreeMap<>();
        for (MapFact map : knowledge.maps()) {
            addName(refs, map.name(), EntityKind.MAP, map.id(), (fields.containsKey(map.id()) ? 0
                    : !"Hidden Street".equals(map.street()) ? 1 : 2) + (map.region() == null ? 3 : 0));
            String base = seriesBase(map.name());
            if (map.region() != null && !base.isEmpty() && !base.equals(normalize(map.name()))) {
                series.computeIfAbsent(base, b -> new TreeSet<>()).add(map.id());
            }
        }
        for (AliasCatalog.Alias alias : catalog.aliases) {
            Entity entity = aliasEntity(alias);
            if (entity == null) {
                continue;
            }
            says.putIfAbsent(entity.key(), alias.say());
            if (entity.kind().ordinal() <= EntityKind.QUEST.ordinal()) {
                for (int id : alias.ids()) {
                    says.putIfAbsent(key(entity.kind(), id), alias.say()); // Zakum1..3, Zakum Helmet (2)/(3)
                }
            }
            for (String name : alias.names()) {
                aliases.computeIfAbsent(normalize(name), n -> new ArrayList<>()).add(entity);
            }
        }
        for (Map.Entry<String, TreeSet<Integer>> run : series.entrySet()) {
            if (run.getValue().size() >= 2 && !aliases.containsKey(run.getKey())) {
                int id = composites.size() + 1;
                String say = knowledge.map(run.getValue().first()).map(map -> placeSay(map.name(), map.street()))
                        .orElse(cleanSay(run.getKey()));
                Entity region = new Entity(EntityKind.REGION, id, run.getKey(), say, IntList.copyOf(run.getValue()), 0, 0, 0);
                composites.add(region);
                refs.computeIfAbsent(run.getKey(), k -> new ArrayList<>()).add(new long[]{key(EntityKind.REGION, id), 0});
            }
        }
        for (Entity composite : composites) {
            for (int map : composite.ids()) {
                if (composite.kind() == EntityKind.REGION) {
                    seriesByMap.putIfAbsent(map, composite);
                } else if (composite.kind() == EntityKind.PQ && !knowledge.map(map).map(MapFact::town).orElse(false)) {
                    pqByMap.putIfAbsent(map, composite);
                }
            }
            if (composite.kind() == EntityKind.PQ) {
                pqByLobby.putIfAbsent(composite.ids().getInt(0), composite);
                Set<Integer> stageReturns = new HashSet<>(); // a stage's forced return is the instance's exit
                for (int i = 1; i < composite.ids().size(); i++) {
                    pqInstances.putIfAbsent(composite.ids().getInt(i), composite);
                    knowledge.map(composite.ids().getInt(i)).map(MapFact::forcedReturn).filter(back -> back != 0)
                            .ifPresent(stageReturns::add);
                }
                for (MapFact map : stageReturns.isEmpty() ? List.<MapFact>of() : knowledge.maps()) {
                    if (stageReturns.contains(map.forcedReturn())) {
                        pqInstances.putIfAbsent(map.id(), composite);
                    }
                }
            }
        }
        keys = refs.keySet().toArray(String[]::new);
        Arrays.sort(keys);
        keyRefs = new long[keys.length][];
        for (int k = 0; k < keys.length; k++) {
            List<long[]> list = refs.get(keys[k]);
            list.sort(Comparator.<long[]>comparingLong(ref -> ref[0] >>> 32).thenComparingLong(ref -> ref[1])
                    .thenComparingLong(ref -> ref[0] & 0xffffffffL));
            keyRefs[k] = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                keyRefs[k][i] = list.get(i)[0];
            }
        }
        indexScrolls();
        for (AliasCatalog.JobBranch job : catalog.jobs) {
            for (String word : job.words()) {
                jobWords.put(normalize(word), job);
            }
        }

        // token vocabulary, postings (vocab id -> name keys) and the n-gram prefix set for mention scanning
        TreeSet<String> tokens = new TreeSet<>();
        for (String key : keys) {
            tokens.addAll(Arrays.asList(key.split(" ")));
        }
        for (String alias : aliases.keySet()) {
            tokens.addAll(Arrays.asList(alias.split(" ")));
        }
        vocab = tokens.toArray(String[]::new);
        List<List<Integer>> post = new ArrayList<>(vocab.length);
        for (int i = 0; i < vocab.length; i++) {
            post.add(new ArrayList<>(2));
        }
        keyTokenStart = new int[keys.length + 1];
        IntListBuilder tokenIds = new IntListBuilder();
        for (int k = 0; k < keys.length; k++) {
            String[] parts = keys[k].split(" ");
            int[] ids = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                ids[i] = Arrays.binarySearch(vocab, parts[i]);
                List<Integer> list = post.get(ids[i]);
                if (list.isEmpty() || list.get(list.size() - 1) != k) {
                    list.add(k);
                }
            }
            Arrays.sort(ids);
            tokenIds.addAll(ids);
            keyTokenStart[k + 1] = tokenIds.size();
        }
        keyTokenIds = tokenIds.toArray();
        postingStart = new int[vocab.length + 1];
        IntListBuilder postingList = new IntListBuilder();
        for (int i = 0; i < vocab.length; i++) {
            for (int k : post.get(i)) {
                postingList.add(k);
            }
            postingStart[i + 1] = postingList.size();
        }
        postingIds = postingList.toArray();
        Map<Integer, List<String>> byLength = new TreeMap<>();
        for (String token : vocab) {
            if (token.length() >= FUZZY_MIN_LENGTH - 1) {
                byLength.computeIfAbsent(token.length(), n -> new ArrayList<>()).add(token);
            }
        }
        int maxLength = byLength.isEmpty() ? 0 : ((TreeMap<Integer, List<String>>) byLength).lastKey();
        vocabByLength = new String[maxLength + 2][];
        for (int n = 0; n < vocabByLength.length; n++) {
            vocabByLength[n] = byLength.getOrDefault(n, List.of()).toArray(String[]::new);
        }
        Set<Long> grams = new HashSet<>();
        for (String key : keys) {
            addPrefixes(grams, key);
        }
        for (String alias : aliases.keySet()) {
            addPrefixes(grams, alias);
        }
        prefixes = grams.stream().mapToLong(Long::longValue).sorted().toArray();
        derivedSays.clear(); // anything spoken while building ran before the scroll and PQ tables existed
    }

    // ---- normalization ----------------------------------------------------------------------

    /**
     * The entity form, applied identically to names and chat: lowercase, apostrophes dropped, other
     * punctuation to spaces, "60 %" / "60 percent" / "60p" folded to "60%", 3+ repeated letters to 2, and
     * every token singular (ies -> y, sses/xes/zes/ches/shes -> drop es, else drop s unless ss/us/is, plus
     * irregulars such as knives, mushies, octopi).
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder spaced = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c == '\'' || c == '’') {
                continue;
            }
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                spaced.append(c);
            } else if (c == '%') {
                spaced.append('%');
            } else {
                spaced.append(' ');
            }
        }
        String[] raw = spaced.toString().trim().split(" +");
        StringBuilder out = new StringBuilder(spaced.length());
        for (int i = 0; i < raw.length; i++) {
            String token = raw[i];
            if (token.isEmpty()) {
                continue;
            }
            boolean digits = Character.isDigit(token.charAt(0));
            if (digits && i + 1 < raw.length && (raw[i + 1].equals("%") || raw[i + 1].equals("percent"))
                    && token.chars().allMatch(Character::isDigit)) {
                token = token + "%";
                i++;
            } else if (digits) {
                Matcher pct = PERCENT_TOKEN.matcher(token);
                if (pct.matches()) {
                    token = pct.group(1) + "%";
                }
            } else {
                token = singular(collapseRepeats(token));
            }
            if (token.equals("%") && out.length() > 0 && Character.isDigit(out.charAt(out.length() - 1))) {
                out.append('%');
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    static String collapseRepeats(String token) {
        StringBuilder out = null;
        int run = 1;
        for (int i = 1; i < token.length(); i++) {
            run = token.charAt(i) == token.charAt(i - 1) && Character.isLetter(token.charAt(i)) ? run + 1 : 1;
            if (run >= 3) {
                if (out == null) {
                    out = new StringBuilder(token.substring(0, i));
                }
            } else if (out != null) {
                out.append(token.charAt(i));
            }
        }
        return out == null ? token : out.toString();
    }

    static String singular(String token) {
        String irregular = IRREGULAR.get(token);
        if (irregular != null) {
            return irregular;
        }
        int n = token.length();
        if (n <= 3 || !Character.isLetter(token.charAt(n - 1))) {
            return token;
        }
        if (token.endsWith("ies") && n > 4) {
            return token.substring(0, n - 3) + "y";
        }
        if (token.endsWith("sses") || token.endsWith("xes") || token.endsWith("zes") || token.endsWith("ches")
                || token.endsWith("shes")) {
            return token.substring(0, n - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, n - 1);
        }
        return token;
    }

    /** The spoken form of a WZ name: lowercase, no "The ", brackets, series numerals, punctuation or colons. */
    static String cleanSay(String name) {
        String text = BRACKETS.matcher(name).replaceAll(" ").toLowerCase(Locale.ROOT)
                .replace("'", "").replace(".", "").replace(":", " ").replace("-", " ").replace(",", "");
        String[] tokens = text.trim().split(" +");
        int start = tokens.length > 1 && tokens[0].equals("the") ? 1 : 0;
        int end = tokens.length;
        while (end - start > 1 && (ROMAN.contains(tokens[end - 1]) || tokens[end - 1].chars().allMatch(Character::isDigit))) {
            end--;
        }
        return String.join(" ", Arrays.asList(tokens).subList(start, end)).trim();
    }

    /** The normalized name a numbered map belongs to ("Ant Tunnel II" -> "ant tunnel"), or "". */
    static String seriesBase(String name) {
        String text = BRACKETS.matcher(name).replaceAll(" ").trim();
        String[] tokens = text.split(" +");
        int end = tokens.length;
        while (end > 1 && (ROMAN.contains(tokens[end - 1].toLowerCase(Locale.ROOT))
                || tokens[end - 1].chars().allMatch(Character::isDigit))) {
            end--;
        }
        return normalize(String.join(" ", Arrays.asList(tokens).subList(0, end)));
    }

    static String plural(String say) {
        int space = say.lastIndexOf(' ');
        String last = say.substring(space + 1);
        String head = say.substring(0, space + 1);
        if (last.isEmpty() || last.endsWith("s") || last.endsWith("z")) {
            return say;
        }
        if (last.endsWith("man")) {
            return head + last.substring(0, last.length() - 3) + "men";
        }
        if (last.endsWith("y") && last.length() > 1 && "aeiou".indexOf(last.charAt(last.length() - 2)) < 0) {
            return head + last.substring(0, last.length() - 1) + "ies";
        }
        if (last.endsWith("x") || last.endsWith("ch") || last.endsWith("sh")) {
            return head + last + "es";
        }
        return head + last + "s";
    }

    // ---- resolution -------------------------------------------------------------------------

    /**
     * Resolves a question slot ("steelies", "the red snails", "glove att 60") to entities of {@code kinds}
     * (in priority order: for one name, only the first allowed kind counts).
     */
    public Resolution resolve(String span, List<EntityKind> kinds) {
        String text = stripArticles(normalize(span));
        if (text.isEmpty() || kinds.isEmpty()) {
            return Resolution.NONE;
        }
        Map<Long, Candidate> found = new LinkedHashMap<>();
        exactAndAlias(text, kinds, ALIAS, EXACT, null, found);
        if (kinds.contains(EntityKind.ITEM)) {
            scrollCandidates(text, kinds, found);
        }
        if (found.isEmpty()) {
            subset(text, kinds, 1d, found);
        }
        if (found.isEmpty()) {
            String corrected = correct(text);
            if (corrected != null) {
                exactAndAlias(corrected, kinds, FUZZY, FUZZY, "fuzzy", found);
                if (found.isEmpty()) {
                    subset(corrected, kinds, FUZZY_SUBSET / (SUBSET_BASE + SUBSET_SPAN), found);
                }
            }
        }
        return finish(text, found);
    }

    /**
     * {@link #resolve} for a question slot as a player typed it: a one-word slot only counts through an alias or an
     * exact (or one-typo) name, and a partial match never counts, so "where is the leader" stays unresolved instead
     * of asking "leader a or b?", and "how do i get out of here" never becomes "a path out of the forest".
     */
    public Resolution resolveSlot(String span, List<EntityKind> kinds) {
        Resolution all = resolve(span, kinds);
        boolean oneWord = all.text().indexOf(' ') < 0;
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : all.candidates()) {
            String how = candidate.how();
            if (!how.endsWith("partial") && !(oneWord && how.endsWith("subset"))) {
                kept.add(candidate);
            }
        }
        return kept.size() == all.candidates().size() ? all : ranked(all.text(), kept);
    }

    /** Free-text names (no question slot): alias hits and exact names of 2+ tokens, longest first. */
    public List<Mention> mentions(String text, List<EntityKind> kinds) {
        String[] tokens = normalize(text).split(" ");
        List<Mention> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.length) {
            int bestEnd = -1;
            Candidate best = null;
            StringBuilder gram = new StringBuilder();
            for (int end = i + 1; end <= Math.min(tokens.length, i + MENTION_MAX_TOKENS); end++) {
                if (end > i + 1) {
                    gram.append(' ');
                }
                gram.append(tokens[end - 1]);
                String key = gram.toString();
                Candidate hit = freeTextHit(key, end - i, kinds);
                if (hit != null) {
                    best = hit;
                    bestEnd = end;
                }
                if (Arrays.binarySearch(prefixes, hash(key)) < 0) {
                    break;
                }
            }
            if (best != null) {
                out.add(new Mention(i, bestEnd, best));
                i = bestEnd;
            } else {
                i++;
            }
        }
        return out;
    }

    private Candidate freeTextHit(String key, int tokenCount, List<EntityKind> kinds) {
        if (tokenCount == 1 && (GRAMMAR.contains(key) || COLLIDES.contains(key) || key.length() < 3)) {
            return null;
        }
        Map<Long, Candidate> found = new LinkedHashMap<>();
        List<Entity> aliased = aliases.get(key);
        if (aliased != null) {
            addAliases(aliased, kinds, ALIAS, "alias", found);
        }
        if (found.isEmpty() && tokenCount >= 2) {
            addExact(key, kinds, kinds, EXACT, "exact", found);
        }
        return found.isEmpty() ? null : finish(key, found).best().orElse(null);
    }

    /** The job branch a class word names ("sin", "fp", "night lord"), if any. */
    Optional<AliasCatalog.JobBranch> jobWord(String word) {
        return Optional.ofNullable(jobWords.get(normalize(word)));
    }

    /** Every class word, normalized, so the detector can find "22 sin" in a training question. */
    Set<String> jobWords() {
        return jobWords.keySet();
    }

    /** The explorer branch of a job id (100s warrior .. 500s pirate), or empty for beginners and other trees. */
    Optional<AliasCatalog.JobBranch> branchOf(int jobId) {
        int branch = jobId < 1000 ? jobId / 100 : 0;
        String name = switch (branch) {
            case 1 -> "warrior";
            case 2 -> "magician";
            case 3 -> "bowman";
            case 4 -> "thief";
            case 5 -> "pirate";
            default -> null;
        };
        for (AliasCatalog.JobBranch job : jobs) {
            if (job.branch().equals(name)) {
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    /** The composite of this kind, name and first id (guide refs: composite ids shift when aliases.yaml changes). */
    Optional<Entity> composite(EntityKind kind, String name, int firstId) {
        for (Entity composite : composites) {
            if (composite.kind() == kind && composite.name().equalsIgnoreCase(name) && !composite.ids().isEmpty()
                    && composite.ids().getInt(0) == firstId) {
                return Optional.of(composite);
            }
        }
        return Optional.empty();
    }

    /** The entity for a game id (ITEM..QUEST) or a composite (REGION/PQ/ROLE), with its spoken name. */
    public Optional<Entity> entity(EntityKind kind, int id) {
        if (kind == EntityKind.REGION || kind == EntityKind.PQ || kind == EntityKind.ROLE) {
            return id >= 1 && id <= composites.size() && composites.get(id - 1).kind() == kind
                    ? Optional.of(composites.get(id - 1)) : Optional.empty();
        }
        String name = switch (kind) {
            case ITEM -> knowledge.item(id).map(ItemFact::name).orElse(null);
            case MOB -> knowledge.mob(id).map(MobFact::name).orElse(null);
            case MAP -> knowledge.map(id).map(MapFact::name).orElse(null);
            case NPC -> knowledge.npc(id).map(NpcFact::name).orElse(null);
            case QUEST -> knowledge.quest(id).map(QuestFact::name).orElse(null);
            default -> null;
        };
        return name == null ? Optional.empty() : Optional.of(Entity.simple(kind, id, name, say(kind, id, name)));
    }

    /** The spoken name of a game id: the alias {@code say}, the scroll grammar, else the cleaned WZ name. */
    public String say(EntityKind kind, int id) {
        return entity(kind, id).map(Entity::say).orElse("");
    }

    /**
     * Whether a phrase reads like a game name: every word is a word some name or alias uses, and it is two
     * words or one of five letters or more ("zakum helmet" yes; "gf", "a life", "pet" no). Unresolved
     * questions about such phrases get an honest "no clue"; the rest stay silent.
     */
    public boolean nameLike(String phrase) {
        String[] tokens = stripArticles(normalize(phrase)).split(" ");
        for (String token : tokens) {
            if (Arrays.binarySearch(vocab, token) < 0) {
                return false;
            }
        }
        return tokens.length >= 2 || tokens[0].length() >= FUZZY_MIN_LENGTH;
    }

    /** Whether aliases.yaml names the entity (a "famous" thing players talk about). */
    public boolean aliased(EntityKind kind, int id) {
        return says.containsKey(key(kind, id));
    }

    /** The spoken role ("potion shop", "storage") of an NPC, when aliases.yaml has a role it does. */
    public Optional<String> roleOf(int npcId) {
        for (Entity composite : composites) {
            if (composite.kind() == EntityKind.ROLE && composite.ids().contains(npcId)) {
                return Optional.of(composite.say());
            }
        }
        return Optional.empty();
    }

    /** The authored area or numbered series ("orbis tower", "ant tunnel") a map belongs to. */
    public Optional<Entity> seriesOf(int mapId) {
        return Optional.ofNullable(seriesByMap.get(mapId));
    }

    /** Every aliases.yaml party quest, in file order. */
    public List<Entity> pqs() {
        List<Entity> out = new ArrayList<>();
        for (Entity composite : composites) {
            if (composite.kind() == EntityKind.PQ) {
                out.add(composite);
            }
        }
        return out;
    }

    /** The party quest whose lobby (aliases.yaml pq ids[0]) the map is; empty for any other map. */
    public Optional<Entity> pqAtLobby(int mapId) {
        return Optional.ofNullable(pqByLobby.get(mapId));
    }

    /** The party quest whose instance the map is part of (a stage, the bonus, the exit); empty for its lobby and the rest. */
    public Optional<Entity> pqOf(int mapId) {
        return Optional.ofNullable(pqInstances.get(mapId));
    }

    /** The cleaned WZ name of a map ("kerning city"), for stripping a town's name off its interiors. */
    String wzSay(int mapId) {
        return knowledge.map(mapId).map(map -> cleanSay(map.name())).orElse("");
    }

    int compositeCount() {
        return composites.size();
    }

    int nameCount() {
        return keys.length;
    }

    /** Rough retained size for the boot log and {@code !knowledge status}. */
    long approxBytes() {
        long bytes = (long) (keyTokenIds.length + keyTokenStart.length + postingIds.length + postingStart.length) * Integer.BYTES
                + (long) prefixes.length * Long.BYTES;
        for (String key : keys) {
            bytes += STRING_BYTES + key.length();
        }
        for (long[] refs : keyRefs) {
            bytes += ARRAY_BYTES + (long) refs.length * Long.BYTES;
        }
        return bytes + (long) vocab.length * STRING_BYTES + (long) (aliases.size() + says.size() + derivedSays.size()) * ENTRY_BYTES;
    }

    private long[] refsOf(String text) {
        int index = Arrays.binarySearch(keys, text);
        return index < 0 ? null : keyRefs[index];
    }

    /** Growable {@code int[]} for building the flat arrays. */
    private static final class IntListBuilder {
        private int[] values = new int[1024];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        void addAll(int[] more) {
            for (int value : more) {
                add(value);
            }
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    int aliasCount() {
        return aliases.size();
    }

    // ---- internals --------------------------------------------------------------------------

    private String say(EntityKind kind, int id, String name) {
        String authored = says.get(key(kind, id));
        if (authored != null) {
            return authored;
        }
        return derivedSays.computeIfAbsent(key(kind, id), k -> derivedSay(kind, id, name));
    }

    private String derivedSay(EntityKind kind, int id, String name) {
        if (kind == EntityKind.ITEM && scrollSay.containsKey(id)) {
            return scrollSay.get(id);
        }
        if (kind == EntityKind.MAP && pqByMap.containsKey(id)) {
            return pqByMap.get(id).say(); // "1st Accompaniment <1st Stage>" is just "kpq"
        }
        if (kind == EntityKind.MAP) {
            return placeSay(name, knowledge.map(id).map(MapFact::street).orElse(""));
        }
        String clean = cleanSay(name);
        return kind == EntityKind.MOB && !knowledge.mob(id).map(MobFact::boss).orElse(false) ? plural(clean) : clean;
    }

    /**
     * A map's spoken name; its street when the name alone is generic words ("kerning city subway", not "line"; "kerning
     * square", not "1st floor 2nd floor area b").
     */
    static String placeSay(String name, String street) {
        String clean = cleanSay(name);
        if (!GENERIC_NAME.matcher(clean).matches()) {
            return clean;
        }
        String spoken = street == null || street.isBlank() ? "" : cleanSay(street);
        return spoken.isEmpty() || GENERIC_PLACE.contains(spoken) ? clean : spoken;
    }

    private static int itemRank(ItemFact item) {
        return item.cash() ? 2 : 0;
    }

    private void addName(Map<String, List<long[]>> refs, String name, EntityKind kind, int id, int rank) {
        String key = normalize(name);
        if (key.isEmpty() || key.equals(MISSING_NAME)) {
            return;
        }
        if (kind == EntityKind.ITEM && rank == 0 && knowledge.dropSources(id).isEmpty() && knowledge.shopsSelling(id).isEmpty()) {
            rank = 1; // non-cash but no source: behind a non-cash copy that drops or sells
        }
        refs.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[]{key(kind, id), rank});
    }

    private Entity aliasEntity(AliasCatalog.Alias alias) {
        return switch (alias.kind()) {
            case ITEM, MOB, MAP, NPC, QUEST -> entity(alias.kind(), alias.ids().getInt(0))
                    .map(e -> new Entity(e.kind(), e.id(), e.name(), alias.say(), alias.ids(), 0, 0, 0)).orElse(null);
            case REGION, PQ -> composite(new Entity(alias.kind(), composites.size() + 1,
                    knowledge.map(alias.ids().getInt(0)).map(MapFact::name).orElse(alias.say()), alias.say(), alias.ids(),
                    alias.npc(), alias.minLevel(), alias.maxLevel()));
            case ROLE -> {
                IntList npcs = roleNpcs(alias);
                yield npcs.isEmpty() ? null : composite(new Entity(EntityKind.ROLE, composites.size() + 1, alias.say(),
                        alias.say(), npcs, 0, 0, 0));
            }
        };
    }

    private Entity composite(Entity entity) {
        composites.add(entity);
        return entity;
    }

    /** NPCs standing on a map that do the role's job: by function text, a name token, or what their shop sells. */
    private IntList roleNpcs(AliasCatalog.Alias role) {
        Set<String> funcs = new HashSet<>();
        role.funcs().forEach(func -> funcs.add(func.toLowerCase(Locale.ROOT)));
        Set<String> nameTokens = new HashSet<>();
        role.nameHas().forEach(token -> nameTokens.add(normalize(token)));
        TreeSet<Integer> out = new TreeSet<>();
        for (NpcFact npc : knowledge.npcs()) {
            if (knowledge.npcMaps(npc.id()).isEmpty()) {
                continue;
            }
            boolean byFunc = funcs.contains(npc.func().toLowerCase(Locale.ROOT));
            boolean byName = false;
            for (String token : normalize(npc.name()).split(" ")) {
                byName |= nameTokens.contains(token);
            }
            if (byFunc || byName) {
                out.add(npc.id());
            }
        }
        if (role.sells() > 0) {
            for (ShopSource shop : knowledge.shopsSelling(role.sells())) {
                if (!shop.npcMaps().isEmpty()) {
                    out.add(shop.npcId());
                }
            }
        }
        return IntList.copyOf(out);
    }

    private void indexScrolls() {
        Map<String, List<Integer>> bySlotStatPct = new TreeMap<>();
        Map<String, TreeSet<Integer>> pcts = new TreeMap<>();
        for (ItemFact item : knowledge.items()) {
            if (item.id() < SCROLL_FIRST || item.id() > SCROLL_LAST || item.success() <= 0 || item.cash()) {
                continue;
            }
            Matcher name = SCROLL_NAME.matcher(normalize(item.name()));
            if (!name.matches()) {
                continue;
            }
            String slot = SCROLL_SLOTS.get(name.group(1));
            String stat = SCROLL_STATS.get(name.group(2));
            if (slot == null || stat == null) {
                continue;
            }
            bySlotStatPct.computeIfAbsent(slot + "|" + stat + "|" + item.success(), k -> new ArrayList<>()).add(item.id());
            pcts.computeIfAbsent(slot + "|" + stat, k -> new TreeSet<>()).add(item.success());
            scrollSay.put(item.id(), SLOT_SAY.getOrDefault(slot, slot) + " " + stat + " " + item.success());
        }
        bySlotStatPct.forEach((key, ids) -> {
            ids.sort(Comparator.comparingInt((Integer id) -> knowledge.dropSources(id).isEmpty()
                    && knowledge.shopsSelling(id).isEmpty() ? 1 : 0).thenComparingInt(id -> id));
            scrolls.put(key, ids.stream().mapToInt(Integer::intValue).toArray());
        });
        pcts.forEach((key, set) -> scrollPcts.put(key, set.stream().sorted(Comparator.reverseOrder())
                .mapToInt(Integer::intValue).toArray()));
    }

    /** "glove att 60", "60% g att", "gatt 60", "helm def": slot + stat (+ %), nothing else. */
    private void scrollCandidates(String text, List<EntityKind> kinds, Map<Long, Candidate> found) {
        String[] tokens = text.split(" ");
        String slot = null;
        String stat = null;
        int pct = -1;
        int i = 0;
        while (i < tokens.length) {
            String token = tokens[i];
            String[] fused = SCROLL_FUSED.get(token);
            if (fused != null && slot == null && stat == null) {
                slot = fused[0];
                stat = fused[1];
                i++;
                continue;
            }
            if (token.endsWith("%") || token.chars().allMatch(Character::isDigit)) {
                int value = parsePct(token);
                if (value < 1 || value > 100 || pct >= 0) {
                    return;
                }
                pct = value;
                i++;
                continue;
            }
            if (SCROLL_FILLERS.contains(token)) {
                i++;
                continue;
            }
            int consumed = 0;
            for (int width = Math.min(3, tokens.length - i); width >= 1 && consumed == 0; width--) {
                String phrase = String.join(" ", Arrays.asList(tokens).subList(i, i + width));
                if (slot == null && SCROLL_SLOTS.containsKey(phrase)) {
                    slot = SCROLL_SLOTS.get(phrase);
                    consumed = width;
                } else if (stat == null && SCROLL_STATS.containsKey(phrase)) {
                    stat = SCROLL_STATS.get(phrase);
                    consumed = width;
                }
            }
            if (consumed == 0) {
                return;
            }
            i += consumed;
        }
        if (slot == null || stat == null) {
            return;
        }
        if (pct >= 0) {
            int[] ids = scrolls.get(slot + "|" + stat + "|" + pct);
            if (ids != null) {
                add(found, entity(EntityKind.ITEM, ids[0]).orElse(null), ALIAS * kindFactor(kinds, EntityKind.ITEM), "scroll");
            }
            return;
        }
        int[] offered = scrollPcts.get(slot + "|" + stat);
        if (offered != null) {
            for (int value : offered) {
                int[] ids = scrolls.get(slot + "|" + stat + "|" + value);
                add(found, entity(EntityKind.ITEM, ids[0]).orElse(null), SCROLL_ANY_PCT * kindFactor(kinds, EntityKind.ITEM), "scroll");
            }
        }
    }

    private static int parsePct(String token) {
        try {
            return Integer.parseInt(token.endsWith("%") ? token.substring(0, token.length() - 1) : token);
        } catch (NumberFormatException notNumber) {
            return -1;
        }
    }

    /** An alias fixes the id for its kind, so the same name's other ids of that kind are not candidates. */
    private void exactAndAlias(String text, List<EntityKind> kinds, double aliasScore, double exactScore, String how,
                               Map<Long, Candidate> found) {
        List<Entity> aliased = aliases.get(text);
        List<EntityKind> rest = kinds;
        if (aliased != null) {
            addAliases(aliased, kinds, aliasScore, how == null ? "alias" : how, found);
            rest = new ArrayList<>(kinds);
            for (Entity entity : aliased) {
                rest.remove(entity.kind());
            }
        }
        if (!(text.indexOf(' ') < 0 && GRAMMAR.contains(text))) {
            addExact(text, kinds, rest, exactScore, how == null ? "exact" : how, found);
        }
    }

    private void addAliases(List<Entity> aliased, List<EntityKind> kinds, double score, String how, Map<Long, Candidate> found) {
        for (Entity entity : aliased) {
            if (kinds.contains(entity.kind())) {
                add(found, entity, score * kindFactor(kinds, entity.kind()), how);
            }
        }
    }

    private void addExact(String text, List<EntityKind> kinds, List<EntityKind> allowed, double score, String how,
                          Map<Long, Candidate> found) {
        long[] refs = refsOf(text);
        if (refs == null) {
            return;
        }
        for (EntityKind kind : kinds) {
            if (!allowed.contains(kind)) {
                continue;
            }
            for (long ref : refs) {
                if (ref >>> 32 == kind.ordinal()) {
                    Entity entity = entityOf(ref);
                    if (entity != null) {
                        add(found, entity, score * kindFactor(kinds, kind) * quality(entity), how);
                    }
                    return; // one name: only the first allowed kind, canonical id first
                }
            }
        }
    }

    /** Every query token in the name, the last one as a prefix; score {@code scale x (0.6 + 0.3 x coverage)}. */
    private void subset(String text, List<EntityKind> kinds, double scale, Map<Long, Candidate> found) {
        String[] query = text.split(" ");
        boolean meaningful = false;
        for (String token : query) {
            meaningful |= !GRAMMAR.contains(token) && !token.chars().allMatch(Character::isDigit);
        }
        if (!meaningful) {
            return;
        }
        int[] exactIds = new int[query.length - 1];
        int smallest = -1;
        for (int i = 0; i < query.length - 1; i++) {
            exactIds[i] = Arrays.binarySearch(vocab, query[i]);
            if (exactIds[i] < 0) {
                return;
            }
            if (smallest < 0 || postingCount(exactIds[i]) < postingCount(exactIds[smallest])) {
                smallest = i;
            }
        }
        String last = query[query.length - 1];
        int lo = lowerBound(vocab, last);
        // the last word may be a prefix ("zakum helm"), except when it is the only word ("beat" is not "beat shirt")
        int hi = query.length == 1 ? (lo < vocab.length && vocab[lo].equals(last) ? lo + 1 : lo)
                : lowerBound(vocab, last + Character.MAX_VALUE);
        if (lo >= hi) {
            return;
        }
        int[] scan;
        if (smallest >= 0) {
            scan = Arrays.copyOfRange(postingIds, postingStart[exactIds[smallest]], postingStart[exactIds[smallest] + 1]);
        } else {
            TreeSet<Integer> union = new TreeSet<>();
            for (int v = lo; v < hi && union.size() <= SUBSET_SCAN_MAX; v++) {
                for (int p = postingStart[v]; p < postingStart[v + 1]; p++) {
                    if (hasKind(keyRefs[postingIds[p]], kinds)) {
                        union.add(postingIds[p]); // "zakum" as a map: 3 maps, not the dozen zakum items too
                    }
                }
            }
            if (union.size() > SUBSET_SINGLE_MAX) {
                return; // one common word ("helm", "sword"): too vague to pick from
            }
            scan = union.stream().mapToInt(Integer::intValue).toArray();
        }
        if (scan.length > SUBSET_SCAN_MAX) {
            return;
        }
        List<Candidate> hits = new ArrayList<>();
        for (int k : scan) {
            int from = keyTokenStart[k];
            int to = keyTokenStart[k + 1];
            boolean all = true;
            for (int id : exactIds) {
                all &= Arrays.binarySearch(keyTokenIds, from, to, id) >= 0;
            }
            boolean prefix = false;
            for (int t = from; t < to; t++) {
                prefix |= keyTokenIds[t] >= lo && keyTokenIds[t] < hi;
            }
            if (!all || !prefix) {
                continue;
            }
            double coverage = Math.min(1d, (double) query.length / (to - from));
            long[] refs = keyRefs[k];
            for (EntityKind kind : kinds) {
                Entity entity = null;
                for (long ref : refs) {
                    if (ref >>> 32 == kind.ordinal()) {
                        entity = entityOf(ref);
                        break;
                    }
                }
                if (entity != null) {
                    hits.add(new Candidate(entity, scale * (SUBSET_BASE + SUBSET_SPAN * coverage) * kindFactor(kinds, kind)
                            * quality(entity), (scale < 1d ? "fuzzy " : "") + (coverage < PARTIAL - EPSILON ? "partial" : "subset")));
                    break;
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Candidate::score).reversed());
        if (query.length == 1) {
            hits.removeIf(hit -> hit.score() < SUBSET_BASE * scale); // one word on a cash item is never a confident match
        }
        for (int i = 0; i < hits.size() && i < MAX_CANDIDATES; i++) {
            add(found, hits.get(i).entity(), hits.get(i).score(), hits.get(i).how());
        }
    }

    /** One edit (insert, delete, substitute, swap) on each unknown token of 5+ letters; null when nothing changes. */
    private String correct(String text) {
        String[] tokens = text.split(" ");
        boolean changed = false;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < FUZZY_MIN_LENGTH || Arrays.binarySearch(vocab, token) >= 0) {
                continue;
            }
            String best = null;
            for (int n = token.length() - 1; n <= token.length() + 1; n++) {
                if (n < 0 || n >= vocabByLength.length) {
                    continue;
                }
                for (String word : vocabByLength[n]) {
                    if (word.charAt(0) == token.charAt(0) && withinOneEdit(token, word)) {
                        if (best == null || postingsOf(word) > postingsOf(best)) {
                            best = word;
                        }
                    }
                }
            }
            if (best != null) {
                tokens[i] = best;
                changed = true;
            }
        }
        return changed ? String.join(" ", tokens) : null;
    }

    private int postingsOf(String word) {
        int index = Arrays.binarySearch(vocab, word);
        return index < 0 ? 0 : postingCount(index);
    }

    private static boolean hasKind(long[] refs, List<EntityKind> kinds) {
        for (long ref : refs) {
            if (kinds.contains(EntityKind.values()[(int) (ref >>> 32)])) {
                return true;
            }
        }
        return false;
    }

    private int postingCount(int vocabId) {
        return postingStart[vocabId + 1] - postingStart[vocabId];
    }

    static boolean withinOneEdit(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > 1) {
            return false;
        }
        int i = 0;
        while (i < n && i < m && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (i == n && i == m) {
            return false; // identical is not an edit
        }
        if (n == m) {
            if (a.substring(i + 1).equals(b.substring(i + 1))) {
                return true;
            }
            return i + 1 < n && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                    && a.substring(i + 2).equals(b.substring(i + 2));
        }
        return n < m ? a.substring(i).equals(b.substring(i + 1)) : a.substring(i + 1).equals(b.substring(i));
    }

    private Resolution finish(String text, Map<Long, Candidate> found) {
        return ranked(text, new ArrayList<>(found.values()));
    }

    private static Resolution ranked(String text, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return new Resolution(text, List.of(), false);
        }
        List<Candidate> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(Candidate::score).reversed());
        if (list.size() > MAX_CANDIDATES) {
            list = list.subList(0, MAX_CANDIDATES);
        }
        Entity first = list.get(0).entity();
        Entity second = list.size() > 1 ? list.get(1).entity() : null;
        boolean tie = second != null && list.get(0).score() - list.get(1).score() < TIE - EPSILON && first.kind() == second.kind()
                && !first.say().equals(second.say()) && !first.name().equals(second.name());
        return new Resolution(text, List.copyOf(list), tie);
    }

    private static void add(Map<Long, Candidate> found, Entity entity, double score, String how) {
        if (entity == null) {
            return;
        }
        Candidate previous = found.get(entity.key());
        if (previous == null || previous.score() < score) {
            found.put(entity.key(), new Candidate(entity, score, how));
        }
    }

    private Entity entityOf(long ref) {
        return entity(EntityKind.values()[(int) (ref >>> 32)], (int) ref).orElse(null);
    }

    private double quality(Entity entity) {
        if (entity.kind() == EntityKind.ITEM) {
            return knowledge.item(entity.id()).map(item -> item.cash() ? CASH : 1d).orElse(1d);
        }
        if (entity.kind() == EntityKind.MOB && entity.id() >= EVENT_MOB_ID && knowledge.spawnMaps(entity.id()).isEmpty()) {
            return EVENT_COPY;
        }
        return 1d;
    }

    private static double kindFactor(List<EntityKind> kinds, EntityKind kind) {
        return 1d - KIND_STEP * Math.max(0, kinds.indexOf(kind));
    }

    private static String stripArticles(String text) {
        String out = text;
        for (String article : new String[]{"the ", "a ", "an ", "some ", "any "}) {
            if (out.startsWith(article) && out.length() > article.length()) {
                out = out.substring(article.length());
            }
        }
        return out;
    }

    private static long key(EntityKind kind, int id) {
        return (long) kind.ordinal() << 32 | (id & 0xffffffffL);
    }

    private static int lowerBound(String[] sorted, String value) {
        int index = Arrays.binarySearch(sorted, value);
        return index >= 0 ? index : -index - 1;
    }

    private static void addPrefixes(Set<Long> grams, String key) {
        int space = key.indexOf(' ');
        while (space > 0) {
            grams.add(hash(key.substring(0, space)));
            space = key.indexOf(' ', space + 1);
        }
    }

    /** 64-bit FNV-1a: the prefix set stores hashes, not strings (a few hundred KB instead of MBs). */
    private static long hash(String text) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static Map<String, String> phraseMap(String... pairs) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return Map.copyOf(out);
    }
}
