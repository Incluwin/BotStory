package testutil;

import org.mockito.MockedStatic;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The boss log's tables behind a mocked DatabaseConnection (ExpeditionBossLog, on the test's own thread): every
 * statement it prepares ({@link #sql}), each as "count|insert &lt;character id&gt; &lt;boss&gt;" ({@link #statements}),
 * and how many connections it asked for. A COUNT finds {@link #counts} for the character (0 when missing).
 */
public final class BossLogDatabase implements AutoCloseable {
    public final List<String> sql = new CopyOnWriteArrayList<>();
    public final List<String> statements = new CopyOnWriteArrayList<>();
    /** Character id -> the entries its COUNT finds today. */
    public final Map<Integer, Integer> counts = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class);

    public BossLogDatabase() {
        try {
            Connection connection = mock(Connection.class);
            database.when(DatabaseConnection::getConnection).thenAnswer(inv -> {
                connections.incrementAndGet();
                return connection;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(inv -> statement(inv.getArgument(0)));
        } catch (SQLException | RuntimeException failure) {
            database.close();
            throw new IllegalStateException(failure);
        }
    }

    /** The connections asked for so far. */
    public int connections() {
        return connections.get();
    }

    /** The INSERTs so far, each as "&lt;character id&gt; &lt;boss&gt;". */
    public List<String> inserts() {
        return statements.stream().filter(line -> line.startsWith("insert ")).map(line -> line.substring(7)).toList();
    }

    /** Forgets what was asked so far (the counts stay). */
    public void clear() {
        sql.clear();
        statements.clear();
        connections.set(0);
    }

    private PreparedStatement statement(String text) throws SQLException {
        sql.add(text);
        String verb = text.startsWith("INSERT") ? "insert " : text.startsWith("SELECT COUNT") ? "count " : "other ";
        PreparedStatement statement = mock(PreparedStatement.class);
        int[] characterId = new int[1];
        doAnswer(set -> {
            characterId[0] = set.getArgument(1);
            return null;
        }).when(statement).setInt(anyInt(), anyInt());
        doAnswer(set -> {
            statements.add(verb + characterId[0] + " " + set.getArgument(1));
            return null;
        }).when(statement).setString(anyInt(), anyString());
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenAnswer(inv -> counts.getOrDefault(characterId[0], 0));
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }

    @Override
    public void close() {
        database.close();
    }
}
