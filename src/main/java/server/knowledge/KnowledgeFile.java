package server.knowledge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parsed knowledge file ({@code server-knowledge.json}, written by {@code KnowledgeExportMain}): WZ facts
 * only, no drops or shops. Immutable; shared by every {@link WorldKnowledge} snapshot until the file is reloaded.
 * Read with the streaming JSON parser (no tree, no databind), so a cold boot load stays well under the budget.
 */
public final class KnowledgeFile {
    static final KnowledgeFile EMPTY = new KnowledgeFile("", "", List.of(), IdTable.empty(), IdTable.empty(),
            IdTable.empty(), IdTable.empty(), IdTable.empty(), List.of(), 0);

    private static final JsonFactory JSON = new JsonFactory();
    private static final int STRING_OVERHEAD = 56;
    private static final int OBJECT_OVERHEAD = 32;

    final String generatedAt;
    final String exporter;
    final List<KnowledgeSources.Source> sources;
    final IdTable<MapFact> maps;
    final IdTable<MobFact> mobs;
    final IdTable<ItemFact> items;
    final IdTable<NpcFact> npcs;
    final IdTable<QuestFact> quests;
    /** Direct script spawns as exported (revive inheritance is applied by {@link WorldKnowledge}). */
    final List<ScriptSpawn> scriptSpawns;
    /** Rough retained size (strings, int arrays, objects), for the boot log. */
    final long approxBytes;

    private KnowledgeFile(String generatedAt, String exporter, List<KnowledgeSources.Source> sources,
                          IdTable<MapFact> maps, IdTable<MobFact> mobs, IdTable<ItemFact> items, IdTable<NpcFact> npcs,
                          IdTable<QuestFact> quests, List<ScriptSpawn> scriptSpawns, long approxBytes) {
        this.generatedAt = generatedAt;
        this.exporter = exporter;
        this.sources = sources;
        this.maps = maps;
        this.mobs = mobs;
        this.items = items;
        this.npcs = npcs;
        this.quests = quests;
        this.scriptSpawns = scriptSpawns;
        this.approxBytes = approxBytes;
    }

    public String generatedAt() {
        return generatedAt;
    }

    public List<KnowledgeSources.Source> sources() {
        return sources;
    }

    public static KnowledgeFile read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            return parse(reader);
        }
    }

    public static KnowledgeFile parse(Reader reader) throws IOException {
        String generatedAt = "";
        String exporter = "";
        List<KnowledgeSources.Source> sources = new ArrayList<>();
        Map<String, Section> sections = new HashMap<>();
        try (JsonParser json = JSON.createParser(reader)) {
            if (json.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("knowledge file is not a JSON object");
            }
            while (json.nextToken() == JsonToken.FIELD_NAME) {
                String field = json.currentName();
                json.nextToken();
                switch (field) {
                    case "generatedAt" -> generatedAt = json.getText();
                    case "exporter" -> exporter = json.getText();
                    case "sources" -> {
                        for (Object row : (List<?>) value(json)) {
                            Map<?, ?> source = (Map<?, ?>) row;
                            sources.add(new KnowledgeSources.Source((String) source.get("path"),
                                    ((Number) source.get("fileCount")).intValue(), ((Number) source.get("totalBytes")).longValue()));
                        }
                    }
                    case "maps", "mobs", "items", "npcs", "quests", "scriptSpawns" -> sections.put(field, new Section(json));
                    default -> json.skipChildren();
                }
            }
        }
        Sizer size = new Sizer();
        Map<String, String> interned = new HashMap<>();

        Section mobRows = section(sections, "mobs");
        TreeMap<Integer, MobFact> mobs = new TreeMap<>();
        for (Object[] row : mobRows.rows) {
            MobFact mob = new MobFact(mobRows.integer(row, "id"), size.string(mobRows.text(row, "name")),
                    mobRows.integer(row, "level"), mobRows.integer(row, "maxHp"), mobRows.integer(row, "exp"),
                    mobRows.flag(row, "boss"), mobRows.flag(row, "undead"), size.ints(mobRows.ints(row, "revive")));
            mobs.put(mob.id(), mob);
        }

        Section mapRows = section(sections, "maps");
        TreeMap<Integer, MapFact> maps = new TreeMap<>();
        for (Object[] row : mapRows.rows) {
            int[][] spawns = mapRows.pairs(row, "mobs");
            long levelSum = 0;
            int levelWeight = 0;
            for (int i = 0; i < spawns[0].length; i++) {
                MobFact mob = mobs.get(spawns[0][i]);
                if (mob != null && !mob.boss() && mob.level() > 0) {
                    levelSum += (long) mob.level() * spawns[1][i];
                    levelWeight += spawns[1][i];
                }
            }
            String region = mapRows.text(row, "region");
            MapFact map = new MapFact(mapRows.integer(row, "id"), size.string(mapRows.text(row, "name")),
                    size.string(mapRows.text(row, "street")), region == null ? null : interned.computeIfAbsent(region, r -> r),
                    mapRows.flag(row, "town"), mapRows.flag(row, "hub"), mapRows.integer(row, "returnMap"),
                    mapRows.integer(row, "forcedReturn"), size.ints(spawns[0]), size.ints(spawns[1]),
                    size.ints(mapRows.ints(row, "npcs")), size.ints(mapRows.ints(row, "exits")),
                    levelWeight == 0 ? 0 : (int) Math.round((double) levelSum / levelWeight));
            maps.put(map.id(), map);
        }

        Section itemRows = section(sections, "items");
        TreeMap<Integer, ItemFact> items = new TreeMap<>();
        for (Object[] row : itemRows.rows) {
            ItemFact item = new ItemFact(itemRows.integer(row, "id"), size.string(itemRows.text(row, "name")),
                    interned.computeIfAbsent(itemRows.text(row, "category"), c -> c), itemRows.integer(row, "reqLevel"),
                    itemRows.integer(row, "reqJob"), itemRows.integer(row, "reqStr"), itemRows.integer(row, "reqDex"),
                    itemRows.integer(row, "reqInt"), itemRows.integer(row, "reqLuk"), itemRows.integer(row, "reqPop"),
                    itemRows.integer(row, "price"), itemRows.flag(row, "cash"), itemRows.flag(row, "unnamed"),
                    itemRows.integer(row, "success"));
            items.put(item.id(), item);
        }

        Section npcRows = section(sections, "npcs");
        TreeMap<Integer, NpcFact> npcs = new TreeMap<>();
        for (Object[] row : npcRows.rows) {
            NpcFact npc = new NpcFact(npcRows.integer(row, "id"), size.string(npcRows.text(row, "name")),
                    interned.computeIfAbsent(npcRows.text(row, "func"), f -> f), size.ints(npcRows.ints(row, "maps")));
            npcs.put(npc.id(), npc);
        }

        Section questRows = section(sections, "quests");
        TreeMap<Integer, QuestFact> quests = new TreeMap<>();
        for (Object[] row : questRows.rows) {
            int[][] itemPairs = questRows.pairs(row, "reqItems");
            int[][] mobPairs = questRows.pairs(row, "reqMobs");
            QuestFact quest = new QuestFact(questRows.integer(row, "id"), size.string(questRows.text(row, "name")),
                    questRows.integer(row, "startNpc"), questRows.integer(row, "completeNpc"),
                    questRows.integer(row, "minLevel"), size.ints(itemPairs[0]), size.ints(itemPairs[1]),
                    size.ints(mobPairs[0]), size.ints(mobPairs[1]), questRows.integer(row, "rewardExp"),
                    questRows.integer(row, "rewardMeso"));
            quests.put(quest.id(), quest);
        }

        Section spawnRows = section(sections, "scriptSpawns");
        List<ScriptSpawn> spawns = new ArrayList<>();
        for (Object[] row : spawnRows.rows) {
            spawns.add(new ScriptSpawn(spawnRows.integer(row, "mobId"), size.string(spawnRows.text(row, "script")),
                    size.ints(spawnRows.ints(row, "maps")), spawnRows.integer(row, "viaNpc"), 0));
        }
        size.objects += maps.size() + mobs.size() + items.size() + npcs.size() + quests.size() + spawns.size();
        return new KnowledgeFile(generatedAt, exporter, List.copyOf(sources), IdTable.ofSorted(maps),
                IdTable.ofSorted(mobs), IdTable.ofSorted(items), IdTable.ofSorted(npcs), IdTable.ofSorted(quests),
                List.copyOf(spawns), size.bytes());
    }

    private static Section section(Map<String, Section> sections, String name) throws IOException {
        Section section = sections.get(name);
        if (section == null) {
            throw new IOException("knowledge file has no '" + name + "' section");
        }
        return section;
    }

    /** The current value as Integer/Long, String, null, List or Map (only used for small values and rows). */
    private static Object value(JsonParser json) throws IOException {
        return switch (json.currentToken()) {
            case VALUE_NUMBER_INT -> json.getNumberType() == JsonParser.NumberType.INT ? json.getIntValue() : json.getLongValue();
            case VALUE_STRING -> json.getText();
            case VALUE_NULL -> null;
            case VALUE_TRUE -> 1;
            case VALUE_FALSE -> 0;
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                while (json.nextToken() != JsonToken.END_ARRAY) {
                    list.add(value(json));
                }
                yield list;
            }
            case START_OBJECT -> {
                Map<String, Object> object = new HashMap<>();
                while (json.nextToken() == JsonToken.FIELD_NAME) {
                    String name = json.currentName();
                    json.nextToken();
                    object.put(name, value(json));
                }
                yield object;
            }
            default -> throw new IOException("unexpected " + json.currentToken() + " in knowledge file");
        };
    }

    /** One columnar section: {@code {"columns": [...], "rows": [[...], ...]}}, read by column name. */
    private static final class Section {
        final List<Object[]> rows = new ArrayList<>();
        final Map<String, Integer> columns = new HashMap<>();

        Section(JsonParser json) throws IOException {
            if (json.currentToken() != JsonToken.START_OBJECT) {
                throw new IOException("knowledge file section is not an object");
            }
            while (json.nextToken() == JsonToken.FIELD_NAME) {
                String field = json.currentName();
                json.nextToken();
                if (field.equals("columns")) {
                    List<?> names = (List<?>) value(json);
                    for (int i = 0; i < names.size(); i++) {
                        columns.put((String) names.get(i), i);
                    }
                } else if (field.equals("rows")) {
                    while (json.nextToken() != JsonToken.END_ARRAY) {
                        rows.add(((List<?>) value(json)).toArray());
                    }
                } else {
                    json.skipChildren();
                }
            }
        }

        private Object cell(Object[] row, String column) {
            Integer index = columns.get(column);
            if (index == null) {
                throw new IllegalStateException("knowledge file column missing: " + column);
            }
            return row[index];
        }

        int integer(Object[] row, String column) {
            return ((Number) cell(row, column)).intValue();
        }

        boolean flag(Object[] row, String column) {
            return integer(row, column) != 0;
        }

        String text(Object[] row, String column) {
            return (String) cell(row, column);
        }

        int[] ints(Object[] row, String column) {
            List<?> list = (List<?>) cell(row, column);
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ((Number) list.get(i)).intValue();
            }
            return out;
        }

        /** {@code [[id, count], ...]} as {ids, counts}. */
        int[][] pairs(Object[] row, String column) {
            List<?> list = (List<?>) cell(row, column);
            int[][] out = new int[2][list.size()];
            for (int i = 0; i < list.size(); i++) {
                List<?> pair = (List<?>) list.get(i);
                out[0][i] = ((Number) pair.get(0)).intValue();
                out[1][i] = ((Number) pair.get(1)).intValue();
            }
            return out;
        }
    }

    /** Counts what the snapshot keeps, for {@link #approxBytes}. */
    private static final class Sizer {
        long chars;
        long strings;
        long ints;
        long objects;

        String string(String value) {
            strings++;
            chars += value.length();
            return value;
        }

        IntList ints(int[] values) {
            if (values.length > 0) {
                objects++;
                ints += values.length;
            }
            return IntList.wrap(values);
        }

        long bytes() {
            return strings * STRING_OVERHEAD + chars + ints * Integer.BYTES + objects * OBJECT_OVERHEAD;
        }
    }
}
