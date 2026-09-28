package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.store.LivingStore.RosterState.ACTIVE;
import static server.living.store.LivingStore.RosterState.AWAY;
import static server.living.store.LivingStore.RosterState.BENCHED;
import static server.living.store.LivingStore.RosterState.HOLD;
import static server.living.store.LivingStore.RosterState.PARKED;
import static server.living.store.LivingStore.RosterState.RELEASED;
import static server.living.store.LivingStore.RosterState.TRAVELLING;

/**
 * The one table of legal seat-state moves (docs/BOT_MANAGEMENT_PLAN.md, "Seat states"), written out here in full so a
 * change to it is a change to this test; RosterService refuses and logs a move the table does not list instead of
 * writing it; RosterEntry's helpers keep the travel, hold and park columns and the ledger clock in step with the state.
 */
class CompanionSeatStateTest {
    private static final Map<RosterState, Set<RosterState>> LEGAL = Map.of(
            ACTIVE, EnumSet.of(BENCHED, AWAY, HOLD, PARKED, RELEASED),
            TRAVELLING, EnumSet.of(ACTIVE, HOLD, BENCHED, PARKED, RELEASED),
            HOLD, EnumSet.of(ACTIVE, TRAVELLING, BENCHED, PARKED, RELEASED),
            AWAY, EnumSet.of(ACTIVE, TRAVELLING, BENCHED, RELEASED),
            BENCHED, EnumSet.of(ACTIVE, TRAVELLING, RELEASED),
            PARKED, EnumSet.of(ACTIVE, TRAVELLING, BENCHED, RELEASED),
            RELEASED, EnumSet.of(BENCHED, AWAY));

    @Test
    void everyMoveIsLegalExactlyWhenTheTableListsItOrTheRowStaysAsItIs() {
        for (RosterState from : RosterState.values()) {
            for (RosterState to : RosterState.values()) {
                boolean expected = from == to || LEGAL.get(from).contains(to);
                assertEquals(expected, CompanionSeatState.legal(from, to), from + " -> " + to);
            }
            assertEquals(LEGAL.get(from), CompanionSeatState.next(from), "next(" + from + ")");
        }
    }

    @Test
    void aNewRowStartsBenchedOrAway() {
        for (RosterState to : RosterState.values()) {
            assertEquals(to == BENCHED || to == AWAY, CompanionSeatState.legal(null, to), "new row as " + to);
        }
        assertFalse(CompanionSeatState.legal(ACTIVE, null));
    }

    @Test
    void thePlansFlowsAreAllLegal() {
        // join-then-travel: call, arrive, or the owner is in a run at the ETA; bench on the way turns it back
        assertTrue(CompanionSeatState.legal(BENCHED, TRAVELLING) && CompanionSeatState.legal(TRAVELLING, ACTIVE)
                && CompanionSeatState.legal(TRAVELLING, HOLD) && CompanionSeatState.legal(TRAVELLING, BENCHED));
        // PQ extras wait outside and rejoin (walk over, or travel); logout parks, grace benches, login walks in
        assertTrue(CompanionSeatState.legal(ACTIVE, HOLD) && CompanionSeatState.legal(HOLD, ACTIVE)
                && CompanionSeatState.legal(HOLD, TRAVELLING));
        assertTrue(CompanionSeatState.legal(ACTIVE, PARKED) && CompanionSeatState.legal(PARKED, BENCHED)
                && CompanionSeatState.legal(PARKED, ACTIVE));
        // boot: ACTIVE, TRAVELLING and HOLD become PARKED
        for (RosterState out : EnumSet.of(ACTIVE, TRAVELLING, HOLD)) {
            assertTrue(CompanionSeatState.legal(out, PARKED), out.name());
        }
        // a benched companion never waits outside a run or parks without having been out
        assertFalse(CompanionSeatState.legal(BENCHED, HOLD));
        assertFalse(CompanionSeatState.legal(BENCHED, PARKED));
        assertFalse(CompanionSeatState.legal(RELEASED, ACTIVE));
    }

    @Test
    void theMembersOfTheOwnersPartyAreActiveAndTravellingOnes() {
        for (RosterState state : RosterState.values()) {
            assertEquals(state == ACTIVE || state == TRAVELLING, CompanionSeatState.partyMember(state), state.name());
        }
    }

    @Test
    void theRosterRefusesAndLogsAMoveTheTableDoesNotList() {
        FakeLivingStore fake = new FakeLivingStore();
        server.living.LivingWorld world = org.mockito.Mockito.mock(server.living.LivingWorld.class);
        org.mockito.Mockito.when(world.store()).thenReturn(fake.store);
        org.mockito.Mockito.when(world.slots()).thenReturn(new CompanionSlots(world));
        RosterEntry benched = row(BENCHED);
        fake.seed(benched);
        RosterService roster = new RosterService(world);

        assertFalse(roster.write(benched.withHold(103000000, 5_000L)), "BENCHED -> HOLD");

        assertEquals(BENCHED, fake.row(10, "olwen").state(), "nothing written");
        assertTrue(fake.saved.isEmpty());
        assertEquals(1, fake.events("roster.illegal_transition").size());
        assertTrue(roster.write(benched.withTravel(70_000L, 5_000L)), "BENCHED -> TRAVELLING");
        assertEquals(TRAVELLING, fake.row(10, "olwen").state());
    }

    @Test
    void theHelpersSetTheLifecycleColumnsTheStateKeepsAndClearTheRest() {
        RosterEntry travelling = row(BENCHED).withTravel(70_000L, 5_000L);
        assertEquals(TRAVELLING, travelling.state());
        assertEquals(70_000L, travelling.etaAtMs());
        RosterEntry held = travelling.withHold(103000000, 6_000L);
        assertEquals(HOLD, held.state());
        assertEquals(0L, held.etaAtMs());
        assertEquals(103000000, held.holdMapId());
        RosterEntry parked = held.withPark(7_000L);
        assertEquals(PARKED, parked.state());
        assertEquals(0, parked.holdMapId());
        assertEquals(7_000L, parked.parkedAtMs());
        RosterEntry active = parked.with(ACTIVE, 8_000L);
        assertEquals(0L, active.parkedAtMs());
        assertEquals(0L, active.etaAtMs());
        assertEquals(0, active.holdMapId());
    }

    @Test
    void onlyBenchGovernedStatesRestartTheLedgerClock() {
        RosterEntry benched = row(BENCHED);
        for (RosterEntry moved : java.util.List.of(benched.withTravel(70_000L, 5_000L), benched.withHold(1, 5_000L),
                benched.withPark(5_000L), benched.with(ACTIVE, 5_000L))) {
            assertEquals(benched.lastLedgerAtMs(), moved.lastLedgerAtMs(), moved.state().name());
            assertEquals(benched.benchedAtMs(), moved.benchedAtMs(), moved.state().name());
        }
        // a PARKED row benched once its grace ends is credited only from the grace deadline, passed as "now"
        RosterEntry parked = benched.withPark(10_000L);
        long graceEnds = parked.parkedAtMs() + 1_200_000L;
        RosterEntry onDuty = parked.with(BENCHED, graceEnds);
        assertEquals(graceEnds, onDuty.lastLedgerAtMs());
        assertEquals(graceEnds, onDuty.benchedAtMs());
        assertEquals(0L, onDuty.parkedAtMs());
    }

    private static RosterEntry row(RosterState state) {
        return new RosterEntry(10, "olwen", state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1_000L, 1_000L,
                1_000L, 0, null);
    }
}
