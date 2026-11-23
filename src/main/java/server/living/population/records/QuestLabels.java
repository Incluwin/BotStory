package server.living.population.records;

import server.living.config.ContentLoader;
import server.living.config.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The quests whose clears are records ({@code rivals.yaml}'s {@code quests}, in the population content folder): an
 * event script, how the records page names it, what a player may type for it, and whether it is a party quest or a
 * boss. A script not listed is no record.
 */
public final class QuestLabels {
    public static final String FILE = "rivals.yaml";

    /** One listed quest. */
    public record Quest(String script, String label, boolean boss, List<String> aliases) {
        public Quest {
            aliases = List.copyOf(aliases);
        }
    }

    private final Map<String, Quest> byScript;
    /** The keys of field bosses ({@link FieldBosses}, N6.1): records of a boss kill, not of an event script. */
    private final java.util.Set<String> fields;

    public QuestLabels(List<Quest> quests) {
        this(quests, FieldBosses.none());
    }

    /** The listed quests, then each catalogued field boss as a boss record under its key (N6.1). */
    public QuestLabels(List<Quest> quests, FieldBosses bosses) {
        Map<String, Quest> map = new LinkedHashMap<>();
        for (Quest quest : quests) {
            map.put(quest.script(), quest);
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (FieldBosses.Boss boss : bosses.all()) {
            map.putIfAbsent(boss.key(), new Quest(boss.key(), boss.label(), true, boss.aliases()));
            keys.add(boss.key());
        }
        this.byScript = map;
        this.fields = java.util.Set.copyOf(keys);
    }

    /** {@code rivals.yaml} in {@code contentDir}; none listed when it is missing. */
    public static QuestLabels load(Path contentDir) {
        Path file = contentDir.resolve(FILE);
        List<Quest> out = new ArrayList<>();
        if (Files.exists(file)) {
            for (Node node : ContentLoader.read(file).get("quests").items()) {
                List<String> aliases = node.has("aliases") ? node.get("aliases").strings() : List.of();
                out.add(new Quest(node.get("script").string(), node.get("label").string(),
                        "boss".equals(node.get("kind").string("pq")), aliases));
            }
        }
        return new QuestLabels(out, FieldBosses.load(contentDir));
    }

    /** Whether {@code script} is a field boss's key (its records come from kills, not an event's clear). */
    public boolean field(String script) {
        return script != null && fields.contains(script);
    }

    /** Listed quests in file order. */
    public List<Quest> all() {
        return List.copyOf(byScript.values());
    }

    public boolean recorded(String script) {
        return script != null && byScript.containsKey(script);
    }

    /** The quest's label; the script name itself when it is not listed. */
    public String label(String script) {
        Quest quest = script == null ? null : byScript.get(script);
        return quest == null ? String.valueOf(script) : quest.label();
    }

    /** The script a player means ("balrog", "Easy Balrog", "kpq", "KerningPQ"), or null. */
    public String find(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String wanted = squash(typed);
        for (Quest quest : byScript.values()) {
            if (squash(quest.script()).equals(wanted) || squash(quest.label()).equals(wanted)) {
                return quest.script();
            }
            for (String alias : quest.aliases()) {
                if (squash(alias).equals(wanted)) {
                    return quest.script();
                }
            }
        }
        return null;
    }

    private static String squash(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
