package server.economy.ledger;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A day closes at the first tick after its midnight, so a server down across that midnight never closed it; at start
 * the ledger closes the latest day with flows that has no close (docs/ECONOMY_PLAN.md E4).
 */
class EconomyLedgerMissedCloseTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @Test
    void theLatestDayWithFlowsAfterTheLastCloseIsClosedAtStart() {
        assertEquals(DAY, EconomyLedger.missed(DAY, DAY.minusDays(1)), "down across its midnight");
        assertEquals(DAY, EconomyLedger.missed(DAY, null), "no close yet at all");
        assertEquals(DAY, EconomyLedger.missed(DAY, DAY.minusDays(3)), "only the latest: an older day's end is gone");
    }

    @Test
    void nothingWhenTheLatestDayWithFlowsIsClosed() {
        assertNull(EconomyLedger.missed(DAY, DAY), "closed by its tick");
        assertNull(EconomyLedger.missed(DAY.minusDays(1), DAY));
        assertNull(EconomyLedger.missed(null, DAY), "no flows before today");
        assertNull(EconomyLedger.missed(null, null));
    }
}
