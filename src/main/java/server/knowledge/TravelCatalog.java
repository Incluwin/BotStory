package server.knowledge;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads {@code travel.yaml} and keeps the legs whose maps and NPC exist in the knowledge file. */
final class TravelCatalog {
    private static final Logger log = LoggerFactory.getLogger(TravelCatalog.class);

    private TravelCatalog() {
    }

    static List<TravelLeg> load(Path yaml, KnowledgeFile file) throws IOException {
        if (!Files.exists(yaml)) {
            log.warn("[knowledge] {} missing: routes use portals only", yaml);
            return List.of();
        }
        Object document;
        try (Reader reader = Files.newBufferedReader(yaml, StandardCharsets.US_ASCII)) {
            document = new YamlReader(reader).read();
        }
        List<TravelLeg> legs = new ArrayList<>();
        if (!(document instanceof Map<?, ?> root) || !(root.get("legs") instanceof List<?> rows)) {
            log.warn("[knowledge] {} has no 'legs' list", yaml);
            return List.of();
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> leg)) {
                continue;
            }
            int from = integer(leg.get("from"));
            int to = integer(leg.get("to"));
            int npc = integer(leg.get("npc"));
            if (file.maps.get(from) == null || file.maps.get(to) == null || npc != 0 && file.npcs.get(npc) == null) {
                log.warn("[knowledge] travel.yaml: dropped leg {} -> {} (npc {}): unknown map or NPC", from, to, npc);
                continue;
            }
            Object kind = leg.get("kind");
            legs.add(new TravelLeg(from, to, npc, kind == null ? "" : kind.toString(), integer(leg.get("cost"))));
        }
        return List.copyOf(legs);
    }

    private static int integer(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException notNumber) {
            return -1;
        }
    }
}
