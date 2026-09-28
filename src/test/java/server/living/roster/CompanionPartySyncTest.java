package server.living.roster;

import net.server.world.CauseScope;
import net.server.world.PartyEvents;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompanionPartySync (docs/BOT_MANAGEMENT_PLAN.md P2a): every party operation x who asked x whose member it
 * touches x in or out of an event instance, reconciled into the roster; the CHANGE_LEADER snapshot; echo
 * suppression; the leader cap that stops expel/refill loops; the circuit breaker.
 */
class CompanionPartySyncTest {
    private static final int ANN = 1;
    private static final int BEN = 2;
    private static final int CARL = 3;
    private static final int MIRA = 950; // a citizen

    private final FakeParties world = new FakeParties()
            .human(ANN).human(BEN).human(CARL)
            .companion("vex", 101, ANN, 0).companion("olwen", 102, ANN, 1).companion("brin", 103, ANN, 2)
            .companion("hadrik", 104, ANN, 3).companion("tobi", 105, ANN, 4)
            .companion("kai", 201, BEN, 0).companion("lune", 202, BEN, 1).companion("sol", 203, BEN, 2)
            .citizen(MIRA);

    // ---- JOIN -------------------------------------------------------------------------------

    @Test
    void aHumanJoiningWithinTheSharesChangesNothing() {
        out(ANN, "vex", "olwen");
        world.changes.clear();

        world.accept(BEN, party(ANN));
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(List.of(ANN, 101, 102, BEN), world.members(party(ANN)));
    }

    @Test
    void aHumanJoiningRebalancesGuestsFirstThenTheLastOfTheLineupWithReturnWhenRoom() {
        out(ANN, "vex", "olwen", "brin");
        assertTrue(world.inviteCitizen(ANN, MIRA)); // Ann + 3 + her guest: 5/6
        world.settle();
        world.changes.clear();

        world.accept(BEN, party(ANN)); // 2 humans: 2 seats each; Ann uses 4
        world.settle();

        assertEquals(List.of("guest " + MIRA, "OVERFLOW brin"), world.changes);
        assertEquals(List.of(ANN, 101, 102, BEN), world.members(party(ANN)));
        assertEquals(RosterState.BENCHED, world.companion("brin").state);
        assertEquals(0, world.waiting.get("brin")); // it comes back when Ann has room again
        assertFalse(world.guests.containsKey(MIRA));
    }

    @Test
    void aCompanionSeatedByTheRosterIsAnEchoAndNeverReconciled() {
        world.create(ANN);
        world.call(ANN, "vex");

        PartyChangeEvent join = world.published.get(world.published.size() - 1);
        assertEquals(PartyOperation.JOIN, join.op());
        assertEquals(RosterService.CAUSE, join.cause());
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("vex").state);
    }

    @Test
    void aCompanionJoiningItsOwnersPartyWithoutACauseIsLeftAlone() {
        out(ANN, "vex");
        world.expel(ANN, 101); // Ann benches Vex
        world.settle();
        world.changes.clear();
        world.companion("vex").state = RosterState.ACTIVE; // the router's ACCEPT: already out, re-invited
        world.companion("vex").loaded = true;

        assertTrue(world.joinParty(101, party(ANN)));
        world.settle();

        assertEquals(List.of(), world.changes);
    }

    @Test
    void aCompanionTurningUpInAPartyWithoutItsOwnerIsTakenBackToItsOwner() {
        int anns = out(ANN, "vex");
        int bens = out(BEN, "kai");
        world.parties.get(anns).members.remove(Integer.valueOf(101)); // past the seat gate somehow
        world.parties.get(bens).members.add(101);
        world.partyOf.put(101, bens);

        world.sync.onPartyChanged(new PartyChangeEvent(bens, PartyOperation.JOIN, 101, 0,
                new PartyEvents.Snapshot(List.of(BEN, 201), BEN), new PartyEvents.Snapshot(List.of(BEN, 201, 101), BEN), null,
                true));
        world.settle();

        assertEquals(1, world.drifts.size());
        assertEquals(List.of("leave 101", "seat vex"), world.changes);
        assertEquals(List.of(ANN, 101), world.members(anns));
        assertEquals(List.of(BEN, 201), world.members(bens));
    }

    @Test
    void aJoinInsideAnEventInstanceWaitsUntilTheInstanceEnds() {
        out(ANN, "vex", "olwen", "brin");
        world.committed.add(ANN);

        world.accept(BEN, party(ANN));
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(1, world.sync.deferredCount());
        assertEquals(RosterState.ACTIVE, world.companion("brin").state);

        world.committed.clear();
        world.sync.resumeDeferred();
        world.settle();

        assertEquals(List.of("OVERFLOW brin"), world.changes);
        assertEquals(0, world.sync.deferredCount());
    }

    // ---- LEAVE ------------------------------------------------------------------------------

    @Test
    void anOwnerWhoLeavesTakesTheCrewAlongAndItReformsAroundThem() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        assertTrue(world.inviteCitizen(ANN, MIRA)); // Ann's two seats: Vex and her guest Mira
        world.settle();
        int shared = party(BEN);
        world.changes.clear();

        world.quit(ANN);
        world.settle();

        int anns = party(ANN);
        assertNotEquals(shared, anns);
        assertEquals(List.of(ANN, 101), world.members(anns));
        assertEquals(List.of(BEN, 201), world.members(shared));
        assertEquals(List.of("guest " + MIRA, "seat vex"), world.changes); // her guest does not follow
        assertEquals(List.of(CompanionPartySync.HOST_LEFT_LINE), world.guestLines); // and does not claim a full crew
        assertEquals(RosterState.ACTIVE, world.companion("vex").state);
    }

    @Test
    void theHumansLeftBehindGetTheirWaitingCompanionsBack() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN)); // Ben's Sol steps out for fair share
        world.settle();
        assertEquals(RosterState.BENCHED, world.companion("sol").state);
        world.changes.clear();

        world.quit(ANN);
        world.settle();

        assertEquals(List.of("recall sol"), world.changes);
        assertEquals(List.of(BEN, 201, 202, 203), world.members(party(BEN)));
        assertFalse(world.waiting.containsKey("sol"));
    }

    @Test
    void aCompanionThatLeftOnItsOwnIsBenchedAndReportedAsDrift() {
        out(ANN, "vex", "olwen");
        world.changes.clear();

        world.leaveParty(101); // an Agent-side leave, no cause token
        world.settle();

        assertEquals(List.of("BENCHED vex"), world.changes);
        assertEquals(RosterState.BENCHED, world.companion("vex").state);
        assertFalse(world.waiting.containsKey("vex"));
        assertEquals(1, world.drifts.size());
    }

    @Test
    void theRostersOwnWalkOutIsAnEcho() {
        out(ANN, "vex", "olwen");
        world.changes.clear();

        world.bench(ANN, "vex");
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(List.of(), world.drifts);
    }

    @Test
    void anOwnerLoggingOffTakesTheCrewOffWithoutBenchingIt() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        world.changes.clear();

        world.logOff(ANN);
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("vex").state); // restored at login
        assertFalse(world.companion("vex").loaded);
    }

    @Test
    void aGuestLeavingNeedsNothingButItsSeatBack() {
        world.create(ANN);
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();

        world.leaveParty(MIRA);
        world.settle();

        assertEquals(List.of(), world.changes);
        assertFalse(world.guests.containsKey(MIRA));
    }

    @Test
    void aLeaveInsideAnEventInstanceWaits() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        world.committed.addAll(List.of(ANN, BEN, 201, 101));
        world.changes.clear();

        world.quit(ANN);
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(party(BEN), world.partyOf(101)); // Vex stays in the run until it ends

        world.committed.clear();
        world.sync.resumeDeferred();
        world.settle();

        assertEquals(List.of("seat vex"), world.changes);
        assertEquals(List.of(ANN, 101), world.members(party(ANN)));
    }

    @Test
    void aFriendAloneInAnEventInstanceDoesNotHoldUpTheCrewOfSomeoneWhoLeaves() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN)); // Ben's Sol steps out for fair share
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        int shared = party(BEN);
        world.committed.add(BEN); // Ben is in a solo quest instance; Ann and her crew are outside
        world.changes.clear();

        world.quit(ANN);
        world.settle();

        assertEquals(List.of("seat vex"), world.changes); // Ann's crew re-forms around her at once
        assertEquals(List.of(ANN, 101), world.members(party(ANN)));
        assertEquals(List.of(BEN, 201, 202), world.members(shared));
        assertEquals(1, world.sync.deferredCount()); // Ben's own part waits for his instance to end

        world.committed.clear();
        world.sync.resumeDeferred();
        world.settle();

        assertEquals(List.of("seat vex", "recall sol"), world.changes);
        assertEquals(List.of(BEN, 201, 202, 203), world.members(shared));
        assertEquals(0, world.sync.deferredCount());
    }

    // ---- EXPEL ------------------------------------------------------------------------------

    @Test
    void anOwnerExpellingTheirOwnCompanionBenchesIt() {
        out(ANN, "vex", "olwen");
        world.changes.clear();

        world.expel(ANN, 101);
        world.settle();

        assertEquals(List.of("BENCHED vex"), world.changes);
        assertFalse(world.waiting.containsKey("vex"));
        assertEquals(0, world.caps.size());
        assertEquals(List.of(), world.drifts);
    }

    @Test
    void anotherLeaderExpellingYourCompanionBenchesItAndCapsYourSeatsThere() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.call(ANN, "olwen");
        world.settle();
        int shared = party(BEN);
        world.changes.clear();

        world.expel(BEN, 101);
        world.settle();

        assertEquals(List.of("cap " + ANN, "EXPELLED vex"), world.changes);
        assertEquals(1, world.caps.get(shared).get(ANN));
        assertEquals(shared, world.waiting.get("vex")); // not back into this party until it disbands
        assertEquals(0, world.free(ANN));
    }

    @Test
    void noExpelRefillLoop() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.call(ANN, "olwen");
        world.settle();
        world.expel(BEN, 101);
        world.settle();
        world.changes.clear();

        assertFalse(world.call(ANN, "vex")); // the leader cap: Ann cannot put Vex straight back
        assertFalse(world.call(ANN, "brin"));
        world.settle();

        assertEquals(List.of(BEN, 201, ANN, 102), world.members(party(BEN)));
        assertEquals(List.of(), world.changes);
        assertEquals(List.of(), world.drifts);

        world.quit(BEN); // the party disbands: the cap goes with it and Vex comes back to Ann
        world.settle();

        assertEquals(List.of(ANN, 102, 101), world.members(party(ANN)));
        assertFalse(world.waiting.containsKey("vex"));
    }

    @Test
    void anExpelSticksEvenWhenThatWasTheOnlyCompanionOut() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex"); // one of her two seats in use
        world.settle();

        world.expel(BEN, 101);
        world.settle();

        // the cap counts the seats she used, not her share: the one the leader emptied stays empty
        assertEquals(0, world.caps.get(party(BEN)).get(ANN));
        assertEquals(0, world.free(ANN));
        assertFalse(world.call(ANN, "vex"));
        assertFalse(world.call(ANN, "olwen"));
        assertEquals(List.of(BEN, 201, ANN), world.members(party(BEN)));
    }

    @Test
    void anExpelBeforeCompanionOpsTookTheJoinCountsTheExpelledCompanionOnce() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex"); // one of her two seats in use
        world.travelSeats.put("vex", ANN); // its TRAVEL seat is still held: CompanionSlots has not taken its JOIN

        world.expel(BEN, 101);
        world.settle();

        // counted once (the seat it held is the one it sat in): the seat the leader emptied stays empty
        assertEquals(0, world.caps.get(party(BEN)).get(ANN));
        assertEquals(0, world.free(ANN));
        assertFalse(world.call(ANN, "olwen"));
    }

    @Test
    void aLeaderExpellingACompanionWhoseOwnerIsNotInThePartyCapsNobody() {
        int anns = out(ANN, "vex");
        int bens = out(BEN, "kai");
        world.parties.get(anns).members.remove(Integer.valueOf(101)); // past the seat gate somehow
        world.parties.get(bens).members.add(101);
        world.partyOf.put(101, bens);

        world.expel(BEN, 101);
        world.settle();

        assertFalse(world.changes.contains("cap " + ANN), world.changes.toString());
        assertEquals(0, world.caps.size());
        assertEquals(RosterState.BENCHED, world.companion("vex").state);
    }

    @Test
    void theLeaderCapAlsoBitesWhereOnlyLeftoverSeatsAreLeft() {
        world.human(4);
        world.companion("ash", 401, 4, 0);
        world.create(BEN);
        world.accept(ANN, party(BEN));
        world.accept(CARL, party(BEN));
        world.accept(4, party(BEN)); // 4 humans: no base seat each, 2 leftover seats first come
        world.settle();
        assertTrue(world.call(ANN, "vex"));
        assertEquals(1, world.free(ANN));
        world.settle();

        world.expel(BEN, 101);
        world.settle();

        assertEquals(0, world.free(ANN)); // two leftover seats are open, but the seat the leader emptied is not refilled
        assertFalse(world.call(ANN, "olwen"));
        assertEquals(2, world.free(4)); // the leftover seats go to the others
    }

    @Test
    void anExpelledOwnerTakesTheCrewAlong() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        world.changes.clear();

        world.expel(BEN, ANN);
        world.settle();

        assertEquals(List.of(ANN, 101), world.members(party(ANN)));
        assertEquals(List.of(BEN, 201), world.members(party(BEN)));
        assertEquals(List.of("seat vex"), world.changes);
        assertEquals(0, world.caps.size()); // expelling a human caps nobody
    }

    @Test
    void anExpelledGuestJustGivesItsSeatBack() {
        world.create(ANN);
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();

        world.expel(ANN, MIRA);
        world.settle();

        assertEquals(List.of(), world.changes);
        assertFalse(world.guests.containsKey(MIRA));
    }

    @Test
    void aHostWhoLogsOffTakesTheirGuestOutOfTheParty() {
        int bens = out(BEN, "kai");
        world.accept(ANN, bens);
        assertTrue(world.inviteCitizen(ANN, MIRA));
        world.settle();
        world.changes.clear();

        world.logOff(ANN);
        world.settle();

        assertEquals(List.of("guest " + MIRA), world.changes);
        assertEquals(List.of("-"), world.guestLines); // nobody to whisper: Ann is gone
        assertEquals(0, world.partyOf(MIRA));
        assertFalse(world.guests.containsKey(MIRA));

        world.expel(BEN, ANN);
        world.settle();

        assertEquals(List.of("guest " + MIRA), world.changes);
        assertEquals(List.of(BEN, 201), world.members(bens));
    }

    @Test
    void anOfflineFriendExpelledGivesTheSeatBackToTheOthersWaitingCompanions() {
        world.companion("pip", 204, BEN, 3).companion("rue", 205, BEN, 4);
        int bens = out(BEN, "kai", "lune", "sol", "pip");
        world.accept(CARL, bens); // two humans: Sol and Pip step out for fair share
        world.settle();
        world.logOff(CARL); // one human left: they come back, and the party is full with Carl offline
        world.settle();
        assertEquals(List.of(BEN, 201, 202, CARL, 203, 204), world.members(bens));
        world.companion("rue").state = RosterState.BENCHED; // pushed out earlier, waiting for room
        world.waiting.put("rue", 0);
        world.changes.clear();

        world.expel(BEN, CARL);
        world.settle();

        assertEquals(List.of("recall rue"), world.changes);
        assertEquals(List.of(BEN, 201, 202, 203, 204, 205), world.members(bens));
    }

    @Test
    void anExpelInsideAnEventInstanceWaits() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        world.committed.addAll(List.of(BEN, 101)); // the run: Ben and Vex are in the instance
        world.changes.clear();

        world.expel(BEN, 101);
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("vex").state);

        world.committed.clear();
        world.sync.resumeDeferred();
        world.settle();

        assertEquals(List.of("cap " + ANN, "EXPELLED vex"), world.changes);
    }

    // ---- DISBAND ----------------------------------------------------------------------------

    @Test
    void aDisbandReformsEachHumansCrewAroundItsOwnOwner() {
        out(BEN, "kai");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.call(ANN, "olwen");
        world.settle();
        int shared = party(BEN);
        world.changes.clear();

        world.quit(BEN); // the leader leaves: the party disbands
        world.settle();

        assertFalse(world.parties.containsKey(shared));
        assertEquals(List.of(ANN, 101, 102), world.members(party(ANN)));
        assertEquals(List.of(BEN, 201), world.members(party(BEN)));
        assertEquals(RosterState.ACTIVE, world.companion("kai").state);
    }

    @Test
    void aDisbandRecallsTheCompanionsThatWaitedForRoom() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN));
        world.settle();
        assertEquals(RosterState.BENCHED, world.companion("sol").state);
        world.changes.clear();

        world.quit(BEN);
        world.settle();

        assertEquals(List.of(BEN, 201, 202, 203), world.members(party(BEN)));
        assertTrue(world.changes.contains("recall sol"));
    }

    @Test
    void aDisbandInsideAnEventInstanceWaits() {
        out(BEN, "kai");
        world.committed.addAll(List.of(BEN, 201));

        world.quit(BEN);
        world.settle();

        assertEquals(RosterState.ACTIVE, world.companion("kai").state);
        assertEquals(0, world.partyOf(201));

        world.committed.clear();
        world.sync.resumeDeferred();

        assertEquals(List.of(BEN, 201), world.members(party(BEN)));
    }

    // ---- CHANGE_LEADER and LOG_ONOFF --------------------------------------------------------

    @Test
    void aLeaderChangeCarriesThePreviousLeaderAndChangesNothing() {
        out(ANN, "vex", "olwen");
        world.accept(BEN, party(ANN));
        world.settle();
        world.changes.clear();

        world.changeLeader(ANN, BEN);
        PartyChangeEvent change = world.published.get(world.published.size() - 1);
        world.settle();

        assertEquals(PartyOperation.CHANGE_LEADER, change.op());
        assertEquals(ANN, change.before().leaderId());
        assertEquals(BEN, change.after().leaderId());
        assertEquals(ANN, change.actorId());
        assertEquals(List.of(), world.changes);
        assertEquals(List.of(ANN, 101, 102, BEN), world.members(party(BEN)));
    }

    @Test
    void aLogOnIsNeverDestructiveEvenPastTheShares() {
        out(ANN, "vex", "olwen", "brin");
        world.accept(BEN, party(ANN)); // Brin steps out
        world.settle();
        world.logOff(BEN);
        world.settle(); // Ben offline: Ann alone gets her share back, Brin returns
        assertEquals(RosterState.ACTIVE, world.companion("brin").state);
        world.changes.clear();

        world.logOn(BEN);
        world.settle();

        assertEquals(List.of(), world.changes); // three of Ann's past a 2-seat share: nobody is benched for it
        assertEquals(List.of(ANN, 101, 102, BEN, 103), world.members(party(ANN)));
    }

    @Test
    void aFriendLoggingBackOnGetsTheirShareBack() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN)); // Ben's Sol steps out; Vex and Olwen are not out yet
        world.settle();
        world.call(ANN, "vex");
        world.call(ANN, "olwen");
        world.settle();
        world.logOff(ANN); // her crew goes with her (ACTIVE rows) and Ben has Sol back in her seats
        world.settle();
        assertEquals(List.of(BEN, 201, 202, ANN, 203), world.members(party(BEN)));
        world.changes.clear();

        world.logOn(ANN);
        world.settle();

        assertEquals(List.of("OVERFLOW sol", "recall vex", "recall olwen"), world.changes);
        assertEquals(List.of(BEN, 201, 202, ANN, 101, 102), world.members(party(BEN)));
        assertEquals(0, world.waiting.get("sol")); // it comes back when Ben has room again
    }

    @Test
    void aRestoreThatFoundNoSeatWaitsBenchedAndComesBackOnceTheLogOnIsReconciled() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN));
        world.settle();
        world.call(ANN, "vex");
        world.call(ANN, "olwen");
        world.settle();
        world.logOff(ANN);
        world.settle();
        world.changes.clear();

        world.logOn(ANN, true); // the restore runs first: one seat left for Ann, Olwen waits benched
        assertTrue(world.companion("vex").loaded);
        assertEquals(RosterState.BENCHED, world.companion("olwen").state);
        world.settle();

        assertEquals(List.of("OVERFLOW sol", "recall olwen"), world.changes);
        assertEquals(List.of(BEN, 201, 202, ANN, 101, 102), world.members(party(BEN)));
    }

    @Test
    void aLogOffIsReadFromTheEventEvenWhileTheLeaverIsStillInStorage() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN)); // Ben's Sol steps out
        world.settle();
        world.call(ANN, "vex");
        world.settle();
        world.changes.clear();

        world.logOff(ANN);
        world.stillInStorage.add(ANN); // the logout published LOG_ONOFF before Ann left storage
        world.settle();

        assertEquals(List.of("recall sol"), world.changes); // not taken for a log-on
        assertFalse(world.companion("vex").loaded); // and nothing is recalled for the one logging off
    }

    @Test
    void aBotLoggingOnChangesNothing() {
        int bens = out(BEN, "kai");
        world.companion("sol").state = RosterState.BENCHED; // waiting for room, with room
        world.waiting.put("sol", 0);
        PartyEvents.Snapshot now = new PartyEvents.Snapshot(List.of(BEN, 201), BEN);

        // publishAgentOnline on every ALREADY_MEMBER join
        world.sync.onPartyChanged(new PartyChangeEvent(bens, PartyOperation.LOG_ONOFF, 201, 0, now, now, null, true));
        world.settle();

        assertEquals(List.of(), world.changes);
    }

    @Test
    void aLogOffGivesTheOthersTheirWaitingCompanionsBack() {
        out(BEN, "kai", "lune", "sol");
        world.accept(ANN, party(BEN));
        world.settle();
        world.changes.clear();

        world.logOff(ANN);
        world.settle();

        assertEquals(List.of("recall sol"), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("sol").state);
    }

    // ---- echoes and the circuit breaker -----------------------------------------------------

    @Test
    void anyCompanionCauseTokenIsAnEcho() {
        out(ANN, "vex", "olwen");
        world.changes.clear();

        try (PartyEvents.Scope cause = CauseScope.open("companion.swap")) {
            world.leaveParty(101);
        }
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("vex").state); // the swap owns this one
    }

    @Test
    void theCircuitBreakerStopsAfterSixCorrectionsInAMinute() {
        out(ANN, "vex", "olwen");
        for (int i = 0; i < CompanionPartySync.BREAKER_OPS; i++) {
            world.leaveParty(101); // Vex keeps wandering off: each time the sync benches it and Ann calls it back
            world.settle();
            assertEquals(RosterState.BENCHED, world.companion("vex").state);
            assertTrue(world.call(ANN, "vex"));
            world.settle();
        }
        assertFalse(breakerOpened());

        world.leaveParty(101);
        world.settle();

        assertTrue(breakerOpened(), world.drifts.toString());
        assertEquals(RosterState.ACTIVE, world.companion("vex").state); // the seventh correction did not run

        world.now += CompanionPartySync.BREAKER_WINDOW_MS;
        world.leaveParty(102);
        world.settle();
        assertEquals(RosterState.BENCHED, world.companion("olwen").state); // a minute later it reconciles again
    }

    @Test
    void anAcceptIsACorrectionAndIsRefusedWhileTheBreakerIsOpen() {
        int anns = out(ANN, "vex", "olwen");
        int bens = out(BEN, "kai");
        for (int i = 0; i < CompanionPartySync.BREAKER_OPS; i++) {
            world.leaveParty(101); // Vex keeps wandering off
            world.settle();
            assertTrue(world.call(ANN, "vex"));
            world.settle();
        }

        world.accept(BEN, anns); // a merge into the party whose breaker is open

        assertTrue(breakerOpened(), world.drifts.toString());
        assertEquals(List.of("already in a party " + BEN), world.refusals);
        assertEquals(List.of(BEN, 201), world.members(bens));
    }

    private boolean breakerOpened() {
        return world.drifts.stream().anyMatch(drift -> drift.startsWith("circuit breaker open"));
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The owner's party with these companions called in, settled; its id. */
    private int out(int owner, String... keys) {
        if (world.partyOf(owner) == 0) {
            world.create(owner);
        }
        for (String key : keys) {
            assertTrue(world.call(owner, key), key);
        }
        world.settle();
        world.changes.clear();
        return world.partyOf(owner);
    }

    private int party(int human) {
        return world.partyOf(human);
    }
}
