package server.knowledge;

import config.KnowledgeConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads and refreshes {@link WorldKnowledge} off the boot path: the knowledge file and travel.yaml once, the
 * live overlay at boot and again after every drop or shop reload. All work runs on one daemon thread, in
 * request order, and each request carries a generation; a request with a newer one queued behind it is
 * skipped, so a slow, older {@code SELECT} can never overwrite newer rows.
 */
public final class KnowledgeLoader {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeLoader.class);
    static final String TRAVEL_FILE = "travel.yaml";
    static final String ALIAS_FILE = "aliases.yaml";
    private static final String FIELDS_FILE = "fields.yaml";
    /** One generated population/fields.yaml row: {@code - { mapId: 100010000, ... capacity: 6 }}. */
    private static final Pattern FIELD_ROW = Pattern.compile("mapId:\\s*(\\d+).*?capacity:\\s*(\\d+)");
    private static final long MB = 1024 * 1024;

    private static final AtomicLong requested = new AtomicLong();
    private static final AtomicBoolean fileRequested = new AtomicBoolean(true);
    private static volatile ExecutorService worker;
    private static volatile KnowledgeFile file;
    private static volatile List<TravelLeg> travel = List.of();
    private static volatile AliasCatalog aliases = AliasCatalog.EMPTY;
    private static volatile Map<Integer, Integer> fields = Map.of();
    private static volatile List<String> stale = List.of();
    private static volatile String lastOverlay = "never";
    private static volatile boolean failureLogged;

    private KnowledgeLoader() {
    }

    /** Boot: after the DB migrations, independent of the living world. No-op when {@code knowledge.ENABLED} is off. */
    public static synchronized void start() {
        KnowledgeConfig config = YamlConfig.config.knowledge;
        if (!config.ENABLED) {
            log.info("[knowledge] disabled (knowledge.ENABLED in config.yaml)");
            return;
        }
        try {
            ContentChat.installAgentSeams(); // each seam stays inert until the snapshot is ready
        } catch (RuntimeException | LinkageError failure) {
            // the agent classes initialise here, earlier than their own boot: a failure costs the companion seams, not the boot
            log.warn("[knowledge] companion seams not installed; companions answer as before", failure);
        }
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "knowledge-loader");
                thread.setDaemon(true);
                return thread;
            });
        }
        submit();
    }

    /**
     * Re-read the knowledge file, travel.yaml, aliases.yaml and fields.yaml as well as the overlay (for {@code !knowledge
     * reload}); false when nothing was queued because knowledge is disabled.
     */
    public static boolean reload() {
        fileRequested.set(true);
        requestOverlayRebuild();
        return worker != null;
    }

    /**
     * Rebuild the overlay from the DB. Called after {@code MonsterInformationProvider.clearDrops()} and
     * {@code ShopFactory.reloadShops()} (GM reload commands, the database console, the admin port); a no-op
     * until {@link #start()} ran.
     */
    public static void requestOverlayRebuild() {
        if (worker != null) {
            submit();
        }
    }

    private static void submit() {
        long generation = requested.incrementAndGet();
        worker.execute(() -> run(generation));
    }

    private static void run(long generation) {
        if (generation < requested.get()) {
            return; // a newer request is queued behind this one and does the same work with fresher rows
        }
        Path dir = Path.of(YamlConfig.config.knowledge.CONTENT_DIR);
        long started = System.nanoTime();
        try {
            boolean fileLoaded = false;
            if (fileRequested.getAndSet(false) || file == null) {
                KnowledgeFile next = KnowledgeFile.read(dir.resolve(KnowledgeSources.FILE_NAME));
                travel = TravelCatalog.load(dir.resolve(TRAVEL_FILE), next);
                aliases = AliasCatalog.load(dir.resolve(ALIAS_FILE), next);
                fields = readFields(Path.of(YamlConfig.config.population.CONTENT_DIR).resolve(FIELDS_FILE));
                file = next;
                fileLoaded = true;
            }
            long fileNanos = System.nanoTime() - started;
            KnowledgeOverlay overlay = KnowledgeOverlayLoader.load();
            long overlayNanos = System.nanoTime() - started - fileNanos;
            WorldKnowledge previous = WorldKnowledge.current();
            boolean rebuilt = !(previous.ready() && previous.isFrom(file));
            WorldKnowledge next = rebuilt
                    ? WorldKnowledge.build(file, travel, aliases, fields, overlay, generation)
                    : previous.withOverlay(overlay, generation);
            if (fileLoaded) {
                checkStaleness(file); // before the publish: a content lint caches the stale list with the snapshot
            }
            if (rebuilt) {
                loadGuides(dir.resolve(GuideImport.FILE_NAME), next); // composite refs resolve against this snapshot's names
            }
            WorldKnowledge.publish(next);
            lastOverlay = Instant.now() + " (" + overlayNanos / 1_000_000 + " ms)";
            failureLogged = false;
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            if (fileLoaded) {
                log.info("[knowledge] loaded {} maps, {} mobs, {} items, {} npcs, {} quests, {} script spawns, {} travel legs, "
                                + "{} aliases; overlay {} drops, {} global, {} shop rows, {} plife; file {} ms, overlay {} ms, total {} ms, ~{} MB heap",
                        file.maps.size(), file.mobs.size(), file.items.size(), file.npcs.size(), file.quests.size(),
                        file.scriptSpawns.size(), travel.size(), aliases.aliases.size(), overlay.dropRows(), overlay.globalDropRows(),
                        overlay.shopRows(), overlay.lifeRows(), fileNanos / 1_000_000, overlayNanos / 1_000_000, totalMs,
                        Math.max(1, next.approxBytes() / MB));
            } else {
                log.info("[knowledge] overlay rebuilt: {} drops, {} global, {} shop rows, {} plife in {} ms",
                        overlay.dropRows(), overlay.globalDropRows(), overlay.shopRows(), overlay.lifeRows(), totalMs);
            }
        } catch (Exception failure) {
            if (!failureLogged) {
                failureLogged = true;
                log.warn("[knowledge] load failed ({}): {}", WorldKnowledge.current().ready()
                        ? "keeping the previous snapshot" : "content answers stay off until a reload succeeds", failure.toString());
            }
        }
    }

    /** The guide claims for a new snapshot; a broken guide file costs the guide answers, never the snapshot. */
    private static void loadGuides(Path path, WorldKnowledge next) {
        if (!YamlConfig.config.knowledge.GUIDES) {
            GuideKnowledge.publish(GuideKnowledge.EMPTY);
            return;
        }
        try {
            GuideKnowledge guides = GuideKnowledge.load(path, next);
            GuideKnowledge.publish(guides);
            log.info("[knowledge] {}", guides.status());
        } catch (Exception failure) {
            GuideKnowledge.publish(GuideKnowledge.EMPTY);
            log.warn("[knowledge] guide file {} not loaded: {}", path, failure.toString());
        }
    }

    /**
     * Open-world map capacities from population/fields.yaml (read line by line: the file is generated, one map
     * per row), so training answers prefer the maps citizens actually train on. Empty when the file is missing.
     */
    static Map<Integer, Integer> readFields(Path yaml) throws IOException {
        Map<Integer, Integer> out = new HashMap<>();
        if (!Files.exists(yaml)) {
            log.warn("[knowledge] {} missing: training answers use the knowledge file only", yaml);
            return out;
        }
        for (String line : Files.readAllLines(yaml, StandardCharsets.US_ASCII)) {
            Matcher row = FIELD_ROW.matcher(line);
            if (row.find()) {
                out.put(Integer.parseInt(row.group(1)), Integer.parseInt(row.group(2)));
            }
        }
        return out;
    }

    /** Compares the file's source fingerprint with a directory listing; logs each changed input once per load. */
    static List<String> checkStaleness(KnowledgeFile loaded) {
        List<String> changed = List.of();
        try {
            changed = changedInputs(loaded);
            for (String input : changed) {
                if ("inputs".equals(input)) {
                    log.warn("[knowledge] stale: the exporter's input list changed, run tools\\knowledge-export.bat");
                } else {
                    log.warn("[knowledge] stale: {} changed since export, run tools\\knowledge-export.bat", input);
                }
            }
        } catch (Exception failure) {
            log.warn("[knowledge] staleness check failed: {}", failure.toString());
        }
        stale = List.copyOf(changed);
        return stale;
    }

    /**
     * The exporter inputs (paths relative to the server directory) whose file count or size differs from the file's
     * fingerprint, plus "inputs" when the exporter's input list changed; empty when the file is fresh. No logging.
     */
    static List<String> changedInputs(KnowledgeFile loaded) throws IOException {
        Map<String, KnowledgeSources.Source> now = KnowledgeSources.measure(Path.of(".")).stream()
                .collect(Collectors.toMap(KnowledgeSources.Source::path, Function.identity()));
        List<String> changed = new ArrayList<>();
        for (KnowledgeSources.Source exported : loaded.sources) {
            KnowledgeSources.Source current = now.get(exported.path());
            if (current == null || current.fileCount() != exported.fileCount()) {
                changed.add(exported.path());
            } else if (current.totalBytes() != exported.totalBytes()
                    && KnowledgeSources.lfBytes(Path.of("."), exported.path()) != exported.totalBytes()) {
                changed.add(exported.path()); // a size difference that is not just CRLF working files
            }
        }
        if (loaded.sources.size() != now.size()) {
            changed.add("inputs");
        }
        return changed;
    }

    /** What the last file load's staleness check found changed (empty when fresh or never loaded). */
    static List<String> staleSources() {
        return stale;
    }

    /** One line for {@code !knowledge status}. */
    public static String status() {
        WorldKnowledge knowledge = WorldKnowledge.current();
        if (!knowledge.ready()) {
            return worker == null ? "knowledge: off" : "knowledge: not ready";
        }
        return "knowledge " + knowledge.dataVersion() + ": " + knowledge.maps().size() + " maps, " + knowledge.mobs().size()
                + " mobs, " + knowledge.items().size() + " items, " + knowledge.overlay().dropRows() + " drops, "
                + knowledge.overlay().shopRows() + " shop rows, " + knowledge.names().aliasCount() + " aliases ("
                + aliases.dropped.size() + " dropped), ~" + Math.max(1, knowledge.approxBytes() / MB) + " MB; "
                + (stale.isEmpty() ? "fresh" : "stale: " + String.join(", ", stale)) + "; last overlay " + lastOverlay
                + "; " + GuideKnowledge.current().status();
    }
}
