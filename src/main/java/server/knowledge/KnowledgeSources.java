package server.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The exact inputs of the knowledge file and their fingerprint (file count + total bytes). The exporter
 * embeds the fingerprint; the boot staleness check recomputes it from a directory listing (no file reads).
 * Only what the exporter reads is listed, so living-world YAML edits and the output file never mark it stale.
 */
public final class KnowledgeSources {
    public static final String FILE_NAME = "server-knowledge.json";

    /** Paths relative to the server directory: single files or directories (walked for .xml / .js). */
    public static final List<String> INPUTS = List.of(
            "wz/String.wz/Map.img.xml", "wz/String.wz/Mob.img.xml", "wz/String.wz/Npc.img.xml",
            "wz/String.wz/Eqp.img.xml", "wz/String.wz/Consume.img.xml", "wz/String.wz/Etc.img.xml",
            "wz/String.wz/Ins.img.xml", "wz/String.wz/Cash.img.xml", "wz/String.wz/Pet.img.xml",
            "wz/Map.wz/Map", "wz/Mob.wz",
            "wz/Quest.wz/QuestInfo.img.xml", "wz/Quest.wz/Check.img.xml", "wz/Quest.wz/Act.img.xml",
            "wz/Item.wz/Consume", "wz/Item.wz/Install", "wz/Item.wz/Etc", "wz/Item.wz/Cash", "wz/Item.wz/Pet",
            "wz/Character.wz/Accessory", "wz/Character.wz/Cap", "wz/Character.wz/Cape", "wz/Character.wz/Coat",
            "wz/Character.wz/Dragon", "wz/Character.wz/Glove", "wz/Character.wz/Longcoat",
            "wz/Character.wz/Pants", "wz/Character.wz/PetEquip", "wz/Character.wz/Ring",
            "wz/Character.wz/Shield", "wz/Character.wz/Shoes", "wz/Character.wz/TamingMob",
            "wz/Character.wz/Weapon",
            "scripts/npc", "scripts/event", "scripts/reactor", "scripts/portal", "scripts/map",
            "scripts/quest", "scripts/item");

    public record Source(String path, int fileCount, long totalBytes) {
    }

    private KnowledgeSources() {
    }

    public static List<Source> measure(Path root) throws IOException {
        List<Source> out = new ArrayList<>(INPUTS.size());
        for (String input : INPUTS) {
            Path path = root.resolve(input);
            int count = 0;
            long bytes = 0;
            if (Files.isRegularFile(path)) {
                count = 1;
                bytes = Files.size(path);
            } else if (Files.isDirectory(path)) {
                for (Path file : files(path)) {
                    count++;
                    bytes += Files.size(file);
                }
            }
            out.add(new Source(input, count, bytes));
        }
        return out;
    }

    /**
     * Like {@link #measure} but counts bytes as if every file had LF line endings, so a checkout with CRLF
     * working files (git normalises them, the content is the same) fingerprints like an LF one. Reads every file;
     * the exporter records this, and the boot check falls back to it only for an input whose listed size differs.
     */
    public static List<Source> measureLf(Path root) throws IOException {
        List<Source> out = new ArrayList<>(INPUTS.size());
        for (String input : INPUTS) {
            Path path = root.resolve(input);
            List<Path> inputFiles = Files.isRegularFile(path) ? List.of(path)
                    : Files.isDirectory(path) ? files(path) : List.of();
            out.add(new Source(input, inputFiles.size(), lfBytes(inputFiles)));
        }
        return out;
    }

    /** Total size of the files with every CR byte dropped. */
    static long lfBytes(List<Path> inputFiles) throws IOException {
        long bytes = 0;
        for (Path file : inputFiles) {
            byte[] content = Files.readAllBytes(file);
            bytes += content.length;
            for (byte b : content) {
                if (b == '\r') {
                    bytes--;
                }
            }
        }
        return bytes;
    }

    /** {@link #lfBytes} of one input path (a file or a directory walked like {@link #measure}). */
    static long lfBytes(Path root, String input) throws IOException {
        Path path = root.resolve(input);
        return lfBytes(Files.isRegularFile(path) ? List.of(path) : Files.isDirectory(path) ? files(path) : List.of());
    }

    /** Input files under a directory, sorted; skips {@code *_old.js} leftovers the server never runs. */
    public static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".img.xml") || name.endsWith(".js") && !name.endsWith("_old.js");
            }).sorted().toList();
        }
    }
}
