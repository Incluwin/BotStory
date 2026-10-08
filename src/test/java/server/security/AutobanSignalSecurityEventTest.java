package server.security;

import client.BotClient;
import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.autoban.AutobanManager;
import net.server.Server;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ThreadManager;
import server.TimerManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * An Agent's autoban signal is a server bug to diagnose, not cheating to review. Citizen Ela42 sold 72 looted items at
 * an NPC shop, passed the 5M meso/min cap and wrote CRITICAL rows to security_event. Each signal from an Agent stays
 * in the in-memory ring that the GM skill probe reads, but it is not persisted, not alerted on and not logged at INFO
 * or higher. A human's signal is unchanged.
 */
class AutobanSignalSecurityEventTest {
    private static final int AGENT_ID = 42_000_042;
    private static final int HUMAN_ID = 42_000_043;
    private static final String MESO_REASON = "meso/min 8437300 > cap 5000000";

    private final RecordingStore store = new RecordingStore();
    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private MockedStatic<ThreadManager> threads;
    private SecurityLogLines lines;

    @BeforeEach
    void setUp() {
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(mock(Server.class));
        ThreadManager databaseLane = mock(ThreadManager.class);
        when(databaseLane.newDatabaseTask(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return true;
        });
        threads = mockStatic(ThreadManager.class);
        threads.when(ThreadManager::getInstance).thenReturn(databaseLane);
        SecurityEventRuntime.installStoreForTesting(store);
        lines = new SecurityLogLines();
    }

    @AfterEach
    void tearDown() {
        lines.close();
        SecurityEventRuntime.clearForTesting();
        threads.close();
        servers.close();
        timers.close();
    }

    @Test
    void anAgentsAutobanSignalsStayInMemoryOnly() {
        Character agent = character("Ela42", AGENT_ID, new BotClient(0, 1));
        long floor = latestSequence();

        fireEverySignal(agent);

        List<SecurityEvent> retained = SecurityEventRuntime.snapshot().stream()
                .filter(event -> event.sequence() > floor).toList();
        assertEquals(List.of("GENERAL", "GENERAL", "GENERAL", "TIMESTAMP_SPAM"),
                retained.stream().map(event -> event.evidence().get("signal")).toList());
        assertEquals(List.of(SecuritySeverity.WARNING, SecuritySeverity.CRITICAL, SecuritySeverity.CRITICAL,
                        SecuritySeverity.CRITICAL),
                retained.stream().map(SecurityEvent::severity).toList());
        assertEquals(List.of(AGENT_ID, AGENT_ID, AGENT_ID, AGENT_ID),
                retained.stream().map(SecurityEvent::characterId).toList());
        assertEquals(List.of(SecurityEventType.AUTOBAN_SIGNAL), retained.stream().map(SecurityEvent::type).distinct().toList());
        long first = retained.getFirst().sequence();
        assertEquals(List.of(first, first + 1, first + 2, first + 3),
                retained.stream().map(SecurityEvent::sequence).toList(), "the sequence numbering is kept");
        assertEquals(List.of(), store.appended, "nothing reaches the security_event table");
        assertEquals(List.of(), lines.atInfoOrHigher(), "no securityEvent or securityAlert line");
    }

    @Test
    void aHumansAutobanSignalsAreUnchanged() {
        Character human = character("Crewfriend", HUMAN_ID, Client.createMock());
        long floor = latestSequence();

        fireEverySignal(human);

        List<SecurityEvent> retained = SecurityEventRuntime.snapshot().stream()
                .filter(event -> event.sequence() > floor).toList();
        assertEquals(4, retained.size());
        assertEquals(retained, store.appended, "every signal is persisted");
        assertEquals(List.of(
                        "INFO securityEvent",
                        "WARN securityEvent", "WARN securityAlert",
                        "WARN securityEvent", "WARN securityAlert",
                        "WARN securityEvent", "WARN securityAlert"),
                lines.atInfoOrHigher(), "every signal is logged and each CRITICAL one is alerted");
    }

    @Test
    void anAgentsSignalKeepsTheSharedSequence() {
        Character agent = character("Ela42", AGENT_ID, new BotClient(0, 1));
        Character human = character("Crewfriend", HUMAN_ID, Client.createMock());

        AutobanFactory.GENERAL.alert(agent, MESO_REASON);
        AutobanFactory.GENERAL.alert(human, MESO_REASON);

        List<SecurityEvent> all = SecurityEventRuntime.snapshot();
        SecurityEvent agentEvent = all.get(all.size() - 2);
        SecurityEvent humanEvent = all.get(all.size() - 1);
        assertEquals(AGENT_ID, agentEvent.characterId());
        assertEquals(HUMAN_ID, humanEvent.characterId());
        assertEquals(agentEvent.sequence() + 1, humanEvent.sequence());
        assertEquals(List.of(humanEvent), store.appended);
    }

    /** Some handlers pass c.getPlayer() before a character is attached; alert accepts null and still reports it. */
    @Test
    void aSignalWithNoCharacterIsStillPersisted() {
        AutobanFactory.GENERAL.alert(null, MESO_REASON);

        assertEquals(1, store.appended.size(), "the signal is persisted like a human's");
        SecurityEvent event = store.appended.getFirst();
        assertEquals(-1, event.characterId());
        assertEquals(-1, event.accountId());
        assertEquals(SecurityEventType.AUTOBAN_SIGNAL, event.type());
        assertEquals(List.of("INFO securityEvent"), lines.atInfoOrHigher());
    }

    /** Every place that writes an AUTOBAN_SIGNAL: AutobanFactory alert and autoban, AutobanManager points and spam. */
    private static void fireEverySignal(Character chr) {
        AutobanFactory.GENERAL.alert(chr, MESO_REASON);
        AutobanFactory.GENERAL.autoban(chr, MESO_REASON);
        AutobanManager manager = new AutobanManager(chr);
        manager.addPoint(AutobanFactory.GENERAL, MESO_REASON);
        manager.setTimestamp(7, 0, 1);
    }

    private static long latestSequence() {
        List<SecurityEvent> events = SecurityEventRuntime.snapshot();
        return events.isEmpty() ? 0L : events.getLast().sequence();
    }

    /** A real character whose client reports it as the logged-in player, as on a live server. */
    private static Character character(String name, int id, Client client) {
        Character chr = Character.getDefault(client);
        chr.setName(name);
        Character spied = spy(chr);
        doReturn(id).when(spied).getId();
        client.setPlayer(spied);
        return spied;
    }

    private static final class RecordingStore implements SecurityEventStore {
        private final List<SecurityEvent> appended = new ArrayList<>();

        @Override public void append(SecurityEvent event) { appended.add(event); }
        @Override public boolean markReviewed(UUID eventId, String reviewer, String note) { return true; }
        @Override public List<SecurityEventReviewRecord> findOpen(int limit) { return List.of(); }
        @Override public int deleteReviewedBefore(Instant cutoff) { return 0; }
    }

    /** INFO and higher lines of the SECURITY and SECURITY_ALERT loggers; the test configuration keeps every logger off. */
    private static final class SecurityLogLines implements AutoCloseable {
        private static final List<String> LOGGERS = List.of("SECURITY", "SECURITY_ALERT");
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final LoggerContext context = LoggerContext.getContext(false);
        private final AbstractAppender appender;

        SecurityLogLines() {
            appender = new AbstractAppender("security-lines", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    String message = event.getMessage().getFormattedMessage();
                    lines.add(event.getLevel() + " " + message.substring(0, Math.max(0, message.indexOf(' '))));
                }
            };
            appender.start();
            for (String name : LOGGERS) {
                LoggerConfig config = new LoggerConfig(name, Level.INFO, false);
                config.addAppender(appender, Level.INFO, null);
                context.getConfiguration().addLogger(name, config);
            }
            context.updateLoggers();
        }

        List<String> atInfoOrHigher() {
            return List.copyOf(lines);
        }

        @Override
        public void close() {
            for (String name : LOGGERS) {
                context.getConfiguration().removeLogger(name);
            }
            context.updateLoggers();
            appender.stop();
        }
    }
}
