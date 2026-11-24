package server.living.population.craft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.itcg.ItcgRecipes.Stack;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes {@code recipes.yaml} ({@link RecipeBook}) from stock play: every forging NPC script is run against a stand-in
 * NPC that has every item and meso and walks every menu, and what an order takes and gives is recorded (so arrow and
 * screw amounts the script works out late come out right); the Maker's recipes come from its two tables. Left out: an
 * outcome by chance (stimulators, the Mu Lung scrolls), a script that asks for a finished quest, anything the stock NPC
 * makes free (no fee is under it), quest and untradeable items, and a recipe whose result an NPC shop buys for more
 * than its materials and the crafter's fee (it would print meso).
 *
 * <p>Run from the server directory: {@code java -cp target/Cosmic.jar server.living.population.craft.RecipeTool}.</p>
 */
public final class RecipeTool {
    private static final Logger log = LoggerFactory.getLogger(RecipeTool.class);
    /** A crafter's fee over the stock one, before any friend's discount. */
    static final int FEE_PERCENT = 70;
    private static final Path SCRIPTS = Path.of("scripts", "npc");
    private static final Path DATA = Path.of("src", "main", "resources", "db", "data");
    private static final Path OUT = Path.of("scripts", "config", "population", RecipeBook.FILE);
    private static final Pattern ROW = Pattern.compile("\\(([0-9, -]+)\\)");
    private static final int WARRIOR = 1;
    private static final int MAGE = 2;
    private static final int BOWMAN = 4;
    private static final int THIEF = 8;
    private static final int PIRATE = 16;

    /** One order as the stock game fills it. */
    record Raw(int result, int count, List<Stack> inputs, int fee, String from) {
    }

    /** What the WZ says of an item; tests bring their own. */
    interface ItemData {
        /** {reqLevel, reqJob, what an NPC shop pays for one}; null for an item that does not exist or may not change hands. */
        int[] of(int itemId);
    }

    private RecipeTool() {
    }

    public static void main(String[] args) throws IOException {
        List<Raw> raws = new ArrayList<>();
        try (var files = Files.list(SCRIPTS)) {
            for (Path script : files.filter(path -> path.toString().endsWith(".js")).sorted().toList()) {
                String source = Files.readString(script);
                if (source.contains("matSet") || source.contains("mats")) {
                    raws.addAll(npc(script));
                }
            }
        }
        raws.addAll(maker(DATA.resolve("111-makercreate-data.sql"), DATA.resolve("112-makerrecipe-data.sql")));
        List<RecipeBook.Recipe> recipes = recipes(raws, new WzItems());
        Files.writeString(OUT, yaml(recipes));
        log.info("{} stock orders, {} recipes written to {}", raws.size(), recipes.size(), OUT);
    }

    // ---- the forging NPCs --------------------------------------------------------------------

    /** Every order {@code script} fills, one of each; empty for one gated by a quest or that is no forge. */
    static List<Raw> npc(Path script) throws IOException {
        String from = script.getFileName().toString().replace(".js", "");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.put("SOURCE", Files.readString(script));
        Map<String, Raw> out = new LinkedHashMap<>();
        try {
            String lines = String.valueOf(engine.eval(WALK));
            for (String line : lines.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|"); // fee|result:count|input:count ...
                String[] made = parts[1].split(":");
                List<Stack> inputs = new ArrayList<>();
                for (String input : parts[2].trim().split(" ")) {
                    String[] pair = input.split(":");
                    inputs.add(new Stack(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
                }
                out.put(line, new Raw(Integer.parseInt(made[0]), Integer.parseInt(made[1]), inputs,
                        Integer.parseInt(parts[0]), from));
            }
        } catch (ScriptException notAForge) {
            log.info("{} is no forge this tool can walk: {}", from, notAForge.getMessage());
            return List.of();
        }
        return List.copyOf(out.values());
    }

    /** Walks every menu of SOURCE and returns a line per order: fee|result:count|input:count ... */
    private static final String WALK = """
            (function () {
              var lines = [];
              var gated = false;
              function walk(path) {
                var last = {kind: 'none'}, gains = [], meso = 0, chance = false;
                var player = new Proxy({}, {get: function () { return function () { return 0; }; }});
                var known = {
                  getPlayer: function () { return player; },
                  getChar: function () { return player; },
                  getMeso: function () { return 2000000000; },
                  getLevel: function () { return 200; },
                  getText: function () { return '1'; },
                  haveItem: function () { return true; },
                  canHold: function () { return true; },
                  isQuestActive: function () { return false; },
                  isQuestStarted: function () { return false; },
                  isQuestCompleted: function () { gated = true; return true; },
                  getQuestStatus: function () { gated = true; return 2; },
                  gainMeso: function (m) { meso += m; },
                  gainItem: function (id, n, show, random) {
                    gains.push([id, n === undefined ? 1 : n]);
                    if (random) { chance = true; }
                  },
                  gainRandomItem: function () { chance = true; },
                  sendSimple: function (text) {
                    var options = [], m, find = /#L(\\d+)#/g;
                    while ((m = find.exec(text)) !== null) { options.push(parseInt(m[1])); }
                    last = {kind: 'simple', options: options};
                  },
                  sendGetNumber: function () { last = {kind: 'number'}; },
                  sendGetText: function () { last = {kind: 'text'}; },
                  sendYesNo: function () { last = {kind: 'yes'}; },
                  sendAcceptDecline: function () { last = {kind: 'yes'}; },
                  sendNext: function () { last = {kind: 'next'}; },
                  sendOk: function () { last = {kind: 'end'}; },
                  dispose: function () { last = {kind: 'end'}; }
                };
                var cm = new Proxy(known, {get: function (t, k) { return t[k] || function () { return 0; }; }});
                var maths = new Proxy({}, {get: function (t, k) {
                  if (k === 'random') { chance = true; }
                  return Math[k];
                }});
                var script = new Function('cm', 'Math', SOURCE + '; return {start: start, action: action};')(cm, maths);
                script.start();
                for (var i = 0; i < path.length; i++) {
                  last = {kind: 'none'};
                  script.action(1, 0, path[i]);
                }
                if (gains.length > 0) {
                  var made = gains.filter(function (g) { return g[1] > 0; });
                  var used = gains.filter(function (g) { return g[1] < 0; });
                  if (!chance && made.length === 1 && used.length > 0) {
                    lines.push((-meso) + '|' + made[0][0] + ':' + made[0][1] + '|' + used.map(function (g) {
                      return g[0] + ':' + (-g[1]);
                    }).join(' '));
                  }
                  return;
                }
                if (path.length > 7) { return; }
                if (last.kind === 'simple') {
                  last.options.forEach(function (o) { walk(path.concat([o])); });
                } else if (last.kind === 'number' || last.kind === 'yes' || last.kind === 'next' || last.kind === 'text') {
                  walk(path.concat([1]));
                }
              }
              walk([]);
              return gated ? '' : lines.join('\\n');
            })()
            """;

    // ---- the Maker -----------------------------------------------------------------------------

    /**
     * The Maker's recipes without a stimulator or gems, at its own price cut to the thousand as it charges it; none that
     * asks for a manual or a worn piece besides its materials.
     */
    static List<Raw> maker(Path create, Path recipe) throws IOException {
        Map<Integer, List<Stack>> inputs = new HashMap<>();
        for (int[] row : rows(recipe)) { // itemid, req_item, count
            inputs.computeIfAbsent(row[0], id -> new ArrayList<>()).add(new Stack(row[1], row[2]));
        }
        Map<Integer, Raw> out = new LinkedHashMap<>();
        for (int[] row : rows(create)) { // id, itemid, req_level, req_maker_level, req_meso, req_item, req_equip, catalyst, quantity, tuc
            List<Stack> needs = inputs.get(row[1]);
            if (needs != null && row[5] <= 0 && row[6] <= 0) {
                out.putIfAbsent(row[1], new Raw(row[1], Math.max(1, row[8]), needs, row[4] / 1000 * 1000, "maker"));
            }
        }
        return List.copyOf(out.values());
    }

    private static List<int[]> rows(Path sql) throws IOException {
        List<int[]> out = new ArrayList<>();
        Matcher matcher = ROW.matcher(Files.readString(sql));
        while (matcher.find()) {
            String[] parts = matcher.group(1).split(",");
            int[] row = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                row[i] = Integer.parseInt(parts[i].trim());
            }
            out.add(row);
        }
        return out;
    }

    // ---- recipes -------------------------------------------------------------------------------

    /** The orders a crafter may take, the cheapest stock price of each (result and inputs), by trade, level and item. */
    static List<RecipeBook.Recipe> recipes(List<Raw> raws, ItemData items) {
        Map<String, Raw> cheapest = new TreeMap<>();
        for (Raw raw : raws) {
            String key = raw.result() + " " + raw.count() + " " + inputs(raw.inputs());
            cheapest.merge(key, raw, (a, b) -> a.fee() <= b.fee() ? a : b);
        }
        List<RecipeBook.Recipe> out = new ArrayList<>();
        Map<String, Integer> variants = new HashMap<>();
        for (Raw raw : cheapest.values()) {
            int fee = (int) (raw.fee() * (long) FEE_PERCENT / 100L);
            int[] made = items.of(raw.result());
            if (made == null || fee < 1 || raw.inputs().stream().anyMatch(input -> input.itemId() / 1000 == 4130
                    || items.of(input.itemId()) == null)) {
                continue;
            }
            long materials = raw.inputs().stream().mapToLong(input -> (long) items.of(input.itemId())[2] * input.count()).sum();
            if ((long) made[2] * raw.count() > materials + fee * 4L / 5L) { // a friend's fee: the lowest there is
                continue;
            }
            String id = "r" + raw.result();
            int variant = variants.merge(id, 1, Integer::sum);
            out.add(new RecipeBook.Recipe(variant == 1 ? id : id + "-" + variant, profession(raw.result(), made[1]),
                    raw.result(), raw.count(), made[0], raw.inputs(), raw.fee(), fee, minutes(raw.result(), made[0]),
                    raw.from()));
        }
        out.sort(Comparator.comparing(RecipeBook.Recipe::profession).thenComparingInt(RecipeBook.Recipe::level)
                .thenComparingInt(RecipeBook.Recipe::result).thenComparing(RecipeBook.Recipe::id));
        return out;
    }

    /** Minutes of a crafter's time: a refine 10, an equip three a level it asks for (20 minutes to 6 hours). */
    static int minutes(int itemId, int level) {
        return itemId / 1_000_000 != 1 ? 10 : Math.max(20, Math.min(360, level * 3));
    }

    static Profession profession(int itemId, int reqJob) {
        if (itemId / 1_000_000 == 2 || itemId / 10_000 == 425) {
            return Profession.ALCHEMIST; // potions, and the Maker's stat gems
        }
        if (itemId / 1_000_000 != 1) {
            return Profession.SMITH; // ores, plates, crystals, screws, processed wood
        }
        int kind = itemId / 10_000;
        if (kind == 107 || kind == 108) {
            return Profession.TAILOR; // shoes, gloves
        }
        boolean soft = reqJob > 0 && (reqJob & (MAGE | THIEF)) != 0 && (reqJob & (WARRIOR | BOWMAN | PIRATE)) == 0;
        return soft ? Profession.TAILOR : Profession.SMITH;
    }

    private static String inputs(List<Stack> inputs) {
        StringBuilder out = new StringBuilder();
        for (Stack input : inputs) {
            out.append(out.isEmpty() ? "" : " ").append(input.itemId()).append('x').append(input.count());
        }
        return out.toString();
    }

    static String yaml(List<RecipeBook.Recipe> recipes) {
        StringBuilder out = new StringBuilder("""
                # What citizen crafters make (docs/WORLD_SYSTEMS_PLAN.md N8.1, server.living.population.craft.RecipeBook).
                # Generated from the stock forging NPC scripts and the Maker tables by server.living.population.craft.RecipeTool:
                # run it again rather than edit this. trade: whose work it is. level: the result's reqLevel. inputs: item x count.
                # stockFee: the stock NPC's (or Maker's) price; fee: the crafter's, always under it. minutes: of the crafter's
                # time, each. from: the stock NPC script, or maker.
                recipes:
                """);
        for (RecipeBook.Recipe recipe : recipes) {
            out.append("  - {id: ").append(recipe.id()).append(", trade: ").append(recipe.profession().word())
                    .append(", result: ").append(recipe.result());
            if (recipe.resultCount() > 1) {
                out.append(", count: ").append(recipe.resultCount());
            }
            out.append(", level: ").append(recipe.level()).append(", inputs: \"").append(inputs(recipe.inputs()))
                    .append("\", stockFee: ").append(recipe.stockFee()).append(", fee: ").append(recipe.fee())
                    .append(", minutes: ").append(recipe.minutes()).append(", from: \"").append(recipe.from()).append("\"}\n");
        }
        return out.toString();
    }

    /** The WZ files, each item read once. */
    private static final class WzItems implements ItemData {
        private final DataProvider equips = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
        private final DataProvider items = DataProviderFactory.getDataProvider(WZFiles.ITEM);
        private final Map<String, String> paths = new HashMap<>();
        private final Map<Integer, int[]> read = new HashMap<>();

        WzItems() {
            for (DataProvider provider : List.of(equips, items)) {
                for (DataDirectoryEntry dir : provider.getRoot().getSubdirectories()) {
                    for (DataFileEntry file : dir.getFiles()) {
                        paths.put(file.getName(), dir.getName() + "/" + file.getName());
                    }
                }
            }
        }

        @Override
        public int[] of(int itemId) {
            return read.computeIfAbsent(itemId, this::read);
        }

        private int[] read(int itemId) {
            String name = "0" + itemId;
            Data data;
            if (itemId / 1_000_000 == 1) {
                String path = paths.get(name + ".img");
                data = path == null ? null : equips.getData(path);
            } else {
                String path = paths.get(name.substring(0, 4) + ".img");
                data = path == null ? null : items.getData(path).getChildByPath(name);
            }
            if (data == null || DataTool.getIntConvert("info/tradeBlock", data, 0) == 1
                    || DataTool.getIntConvert("info/quest", data, 0) == 1
                    || DataTool.getIntConvert("info/cash", data, 0) == 1) {
                return null;
            }
            return new int[]{DataTool.getIntConvert("info/reqLevel", data, 0),
                    DataTool.getIntConvert("info/reqJob", data, 0), DataTool.getIntConvert("info/price", data, 0)};
        }
    }
}
