package server.living.population.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One story filed to the paper desk (a {@code paper.item} row of living_event_log): its catalog key
 * ("section.story", as {@code town.wedding}), its weight against the week's other stories, and the words that fill
 * its templates. The row's detail is {@code {"k":key,"w":weight,"s":{slot:value}}}, at most 512 characters.
 */
public record PaperItem(long atMs, String key, int weight, Map<String, String> slots) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public PaperItem {
        slots = Map.copyOf(slots);
    }

    /** The section a key files under: what comes before its first dot ("town" for "town.wedding"). */
    public static String section(String key) {
        int dot = key == null ? -1 : key.indexOf('.');
        return dot <= 0 ? "" : key.substring(0, dot);
    }

    String detail() {
        ObjectNode root = JSON.createObjectNode();
        root.put("k", key);
        root.put("w", weight);
        ObjectNode s = root.putObject("s");
        slots.forEach(s::put);
        return root.toString();
    }

    /** The item a row's detail holds; null when it is not one. */
    static PaperItem parse(long atMs, String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(detail);
            String key = root.path("k").asText("");
            if (section(key).isEmpty()) {
                return null;
            }
            Map<String, String> slots = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = root.path("s").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> slot = it.next();
                slots.put(slot.getKey(), slot.getValue().asText());
            }
            return new PaperItem(atMs, key, root.path("w").asInt(0), slots);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
