package server.living.population;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Player prices for the price index's seeds (docs/ECONOMY_PLAN.md E2, rule 5): the GMS-era tables SoloMapling ships
 * ({@link #DIR}, read as data; SoloMapling itself stays off), each at the latest version not after this server's.
 * An item they do not list falls back to the NPC price multiple, and the market basket comes before both.
 */
final class PriceSeeds {
    private static final Logger log = LoggerFactory.getLogger(PriceSeeds.class);
    static final Path DIR = Path.of("src/main/java/soloMapling/itemPool/itemConfig");
    /** This server is v83; a table's price for a later version is not this server's. */
    static final int VERSION = 83;

    private static volatile Map<Integer, Long> loaded;

    private PriceSeeds() {
    }

    /** The seed for {@code itemId}, or 0 when the tables do not list it. */
    static long of(int itemId) {
        Map<Integer, Long> seeds = loaded;
        if (seeds == null) {
            synchronized (PriceSeeds.class) {
                if (loaded == null) {
                    loaded = load(DIR);
                }
                seeds = loaded;
            }
        }
        return seeds.getOrDefault(itemId, 0L);
    }

    /** Every item the tables in {@code dir} price; a file that is missing or not shaped like one is skipped. */
    static Map<Integer, Long> load(Path dir) {
        Map<Integer, Long> seeds = new HashMap<>();
        if (!Files.isDirectory(dir)) {
            return seeds;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : files) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    if (new YamlReader(reader).read() instanceof Map<?, ?> groups) {
                        for (Object group : groups.values()) {
                            if (group instanceof List<?> entries) {
                                entries.forEach(entry -> add(seeds, entry));
                            }
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    log.debug("price seeds: skipped {}", file.getFileName(), failure);
                }
            }
        } catch (IOException failure) {
            log.warn("price seeds: {} unreadable", dir, failure);
        }
        return seeds;
    }

    private static void add(Map<Integer, Long> seeds, Object entry) {
        if (!(entry instanceof Map<?, ?> item) || !(item.get("variant_id") instanceof List<?> ids)
                || !(item.get("price") instanceof Map<?, ?> prices)) {
            return;
        }
        long price = priceAt(prices, VERSION);
        if (price <= 0L) {
            return;
        }
        for (Object id : ids) {
            try {
                seeds.put(Integer.parseInt(String.valueOf(id).trim()), price);
            } catch (NumberFormatException ignored) {
                // not an item id
            }
        }
    }

    /** The price for the latest version key at or before {@code version}; 0 when every key is later. */
    static long priceAt(Map<?, ?> prices, int version) {
        int best = Integer.MIN_VALUE;
        long price = 0L;
        for (Map.Entry<?, ?> entry : prices.entrySet()) {
            try {
                int key = Integer.parseInt(String.valueOf(entry.getKey()).trim());
                if (key <= version && key > best) {
                    best = key;
                    price = Long.parseLong(String.valueOf(entry.getValue()).trim());
                }
            } catch (NumberFormatException ignored) {
                // not a version: price pair
            }
        }
        return price;
    }
}
