package server.economy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** The complete durable economy-owned state written with the journal commit. */
public final class EconomyDurableState {
    @FunctionalInterface
    interface Persistence {
        void persist(Connection connection) throws SQLException;
    }

    private final List<ParticipantState> participants;
    private final List<EconomyAtomicPersistence> additionalPersistence;
    private final Persistence testPersistence;

    private EconomyDurableState(List<ParticipantState> participants,
                                List<EconomyAtomicPersistence> additionalPersistence,
                                Persistence testPersistence) {
        this.participants = List.copyOf(participants);
        this.additionalPersistence = List.copyOf(additionalPersistence);
        this.testPersistence = testPersistence;
    }

    static EconomyDurableState capture(EconomyParticipantSnapshot primaryBefore,
                                       EconomyParticipantSnapshot primaryAfter,
                                       EconomyParticipantSnapshot secondaryBefore,
                                       EconomyParticipantSnapshot secondaryAfter,
                                       List<EconomyAtomicPersistence> additionalPersistence) {
        return new EconomyDurableState(secondaryAfter == null
                ? List.of(new ParticipantState(primaryBefore, primaryAfter))
                : List.of(new ParticipantState(primaryBefore, primaryAfter),
                        new ParticipantState(secondaryBefore, secondaryAfter)),
                additionalPersistence, null);
    }

    static EconomyDurableState forTesting(Persistence persistence) {
        return new EconomyDurableState(List.of(), List.of(), persistence);
    }

    void persist(Connection connection) throws SQLException {
        if (testPersistence != null) {
            testPersistence.persist(connection);
            return;
        }
        for (ParticipantState participant : participants) {
            participant.after().persist(connection, participant.before());
        }
        for (EconomyAtomicPersistence persistence : additionalPersistence) {
            persistence.persist(connection);
        }
    }

    /** What the operation enlisted besides its participants (EconomyTransactionContext.enlist), for tests. */
    List<EconomyAtomicPersistence> additionalPersistence() { return additionalPersistence; }

    private record ParticipantState(EconomyParticipantSnapshot before,
                                    EconomyParticipantSnapshot after) { }
}
