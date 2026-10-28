package server.economy.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 10-28 boot crash: EconomyLedger.start() runs before Server starts the TimerManager, and registering its tick
 * there threw (the executors are null until TimerManager.start()), which ended the boot. The tick is scheduled with
 * the other timers instead.
 */
class EconomyLedgerBootOrderTest {
    private static final Path SERVER = Path.of("src/main/java/net/server/Server.java");
    private static final Path LEDGER = Path.of("src/main/java/server/economy/ledger/EconomyLedger.java");

    @Test
    void startNeverTouchesTheTimerManager() throws IOException {
        Matcher start = Pattern.compile("public static synchronized void start\\(\\) \\{(.*?)\\n    }", Pattern.DOTALL)
                .matcher(Files.readString(LEDGER));
        assertTrue(start.find(), "EconomyLedger.start()");
        assertFalse(start.group(1).contains("TimerManager"), start.group(1));
    }

    @Test
    void ticksAreScheduledOnceTheTimerManagerRuns() throws IOException {
        String server = Files.readString(SERVER);
        int timersStart = server.indexOf("tMan.start();");
        int ledgerStart = server.indexOf("EconomyLedger.start();");
        int ticks = server.indexOf("EconomyLedger.scheduleTicks();");
        assertTrue(timersStart > 0 && ledgerStart > 0 && ticks > 0, "all three calls are in Server.java");
        assertTrue(ticks > timersStart, "scheduleTicks after tMan.start()");
    }
}
