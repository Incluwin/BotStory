package tools;

import client.DefaultDates;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pooled connections run their sessions in UTC whatever zone the JVM had when they opened, so Cosmic's TIMESTAMP writes
 * keep their instant. Before, the session zone was SYSTEM (America/New_York on the live host) and Connector/J rendered a
 * Timestamp in the JVM zone captured at connect: GMT once Server had set server.TIMEZONE. A GMT wall clock in New York's
 * spring-forward hour (the next is 2027-03-14 02:00-03:00 UTC) then failed accounts.lastlogin and
 * characters.lastLogoutTime writes with "Incorrect datetime value", and the fall-back hour stored some instants an hour
 * off. Opt in with -Dcosmic.test.mysql=true (DB_PASS from FriendServer/.env). Only TEMPORARY tables are written.
 */
class DatabaseConnectionUtcMySqlIntegrationTest {
    private static final Instant SPRING_FORWARD_GAP_AS_GMT = Instant.parse("2027-03-14T02:30:00Z");
    private static final Instant FALL_BACK_EDT = Instant.parse("2026-11-01T05:30:00Z");
    private static final Instant FALL_BACK_EST = Instant.parse("2026-11-01T06:30:00Z");
    private static final Instant WINTER = Instant.parse("2027-01-15T12:00:00Z");
    private static final Instant[] INSTANTS = {SPRING_FORWARD_GAP_AS_GMT, FALL_BACK_EDT, FALL_BACK_EST, WINTER};
    /** 2005-05-11T00:00Z: DefaultDates.getTempban() as the GMT JVM means it. */
    private static final long TEMPBAN_SENTINEL_EPOCH = 1_115_769_600L;

    private TimeZone originalZone;
    private HikariDataSource pool;

    @BeforeEach
    void optIn() {
        assumeTrue(Boolean.getBoolean("cosmic.test.mysql"), "Enable explicitly with -Dcosmic.test.mysql=true");
        originalZone = TimeZone.getDefault();
    }

    @AfterEach
    void close() {
        if (pool != null) {
            pool.close();
        }
        if (originalZone != null) {
            TimeZone.setDefault(originalZone);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "GMT"})
    void sessionIsUtcWhicheverZoneTheJvmHadAtConnect(String zoneAtConnect) throws Exception {
        try (Connection con = connectIn(zoneAtConnect);
             Statement s = con.createStatement();
             ResultSet rs = s.executeQuery("SELECT @@session.time_zone, TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())")) {
            assertTrue(rs.next());
            assertEquals("+00:00", rs.getString(1));
            assertEquals(0L, rs.getLong(2), "NOW() is the UTC wall clock");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "GMT"})
    void timestampAndDatetimeKeepTheInstantInTheGapAndTheRepeatedHour(String zoneAtConnect) throws Exception {
        try (Connection con = connectIn(zoneAtConnect)) {
            try (Statement s = con.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE tz_probe (id INT PRIMARY KEY, ts TIMESTAMP NULL, dt DATETIME NULL)");
            }
            for (int i = 0; i < INSTANTS.length; i++) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO tz_probe VALUES (?, ?, ?)")) {
                    ps.setInt(1, i);
                    ps.setTimestamp(2, Timestamp.from(INSTANTS[i]));
                    ps.setTimestamp(3, Timestamp.from(INSTANTS[i]));
                    ps.executeUpdate();
                }
            }
            try (Statement s = con.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, UNIX_TIMESTAMP(ts), UNIX_TIMESTAMP(dt), ts, dt FROM tz_probe ORDER BY id")) {
                for (Instant expected : INSTANTS) {
                    assertTrue(rs.next());
                    assertEquals(expected.getEpochSecond(), rs.getLong(2), "stored TIMESTAMP " + expected);
                    assertEquals(expected.getEpochSecond(), rs.getLong(3), "stored DATETIME " + expected);
                    assertEquals(expected, rs.getTimestamp(4).toInstant(), "TIMESTAMP read back " + expected);
                    assertEquals(expected, rs.getTimestamp(5).toInstant(), "DATETIME read back " + expected);
                }
            }
        }
    }

    /** Client.updateLoginState, Character.logOff and LivingStore.lastSeenAtMs, on stand-ins for their columns. */
    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "GMT"})
    void loginAndLogoutWrittenInTheGapHourReadBackAsTheSameInstant(String zoneAtConnect) throws Exception {
        try (Connection con = connectIn(zoneAtConnect)) {
            try (Statement s = con.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE tz_accounts (id INT PRIMARY KEY, loggedin TINYINT NOT NULL DEFAULT 0,"
                        + " lastlogin TIMESTAMP NULL DEFAULT NULL)");
                s.execute("CREATE TEMPORARY TABLE tz_characters (id INT PRIMARY KEY, accountid INT NOT NULL,"
                        + " lastLogoutTime TIMESTAMP NOT NULL DEFAULT '2015-01-01 05:00:00')");
                s.execute("INSERT INTO tz_accounts (id) VALUES (1)");
                s.execute("INSERT INTO tz_characters (id, accountid) VALUES (1, 1)");
            }
            Timestamp login = Timestamp.from(SPRING_FORWARD_GAP_AS_GMT);
            Timestamp logout = Timestamp.from(SPRING_FORWARD_GAP_AS_GMT.plusSeconds(20 * 60));
            try (PreparedStatement ps = con.prepareStatement("UPDATE tz_accounts SET loggedin = ?, lastlogin = ? WHERE id = ?")) {
                ps.setInt(1, 2);
                ps.setTimestamp(2, login);
                ps.setInt(3, 1);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE tz_characters SET lastLogoutTime=? WHERE id=?")) {
                ps.setTimestamp(1, logout);
                ps.setInt(2, 1);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT c.lastLogoutTime, a.lastlogin,"
                    + " UNIX_TIMESTAMP(c.lastLogoutTime), UNIX_TIMESTAMP(a.lastlogin) FROM tz_characters c"
                    + " LEFT JOIN tz_accounts a ON a.id = c.accountid WHERE c.id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(logout, rs.getTimestamp(1));
                    assertEquals(login, rs.getTimestamp(2));
                    assertEquals(logout.getTime() / 1000L, rs.getLong(3));
                    assertEquals(login.getTime() / 1000L, rs.getLong(4));
                }
            }
        }
    }

    /** CURRENT_TIMESTAMP defaults and NOW() read back as the current instant. */
    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "GMT"})
    void serverSideNowReadsBackAsNow(String zoneAtConnect) throws Exception {
        try (Connection con = connectIn(zoneAtConnect)) {
            try (Statement s = con.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE tz_now (id INT PRIMARY KEY, ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + " dt DATETIME NULL)");
                s.execute("INSERT INTO tz_now (id, dt) VALUES (1, NOW())");
            }
            long now = System.currentTimeMillis();
            try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery("SELECT ts, dt FROM tz_now")) {
                assertTrue(rs.next());
                assertEquals(now, rs.getTimestamp(1).getTime(), 5_000L);
                assertEquals(now, rs.getTimestamp(2).getTime(), 5_000L);
            }
        }
    }

    /** The "never banned" tempban reads back as DefaultDates.getTempban(), which Client and Character compare with. */
    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "GMT"})
    void tempbanSentinelReadsBackAsTheDefault(String zoneAtConnect) throws Exception {
        try (Connection con = connectIn(zoneAtConnect)) {
            try (Statement s = con.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE tz_tempban (id INT PRIMARY KEY,"
                        + " tempban TIMESTAMP NOT NULL DEFAULT '2005-05-11 00:00:00')");
                s.execute("INSERT INTO tz_tempban (id) VALUES (1)"); // accounts.tempban's default, once repaired
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO tz_tempban (id, tempban) VALUES (2, ?)")) {
                ps.setTimestamp(1, Timestamp.valueOf(DefaultDates.getTempban())); // LoginPasswordHandler, BotSeedingUtility
                ps.executeUpdate();
            }
            try (Statement s = con.createStatement();
                 ResultSet rs = s.executeQuery("SELECT tempban, UNIX_TIMESTAMP(tempban) FROM tz_tempban ORDER BY id")) {
                for (int row = 1; row <= 2; row++) {
                    assertTrue(rs.next());
                    assertEquals(DefaultDates.getTempban(), rs.getTimestamp(1).toLocalDateTime(), "row " + row);
                    assertEquals(TEMPBAN_SENTINEL_EPOCH, rs.getLong(2), "row " + row);
                }
            }
        }
    }

    /** The 2026-09-30 changeset's statements, run against TEMPORARY stand-ins for accounts and characters. */
    @Test
    void repairChangesetNormalisesTheTempbanSentinelAndClampsFutureTimes() throws Exception {
        try (Connection con = connectIn("GMT")) {
            try (Statement s = con.createStatement()) {
                // accounts.tempban's live default: '2005-05-11 00:00:00' fixed in a New York session, 04:00Z.
                s.execute("CREATE TEMPORARY TABLE tz_accounts (id INT PRIMARY KEY,"
                        + " tempban TIMESTAMP NOT NULL DEFAULT '2005-05-11 04:00:00', lastlogin TIMESTAMP NULL)");
                s.execute("CREATE TEMPORARY TABLE tz_characters (id INT PRIMARY KEY, lastLogoutTime TIMESTAMP NOT NULL,"
                        + " lastExpGainTime TIMESTAMP NOT NULL)");
                s.execute("INSERT INTO tz_accounts VALUES"
                        + " (1, '2005-05-11 04:00:00', NOW() + INTERVAL 4 HOUR)," // New York sentinel, shifted login
                        + " (2, '2005-05-11 00:00:00', NOW() - INTERVAL 1 HOUR)," // right sentinel, right login
                        + " (3, '2027-01-01 00:00:00', NULL)");                   // a real temp ban
                s.execute("INSERT INTO tz_characters VALUES"
                        + " (1, NOW() + INTERVAL 4 HOUR, NOW() + INTERVAL 8 HOUR),"
                        + " (2, '2026-09-29 12:00:00', '2026-09-29 11:00:00')");
                for (String statement : repairStatements()) {
                    String standIn = statement.replaceAll("\\baccounts\\b", "tz_accounts")
                            .replaceAll("\\bcharacters\\b", "tz_characters");
                    assertFalse(standIn.matches("(?s).*\\b(accounts|characters)\\b.*"), "live table in " + standIn);
                    s.execute(standIn);
                }
                s.execute("INSERT INTO tz_accounts (id) VALUES (4)");
            }
            try (Statement s = con.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, UNIX_TIMESTAMP(tempban), lastlogin <= NOW(),"
                         + " TIMESTAMPDIFF(SECOND, lastlogin, NOW()) FROM tz_accounts ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(TEMPBAN_SENTINEL_EPOCH, rs.getLong(2), "New York sentinel normalised");
                assertEquals(0L, rs.getLong(4), 60L, "future login clamped to now");
                assertTrue(rs.next());
                assertEquals(TEMPBAN_SENTINEL_EPOCH, rs.getLong(2));
                assertEquals(3_600L, rs.getLong(4), 60L, "past login untouched");
                assertTrue(rs.next());
                assertEquals(Instant.parse("2027-01-01T00:00:00Z").getEpochSecond(), rs.getLong(2), "real ban untouched");
                assertTrue(rs.next());
                assertEquals(TEMPBAN_SENTINEL_EPOCH, rs.getLong(2), "column default repaired");
            }
            try (Statement s = con.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, TIMESTAMPDIFF(SECOND, lastLogoutTime, NOW()),"
                         + " TIMESTAMPDIFF(SECOND, lastExpGainTime, NOW()), CAST(lastLogoutTime AS CHAR) FROM tz_characters ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(0L, rs.getLong(2), 60L);
                assertEquals(0L, rs.getLong(3), 60L);
                assertTrue(rs.next());
                assertEquals("2026-09-29 12:00:00", rs.getString(4), "past logout untouched");
            }
        }
    }

    private static List<String> repairStatements() throws Exception {
        Document changelog = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                DatabaseConnectionUtcMySqlIntegrationTest.class.getResourceAsStream("/db/extensions/2026-09-30-jdbc-utc-session-repair.xml"));
        String sql = changelog.getElementsByTagName("sql").item(0).getTextContent().replaceAll("(?m)--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.trim());
            }
        }
        assertEquals(6, statements.size(), statements.toString());
        return statements;
    }

    /** A connection from a pool built as DatabaseConnection builds its own, opened while the JVM ran in {@code zone}. */
    private Connection connectIn(String zone) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        HikariConfig config = DatabaseConnection.getConfig();
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        pool = new HikariDataSource(config);
        Connection con = pool.getConnection();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT")); // server.TIMEZONE, as the running server has it
        return con;
    }
}
