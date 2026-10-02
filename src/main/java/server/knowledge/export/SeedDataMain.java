package server.knowledge.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import server.knowledge.SeedData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the Liquibase seed replay ({@link SeedData}) as JSON for the PowerShell catalog exporters:
 * {@code SeedDataMain [db-root] [out.json]}, defaults {@code src/main/resources/db} and {@code tmp/seed-data.json}.
 * Each table lists its columns in the old SQL tuple order, so a row is read by index; a column the seed leaves out is 0.
 */
public final class SeedDataMain {
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "drop_data", List.of("dropperid", "itemid", "minimum_quantity", "maximum_quantity", "questid", "chance"),
            "drop_data_global", List.of("continent", "itemid", "minimum_quantity", "maximum_quantity", "questid", "chance"),
            "shops", List.of("shopid", "npcid"),
            "shopitems", List.of("shopid", "itemid", "price", "pitch", "position"));

    private SeedDataMain() {
    }

    public static void main(String[] args) throws IOException {
        Configurator.initialize(new DefaultConfiguration()); // console at ERROR only: never the server's file appenders
        Path root = Path.of(args.length > 0 ? args[0] : SeedData.DEFAULT_ROOT);
        Path target = Path.of(args.length > 1 ? args[1] : "tmp/seed-data.json");
        long started = System.nanoTime();
        SeedData.Replay replay = SeedData.replay(root);
        Files.createDirectories(target.toAbsolutePath().getParent());
        JsonFactory json = JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        try (JsonGenerator out = json.createGenerator(target.toFile(), JsonEncoding.UTF8)) {
            out.writeStartObject();
            out.writeStringField("source", root.toString().replace('\\', '/'));
            out.writeArrayFieldStart("applied");
            for (String id : replay.applied()) {
                out.writeString(id);
            }
            out.writeEndArray();
            out.writeArrayFieldStart("warnings");
            for (String warning : replay.warnings()) {
                out.writeString(warning);
            }
            out.writeEndArray();
            out.writeObjectFieldStart("tables");
            for (String table : SeedData.TABLES) {
                List<String> columns = COLUMNS.get(table);
                out.writeObjectFieldStart(table);
                out.writeArrayFieldStart("columns");
                for (String column : columns) {
                    out.writeString(column);
                }
                out.writeEndArray();
                out.writeArrayFieldStart("rows");
                for (Map<String, String> row : replay.tables().get(table)) {
                    out.writeStartArray();
                    for (String column : columns) {
                        out.writeNumber(SeedData.column(row, column));
                    }
                    out.writeEndArray();
                }
                out.writeEndArray();
                out.writeEndObject();
            }
            out.writeEndObject();
            out.writeEndObject();
        }
        for (String table : SeedData.TABLES) {
            System.out.println(table + ": " + replay.tables().get(table).size() + " rows");
        }
        for (String warning : replay.warnings()) {
            System.out.println("WARN " + warning);
        }
        System.out.printf("%d changesets applied in %d ms -> %s%n", replay.applied().size(),
                (System.nanoTime() - started) / 1_000_000, target.toAbsolutePath().normalize());
    }
}
