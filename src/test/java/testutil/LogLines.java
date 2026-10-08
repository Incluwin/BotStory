package testutil;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** One class's INFO and higher log lines while a test runs; the test configuration keeps every logger off. */
public final class LogLines implements AutoCloseable {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final LoggerContext context = LoggerContext.getContext(false);
    private final String loggerName;
    private final AbstractAppender appender;

    public LogLines(Class<?> source) {
        loggerName = source.getName();
        appender = new AbstractAppender("test-lines-" + loggerName, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerConfig config = new LoggerConfig(loggerName, Level.INFO, false);
        config.addAppender(appender, Level.INFO, null);
        context.getConfiguration().addLogger(loggerName, config);
        context.updateLoggers();
    }

    public List<String> startingWith(String prefix) {
        return lines.stream().filter(line -> line.startsWith(prefix)).toList();
    }

    @Override
    public void close() {
        context.getConfiguration().removeLogger(loggerName);
        context.updateLoggers();
        appender.stop();
    }
}
