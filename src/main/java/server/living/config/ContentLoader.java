package server.living.config;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import server.living.config.Content.Arc;
import server.living.config.Content.ArcAdvance;
import server.living.config.Content.ArcGate;
import server.living.config.Content.BondStage;
import server.living.config.Content.Chapter;
import server.living.config.Content.ChapterGate;
import server.living.config.Content.Contract;
import server.living.config.Content.ContractItem;
import server.living.config.Content.DropRule;
import server.living.config.Content.ItemStack;
import server.living.config.Content.KillProof;
import server.living.config.Content.LeaveRule;
import server.living.config.Content.LevelBand;
import server.living.config.Content.Loadout;
import server.living.config.Content.LootShare;
import server.living.config.Content.OfferWhen;
import server.living.config.Content.Persona;
import server.living.config.Content.Phrase;
import server.living.config.Content.Price;
import server.living.config.Content.Quest;
import server.living.config.Content.QuestLength;
import server.living.config.Content.Relationship;
import server.living.config.Content.Requirements;
import server.living.config.Content.Reward;
import server.living.config.Content.RoutineSlot;
import server.living.config.Content.Rumor;
import server.living.config.Content.RumorLine;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Strength;
import server.living.config.Content.Terms;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the content directory into a {@link LivingContent}. Layout:
 * <pre>
 * personas/**.yaml   quests/**.yaml   contracts/**.yaml   arcs/**.yaml   rumors/**.yaml
 * loadouts.yaml      command-phrases.yaml      quest-log-ids.yaml (written by the quest-log export)
 * </pre>
 * A file holds one object or a list of objects. Every parse error names file and path.
 */
public final class ContentLoader {
    private ContentLoader() {
    }

    public static LivingContent load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new ContentException("content directory not found: " + root.toAbsolutePath());
        }
        Map<String, Persona> personas = new LinkedHashMap<>();
        Map<String, Quest> quests = new LinkedHashMap<>();
        Map<String, Contract> contracts = new LinkedHashMap<>();
        Map<String, Arc> arcs = new LinkedHashMap<>();
        Map<String, Rumor> rumors = new LinkedHashMap<>();
        Map<String, Loadout> loadouts = new LinkedHashMap<>();
        List<Phrase> phrases = new ArrayList<>();
        Map<String, Integer> questLogIds = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (Node node : documents(root.resolve("personas"))) {
            Persona persona = persona(node);
            putUnique(personas, persona.id(), persona, node);
        }
        for (Node node : documents(root.resolve("quests"))) {
            Quest quest = quest(node);
            putUnique(quests, quest.id(), quest, node);
        }
        for (Node node : documents(root.resolve("contracts"))) {
            Contract contract = contract(node);
            putUnique(contracts, contract.id(), contract, node);
        }
        for (Node node : documents(root.resolve("arcs"))) {
            Arc arc = arc(node);
            putUnique(arcs, arc.id(), arc, node);
        }
        for (Node node : documents(root.resolve("rumors"))) {
            Rumor rumor = rumor(node);
            putUnique(rumors, rumor.id(), rumor, node);
        }
        Path loadoutFile = root.resolve("loadouts.yaml");
        if (Files.exists(loadoutFile)) {
            for (Node node : read(loadoutFile).get("loadouts").items()) {
                Loadout loadout = loadout(node);
                putUnique(loadouts, loadout.id(), loadout, node);
            }
        }
        Path phraseFile = root.resolve("command-phrases.yaml");
        if (Files.exists(phraseFile)) {
            for (Node node : read(phraseFile).get("commands").items()) {
                phrases.add(phrase(node));
            }
        }
        Path questLogFile = root.resolve(QUEST_LOG_IDS_FILE);
        if (Files.exists(questLogFile)) {
            Node ids = read(questLogFile).get("ids");
            for (Map.Entry<String, Node> entry : ids.entries().entrySet()) {
                questLogIds.put(entry.getKey(), entry.getValue().integer());
            }
        }
        LivingContent content = new LivingContent(personas, quests, contracts, arcs, rumors, loadouts, phrases, questLogIds, warnings);
        warnings.addAll(ContentLint.check(content));
        return new LivingContent(personas, quests, contracts, arcs, rumors, loadouts, phrases, questLogIds, warnings);
    }

    public static final String QUEST_LOG_IDS_FILE = "quest-log-ids.yaml";

    // ---- files ---------------------------------------------------------------------------

    static List<Node> documents(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Node> nodes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isRegularFile(file) && (name.endsWith(".yaml") || name.endsWith(".yml"));
            }).sorted().forEach(file -> {
                Node document = read(file);
                if (document.isList()) {
                    nodes.addAll(document.items());
                } else if (!document.isMissing()) {
                    nodes.add(document);
                }
            });
        } catch (IOException failure) {
            throw new ContentException("cannot list " + directory, failure);
        }
        return nodes;
    }

    static Node read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            YamlReader yaml = new YamlReader(reader);
            Object value = yaml.read();
            return Node.root(value, file.getFileName().toString());
        } catch (YamlException failure) {
            throw new ContentException(file + ": " + failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new ContentException("cannot read " + file, failure);
        }
    }

    private static <T> void putUnique(Map<String, T> target, String id, T value, Node node) {
        if (id == null || id.isBlank()) {
            throw new ContentException(node.path() + ": id is required");
        }
        if (target.putIfAbsent(id, value) != null) {
            throw new ContentException(node.path() + ": duplicate id '" + id + "'");
        }
    }

    // ---- personas ------------------------------------------------------------------------

    static Persona persona(Node node) {
        String id = node.get("id").string();
        Node home = node.get("home");
        List<RoutineSlot> routine = new ArrayList<>();
        for (Node slot : home.get("routine").items()) {
            routine.add(routineSlot(slot));
        }
        Map<String, List<String>> dialogue = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : node.get("dialogue").entries().entrySet()) {
            dialogue.put(entry.getKey(), entry.getValue().strings());
        }
        List<Relationship> relationships = new ArrayList<>();
        for (Node rel : node.get("relationships").items()) {
            relationships.add(new Relationship(rel.get("persona").string(), rel.get("kind").enumName("KNOWS")));
        }
        Node level = node.get("level");
        LevelBand band = level.isMap()
                ? new LevelBand(level.get("min").integer(), level.get("max").integer())
                : new LevelBand(level.integer(10), level.integer(10));
        if (band.min() < 1 || band.max() < band.min()) {
            throw new ContentException(level.path() + ": min/max must be positive and ordered");
        }
        return new Persona(
                id,
                node.get("name").string(id),
                node.get("title").string(""),
                node.get("job").enumName("BEGINNER"),
                band,
                Strength.valueOf(node.get("strength").enumName("SEASONED")),
                node.get("loadout").string(null),
                node.get("personality").string("relaxed-v1"),
                node.get("dialogueStyle").string(null),
                home.get("town").string(),
                routine,
                node.get("recruitable").bool(false),
                node.get("contract").string(null),
                node.get("questLines").strings(),
                node.get("arc").string(null),
                relationships,
                node.get("rumors").strings(),
                dialogue,
                node.get("benchToleranceDays").integer(-1),
                node.get("paceGap").integer(-1),
                node.get("maxLag").integer(-1),
                node.get("giftItemIds").integers(),
                node.get("portraitNpcId").integer(0),
                node.path());
    }

    static RoutineSlot routineSlot(Node slot) {
        int from = slot.get("from").integer(0);
        int to = slot.get("to").integer(24);
        if (from < 0 || from > 24 || to < 0 || to > 24) {
            throw new ContentException(slot.path() + ": hours must be within 0..24");
        }
        return new RoutineSlot(from, to, slot.get("spot").string(null), slot.get("activity").enumName("LINGER"),
                slot.get("materialize").bool(true));
    }

    // ---- quests --------------------------------------------------------------------------

    static Quest quest(Node node) {
        String id = node.get("id").string();
        List<Step> steps = new ArrayList<>();
        for (Node stepNode : node.get("steps").items()) {
            steps.add(step(stepNode));
        }
        if (steps.isEmpty()) {
            throw new ContentException(node.path() + ": a quest needs at least one step");
        }
        Node complete = node.get("onComplete").get("arcAdvance");
        ArcAdvance advance = complete.isMissing() ? null
                : new ArcAdvance(complete.get("arc").string(), complete.get("to").string());
        return new Quest(
                id,
                node.get("persona").string(),
                node.get("title").string(id),
                QuestLength.valueOf(node.get("length").enumName("SHORT")),
                node.get("repeatable").bool(false),
                requirements(node.get("requires")),
                steps,
                reward(node.get("rewards")),
                advance,
                node.path());
    }

    static Requirements requirements(Node node) {
        Node level = node.get("level");
        return new Requirements(
                level.get("min").integer(1),
                level.get("max").integer(200),
                BondStage.valueOf(node.get("bondStage").get("min").enumName("NONE")),
                node.get("flags").strings(),
                node.get("notFlags").strings(),
                node.get("questsCompleted").strings());
    }

    static Step step(Node node) {
        StepType type = StepType.valueOf(node.get("type").enumName("TALK"));
        Node drop = node.get("dropFrom");
        DropRule dropRule = drop.isMissing() ? null
                : new DropRule(drop.get("mobIds").integers(), drop.get("chance").decimal(0.1));
        Node take = node.get("take");
        ItemStack takeStack = take.isMissing() ? null
                : new ItemStack(take.get("itemId").integer(), take.get("count").integer(1));
        Step step = new Step(
                node.get("id").string(type.name().toLowerCase(Locale.ROOT)),
                type,
                node.get("say").strings(),
                node.get("itemId").integer(0),
                node.get("count").integer(1),
                node.get("mobIds").integers(),
                node.get("mapIds").integers(),
                node.get("to").string(null),
                node.get("persona").string(null),
                node.get("rumorId").string(null),
                node.get("meso").integer(0),
                node.get("level").integer(0),
                node.get("hours").integer(0),
                dropRule,
                node.get("with").string(null),
                takeStack,
                node.get("label").string(null));
        switch (type) {
            case KILL_MOBS, HUNT_WITH, KILL_BOSS -> {
                if (step.mobIds().isEmpty()) {
                    throw new ContentException(node.path() + ": " + type + " needs mobIds");
                }
            }
            case COLLECT_ITEM, GIVE_ITEM -> {
                if (step.itemId() <= 0) {
                    throw new ContentException(node.path() + ": " + type + " needs itemId");
                }
            }
            case DELIVER -> {
                if (step.to() == null) {
                    throw new ContentException(node.path() + ": DELIVER needs to");
                }
            }
            case VISIT_MAP -> {
                if (step.mapIds().isEmpty()) {
                    throw new ContentException(node.path() + ": VISIT_MAP needs mapIds");
                }
            }
            case TALK_TO, ESCORT -> {
                if (step.persona() == null) {
                    throw new ContentException(node.path() + ": " + type + " needs persona");
                }
            }
            case HEAR_RUMOR -> {
                if (step.rumorId() == null) {
                    throw new ContentException(node.path() + ": HEAR_RUMOR needs rumorId");
                }
            }
            case PAY -> {
                if (step.meso() <= 0) {
                    throw new ContentException(node.path() + ": PAY needs meso");
                }
            }
            case REACH_LEVEL -> {
                if (step.level() <= 0) {
                    throw new ContentException(node.path() + ": REACH_LEVEL needs level");
                }
            }
            case WAIT -> {
                if (step.hours() <= 0) {
                    throw new ContentException(node.path() + ": WAIT needs hours");
                }
            }
            default -> { }
        }
        if (type == StepType.HUNT_WITH && step.persona() == null) {
            throw new ContentException(node.path() + ": HUNT_WITH needs persona");
        }
        return step;
    }

    static Reward reward(Node node) {
        List<ItemStack> items = new ArrayList<>();
        for (Node item : node.get("items").items()) {
            items.add(new ItemStack(item.get("itemId").integer(), item.get("count").integer(1)));
        }
        return new Reward(
                node.get("meso").integer(0),
                node.get("exp").integer(0),
                items,
                node.get("bondPoints").integer(0),
                node.get("flags").strings(),
                node.get("fame").integer(0),
                node.get("worldFlags").strings());
    }

    // ---- contracts -----------------------------------------------------------------------

    static Contract contract(Node node) {
        Node when = node.get("offerWhen");
        Node arcNode = when.get("arcChapterAtLeast");
        OfferWhen offerWhen = new OfferWhen(
                BondStage.valueOf(when.get("bondStage").enumName("TRUSTED")),
                when.get("flags").strings(),
                arcNode.get("arc").string(null),
                arcNode.get("chapter").string(null));
        Node priceNode = node.get("price");
        List<ContractItem> items = new ArrayList<>();
        for (Node item : priceNode.get("items").items()) {
            items.add(new ContractItem(item.get("itemId").integer(), item.get("count").integer(1),
                    item.get("keep").bool(false)));
        }
        Node proofNode = priceNode.get("proof").get("killMobs");
        KillProof proof = proofNode.isMissing() ? null
                : new KillProof(proofNode.get("mobIds").integers(), proofNode.get("count").integer(1));
        Price price = new Price(priceNode.get("meso").longValue(0L), items, proof);
        Node termsNode = node.get("terms");
        List<LeaveRule> leaves = new ArrayList<>();
        for (String rule : termsNode.get("leavesOn").strings()) {
            leaves.add(LeaveRule.valueOf(rule.toUpperCase(Locale.ROOT)));
        }
        if (leaves.isEmpty()) {
            leaves.add(LeaveRule.DISMISS);
            leaves.add(LeaveRule.LEADER_OFFLINE_20M);
        }
        Terms terms = new Terms(
                termsNode.get("durationHours").integer(0),
                termsNode.get("maxLevelGap").integer(0),
                LootShare.valueOf(termsNode.get("lootShare").enumName("OWN_DROPS")),
                leaves,
                termsNode.get("cooldownHoursAfterLeave").integer(0),
                termsNode.get("rejoinPrice").get("meso").longValue(price.meso()),
                termsNode.get("ownDropsPercent").integer(10));
        return new Contract(node.get("id").string(), offerWhen, price, terms, node.path());
    }

    // ---- arcs ----------------------------------------------------------------------------

    static Arc arc(Node node) {
        List<Chapter> chapters = new ArrayList<>();
        for (Node chapterNode : node.get("chapters").items()) {
            Node gate = chapterNode.get("enterWhen");
            Node rumor = gate.get("rumorPerformed");
            ChapterGate enterWhen = gate.isMissing() ? null : new ChapterGate(
                    gate.get("flags").strings(),
                    rumor.isMissing() ? null : rumor.string(),
                    gate.get("times").integer(1),
                    gate.get("adventurerHoursTotal").integer(0),
                    gate.get("distinctPlayers").integer(1));
            Map<String, List<String>> overrides = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : chapterNode.get("dialogueOverrides").entries().entrySet()) {
                overrides.put(entry.getKey(), entry.getValue().strings());
            }
            List<RoutineSlot> routine = new ArrayList<>();
            for (Node slot : chapterNode.get("routineOverride").items()) {
                routine.add(routineSlot(slot));
            }
            Node unlocks = chapterNode.get("unlocks");
            Node recruitable = chapterNode.get("recruitable");
            chapters.add(new Chapter(
                    chapterNode.get("id").string(),
                    chapterNode.get("title").string(""),
                    enterWhen,
                    overrides,
                    routine,
                    unlocks.get("questLines").strings(),
                    unlocks.get("rumors").strings(),
                    unlocks.get("contract").string(null),
                    recruitable.isMissing() ? null : recruitable.bool(false),
                    chapterNode.get("loadout").string(null)));
        }
        if (chapters.isEmpty()) {
            throw new ContentException(node.path() + ": an arc needs chapters");
        }
        return new Arc(node.get("id").string(), node.get("persona").string(), chapters, node.path());
    }

    // ---- rumors --------------------------------------------------------------------------

    static Rumor rumor(Node node) {
        Node where = node.get("where");
        Node when = node.get("when");
        Node arcGate = when.get("requiresArc");
        List<RumorLine> lines = new ArrayList<>();
        for (Node line : node.get("lines").items()) {
            lines.add(new RumorLine(line.get("by").string(), line.get("text").string()));
        }
        if (lines.isEmpty()) {
            throw new ContentException(node.path() + ": a rumor needs lines");
        }
        return new Rumor(
                node.get("id").string(),
                node.get("participants").strings(),
                where.get("town").string(),
                where.get("spot").string(null),
                when.get("serverHours").integers(),
                when.get("everyDays").integer(1),
                arcGate.isMissing() ? null
                        : new ArcGate(arcGate.get("arc").string(), arcGate.get("chapterAtLeast").string()),
                lines,
                node.get("setsFlagOnListeners").string(null),
                node.path());
    }

    // ---- loadouts and phrases ------------------------------------------------------------

    static Loadout loadout(Node node) {
        List<ItemStack> consumables = new ArrayList<>();
        for (Node item : node.get("consumables").items()) {
            consumables.add(new ItemStack(item.get("itemId").integer(), item.get("count").integer(1)));
        }
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : node.get("stats").entries().entrySet()) {
            stats.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().integer());
        }
        return new Loadout(node.get("id").string(), node.get("equips").integers(), consumables, stats);
    }

    static Phrase phrase(Node node) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : node.get("slots").entries().entrySet()) {
            slots.put(entry.getKey(), entry.getValue().string());
        }
        return new Phrase(
                node.get("canonical").string(),
                node.get("aliases").strings(),
                node.get("prefixes").strings(),
                node.get("tails").strings(),
                slots,
                node.get("status").enumName("LIVE"),
                node.get("reply").string(null));
    }
}
