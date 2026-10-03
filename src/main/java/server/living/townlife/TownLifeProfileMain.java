package server.living.townlife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Command line generator and auditor for TownLife profiles, run from the server directory:
 * {@code tools\town-life-profiles.bat generate <mapId>[=<profileId>] ... [--out <dir>] [--index] [--replace]
 * [--admission AMBIENT|MANUAL_ONLY]} or {@code check [<profileId|mapId> ...]}. Exit code 1 on any refusal or error,
 * 2 on bad arguments.
 */
public final class TownLifeProfileMain {
    /** The hand-authored Victoria profiles: persona/rumor YAML and tests depend on their venue ids; generate never writes them. */
    public static final Set<String> HAND_AUTHORED = Set.of(
            "lith-harbor", "henesys", "kerning-city", "perion", "ellinia", "nautilus", "sleepywood");
    private static final Path RESOURCES = Path.of("src/main/resources/agents/town-life");
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern MAP_ID = Pattern.compile("[1-9]\\d{0,8}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TownLifeProfileMain() {
    }

    /** An index.json entry: its file and the map and profile id inside it. */
    private record Entry(String file, int mapId, String profileId) {
    }

    public static void main(String[] args) throws IOException {
        Configurator.initialize(new DefaultConfiguration()); // console at ERROR only: never the server's log files
        int status;
        if (args.length > 0 && args[0].equals("generate")) {
            status = generate(List.of(args).subList(1, args.length), RESOURCES);
        } else if (args.length > 0 && args[0].equals("check")) {
            status = check(List.of(args).subList(1, args.length));
        } else {
            status = usage();
        }
        System.exit(status);
    }

    private static int usage() {
        System.out.println("Usage: town-life-profiles generate <mapId>[=<profileId>] ... [--out <dir>] [--index]"
                + " [--replace] [--admission AMBIENT|MANUAL_ONLY]");
        System.out.println("       town-life-profiles check [<profileId|mapId> ...]");
        return 2;
    }

    /**
     * Generates the given towns into --out (default: the resources dir). A town already indexed is regenerated only
     * with --replace, under its indexed profile id and file; hand-authored ids, ids indexed for another map and towns
     * listed twice are refused.
     */
    static int generate(List<String> args, Path resources) throws IOException {
        Path out = resources;
        boolean index = false;
        boolean replace = false;
        AgentTownLifeAdmissionMode admission = AgentTownLifeAdmissionMode.AMBIENT;
        List<String[]> towns = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ((arg.equals("--out") || arg.equals("--admission")) && i + 1 == args.size()) {
                return usage();
            }
            switch (arg) {
                case "--out" -> out = Path.of(args.get(++i));
                case "--index" -> index = true;
                case "--replace" -> replace = true;
                case "--admission" -> {
                    try {
                        admission = AgentTownLifeAdmissionMode.valueOf(args.get(++i));
                    } catch (IllegalArgumentException unknown) {
                        return usage();
                    }
                }
                default -> {
                    String[] town = arg.split("=", 2);
                    if (!MAP_ID.matcher(town[0]).matches() || town.length > 1 && !PROFILE_ID.matcher(town[1]).matches()) {
                        return usage();
                    }
                    towns.add(town);
                }
            }
        }
        if (towns.isEmpty()) {
            return usage();
        }
        List<String> files = indexFiles(out);
        List<Entry> indexed = new ArrayList<>();
        for (Path dir : out.equals(resources) ? List.of(out) : List.of(resources, out)) {
            for (String file : indexFiles(dir)) {
                JsonNode profile = MAPPER.readTree(dir.resolve(file).toFile());
                indexed.add(new Entry(file, profile.path("mapId").asInt(), profile.path("profileId").asText()));
            }
        }
        int failures = 0;
        List<Entry> written = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String[] town : towns) {
            int mapId = Integer.parseInt(town[0]);
            Entry existing = indexed.stream().filter(entry -> entry.mapId() == mapId).findFirst().orElse(null);
            String profileId = existing != null ? existing.profileId() : town.length > 1 ? town[1] : null;
            String refusal = existing != null && !replace ? "already in index.json as " + profileId + " (use --replace)"
                    : existing != null && town.length > 1 && !town[1].equals(profileId) ? "indexed as " + profileId
                    : profileId == null ? null : refusal(mapId, profileId, indexed, written, out);
            TownMapData data = null;
            if (refusal == null) {
                try {
                    data = TownMapData.load(mapId);
                } catch (RuntimeException failure) {
                    refusal = String.valueOf(failure.getMessage());
                }
            }
            if (refusal == null && profileId == null) {
                profileId = TownLifeProfileGenerator.slug(TownLifeProfileGenerator.clean(data.mapName()));
                refusal = refusal(mapId, profileId, indexed, written, out);
            }
            if (refusal != null) {
                System.out.println("REFUSED " + String.join("=", town) + ": " + refusal);
                failures++;
                continue;
            }
            TownLifeProfileGenerator.Result result;
            try {
                result = TownLifeProfileGenerator.generate(data, profileId, admission);
            } catch (RuntimeException failure) {
                System.out.println("REFUSED " + String.join("=", town) + ": " + failure);
                failures++;
                continue;
            }
            System.out.println(profileId + " (" + mapId + ", " + data.mapName() + ")");
            result.report().forEach(line -> System.out.println("  " + line));
            result.warnings().forEach(line -> System.out.println("  WARN " + line));
            if (!result.ok()) {
                result.problems().forEach(line -> System.out.println("  ERROR " + line));
                System.out.println("  REFUSED: not written");
                failures++;
                continue;
            }
            String file = existing != null ? existing.file() : profileId + ".json";
            Files.createDirectories(out);
            write(out.resolve(file), result.json());
            System.out.println("  wrote " + out.resolve(file));
            written.add(new Entry(file, mapId, profileId));
            if (!files.contains(file) && !added.contains(file)) {
                added.add(file);
            }
        }
        if (index && !added.isEmpty()) {
            List<String> all = new ArrayList<>(files);
            all.addAll(added);
            Set<Integer> mapIds = new HashSet<>();
            Set<String> profileIds = new HashSet<>();
            for (String file : all) {
                JsonNode profile = MAPPER.readTree(out.resolve(file).toFile());
                if (!mapIds.add(profile.path("mapId").asInt()) || !profileIds.add(profile.path("profileId").asText())) {
                    System.out.println("REFUSED index.json: " + file + " repeats a map or profile id; index not written");
                    return 1;
                }
            }
            StringJoiner lines = new StringJoiner(",\n", "{\n  \"profiles\": [\n", "\n  ]\n}\n");
            all.forEach(file -> lines.add("    \"" + file + "\""));
            write(out.resolve("index.json"), lines.toString());
            System.out.println("index.json: added " + added);
        }
        return failures == 0 ? 0 : 1;
    }

    /**
     * Why the profile id may not be written for the map, or null. Its file may only replace a profile of the same map,
     * never index.json, ambient-deployment.json or another map's profile.
     */
    private static String refusal(int mapId, String profileId, List<Entry> indexed, List<Entry> written, Path out)
            throws IOException {
        if (!PROFILE_ID.matcher(profileId).matches()) {
            return "'" + profileId + "' is not a kebab-case profile id";
        }
        if (HAND_AUTHORED.contains(profileId)) {
            return profileId + " is hand-authored";
        }
        Path file = out.resolve(profileId + ".json");
        if (profileId.equals("index")
                || Files.exists(file) && MAPPER.readTree(file.toFile()).path("mapId").asInt() != mapId) {
            return file + " is not a profile of map " + mapId;
        }
        if (indexed.stream().anyMatch(entry -> entry.profileId().equals(profileId) && entry.mapId() != mapId)) {
            return profileId + " is already indexed for another map";
        }
        if (written.stream().anyMatch(entry -> entry.mapId() == mapId || entry.profileId().equals(profileId))) {
            return "already generated in this run";
        }
        return null;
    }

    /** Writes beside the target, then moves it into place, so a failed write never leaves a truncated file. */
    private static void write(Path target, String text) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int check(List<String> selectors) throws IOException {
        if (!Files.exists(RESOURCES.resolve("index.json"))) {
            System.out.println("ERROR no " + RESOURCES.resolve("index.json") + " (run from the server directory)");
            return 1;
        }
        int errors = 0;
        List<String> unmatched = new ArrayList<>(selectors);
        for (String file : indexFiles(RESOURCES)) {
            AgentTownLifeProfile profile;
            try {
                profile = MAPPER.readValue(RESOURCES.resolve(file).toFile(), AgentTownLifeProfile.class);
            } catch (IOException failure) {
                System.out.println(file + ": ERROR does not parse: " + failure.getMessage());
                errors++;
                continue;
            }
            String mapId = String.valueOf(profile.mapId());
            if (!selectors.isEmpty() && !selectors.contains(profile.profileId()) && !selectors.contains(mapId)) {
                continue;
            }
            unmatched.remove(profile.profileId());
            unmatched.remove(mapId);
            TownLifeGeometryCheck.Result result = TownLifeGeometryCheck.check(profile, TownMapData.load(profile.mapId()));
            System.out.println(profile.profileId() + " (" + mapId + "): " + result.errors().size() + " errors, "
                    + result.warnings().size() + " warnings");
            result.errors().forEach(line -> System.out.println("  ERROR " + line));
            result.warnings().forEach(line -> System.out.println("  WARN " + line));
            errors += result.errors().size();
        }
        unmatched.forEach(selector -> System.out.println("ERROR no profile " + selector + " in index.json"));
        return errors == 0 && unmatched.isEmpty() ? 0 : 1;
    }

    private static List<String> indexFiles(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        Path index = dir.resolve("index.json");
        if (Files.exists(index)) {
            MAPPER.readTree(index.toFile()).path("profiles").forEach(file -> files.add(file.asText()));
        }
        return files;
    }
}
