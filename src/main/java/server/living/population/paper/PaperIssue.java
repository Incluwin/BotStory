package server.living.population.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One week's paper as it was composed (a living_paper row): its sections in catalog order, each with its stories in
 * the words chosen at compose time. Stored as JSON, so a reader a week later sees the same issue.
 */
public record PaperIssue(int weekOf, long issuedAtMs, List<Section> sections) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One story: its catalog key ("town.wedding"), weight, words, and the characters it names. */
    public record Story(String key, int weight, String headline, String body, String gist, List<String> names) {
        public Story {
            names = List.copyOf(names);
        }
    }

    public record Section(String id, String title, List<Story> stories) {
        public Section {
            stories = List.copyOf(stories);
        }
    }

    public PaperIssue {
        sections = List.copyOf(sections);
    }

    public boolean empty() {
        return sections.stream().allMatch(section -> section.stories().isEmpty());
    }

    /** The {@code count} heaviest stories over every section (ties: the earlier section, then the earlier story). */
    public List<Story> headlines(int count) {
        List<Story> all = new ArrayList<>();
        for (Section section : sections) {
            all.addAll(section.stories());
        }
        all.sort(Comparator.comparingInt(Story::weight).reversed()); // stable: catalog order breaks ties
        return all.subList(0, Math.min(count, all.size()));
    }

    /** Whether a story of this issue names {@code name} (case does not matter). */
    public boolean names(String name) {
        for (Section section : sections) {
            for (Story story : section.stories()) {
                for (String named : story.names()) {
                    if (named.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("week", weekOf);
        ArrayNode list = root.putArray("sections");
        for (Section section : sections) {
            ObjectNode one = list.addObject();
            one.put("id", section.id());
            one.put("title", section.title());
            ArrayNode stories = one.putArray("stories");
            for (Story story : section.stories()) {
                ObjectNode s = stories.addObject();
                s.put("key", story.key());
                s.put("weight", story.weight());
                s.put("headline", story.headline());
                s.put("body", story.body());
                s.put("gist", story.gist());
                ArrayNode names = s.putArray("names");
                story.names().forEach(names::add);
            }
        }
        return root.toString();
    }

    /** The issue a living_paper row holds; null when its body cannot be read. */
    public static PaperIssue fromJson(int weekOf, long issuedAtMs, String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<Section> sections = new ArrayList<>();
            for (JsonNode one : root.path("sections")) {
                List<Story> stories = new ArrayList<>();
                for (JsonNode s : one.path("stories")) {
                    List<String> names = new ArrayList<>();
                    s.path("names").forEach(name -> names.add(name.asText()));
                    stories.add(new Story(s.path("key").asText(), s.path("weight").asInt(), s.path("headline").asText(),
                            s.path("body").asText(), s.path("gist").asText(), names));
                }
                sections.add(new Section(one.path("id").asText(), one.path("title").asText(), stories));
            }
            return new PaperIssue(weekOf, issuedAtMs, sections);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
