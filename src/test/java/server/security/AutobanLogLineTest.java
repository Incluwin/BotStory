package server.security;

import client.BotClient;
import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.autoban.AutobanManager;
import config.YamlConfig;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An Agent's autoban point or alert is a server bug, not cheating to review. Citizen Luon sold loot at an NPC shop and
 * wrote one "Autoban - chr Luon caused GENERAL meso/min ..." INFO line per sale to the main server log. An Agent's
 * autoban lines go to DEBUG, and its signal still reaches the in-memory ring that the GM skill probe reads. A human's
 * lines are unchanged.
 */
class AutobanLogLineTest {
    private static final String MESO_REASON = "meso/min 5051504 > cap 5000000";

    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private MockedStatic<ThreadManager> threads;
    private AutobanLines lines;
    private boolean useAutoban;
    private boolean useAutobanLog;

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
        SecurityEventRuntime.installStoreForTesting(new DiscardingStore());
        useAutoban = YamlConfig.config.server.USE_AUTOBAN;
        useAutobanLog = YamlConfig.config.server.USE_AUTOBAN_LOG;
        YamlConfig.config.server.USE_AUTOBAN = false;
        YamlConfig.config.server.USE_AUTOBAN_LOG = true;
        lines = new AutobanLines();
    }

    @AfterEach
    void tearDown() {
        lines.close();
        YamlConfig.config.server.USE_AUTOBAN_LOG = useAutobanLog;
        YamlConfig.config.server.USE_AUTOBAN = useAutoban;
        SecurityEventRuntime.clearForTesting();
        threads.close();
        servers.close();
        timers.close();
    }

    @Test
    void anAgentsAutobanLinesGoToDebug() {
        Character agent = character("Luon", new BotClient(0, 1));
        long floor = latestSequence();

        new AutobanManager(agent).addPoint(AutobanFactory.GENERAL, MESO_REASON);
        AutobanFactory.DISTANCE_HACK.alert(agent, MESO_REASON);

        assertEquals(List.of(
                        "DEBUG Autoban - chr Luon caused GENERAL " + MESO_REASON,
                        "DEBUG Autoban alert - chr Luon caused DISTANCE_HACK-" + MESO_REASON),
                lines.all(), "no INFO line, the same text at DEBUG");
        assertEquals(2, SecurityEventRuntime.snapshot().stream().filter(event -> event.sequence() > floor).count(),
                "the GM skill probe still reads both signals");
    }

    @Test
    void aHumansAutobanLinesAreUnchanged() {
        Character human = character("Crewfriend", Client.createMock());

        new AutobanManager(human).addPoint(AutobanFactory.GENERAL, MESO_REASON);
        AutobanFactory.DISTANCE_HACK.alert(human, MESO_REASON);

        assertEquals(List.of(
                        "INFO Autoban - chr Crewfriend caused GENERAL " + MESO_REASON,
                        "INFO Autoban alert - chr Crewfriend caused DISTANCE_HACK-" + MESO_REASON),
                lines.all());
    }

    /** Some handlers pass c.getPlayer() before a character is attached; alert accepts null and logs it like a human's. */
    @Test
    void anAlertWithNoCharacterStaysAtInfo() {
        AutobanFactory.DISTANCE_HACK.alert(null, MESO_REASON);

        assertEquals(List.of("INFO Autoban alert - chr  caused DISTANCE_HACK-" + MESO_REASON), lines.all());
    }

    private static long latestSequence() {
        List<SecurityEvent> events = SecurityEventRuntime.snapshot();
        return events.isEmpty() ? 0L : events.getLast().sequence();
    }

    private static Character character(String name, Client client) {
        Character chr = Character.getDefault(client);
        chr.setName(name);
        client.setPlayer(chr);
        return chr;
    }

    private static final class DiscardingStore implements SecurityEventStore {
        @Override public void append(SecurityEvent event) { }
        @Override public boolean markReviewed(UUID eventId, String reviewer, String note) { return true; }
        @Override public List<SecurityEventReviewRecord> findOpen(int limit) { return List.of(); }
        @Override public int deleteReviewedBefore(Instant cutoff) { return 0; }
    }

    /** Every line of the two autoban loggers, DEBUG included; the test configuration keeps every logger off. */
    private static final class AutobanLines implements AutoCloseable {
        private static final List<String> LOGGERS = List.of(AutobanManager.class.getName(), AutobanFactory.class.getName());
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final LoggerContext context = LoggerContext.getContext(false);
        private final AbstractAppender appender;

        AutobanLines() {
            appender = new AbstractAppender("autoban-lines", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
                }
            };
            appender.start();
            for (String name : LOGGERS) {
                LoggerConfig config = new LoggerConfig(name, Level.DEBUG, false);
                config.addAppender(appender, Level.DEBUG, null);
                context.getConfiguration().addLogger(name, config);
            }
            context.updateLoggers();
        }

        List<String> all() {
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
