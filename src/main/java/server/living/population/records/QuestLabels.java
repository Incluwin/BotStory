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

    public QuestLabels(List<Quest> quests) {
        Map<String, Quest> map = new LinkedHashMap<>();
        for (Quest quest : quests) {
            map.put(quest.script(), quest);
        }
        this.byScript = map;
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
        return new QuestLabels(out);
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
