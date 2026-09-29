package server.economy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * For tests outside this package whose code runs an economy transaction: a journal that keeps its states in memory and,
 * inside each commit, runs what the operation enlisted (EconomyTransactionContext.enlist) against a test's connection,
 * as the database journal runs it in the same database transaction. A failure there fails the commit, which rolls the
 * participants back. The participants' own rows are not written (a test's characters are mocks).
 */
public final class EconomyJournalTestSupport {
    private EconomyJournalTestSupport() {
    }

    /** Installs the journal; returns the kinds of the operations it commits from now on, in order. */
    public static List<EconomyOperationKind> commitEnlistedAgainst(Connection connection) {
        List<EconomyOperationKind> committed = new CopyOnWriteArrayList<>();
        InMemoryEconomyTransactionJournal states = new InMemoryEconomyTransactionJournal();
        EconomyTransactionCoordinator.installJournalForTesting(new EconomyTransactionJournal() {
            @Override
            public EconomyPrepareResult prepare(EconomyOperation operation) {
                return states.prepare(operation);
            }

            @Override
            public void commit(EconomyOperation operation, EconomyDurableState durableState) {
                try {
                    for (EconomyAtomicPersistence persistence : durableState.additionalPersistence()) {
                        persistence.persist(connection);
                    }
                } catch (SQLException failure) {
                    throw new EconomyTransactionException("Could not atomically commit economy operation", failure);
                }
                states.commit(operation, durableState);
                committed.add(operation.kind());
            }

            @Override
            public void transition(EconomyOperation operation, EconomyJournalStatus status, String failureReason) {
                states.transition(operation, status, failureReason);
            }

            @Override
            public int reconcileStalePrepared(Duration age) {
                return states.reconcileStalePrepared(age);
            }
        });
        return committed;
    }

    /** Back to the journal the tests start with. */
    public static void reset() {
        EconomyTransactionCoordinator.resetJournalForTesting();
    }
}
