package tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import config.YamlConfig;
import database.note.NoteRowMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Frz (Big Daddy)
 * @author The Real Spookster - some modifications to this beautiful code
 * @author Ronan - some connection pool to this beautiful code
 */
public class DatabaseConnection {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
    private static HikariDataSource dataSource;
    private static Jdbi jdbi;

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Unable to get connection - connection pool is uninitialized");
        }

        return dataSource.getConnection();
    }

    public static boolean isInitialized() {
        return dataSource != null;
    }

    /** Read-only pool access for modules that require a DataSource boundary. */
    public static DataSource dataSource() {
        if (dataSource == null) throw new IllegalStateException("Database connection pool is uninitialized");
        return dataSource;
    }

    public static String poolStats() {
        if (dataSource == null || dataSource.getHikariPoolMXBean() == null) {
            return "dbPool=uninitialized";
        }
        return "dbPool active=" + dataSource.getHikariPoolMXBean().getActiveConnections()
                + " idle=" + dataSource.getHikariPoolMXBean().getIdleConnections()
                + " total=" + dataSource.getHikariPoolMXBean().getTotalConnections()
                + " waiting=" + dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
                + " max=" + dataSource.getMaximumPoolSize()
                + " connTimeoutMs=" + dataSource.getConnectionTimeout();
    }

    public static Handle getHandle() {
        if (jdbi == null) {
            throw new IllegalStateException("Unable to get handle - connection pool is uninitialized");
        }

        return jdbi.open();
    }

    private static String envOrConfig(String envKey, String configValue) {
        String override = System.getenv(envKey);
        return override != null ? override : configValue;
    }

    private static String getDbUrl() {
        String host = envOrConfig("DB_HOST", YamlConfig.config.server.DB_HOST);
        return String.format(YamlConfig.config.server.DB_URL_FORMAT, host);
    }

    public static String configuredUsername() {
        return setting("DB_USER", YamlConfig.config.server.DB_USER);
    }

    public static String configuredPassword() {
        // cosmic_agents uses DB_PASSWORD; the FriendServer stack (.env, start_all.bat) exports DB_PASS.
        return setting("DB_PASSWORD", setting("DB_PASS", YamlConfig.config.server.DB_PASS));
    }

    static HikariConfig getConfig() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(getDbUrl());
        config.setUsername(configuredUsername());
        config.setPassword(configuredPassword());

        final int initFailTimeoutSeconds = YamlConfig.config.server.INIT_CONNECTION_POOL_TIMEOUT;
        config.setInitializationFailTimeout(SECONDS.toMillis(initFailTimeoutSeconds));
        config.setConnectionTimeout(SECONDS.toMillis(intSetting("cosmic.db.connectionTimeoutSeconds", "COSMIC_DB_CONNECTION_TIMEOUT_SECONDS", 30))); // Hikari default
        config.setMaximumPoolSize(intSetting("cosmic.db.maxPoolSize", "COSMIC_DB_MAX_POOL_SIZE", 10)); // Hikari default

        config.addDataSourceProperty("cachePrepStmts", true);
        config.addDataSourceProperty("prepStmtCacheSize", 25);
        config.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);

        // Every session runs in UTC, so a java.sql.Timestamp crosses JDBC as its exact instant and NOW() is the UTC
        // wall clock. Without this the session zone is the server's SYSTEM zone (America/New_York on the live host),
        // and Connector/J renders a Timestamp in the JVM zone its connection captured when it opened: the machine zone
        // before Server sets server.TIMEZONE, GMT after. A GMT wall clock in New York's spring-forward hour then fails
        // the write ("Incorrect datetime value"). The driver sets the zone as '+00:00', which needs no time zone tables.
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", true);

        return config;
    }

    private static String setting(String environmentName, String fallback) {
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intSetting(String propertyName, String environmentName, int defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        String rawValue = propertyValue != null ? propertyValue : System.getenv(environmentName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(rawValue.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid integer setting {} / {}='{}'", propertyName, environmentName, rawValue);
        }
        return defaultValue;
    }

    /**
     * Initiate connection to the database
     *
     * @return true if connection to the database initiated successfully, false if not successful
     */
    public static boolean initializeConnectionPool() {
        if (dataSource != null) {
            return true;
        }

        final HikariConfig config = getConfig();
        log.info("Initializing database connection pool. Connecting to:'{}' with user:'{}'", config.getJdbcUrl(),
                config.getUsername());
        Instant initStart = Instant.now();
        try {
            dataSource = new HikariDataSource(config);
            initializeJdbi(dataSource);
            long initDuration = Duration.between(initStart, Instant.now()).toMillis();
            log.info("Connection pool initialized in {} ms", initDuration);
            return true;
        } catch (Exception e) {
            long timeout = Duration.between(initStart, Instant.now()).getSeconds();
            log.error("Failed to initialize database connection pool. Gave up after {} seconds.", timeout);
        }

        // Timed out - failed to initialize
        return false;
    }

    private static void initializeJdbi(DataSource dataSource) {
        jdbi = Jdbi.create(dataSource)
                .registerRowMapper(new NoteRowMapper());
    }
}
