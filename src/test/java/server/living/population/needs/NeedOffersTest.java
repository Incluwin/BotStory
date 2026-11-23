package server.living.population.needs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The board's seam to learned offers (I2.2): no learner, the configured premium and nothing heard; one installed, its
 * premium, and each ended row NeedStore.update writes, told whether it was written on an autocommit connection, after
 * the write and only if it went through; a learner that throws never reaches the store call.
 */
class NeedOffersTest {
    private final List<Object[]> heard = new ArrayList<>();

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    private void listen() {
        NeedOffers.install(new NeedOffers.Learner() {
            @Override
            public double premium(int citizenId, int itemId, double fixed) {
                return 1.25d;
            }

            @Override
            public void closed(Need closed, long nowMs, boolean committed) {
                heard.add(new Object[]{closed, committed});
            }
        });
    }

    private static Need need(Need.State state) {
        return new Need(9, 1, NeedKind.SUPPLY, 2000002, 5, 5, 125L, "", 0, state, 0, 0L, 0, false, 1_000L, 0L,
                state == Need.State.OPEN ? 0L : 2_000L);
    }

    @Test
    void withoutALearnerThePremiumIsTheConfiguredOne() {
        assertEquals(1.15d, NeedOffers.premium(1, 2000002, 1.15d), 0.0d);
        NeedDerivation.closed(null, 0L, true); // nothing to tell, nothing thrown
        assertEquals(115L, NeedDerivation.unitOffer(100, NeedOffers.premium(1, 2000002, 1.15d)));
    }

    @Test
    void anInstalledLearnerPricesAndHearsEndings() {
        listen();
        assertEquals(125L, NeedDerivation.unitOffer(100, NeedOffers.premium(1, 2000002, 1.15d)));
        Need need = need(Need.State.MET);
        NeedDerivation.closed(need, 1L, true);
        assertEquals(1, heard.size());
        assertEquals(need, heard.get(0)[0]);
    }

    @Test
    void aLearnerThatThrowsNeverReachesTheBoard() {
        NeedOffers.install(new NeedOffers.Learner() {
            @Override
            public double premium(int citizenId, int itemId, double fixed) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void closed(Need closed, long nowMs, boolean committed) {
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(1.15d, NeedOffers.premium(1, 2000002, 1.15d), 0.0d, "the configured premium holds");
        NeedDerivation.closed(need(Need.State.MET), 1L, true); // logged, not thrown
    }

    private static Connection connection(PreparedStatement ps, boolean autoCommit) throws SQLException {
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(con.getAutoCommit()).thenReturn(autoCommit);
        return con;
    }

    @Test
    void theStoreTellsAnEndedRowOnceAfterItsWriteWithItsCommitMode() throws SQLException {
        listen();
        NeedStore store = new NeedStore();
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection inTransaction = connection(ps, false);
        store.update(inTransaction, need(Need.State.MET));
        assertEquals(1, heard.size());
        assertEquals(false, heard.get(0)[1], "inside a transaction: not known to hold yet");
        verify(ps).executeUpdate();
        verify(inTransaction).prepareStatement(anyString());
        verify(inTransaction).getAutoCommit();
        verifyNoMoreInteractions(inTransaction); // no I/O of its own on the caller's connection

        store.update(connection(mock(PreparedStatement.class), true), need(Need.State.EXPIRED));
        assertEquals(true, heard.get(1)[1], "autocommit: it holds");

        store.update(connection(mock(PreparedStatement.class), true), need(Need.State.OPEN));
        store.update(connection(mock(PreparedStatement.class), true), need(Need.State.ESCROW));
        assertEquals(2, heard.size(), "open and escrow rows are not ends");
    }

    @Test
    void aWriteThatFailsTellsNothingAndAnUnreadableCommitModeIsATransaction() throws SQLException {
        listen();
        NeedStore store = new NeedStore();
        PreparedStatement failing = mock(PreparedStatement.class);
        when(failing.executeUpdate()).thenThrow(new SQLException("lost"));
        assertThrows(SQLException.class, () -> store.update(connection(failing, true), need(Need.State.MET)));
        assertTrue(heard.isEmpty(), "a failed write never reaches the learner");

        Connection odd = connection(mock(PreparedStatement.class), true);
        when(odd.getAutoCommit()).thenThrow(new SQLException("closed"));
        store.update(odd, need(Need.State.MET));
        assertEquals(false, heard.get(0)[1], "unsure: it waits for the ledger to confirm");
    }
}
