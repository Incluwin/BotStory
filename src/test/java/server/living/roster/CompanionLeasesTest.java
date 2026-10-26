package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.roster.CompanionLeases.Kind;
import server.living.roster.CompanionLeases.Lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLeasesTest {
    private static final int OLWEN = 900;
    private static final int ALICE = 10;
    private static final int BOB = 20;

    private final CompanionLeases leases = new CompanionLeases();

    @Test
    void aFreeCharacterIsLeasedAndTheSameHolderMayAskAgain() {
        assertNull(leases.holder(OLWEN));

        assertTrue(leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE));
        assertTrue(leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE));

        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
    }

    @Test
    void aLeasedCharacterRefusesEveryOtherKindAndOwner() {
        assertTrue(leases.tryAcquire(OLWEN, Kind.BENCH_LIVE, ALICE));

        assertFalse(leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE));
        assertFalse(leases.tryAcquire(OLWEN, Kind.BENCH_LIVE, BOB));
        assertFalse(leases.tryAcquire(OLWEN, Kind.POPULATION, 0));
        assertEquals(new Lease(Kind.BENCH_LIVE, ALICE), leases.holder(OLWEN));
    }

    @Test
    void releaseDropsOnlyALeaseOfTheSameKind() {
        leases.tryAcquire(OLWEN, Kind.RESIDENT, 0);

        assertFalse(leases.release(OLWEN, Kind.COMPANION));
        assertEquals(Kind.RESIDENT, leases.holder(OLWEN).kind());

        assertTrue(leases.release(OLWEN, Kind.RESIDENT));
        assertNull(leases.holder(OLWEN));
        assertFalse(leases.release(OLWEN, Kind.RESIDENT));
        assertTrue(leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE));
    }

    @Test
    void aTransferMovesTheLeaseInOneStepOnlyFromItsHolder() {
        Lease asCompanion = new Lease(Kind.COMPANION, ALICE);
        Lease asLoan = new Lease(Kind.LOAN, BOB);
        assertFalse(leases.transfer(OLWEN, asCompanion, asLoan)); // free: nothing to move
        assertNull(leases.holder(OLWEN));

        leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE);
        assertFalse(leases.transfer(OLWEN, new Lease(Kind.COMPANION, BOB), asLoan)); // not Bob's to move
        assertFalse(leases.transfer(OLWEN, new Lease(Kind.BENCH_LIVE, ALICE), asLoan));
        assertEquals(asCompanion, leases.holder(OLWEN));

        assertTrue(leases.transfer(OLWEN, asCompanion, asLoan));
        assertEquals(asLoan, leases.holder(OLWEN));
        assertFalse(leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE)); // no gap for anyone to load it in between
        assertFalse(leases.release(OLWEN, Kind.COMPANION));

        assertTrue(leases.transfer(OLWEN, asLoan, asCompanion)); // and back
        assertEquals(asCompanion, leases.holder(OLWEN));
        assertFalse(leases.transfer(OLWEN, asCompanion, null));
    }

    @Test
    void countsLeasesByKind() {
        leases.tryAcquire(900, Kind.EXPEDITION, ALICE);
        leases.tryAcquire(901, Kind.EXPEDITION, BOB);
        leases.tryAcquire(902, Kind.PQ_BACKGROUND, 0);

        assertEquals(2, leases.count(Kind.EXPEDITION));
        assertEquals(1, leases.count(Kind.PQ_BACKGROUND));
        assertEquals(0, leases.count(Kind.COMPANION));
    }
}
