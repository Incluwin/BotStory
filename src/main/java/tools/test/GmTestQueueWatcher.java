package tools.test;

import client.Character;
import client.Client;
import client.command.CommandsExecutor;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tails logs/gm-command-queue.txt and dispatches each line as if the named
 * GM character had typed the command in chat. Intended ONLY for dev/testing
 * on a trusted host — do NOT ship in production.
 *
 * Line format: "<characterName>|<!command ...>"
 *
 * Wire it up once in Server.init() (or wherever startup threads are kicked
 * off) with:
 *
 *     tools.test.GmTestQueueWatcher.start(
 *         java.nio.file.Paths.get("logs/gm-command-queue.txt"));
 *
 * Guard with a YAML flag so prod builds never start it.
 */
public final class GmTestQueueWatcher {
    private static final Logger log = LoggerFactory.getLogger(GmTestQueueWatcher.class);

    private final Path queue;
    private long offset;
    private ScheduledExecutorService exec;

    private GmTestQueueWatcher(Path queue) {
        this.queue = queue;
    }

    public static GmTestQueueWatcher start(Path queue) {
        GmTestQueueWatcher w = new GmTestQueueWatcher(queue);
        w.begin();
        return w;
    }

    private void begin() {
        try {
            if (!Files.exists(queue)) {
                Files.createDirectories(queue.getParent());
                Files.createFile(queue);
            }
            offset = Files.size(queue);
        } catch (IOException e) {
            log.warn("GmTestQueueWatcher: cannot init queue {}", queue, e);
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gm-test-queue");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, 500, 300, TimeUnit.MILLISECONDS);
        log.info("GmTestQueueWatcher active on {}", queue.toAbsolutePath());
    }

    public void stop() {
        if (exec != null) exec.shutdownNow();
    }

    private void tick() {
        try {
            long size = Files.size(queue);
            if (size <= offset) {
                if (size < offset) offset = 0;
                return;
            }
            try (RandomAccessFile f = new RandomAccessFile(queue.toFile(), "r")) {
                f.seek(offset);
                String line;
                while ((line = f.readLine()) != null) {
                    dispatchLine(line);
                }
                offset = f.getFilePointer();
            }
        } catch (IOException e) {
            log.warn("GmTestQueueWatcher read failed", e);
        }
    }

    private void dispatchLine(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return;
        int pipe = line.indexOf('|');
        if (pipe <= 0 || pipe == line.length() - 1) {
            log.warn("GmTestQueueWatcher: bad line {}", line);
            return;
        }
        String chrName = line.substring(0, pipe).trim();
        String message = line.substring(pipe + 1).trim();
        if (!message.startsWith("!") && !message.startsWith("@")) {
            log.warn("GmTestQueueWatcher: not a command: {}", message);
            return;
        }
        Character chr = findCharacter(chrName);
        if (chr == null) {
            log.warn("GmTestQueueWatcher: character offline: {}", chrName);
            return;
        }
        Client c = chr.getClient();
        if (c == null || !chr.isGM()) {
            log.warn("GmTestQueueWatcher: {} not a GM or no client", chrName);
            return;
        }
        try {
            CommandsExecutor.getInstance().handle(c, message);
        } catch (Throwable t) {
            log.warn("GmTestQueueWatcher: dispatch threw for '{}'", message, t);
        }
    }

    private static Character findCharacter(String name) {
        try {
            for (net.server.world.World w : Server.getInstance().getWorlds()) {
                Character chr = w.getPlayerStorage().getCharacterByName(name);
                if (chr != null) return chr;
            }
        } catch (Throwable t) {
            log.warn("lookup failed", t);
        }
        return null;
    }
}
