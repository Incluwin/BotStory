package server.living.population.paper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** In-memory storage and sources for the paper's tests. */
final class PaperFakes {
    private PaperFakes() {
    }

    /** The desk's storage: issues by week, filed rows, seen markers; {@code inserts} counts rows written. */
    static final class Store implements PaperDesk.Store {
        final TreeMap<Integer, PaperDesk.Stored> issues = new TreeMap<>();
        final List<String> filed = new ArrayList<>();
        final Map<Integer, Integer> seen = new HashMap<>();
        final Map<Integer, String> names = new HashMap<>();
        int inserts;
        int seenWrites;
        boolean down;

        @Override
        public synchronized boolean file(String detail) {
            filed.add(detail);
            return true;
        }

        @Override
        public synchronized Optional<PaperDesk.Stored> issue(int weekOf) {
            return down ? null : Optional.ofNullable(issues.get(weekOf));
        }

        @Override
        public synchronized boolean insert(int weekOf, long issuedAtMs, String body) {
            if (down || issues.containsKey(weekOf)) {
                return false;
            }
            issues.put(weekOf, new PaperDesk.Stored(weekOf, issuedAtMs, body));
            inserts++;
            return true;
        }

        @Override
        public synchronized Optional<PaperDesk.Stored> latestAtOrBefore(int weekOf) {
            var entry = issues.floorEntry(weekOf);
            return entry == null ? Optional.empty() : Optional.of(entry.getValue());
        }

        @Override
        public synchronized Integer lastSeen(int characterId) {
            return seen.getOrDefault(characterId, -1);
        }

        @Override
        public synchronized void seen(int characterId, int weekOf) {
            seen.put(characterId, weekOf);
            seenWrites++;
        }

        @Override
        public String name(int characterId) {
            return names.get(characterId);
        }
    }

    /** What an issue reads, as rows a test puts in. */
    static final class Sources implements PaperCompose.Sources {
        final List<PaperItem> items = new ArrayList<>();
        final List<PaperCompose.Champion> champions = new ArrayList<>();
        final List<PaperCompose.Climb> climbs = new ArrayList<>();
        final List<PaperCompose.ClearRow> clears = new ArrayList<>();
        final List<PaperCompose.ClearRow> olderClears = new ArrayList<>();
        final List<PaperCompose.Price> prices = new ArrayList<>();
        final List<PaperCompose.RivalBeat> beats = new ArrayList<>();
        final Map<String, String> labels = new HashMap<>(Map.of("KerningPQ", "Kerning PQ", "LudiPQ", "Ludibrium PQ",
                "BalrogBattle_Easy", "Easy Balrog"));
        final Map<Integer, String> itemNames = new HashMap<>();
        long lastFromMs;
        long lastToMs;

        @Override
        public List<PaperItem> items(long fromMs, long toMs) {
            lastFromMs = fromMs;
            lastToMs = toMs;
            return items.stream().filter(item -> item.atMs() >= fromMs && item.atMs() < toMs).toList();
        }

        @Override
        public List<PaperCompose.Champion> champions(int weekOf) {
            return champions;
        }

        @Override
        public List<PaperCompose.Climb> climbs(int weekOf) {
            return climbs;
        }

        @Override
        public List<PaperCompose.ClearRow> clears(long fromMs, long toMs) {
            return clears.stream().filter(clear -> clear.clearedAtMs() >= fromMs && clear.clearedAtMs() < toMs).toList();
        }

        @Override
        public Map<String, Long> fastestBefore(long beforeMs) {
            Map<String, Long> out = new HashMap<>();
            for (PaperCompose.ClearRow clear : olderClears) {
                if (clear.durationMs() > 0) {
                    out.merge(clear.quest(), clear.durationMs(), Math::min);
                }
            }
            return out;
        }

        @Override
        public Set<String> clearedBefore(long beforeMs) {
            Set<String> out = new HashSet<>();
            olderClears.forEach(clear -> out.add(clear.quest()));
            return out;
        }

        @Override
        public List<PaperCompose.Price> prices(LocalDate from, LocalDate to) {
            return prices.stream().filter(price -> !price.day().isBefore(from) && !price.day().isAfter(to)).toList();
        }

        @Override
        public List<PaperCompose.RivalBeat> rivalBeats(long fromMs, long toMs) {
            return beats;
        }

        @Override
        public String questLabel(String script) {
            return labels.get(script);
        }

        @Override
        public String itemName(int itemId) {
            return itemNames.get(itemId);
        }
    }

    static PaperCatalog shippedCatalog() {
        return PaperCatalog.load(java.nio.file.Path.of("scripts/config/population"));
    }
}
