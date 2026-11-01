package server.economy;

import java.sql.Connection;
import java.sql.SQLException;

/** Additional state that must commit with the participant inventories and the journal entry. */
@FunctionalInterface
public interface EconomyAtomicPersistence {
    void persist(Connection connection) throws SQLException;
}
