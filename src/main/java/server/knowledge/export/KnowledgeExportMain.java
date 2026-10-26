package server.knowledge.export;

import server.knowledge.KnowledgeSources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code scripts/config/knowledge/server-knowledge.json} from the server's own WZ XML and
 * scripts: maps (name, street, region, town/hub, return map, mobs, NPCs, exits), mobs, items (category,
 * requirements, sell price, scroll success %), NPCs with their function text and maps, quests, and mobs
 * spawned by literal id in scripts.
 * Drops and shops are not in the file; they come from the live DB at boot ({@code KnowledgeOverlayLoader}).
 *
 * <p>Maps follow {@code MapFactory}: a map whose {@code info/link} names another map takes its life,
 * portals and reactors from that map and keeps its own info and String.wz name. Exits are portal targets
 * plus script portals whose {@code scripts/portal/<name>.js} warps to one literal map (or, in a
 * {@code switch} on the current map, to one literal map for this map).
 *
 * <p>{@code tools\knowledge-export.bat [server-dir] [out.json]}. Pure file parsing, no database.
 */
public final class KnowledgeExportMain {
    public static final String EXPORTER = "KnowledgeExportMain 3";
    private static final int NO_MAP = 999_999_999;
    private static final Pattern SPAWN_CALL = Pattern.compile(
            "\\b(?:getMonster|spawnMonster\\w*|spawnMob\\w*|summonMob\\w*)\\(([^()]*)");
    private static final Pattern ID_LITERAL = Pattern.compile("(?<![\\w.])(\\d{7})(?![\\w.])");
    private static final Pattern MAP_LITERAL = Pattern.compile("\\bgetMap(?:Instance)?\\(\\s*(\\d{9})\\s*\\)");
    private static final Pattern WARP_LITERAL = Pattern.compile("\\bwarp\\w*\\(\\s*(\\d{9})\\b");
    private static final Pattern CASE_WARP = Pattern.compile(
            "\\bcase\\s+(\\d{9})\\s*:(?:(?!\\bcase\\s).)*?\\bwarp\\w*\\(\\s*(\\d{9})\\b", Pattern.DOTALL);
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** The scroll % players see: String.wz desc "Success rate:60%" (Item.wz info/success when the desc has none). */
    private static final Pattern SUCCESS_RATE = Pattern.compile("(?i)success rate\\s*:?\\s*(\\d{1,3})\\s*%");
    private static final Set<String> MAP_PARTS = Set.of("info", "life", "portal", "reactor");
    private static final Set<String> INFO_ONLY = Set.of("info");
    private static final String[] ITEM_FOLDERS = {"Consume", "Install", "Etc", "Cash"};
    private static final String[] STRING_ITEM_FILES = {"Eqp", "Consume", "Etc", "Ins", "Cash", "Pet"};

    private KnowledgeExportMain() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path out = args.length > 1 ? Path.of(args[1])
                : root.resolve("scripts/config/knowledge").resolve(KnowledgeSources.FILE_NAME);
        long started = System.nanoTime();
        String json = export(root, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json, StandardCharsets.US_ASCII);
        System.out.printf("%s: %d bytes in %d ms -> %s%n", EXPORTER, json.length(),
                (System.nanoTime() - started) / 1_000_000, out.toAbsolutePath().normalize());
    }

    /** The whole file as text; {@code generatedAt} is the only non-deterministic field. */
    public static String export(Path root, String generatedAt) throws IOException {
        Path wz = root.resolve("wz");
        Map<Integer, String[]> mapNames = mapNames(wz.resolve("String.wz/Map.img.xml"));
        Map<Integer, RawMap> raw = readMaps(wz.resolve("Map.wz/Map"));
        Map<String, ScriptWarp> portalWarps = portalWarps(root.resolve("scripts/portal"));
        Map<Integer, MobRow> mobs = readMobs(wz);
        Map<Integer, String> npcNames = names(wz.resolve("String.wz/Npc.img.xml"));
        Map<Integer, String> npcFuncs = strings(wz.resolve("String.wz/Npc.img.xml"), "func");

        Map<Integer, MapRow> maps = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> npcMaps = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> reactorMaps = new HashMap<>();
        Map<String, TreeSet<Integer>> portalScriptMaps = new HashMap<>();
        Map<String, TreeSet<Integer>> enterScriptMaps = new HashMap<>();
        for (RawMap map : raw.values()) {
            RawMap life = map.link != 0 && raw.containsKey(map.link) ? raw.get(map.link) : map;
            Map<Integer, Integer> mobCounts = new TreeMap<>();
            TreeSet<Integer> npcs = new TreeSet<>();
            for (int[] entry : life.life) {
                if (entry[0] == 'm') {
                    if (mobs.containsKey(entry[1])) {
                        mobCounts.merge(entry[1], 1, Integer::sum);
                    }
                } else {
                    npcs.add(entry[1]);
                    npcMaps.computeIfAbsent(entry[1], id -> new TreeSet<>()).add(map.id);
                }
            }
            TreeSet<Integer> exits = new TreeSet<>();
            for (int target : life.portalTargets) {
                if (target != NO_MAP && target != map.id && raw.containsKey(target)) {
                    exits.add(target);
                }
            }
            for (int reactor : life.reactors) {
                reactorMaps.computeIfAbsent(reactor, id -> new TreeSet<>()).add(map.id);
            }
            for (String script : life.portalScripts) {
                portalScriptMaps.computeIfAbsent(script, s -> new TreeSet<>()).add(map.id);
                ScriptWarp warp = portalWarps.get(script);
                int target = warp == null ? 0 : warp.byMap.getOrDefault(map.id, warp.single);
                if (target != 0 && target != map.id && raw.containsKey(target)) {
                    exits.add(target);
                }
            }
            enterScriptMaps.computeIfAbsent("onUserEnter/" + map.onUserEnter, s -> new TreeSet<>()).add(map.id);
            enterScriptMaps.computeIfAbsent("onFirstUserEnter/" + map.onFirstUserEnter, s -> new TreeSet<>()).add(map.id);
            String[] name = mapNames.getOrDefault(map.id, new String[]{"", ""});
            maps.put(map.id, new MapRow(map.id, name[1], name[0], region(map.id), map.town, map.returnMap,
                    map.forcedReturn, mobCounts, npcs, exits));
        }

        Map<Integer, String> npcs = new TreeMap<>();
        npcNames.forEach((id, name) -> {
            if (!name.isEmpty()) {
                npcs.put(id, name);
            }
        });
        npcMaps.keySet().forEach(id -> npcs.putIfAbsent(id, npcNames.getOrDefault(id, "")));

        Map<Integer, ItemRow> items = readItems(wz);
        List<QuestRow> quests = readQuests(wz.resolve("Quest.wz"));
        List<SpawnRow> spawns = readScriptSpawns(root, mobs.keySet(), npcMaps, reactorMaps,
                portalScriptMaps, enterScriptMaps);
        return write(KnowledgeSources.measureLf(root), generatedAt, maps, mobs, items, npcs, npcFuncs, npcMaps, quests, spawns);
    }

    // ---- maps ------------------------------------------------------------------------------

    private static final class RawMap {
        int id;
        boolean town;
        int returnMap = NO_MAP;
        int forcedReturn;
        int link;
        String onUserEnter;
        String onFirstUserEnter;
        final List<int[]> life = new ArrayList<>();
        final List<Integer> portalTargets = new ArrayList<>();
        final List<String> portalScripts = new ArrayList<>();
        final List<Integer> reactors = new ArrayList<>();
    }

    private record MapRow(int id, String name, String street, String region, boolean town, int returnMap,
                          int forcedReturn, Map<Integer, Integer> mobs, Set<Integer> npcs, Set<Integer> exits) {
    }

    private static Map<Integer, RawMap> readMaps(Path mapRoot) throws IOException {
        Map<Integer, RawMap> maps = new TreeMap<>();
        for (Path file : KnowledgeSources.files(mapRoot)) {
            String folder = file.getParent().getFileName().toString();
            if (!folder.matches("Map\\d")) {
                continue; // AreaCode.img.xml at the root is not a map
            }
            WzXml.Node node = WzXml.read(file, (depth, name) -> depth != 1 || MAP_PARTS.contains(name), null);
            RawMap map = new RawMap();
            map.id = Integer.parseInt(file.getFileName().toString().replace(".img.xml", ""));
            WzXml.Node info = node.child("info");
            if (info != null) {
                map.town = info.intAt("town", 0) != 0;
                map.returnMap = info.intAt("returnMap", NO_MAP);
                int forced = info.intAt("forcedReturn", NO_MAP);
                map.forcedReturn = forced == NO_MAP ? 0 : forced;
                map.link = WzXml.parseInt(info.stringAt("link"), 0);
                map.onUserEnter = blankToDefault(info.stringAt("onUserEnter"), map.id);
                map.onFirstUserEnter = blankToDefault(info.stringAt("onFirstUserEnter"), map.id);
            } else {
                map.onUserEnter = map.onFirstUserEnter = String.valueOf(map.id);
            }
            WzXml.Node life = node.child("life");
            if (life != null) {
                for (WzXml.Node entry : life.children) {
                    String type = entry.stringAt("type");
                    int id = WzXml.parseInt(entry.stringAt("id"), 0);
                    if (id > 0 && ("m".equals(type) || "n".equals(type))) {
                        map.life.add(new int[]{type.charAt(0), id});
                    }
                }
            }
            WzXml.Node portals = node.child("portal");
            if (portals != null) {
                for (WzXml.Node portal : portals.children) {
                    map.portalTargets.add(portal.intAt("tm", NO_MAP));
                    String script = portal.stringAt("script");
                    if (script != null && !script.isBlank()) {
                        map.portalScripts.add(script.trim());
                    }
                }
            }
            WzXml.Node reactors = node.child("reactor");
            if (reactors != null) {
                for (WzXml.Node reactor : reactors.children) {
                    int id = WzXml.parseInt(reactor.stringAt("id"), 0);
                    if (id > 0) {
                        map.reactors.add(id);
                    }
                }
            }
            maps.put(map.id, map);
        }
        return maps;
    }

    private static String blankToDefault(String script, int mapId) {
        return script == null || script.isBlank() ? String.valueOf(mapId) : script.trim();
    }

    /** Coarse area by id prefix; null for event, instance and GM maps. */
    static String region(int mapId) {
        if (mapId < 100_000_000) {
            return "maple-island";
        }
        return switch (mapId / 1_000_000) {
            case 100, 101, 102, 103, 104, 105, 106, 107, 108 -> "victoria";
            case 110 -> "florina";
            case 120 -> "nautilus";
            case 130 -> "ereve";
            case 140 -> "rien";
            case 200 -> "orbis";
            case 209 -> "happyville";
            case 211, 280 -> "el-nath"; // 21106-21107: Lion King's Castle, a Community Repack map (server.mods.RepackMaps)
            case 219 -> "coke-town"; // Repack
            case 220 -> "ludibrium";
            case 221 -> "omega-sector";
            case 222 -> "korean-folk-town";
            case 230 -> "aquarium";
            case 240 -> "leafre";
            case 250 -> "mu-lung";
            case 251 -> "herb-town";
            case 260 -> "ariant";
            case 261 -> "magatia";
            case 270 -> "temple-of-time";
            case 271, 272, 273 -> "future"; // Repack: Gate to the Future, Crack in Time, Twilight Perion
            case 300 -> "ellin-forest";
            case 450 -> "arcane-river"; // Repack, scenery only
            case 540, 541 -> "singapore";
            case 550, 551 -> "malaysia";
            case 600, 682 -> "new-leaf-city";
            case 610 -> "crimsonwood";
            case 680 -> "amoria";
            case 800 -> "zipangu";
            case 801 -> "showa";
            default -> null;
        };
    }

    /** Literal warp targets of one portal script: its only target, and per-map targets from switch cases. */
    private record ScriptWarp(int single, Map<Integer, Integer> byMap) {
    }

    private static Map<String, ScriptWarp> portalWarps(Path portalScripts) throws IOException {
        Map<String, ScriptWarp> out = new HashMap<>();
        if (!Files.isDirectory(portalScripts)) {
            return out;
        }
        for (Path file : KnowledgeSources.files(portalScripts)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Set<Integer> targets = new TreeSet<>();
            Matcher warp = WARP_LITERAL.matcher(text);
            while (warp.find()) {
                targets.add(Integer.parseInt(warp.group(1)));
            }
            Map<Integer, Integer> byMap = new HashMap<>();
            Matcher cases = CASE_WARP.matcher(text);
            while (cases.find()) {
                byMap.putIfAbsent(Integer.parseInt(cases.group(1)), Integer.parseInt(cases.group(2)));
            }
            if (!targets.isEmpty()) {
                out.put(file.getFileName().toString().replace(".js", ""),
                        new ScriptWarp(targets.size() == 1 ? targets.iterator().next() : 0, byMap));
            }
        }
        return out;
    }

    // ---- mobs ------------------------------------------------------------------------------

    private record MobRow(int id, String name, int level, int maxHp, int exp, boolean boss, boolean undead,
                          List<Integer> revive) {
    }

    private static Map<Integer, MobRow> readMobs(Path wz) throws IOException {
        Map<Integer, String> names = names(wz.resolve("String.wz/Mob.img.xml"));
        Map<Integer, MobRow> mobs = new TreeMap<>();
        for (Path file : KnowledgeSources.files(wz.resolve("Mob.wz"))) {
            if (!file.getParent().getFileName().toString().equals("Mob.wz")) {
                continue; // QuestCountGroup/ holds quest kill groups, not mobs
            }
            int id = Integer.parseInt(file.getFileName().toString().replace(".img.xml", ""));
            WzXml.Node info = WzXml.read(file, (depth, name) -> depth != 1 || "info".equals(name), INFO_ONLY).child("info");
            if (info == null) {
                continue;
            }
            List<Integer> revive = new ArrayList<>();
            WzXml.Node reviveNode = info.child("revive");
            if (reviveNode != null) {
                for (WzXml.Node child : reviveNode.children) {
                    revive.add(WzXml.parseInt(child.value, 0));
                }
            }
            mobs.put(id, new MobRow(id, names.getOrDefault(id, ""), info.intAt("level", 0), info.intAt("maxHP", 1),
                    info.intAt("exp", 0), info.intAt("boss", 0) > 0, info.intAt("undead", 0) > 0, revive));
        }
        return mobs;
    }

    // ---- items -----------------------------------------------------------------------------

    private record ItemRow(int id, String name, String category, int reqLevel, int reqJob, int reqStr, int reqDex,
                           int reqInt, int reqLuk, int reqPop, int price, boolean cash, boolean unnamed, int success) {
    }

    private static Map<Integer, ItemRow> readItems(Path wz) throws IOException {
        Map<Integer, String> names = new HashMap<>();
        for (String file : STRING_ITEM_FILES) {
            names.putAll(names(wz.resolve("String.wz/" + file + ".img.xml")));
        }
        Map<Integer, String> descs = strings(wz.resolve("String.wz/Consume.img.xml"), "desc");
        Map<Integer, ItemRow> items = new TreeMap<>();
        for (String input : KnowledgeSources.INPUTS) {
            if (!input.startsWith("wz/Character.wz/")) {
                continue;
            }
            String folder = input.substring("wz/Character.wz/".length());
            for (Path file : KnowledgeSources.files(wz.resolve("Character.wz/" + folder))) {
                int id = Integer.parseInt(file.getFileName().toString().replace(".img.xml", ""));
                WzXml.Node root = WzXml.read(file, (depth, name) -> depth != 1 || "info".equals(name), INFO_ONLY);
                WzXml.Node info = root == null ? null : root.child("info");
                items.put(id, item(id, equipCategory(folder, id), info, names, info != null && info.intAt("cash", 0) != 0));
            }
        }
        for (String folder : ITEM_FOLDERS) {
            for (Path file : KnowledgeSources.files(wz.resolve("Item.wz/" + folder))) {
                WzXml.Node root = WzXml.read(file, (depth, name) -> depth != 2 || "info".equals(name), null);
                for (WzXml.Node entry : root.children) {
                    if (!DIGITS.matcher(entry.name).matches()) {
                        continue;
                    }
                    int id = Integer.parseInt(entry.name);
                    String category = switch (id / 1_000_000) {
                        case 2 -> "use";
                        case 3 -> "setup";
                        case 4 -> "etc";
                        default -> "cash";
                    };
                    ItemRow item = item(id, category, entry.child("info"), names, "cash".equals(category));
                    if ("use".equals(category)) {
                        Matcher rate = SUCCESS_RATE.matcher(descs.getOrDefault(id, ""));
                        int success = rate.find() ? Integer.parseInt(rate.group(1))
                                : entry.child("info") == null ? 0 : entry.child("info").intAt("success", 0);
                        item = new ItemRow(id, item.name, category, item.reqLevel, item.reqJob, item.reqStr, item.reqDex,
                                item.reqInt, item.reqLuk, item.reqPop, item.price, item.cash, item.unnamed, success);
                    }
                    items.put(id, item);
                }
            }
        }
        for (Path file : KnowledgeSources.files(wz.resolve("Item.wz/Pet"))) {
            int id = Integer.parseInt(file.getFileName().toString().replace(".img.xml", ""));
            WzXml.Node root = WzXml.read(file, (depth, name) -> depth != 1 || "info".equals(name), INFO_ONLY);
            items.put(id, item(id, "pet", root == null ? null : root.child("info"), names, true));
        }
        return items;
    }

    private static ItemRow item(int id, String category, WzXml.Node info, Map<Integer, String> names, boolean cash) {
        String name = names.getOrDefault(id, "");
        if (info == null) {
            return new ItemRow(id, name, category, 0, 0, 0, 0, 0, 0, 0, 0, cash, name.isEmpty(), 0);
        }
        return new ItemRow(id, name, category, info.intAt("reqLevel", 0), info.intAt("reqJob", 0),
                info.intAt("reqSTR", 0), info.intAt("reqDEX", 0), info.intAt("reqINT", 0), info.intAt("reqLUK", 0),
                info.intAt("reqPOP", 0), info.intAt("price", 0), cash, name.isEmpty(), 0);
    }

    /** Equip slot from the Character.wz folder; accessories split by id range. */
    static String equipCategory(String folder, int id) {
        if (folder.equals("Accessory")) {
            return switch (id / 10_000) {
                case 101 -> "face";
                case 102 -> "eye";
                case 103 -> "earring";
                case 112 -> "pendant";
                case 113 -> "belt";
                case 114 -> "medal";
                case 115 -> "shoulder";
                default -> "accessory";
            };
        }
        return folder.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- quests ----------------------------------------------------------------------------

    private record QuestRow(int id, String name, int startNpc, int completeNpc, int minLevel, List<int[]> items,
                            List<int[]> mobs, int exp, int meso) {
    }

    private static List<QuestRow> readQuests(Path questWz) throws IOException {
        WzXml.Node info = WzXml.read(questWz.resolve("QuestInfo.img.xml"), WzXml.ALL, null);
        WzXml.Node check = WzXml.read(questWz.resolve("Check.img.xml"), WzXml.ALL, null);
        WzXml.Node act = WzXml.read(questWz.resolve("Act.img.xml"), WzXml.ALL, null);
        Map<Integer, QuestRow> quests = new TreeMap<>();
        for (WzXml.Node quest : check.children) {
            if (!DIGITS.matcher(quest.name).matches()) {
                continue;
            }
            int id = Integer.parseInt(quest.name);
            WzXml.Node questInfo = info.child(quest.name);
            WzXml.Node reward = act.child(quest.name);
            quests.put(id, new QuestRow(id, clean(questInfo == null ? null : questInfo.stringAt("name")),
                    quest.intAt("0/npc", 0), quest.intAt("1/npc", 0), quest.intAt("0/lvmin", 0),
                    pairs(quest.at("1/item")), pairs(quest.at("1/mob")),
                    reward == null ? 0 : reward.intAt("1/exp", 0), reward == null ? 0 : reward.intAt("1/money", 0)));
        }
        return new ArrayList<>(quests.values());
    }

    private static List<int[]> pairs(WzXml.Node list) {
        List<int[]> out = new ArrayList<>();
        if (list != null) {
            for (WzXml.Node entry : list.children) {
                int id = entry.intAt("id", 0);
                int count = entry.intAt("count", 0);
                if (id > 0 && count > 0) {
                    out.add(new int[]{id, count});
                }
            }
        }
        return out;
    }

    // ---- script spawns ---------------------------------------------------------------------

    private record SpawnRow(int mobId, String script, Set<Integer> maps, int viaNpc) {
    }

    private static List<SpawnRow> readScriptSpawns(Path root, Set<Integer> mobIds, Map<Integer, TreeSet<Integer>> npcMaps,
                                                   Map<Integer, TreeSet<Integer>> reactorMaps,
                                                   Map<String, TreeSet<Integer>> portalScriptMaps,
                                                   Map<String, TreeSet<Integer>> enterScriptMaps) throws IOException {
        Map<String, SpawnRow> rows = new TreeMap<>();
        for (String input : KnowledgeSources.INPUTS) {
            if (!input.startsWith("scripts/")) {
                continue;
            }
            Path dir = root.resolve(input);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path file : KnowledgeSources.files(dir)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                TreeSet<Integer> spawned = new TreeSet<>();
                Matcher call = SPAWN_CALL.matcher(text);
                int from = 0;
                while (call.find(from)) {
                    Matcher id = ID_LITERAL.matcher(call.group(1));
                    while (id.find()) {
                        int mobId = Integer.parseInt(id.group(1));
                        if (mobIds.contains(mobId)) {
                            spawned.add(mobId);
                        }
                    }
                    from = call.start(1); // nested calls: spawnMonsterOnGroundBelow(LifeFactory.getMonster(id), ...)
                }
                if (spawned.isEmpty()) {
                    continue;
                }
                String script = root.resolve("scripts").relativize(file).toString().replace('\\', '/');
                String folder = script.substring(0, script.indexOf('/'));
                String base = script.substring(script.lastIndexOf('/') + 1).replace(".js", "");
                int viaNpc = 0;
                Set<Integer> maps = new TreeSet<>();
                switch (folder) {
                    case "npc" -> {
                        if (DIGITS.matcher(base).matches()) {
                            viaNpc = Integer.parseInt(base);
                            maps.addAll(npcMaps.getOrDefault(viaNpc, new TreeSet<>()));
                        }
                    }
                    case "reactor" -> {
                        if (DIGITS.matcher(base).matches()) {
                            maps.addAll(reactorMaps.getOrDefault(Integer.parseInt(base), new TreeSet<>()));
                        }
                    }
                    case "portal" -> maps.addAll(portalScriptMaps.getOrDefault(base, new TreeSet<>()));
                    case "map" -> maps.addAll(enterScriptMaps.getOrDefault(script.substring("map/".length())
                            .replace(".js", ""), new TreeSet<>()));
                    case "event" -> {
                        Set<Integer> literal = new TreeSet<>();
                        Matcher map = MAP_LITERAL.matcher(text);
                        while (map.find()) {
                            literal.add(Integer.parseInt(map.group(1)));
                        }
                        if (literal.size() == 1) {
                            maps.addAll(literal); // several literal maps: the spawn map is not knowable
                        }
                    }
                    default -> {
                        // quest and item scripts run wherever the player is
                    }
                }
                for (int mobId : spawned) {
                    rows.put(String.format("%07d|%s", mobId, script), new SpawnRow(mobId, script, maps, viaNpc));
                }
            }
        }
        return new ArrayList<>(rows.values());
    }

    // ---- String.wz -------------------------------------------------------------------------

    /** Every {@code <imgdir name="id"><string name="name">} pair in a String.wz file, at any depth. */
    private static Map<Integer, String> names(Path file) throws IOException {
        return strings(file, "name");
    }

    /** Every {@code <imgdir name="id"><string name="field">} value in a String.wz file, at any depth, cleaned. */
    private static Map<Integer, String> strings(Path file, String field) throws IOException {
        Map<Integer, String> out = new HashMap<>();
        if (Files.exists(file)) {
            collectStrings(WzXml.read(file, WzXml.ALL, null), field, out);
        }
        return out;
    }

    private static void collectStrings(WzXml.Node node, String field, Map<Integer, String> out) {
        for (WzXml.Node child : node.children) {
            if (!child.children.isEmpty()) {
                if (DIGITS.matcher(child.name).matches()) {
                    String value = child.stringAt(field);
                    if (value != null) {
                        out.put(Integer.parseInt(child.name), clean(value));
                    }
                }
                collectStrings(child, field, out);
            }
        }
    }

    private static Map<Integer, String[]> mapNames(Path file) throws IOException {
        Map<Integer, String[]> out = new HashMap<>();
        collectMapNames(WzXml.read(file, WzXml.ALL, null), out);
        return out;
    }

    private static void collectMapNames(WzXml.Node node, Map<Integer, String[]> out) {
        for (WzXml.Node child : node.children) {
            if (DIGITS.matcher(child.name).matches() && (child.child("mapName") != null || child.child("streetName") != null)) {
                out.put(Integer.parseInt(child.name), new String[]{clean(child.stringAt("streetName")), clean(child.stringAt("mapName"))});
            } else if (!child.children.isEmpty()) {
                collectMapNames(child, out);
            }
        }
    }

    /** Trimmed, with runs of spaces collapsed (String.wz stores "Mano " and "Kid  Snowman"). */
    static String clean(String name) {
        return name == null ? "" : name.trim().replaceAll(" {2,}", " ");
    }

    // ---- output ----------------------------------------------------------------------------

    private static String write(List<KnowledgeSources.Source> sources, String generatedAt, Map<Integer, MapRow> maps,
                                Map<Integer, MobRow> mobs, Map<Integer, ItemRow> items, Map<Integer, String> npcs,
                                Map<Integer, String> npcFuncs, Map<Integer, TreeSet<Integer>> npcMaps, List<QuestRow> quests,
                                List<SpawnRow> spawns) {
        StringBuilder out = new StringBuilder(4 << 20);
        out.append("{\n\"format\": 1,\n\"exporter\": ").append(quote(EXPORTER))
                .append(",\n\"generatedAt\": ").append(quote(generatedAt)).append(",\n\"sources\": [\n");
        for (int i = 0; i < sources.size(); i++) {
            KnowledgeSources.Source source = sources.get(i);
            out.append("{\"path\": ").append(quote(source.path())).append(", \"fileCount\": ").append(source.fileCount())
                    .append(", \"totalBytes\": ").append(source.totalBytes()).append(i + 1 < sources.size() ? "},\n" : "}\n");
        }
        out.append("],\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("maps", maps.size());
        counts.put("mobs", mobs.size());
        counts.put("items", items.size());
        counts.put("npcs", npcs.size());
        counts.put("quests", quests.size());
        counts.put("scriptSpawns", spawns.size());
        out.append("\"counts\": {");
        int c = 0;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            out.append(c++ == 0 ? "" : ", ").append(quote(count.getKey())).append(": ").append(count.getValue());
        }
        out.append("},\n");

        section(out, "maps", "\"id\", \"name\", \"street\", \"region\", \"town\", \"hub\", \"returnMap\", \"forcedReturn\", \"mobs\", \"npcs\", \"exits\"",
                maps.values(), (row, line) -> line.append(row.id).append(',').append(quote(row.name)).append(',')
                        .append(quote(row.street)).append(',').append(row.region == null ? "null" : quote(row.region))
                        .append(',').append(row.town ? 1 : 0).append(',').append(row.town && row.returnMap == row.id ? 1 : 0)
                        .append(',').append(row.returnMap).append(',').append(row.forcedReturn).append(',')
                        .append(pairs(row.mobs)).append(',').append(ints(row.npcs)).append(',').append(ints(row.exits)));
        section(out, "mobs", "\"id\", \"name\", \"level\", \"maxHp\", \"exp\", \"boss\", \"undead\", \"revive\"",
                mobs.values(), (row, line) -> line.append(row.id).append(',').append(quote(row.name)).append(',')
                        .append(row.level).append(',').append(row.maxHp).append(',').append(row.exp).append(',')
                        .append(row.boss ? 1 : 0).append(',').append(row.undead ? 1 : 0).append(',').append(ints(row.revive)));
        section(out, "items", "\"id\", \"name\", \"category\", \"reqLevel\", \"reqJob\", \"reqStr\", \"reqDex\", \"reqInt\", \"reqLuk\", \"reqPop\", \"price\", \"cash\", \"unnamed\", \"success\"",
                items.values(), (row, line) -> line.append(row.id).append(',').append(quote(row.name)).append(',')
                        .append(quote(row.category)).append(',').append(row.reqLevel).append(',').append(row.reqJob)
                        .append(',').append(row.reqStr).append(',').append(row.reqDex).append(',').append(row.reqInt)
                        .append(',').append(row.reqLuk).append(',').append(row.reqPop).append(',').append(row.price)
                        .append(',').append(row.cash ? 1 : 0).append(',').append(row.unnamed ? 1 : 0).append(',').append(row.success));
        section(out, "npcs", "\"id\", \"name\", \"func\", \"maps\"", npcs.entrySet(),
                (row, line) -> line.append(row.getKey()).append(',').append(quote(row.getValue())).append(',')
                        .append(quote(npcFuncs.getOrDefault(row.getKey(), ""))).append(',')
                        .append(ints(npcMaps.getOrDefault(row.getKey(), new TreeSet<>()))));
        section(out, "quests", "\"id\", \"name\", \"startNpc\", \"completeNpc\", \"minLevel\", \"reqItems\", \"reqMobs\", \"rewardExp\", \"rewardMeso\"",
                quests, (row, line) -> line.append(row.id).append(',').append(quote(row.name)).append(',')
                        .append(row.startNpc).append(',').append(row.completeNpc).append(',').append(row.minLevel)
                        .append(',').append(pairs(row.items)).append(',').append(pairs(row.mobs)).append(',')
                        .append(row.exp).append(',').append(row.meso));
        section(out, "scriptSpawns", "\"mobId\", \"script\", \"maps\", \"viaNpc\"", spawns,
                (row, line) -> line.append(row.mobId).append(',').append(quote(row.script)).append(',')
                        .append(ints(row.maps)).append(',').append(row.viaNpc));
        out.setLength(out.length() - 2); // the last section's ",\n"
        out.append("\n}\n");
        return out.toString();
    }

    private interface RowWriter<T> {
        void write(T row, StringBuilder line);
    }

    private static <T> void section(StringBuilder out, String name, String columns, Iterable<T> rows, RowWriter<T> writer) {
        out.append(quote(name)).append(": {\"columns\": [").append(columns).append("], \"rows\": [\n");
        boolean first = true;
        for (T row : rows) {
            out.append(first ? "[" : ",\n[");
            writer.write(row, out);
            out.append(']');
            first = false;
        }
        out.append("\n]},\n");
    }

    private static String ints(Iterable<Integer> values) {
        StringBuilder out = new StringBuilder("[");
        for (int value : values) {
            out.append(out.length() > 1 ? "," : "").append(value);
        }
        return out.append(']').toString();
    }

    private static String pairs(Map<Integer, Integer> values) {
        StringBuilder out = new StringBuilder("[");
        values.forEach((id, count) -> out.append(out.length() > 1 ? "," : "").append('[').append(id).append(',').append(count).append(']'));
        return out.append(']').toString();
    }

    private static String pairs(List<int[]> values) {
        StringBuilder out = new StringBuilder("[");
        for (int[] pair : values) {
            out.append(out.length() > 1 ? "," : "").append('[').append(pair[0]).append(',').append(pair[1]).append(']');
        }
        return out.append(']').toString();
    }

    /** JSON string, ASCII only (non-ASCII as \\u escapes) so the file never depends on a charset. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20 || ch > 0x7e) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
