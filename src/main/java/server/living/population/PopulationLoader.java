package server.living.population;

import server.knowledge.EntityKind;
import server.knowledge.KnowledgeLint;
import server.living.config.Content;
import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.config.Node;
import server.living.population.PopulationContent.Career;
import server.living.population.PopulationContent.Cohort;
import server.living.population.PopulationContent.EmblemColor;
import server.living.population.PopulationContent.EmblemWord;
import server.living.population.PopulationContent.Alliances;
import server.living.population.PopulationContent.Emblems;
import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.GuildNames;
import server.living.population.PopulationContent.Intent;
import server.living.population.PopulationContent.LoadoutBand;
import server.living.population.PopulationContent.Lobby;
import server.living.population.PopulationContent.NamePattern;
import server.living.population.PopulationContent.Names;
import server.living.population.PopulationContent.Region;
import server.living.population.PopulationContent.Voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads the population content directory: cohorts.yaml, careers.yaml, names.yaml, voices.yaml,
 * guild-names.yaml, regions.yaml, fields.yaml and chatter/*.yaml. Lint findings ride along as
 * warnings ({@code ERROR ...} lines fail {@code !citizens reload}); with server knowledge
 * ({@link KnowledgeLint#current()}) map and item ids, PQ lobbies, training fields and career gear are checked too.
 */
public final class PopulationLoader {
    private static final Set<String> TRIGGERS = Set.of("enter", "dwell", "levelup.other", "levelup.self", "death.other",
            "death.self", "drop.rare", "drop.rare.self", "boss", "jobadvance.other", "jobadvance.self", "jobadvance.offscreen.self", "login", "logout", "login.buddy",
            "smega", "party.forming", "party.invite", "price", "afk", "back", "lag",
            // party quests citizens run (CitizenPartyQuests)
            "pq.lf", "pq.join", "pq.go", "pq.clear", "pq.fail", "pq.down", "pq.giveup", "pq.call", "pq.call.answer",
            "pq.thanks", "pq.wait",
            // boss expeditions citizens run (CitizenPartyQuests with CitizenBossDoor)
            "boss.lf", "boss.join", "boss.signup", "boss.go", "boss.clear", "boss.fail", "boss.down", "boss.giveup",
            "boss.broke", "boss.call", "boss.call.answer", "boss.thanks", "boss.wait",
            // the people citizens remember (PersonMemories): meeting again, and a "hi" from them
            "memory.greet.pq", "memory.greet.pqfail", "memory.greet.party", "memory.greet.bought", "memory.greet.sold",
            "memory.greet.famed", "memory.greet.helped", "memory.greet.talk", "memory.greet.warm", "memory.greet.vague",
            "memory.greet.cool", "memory.hello", "memory.hello.cool",
            // minigames citizens play in towns (CitizenMiniGames): wanting a game, room titles, room chat
            "game.lf", "game.title.omok", "game.title.cards", "game.hi", "game.nice", "game.win", "game.loss", "game.tie",
            "game.bye",
            // a citizen asking after a friend who is away (PersonMemories.askAfter, the letters)
            "memory.asked");
    /** The triggers PersonMemories speaks with: each needs at least one map line, or that greeting is never said. */
    static final Set<String> MEMORY_TRIGGERS = Set.of("memory.greet.pq", "memory.greet.pqfail", "memory.greet.party",
            "memory.greet.bought", "memory.greet.sold", "memory.greet.famed", "memory.greet.helped", "memory.greet.talk",
            "memory.greet.warm", "memory.greet.vague", "memory.greet.cool", "memory.hello", "memory.hello.cool",
            "memory.asked");
    private static final Set<String> WHERE = Set.of("town", "field", "fm", "lobby", "any");
    private static final Set<String> CHANNELS = Set.of("map", "party", "guild", "buddy", "smega", "room");

    private PopulationLoader() {
    }

    public static PopulationContent load(Path root, LivingContent living) {
        if (!Files.isDirectory(root)) {
            throw new ContentException("population content directory not found: " + root.toAbsolutePath());
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Cohort> cohorts = new LinkedHashMap<>();
        for (Node node : listOf(root.resolve("cohorts.yaml"), "cohorts")) {
            Cohort cohort = cohort(node);
            put(cohorts, cohort.id(), cohort, node);
        }
        Map<String, Career> careers = new LinkedHashMap<>();
        for (Node node : listOf(root.resolve("careers.yaml"), "careers")) {
            Career career = career(node);
            put(careers, career.id(), career, node);
        }
        Names names = names(root.resolve("names.yaml"));
        Map<String, Voice> voices = new LinkedHashMap<>();
        for (Node node : listOf(root.resolve("voices.yaml"), "voices")) {
            Voice voice = voice(node);
            put(voices, voice.id(), voice, node);
        }
        GuildNames guildNames = guildNames(root.resolve("guild-names.yaml"));
        Map<String, Region> regions = new LinkedHashMap<>();
        for (Node node : listOf(root.resolve("regions.yaml"), "regions")) {
            Region region = region(node);
            put(regions, region.id(), region, node);
        }
        Map<Integer, Field> fields = new LinkedHashMap<>();
        for (Node node : listOf(root.resolve("fields.yaml"), "fields")) {
            Field field = new Field(node.get("mapId").integer(), node.get("name").string("map"), node.get("street").string(""),
                    node.get("region").string("victoria"), node.get("town").bool(false), node.get("mobs").integer(0),
                    node.get("mobLevel").integer(0), node.get("capacity").integer(0));
            fields.put(field.mapId(), field);
        }
        List<Intent> intents = new ArrayList<>();
        Path chatter = root.resolve("chatter");
        if (Files.isDirectory(chatter)) {
            try (Stream<Path> files = Files.list(chatter)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                    for (Node node : ContentLoader.read(file).get("intents").items()) {
                        intents.add(intent(node, file.getFileName().toString()));
                    }
                }
            } catch (IOException failure) {
                throw new ContentException("cannot list " + chatter, failure);
            }
        }
        List<PopulationContent.Good> basket = new ArrayList<>();
        int permit = 5140000;
        Path market = root.resolve("market.yaml");
        if (Files.exists(market)) {
            Node node = ContentLoader.read(market);
            permit = node.get("permit").integer(permit);
            for (Node good : node.get("basket").items()) {
                basket.add(new PopulationContent.Good(good.get("itemId").integer(), good.get("price").longValue(1000L)));
            }
        }
        PopulationContent content = new PopulationContent(cohorts, careers, names, voices, guildNames, regions, fields, intents,
                basket, permit, warnings);
        warnings.addAll(lint(content, living));
        return new PopulationContent(cohorts, careers, names, voices, guildNames, regions, fields, intents, basket, permit, warnings);
    }

    static List<String> lint(PopulationContent content, LivingContent living) {
        KnowledgeLint knowledge = KnowledgeLint.current();
        List<String> out = new ArrayList<>();
        int share = 0;
        for (Cohort cohort : content.cohorts().values()) {
            share += cohort.share();
            if (cohort.weekday().length != 24 || cohort.weekend().length != 24) {
                out.add("ERROR cohorts.yaml " + cohort.id() + ": weekday/weekend need 24 hour weights");
            }
            if (cohort.sessionMin() < 5 || cohort.sessionMax() < cohort.sessionMin()) {
                out.add("ERROR cohorts.yaml " + cohort.id() + ": sessionMinutes must be a range >= 5");
            }
        }
        if (!content.cohorts().isEmpty() && share != 100) {
            out.add("WARN cohorts.yaml: shares sum to " + share + ", not 100");
        }
        Map<String, List<KnowledgeLint.Holder>> holders = new LinkedHashMap<>();
        for (Career career : content.careers().values()) {
            if (server.living.resident.ResidentShaper.job(career.job()) == client.Job.BEGINNER && !"BEGINNER".equalsIgnoreCase(career.job())) {
                out.add("ERROR careers.yaml " + career.id() + ": unknown job '" + career.job() + "'");
            }
            int from = 1;
            for (LoadoutBand band : career.loadouts()) {
                if (living != null && living.loadout(band.loadout()) == null) {
                    out.add("ERROR careers.yaml " + career.id() + ": loadout '" + band.loadout() + "' not in living-world loadouts.yaml");
                }
                holders.computeIfAbsent(band.loadout(), id -> new ArrayList<>())
                        .add(new KnowledgeLint.Holder("career " + career.id(), from, band.maxLevel()));
                from = band.maxLevel() + 1;
            }
        }
        if (knowledge != null && living != null) {
            for (Map.Entry<String, List<KnowledgeLint.Holder>> entry : holders.entrySet()) {
                Content.Loadout loadout = living.loadout(entry.getKey());
                if (loadout != null) {
                    knowledge.gear("careers.yaml loadout " + loadout.id(), loadout.equipIds(), loadout.outfit(), entry.getValue(), out);
                }
            }
        }
        if (content.names().patterns().isEmpty()) {
            out.add("ERROR names.yaml: no patterns");
        }
        int voiceShare = 0;
        for (Voice voice : content.voices().values()) {
            voiceShare += voice.share();
        }
        if (!content.voices().isEmpty() && voiceShare != 100) {
            out.add("WARN voices.yaml: shares sum to " + voiceShare + ", not 100");
        }
        for (Region region : content.regions().values()) {
            for (int town : region.towns()) {
                Field field = content.field(town);
                String label = knowledge == null ? String.valueOf(town) : knowledge.label(EntityKind.MAP, town);
                if (field == null) {
                    out.add("WARN regions.yaml " + region.id() + ": town map " + label + " not in fields.yaml");
                } else if (!field.town()) {
                    out.add("ERROR regions.yaml " + region.id() + ": map " + label + " is not a town");
                }
                if (knowledge != null) {
                    knowledge.require(EntityKind.MAP, town, "regions.yaml " + region.id() + " town", out);
                }
            }
            if (knowledge != null) {
                knowledge.require(EntityKind.MAP, region.fm(), "regions.yaml " + region.id() + " fm", out);
                for (Lobby lobby : region.lobbies()) {
                    String where = "regions.yaml " + region.id() + " lobby " + lobby.id();
                    if (knowledge.require(EntityKind.MAP, lobby.mapId(), where, out)) {
                        knowledge.lobby(lobby.id(), lobby.mapId(), where, out);
                    }
                }
            }
        }
        if (knowledge != null) {
            for (Field field : content.fields().values()) {
                // the TRAIN filter of FieldCatalog.trainingField: citizens are sent to train here
                if (knowledge.require(EntityKind.MAP, field.mapId(), "fields.yaml", out) && !field.town() && field.capacity() > 0
                        && field.mobLevel() > 0) {
                    knowledge.trainingField(field.mapId(), "fields.yaml", out);
                }
            }
            knowledge.require(EntityKind.ITEM, content.permitItemId(), "market.yaml permit", out);
            for (PopulationContent.Good good : content.basket()) {
                if (knowledge.require(EntityKind.ITEM, good.itemId(), "market.yaml basket", out)) {
                    knowledge.obtainable(good.itemId(), "market.yaml basket", out);
                }
            }
        }
        Set<String> intentIds = new java.util.HashSet<>();
        for (Intent intent : content.intents()) {
            String where = intent.file() + " " + intent.id();
            if (!intentIds.add(intent.id())) {
                out.add("ERROR " + where + ": duplicate intent id");
            }
            if (!TRIGGERS.contains(intent.trigger())) {
                out.add("ERROR " + where + ": unknown trigger '" + intent.trigger() + "'");
            }
            if (!WHERE.contains(intent.where())) {
                out.add("ERROR " + where + ": unknown where '" + intent.where() + "'");
            }
            if (!CHANNELS.contains(intent.channel())) {
                out.add("ERROR " + where + ": unknown channel '" + intent.channel() + "'");
            }
            if (intent.lines().isEmpty()) {
                out.add("ERROR " + where + ": no lines");
            }
            if (knowledge != null) {
                for (int mapId : intent.mapIds()) {
                    knowledge.require(EntityKind.MAP, mapId, where, out);
                }
            }
            for (String line : intent.lines()) {
                for (char c : line.toCharArray()) {
                    if (c > 0x7e) {
                        out.add("ERROR " + where + ": non-ASCII character in '" + line + "'");
                        break;
                    }
                }
            }
        }
        for (String trigger : MEMORY_TRIGGERS) {
            if (content.intents(trigger).stream().noneMatch(intent -> intent.channel().equals("map") && intent.where().equals("any"))) {
                out.add("WARN chatter: no map line for '" + trigger + "' that fits anywhere: citizens never say it");
            }
        }
        out.addAll(GuildEmblems.lint(content.guildNames().emblems()));
        return out;
    }

    // ---- readers -------------------------------------------------------------------------

    private static List<Node> listOf(Path file, String key) {
        if (!Files.exists(file)) {
            return List.of();
        }
        return ContentLoader.read(file).get(key).items();
    }

    private static Cohort cohort(Node node) {
        ZoneId zone;
        try {
            zone = ZoneId.of(node.get("zone").string("America/New_York"));
        } catch (DateTimeException invalid) {
            throw new ContentException(node.path() + ": bad zone");
        }
        return new Cohort(node.get("id").string(), zone, node.get("share").integer(0), node.get("peakOnlinePercent").integer(30),
                weights(node.get("weekday")), weights(node.get("weekend")),
                node.get("sessionMinutes").get("min").integer(60), node.get("sessionMinutes").get("max").integer(180),
                intMap(node.get("activities")));
    }

    private static int[] weights(Node node) {
        List<Integer> values = node.integers();
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(0, Math.min(10, values.get(i)));
        }
        return out;
    }

    private static Career career(Node node) {
        List<LoadoutBand> bands = new ArrayList<>();
        for (Node band : node.get("loadouts").items()) {
            bands.add(new LoadoutBand(band.get("maxLevel").integer(200), band.get("loadout").string()));
        }
        return new Career(node.get("id").string(), node.get("job").string("BEGINNER"), node.get("popularity").integer(1),
                node.get("levelCap").integer(70), bands);
    }

    private static Names names(Path file) {
        if (!Files.exists(file)) {
            return new Names(List.of(), Map.of(), List.of(), List.of(), List.of());
        }
        Node root = ContentLoader.read(file);
        Node syllables = root.get("syllables");
        return new Names(patterns(root.get("patterns")), stringLists(root.get("lists")),
                syllables.get("start").strings(), syllables.get("middle").strings(), syllables.get("end").strings());
    }

    private static List<NamePattern> patterns(Node node) {
        List<NamePattern> out = new ArrayList<>();
        for (Node item : node.items()) {
            out.add(new NamePattern(item.get("pattern").string(), item.get("weight").integer(1)));
        }
        return out;
    }

    private static Voice voice(Node node) {
        return new Voice(node.get("id").string(), node.get("style").string("relaxed-v1"), node.get("share").integer(0),
                node.get("lowercase").decimal(0.5), node.get("typoRate").decimal(0.0), node.get("abbreviationRate").decimal(0.0),
                stringMap(node.get("abbreviations")), node.get("emoticons").strings(), node.get("emoticonRate").decimal(0.0),
                node.get("petPhrases").strings(), node.get("petPhraseRate").decimal(0.0), node.get("delayMsPerChar").integer(50),
                node.get("ignoreRate").decimal(0.1));
    }

    private static GuildNames guildNames(Path file) {
        if (!Files.exists(file)) {
            return new GuildNames(List.of(), Map.of(), List.of(), Emblems.none(), Alliances.none());
        }
        Node root = ContentLoader.read(file);
        return new GuildNames(patterns(root.get("patterns")), stringLists(root.get("lists")), root.get("mottos").strings(),
                emblems(root.get("emblems")), alliances(root.get("alliances")));
    }

    private static Alliances alliances(Node node) {
        if (node.isMissing()) {
            return Alliances.none();
        }
        return new Alliances(patterns(node.get("patterns")), stringLists(node.get("lines")));
    }

    private static Emblems emblems(Node node) {
        if (node.isMissing()) {
            return Emblems.none();
        }
        Map<Integer, EmblemColor> colors = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : node.get("colors").entries().entrySet()) {
            Node color = entry.getValue();
            int id;
            try {
                id = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException invalid) {
                throw new ContentException(color.path() + ": a colour id must be a whole number");
            }
            colors.put(id, new EmblemColor(id, color.get("name").string(), rgb(color.get("background")),
                    rgb(color.get("mark"))));
        }
        Map<String, EmblemWord> words = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : node.get("words").entries().entrySet()) {
            Node word = entry.getValue();
            words.put(entry.getKey().toLowerCase(Locale.ROOT), new EmblemWord(word.get("marks").integers(),
                    word.get("colors").strings(), word.get("background").integer(0)));
        }
        return new Emblems(node.get("minContrast").decimal(3.0d), colors, node.get("backgrounds").integers(),
                node.get("defaultColors").strings(), words);
    }

    private static int rgb(Node node) {
        String raw = node.string();
        if (!raw.matches("#[0-9a-fA-F]{6}")) {
            throw new ContentException(node.path() + " must be a #rrggbb colour, got '" + raw + "'");
        }
        return Integer.parseInt(raw.substring(1), 16);
    }

    private static Region region(Node node) {
        List<Lobby> lobbies = new ArrayList<>();
        for (Node lobby : node.get("lobbies").items()) {
            lobbies.add(new Lobby(lobby.get("id").string(), lobby.get("mapId").integer(), lobby.get("levels").get("min").integer(1),
                    lobby.get("levels").get("max").integer(200), lobby.get("name").string(lobby.get("id").string().toUpperCase(Locale.ROOT))));
        }
        Node townWeights = node.get("townWeights");
        return new Region(node.get("id").string(), node.get("levels").get("min").integer(1), node.get("levels").get("max").integer(200),
                node.get("towns").integers(), townWeights.isMissing() ? List.of() : townWeights.integers(),
                lobbies, node.get("fm").integer(910000000), node.get("weight").integer(1),
                node.get("line").isMissing() ? null : node.get("line").string());
    }

    private static Intent intent(Node node, String file) {
        Map<String, List<String>> styles = new LinkedHashMap<>();
        Node styleNode = node.get("styles");
        if (styleNode.isMap()) {
            for (String key : styleNode.keys()) {
                styles.put(key, styleNode.get(key).strings());
            }
        }
        return new Intent(node.get("id").string(), node.get("trigger").string("dwell"), node.get("where").string("any"),
                node.get("mapIds").integers(), node.get("channel").string("map"), node.get("weight").integer(5),
                node.get("cooldownMs").longValue(300_000L), node.get("lines").strings(), styles, file, node.get("maxLevel").integer(0));
    }

    private static Map<String, Integer> intMap(Node node) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (node.isMap()) {
            for (String key : node.keys()) {
                out.put(key.toUpperCase(Locale.ROOT), node.get(key).integer(0));
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(Node node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isMap()) {
            for (String key : node.keys()) {
                out.put(key, node.get(key).string(""));
            }
        }
        return out;
    }

    private static Map<String, List<String>> stringLists(Node node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node.isMap()) {
            for (String key : node.keys()) {
                out.put(key, node.get(key).strings());
            }
        }
        return out;
    }

    private static <T> void put(Map<String, T> target, String id, T value, Node node) {
        if (id == null || id.isBlank()) {
            throw new ContentException(node.path() + ": id is required");
        }
        if (target.putIfAbsent(id, value) != null) {
            throw new ContentException(node.path() + ": duplicate id '" + id + "'");
        }
    }
}
