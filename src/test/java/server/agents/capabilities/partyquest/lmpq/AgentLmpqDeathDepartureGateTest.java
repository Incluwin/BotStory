package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h B4: a dead Agent leaves the LMPQ event only while the event can spare it. The fake event models LudiMazePQ.js:
 * a member's respawn walks it out of the maze, and changedMap ends the event when the count it sees (taken before the
 * unregister) is at the stock floor of 3, then unregisters the member. At the floor a dead member stays down only in
 * Room 16 (or, from CLEARING on, on the clear map 809050016), where its body does not keep the team apart for Pietri;
 * anywhere else it respawns and the event ends as it does today.
 */
class AgentLmpqDeathDepartureGateTest {
    private static final int LEADER = 763_100;
    private static final int A = 763_101;
    private static final int B = 763_102;
    private static final int C = 763_103;
    private static final int ROOM_16 = AgentLmpqDefinition.CLEAR_NPC_MAP;
    private static final int ROOM_7 = AgentLmpqDefinition.mapForRoom(7);
    private static final int CLEAR_MAP = AgentLmpqDefinition.REWARD_MAP;

    /** LudiMazePQ.js changedMap on a member leaving the maze, as the gate's event view sees it. */
    private static final class FakeEvent implements AgentLmpqDeathDepartureGate.EventView {
        final Set<Integer> registered = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicBoolean ended = new AtomicBoolean();
        boolean disposed;
        boolean cleared;

        FakeEvent(int... ids) {
            for (int id : ids) registered.add(id);
        }

        /** The respawn of {@code id}: changedMap's floor check, then the unregister. */
        Runnable respawnOf(int id, AtomicInteger respawns) {
            return () -> {
                respawns.incrementAndGet();
                synchronized (registered) {
                    if (registered.size() <= AgentLmpqDefinition.MIN_PARTY_SIZE) ended.set(true);
                    registered.remove(id);
                }
            };
        }

        @Override
        public boolean disposed() { return disposed; }

        @Override
        public boolean cleared() { return cleared; }

        @Override
        public int leaderId() { return LEADER; }

        @Override
        public Set<Integer> registeredIds() {
            synchronized (registered) {
                return Set.copyOf(registered);
            }
        }
    }

    private static AgentLmpqSession session(LmpqRunFixture run, AgentLmpqSession.Phase phase) {
        return run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true, phase,
                run.human(LEADER, "Crewfriend"), run.companion(A, "Dorran"), run.companion(B, "Rurik"),
                run.companion(C, "Yara"));
    }

    private static boolean ask(AgentLmpqSession session, int id, int mapId, FakeEvent event, Runnable respawn,
                               long nowMs) {
        return AgentLmpqDeathDepartureGate.respawnOrHold(session, id, "member", mapId, event, respawn, nowMs);
    }

    @Test
    void aDeathAtFourLeavesAndTheEventLivesOn() {
        try (LmpqRunFixture run = new LmpqRunFixture();
             LmpqLogLines log = new LmpqLogLines(AgentLmpqDeathDepartureGate.class)) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.FARMING);
            FakeEvent event = new FakeEvent(LEADER, A, B, C);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", ROOM_16, event, event.respawnOf(A, respawns), 10_000L),
                    "four can spare one, wherever it lies");

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get(), "three stay: the event lives on");
            assertEquals(Set.of(LEADER, B, C), event.registeredIds());
            assertEquals(0, session.heldDeadCount());
            assertEquals(List.of("LMPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=PROCEED map=809050015 registered=4 inFlight=0 floor=3 heldMs=0"
                            + " phase=FARMING companionRun=true"),
                    log.startingWith("LMPQ departure: "));
        }
    }

    @Test
    void atThreeABodyInRoom16StaysDownAndRegistered() {
        try (LmpqRunFixture run = new LmpqRunFixture();
             LmpqLogLines log = new LmpqLogLines(AgentLmpqDeathDepartureGate.class)) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.REGROUPING);
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentLmpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", ROOM_16, event, event.respawnOf(A, respawns), 10_000L));
            assertFalse(AgentLmpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", ROOM_16, event, event.respawnOf(A, respawns), 10_500L));

            assertEquals(0, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(A), "the held member is still registered");
            assertEquals(1, session.heldDeadCount());
            assertEquals(List.of("LMPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=HOLD map=809050015 registered=3 inFlight=0 floor=3 heldMs=0"
                            + " phase=REGROUPING companionRun=true"),
                    log.startingWith("LMPQ departure: "), "one line per hold");
        }
    }

    @Test
    void atThreeABodyInRoom7RespawnsAndTheEventEndsAsToday() {
        try (LmpqRunFixture run = new LmpqRunFixture();
             LmpqLogLines log = new LmpqLogLines(AgentLmpqDeathDepartureGate.class)) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.FARMING);
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", ROOM_7, event, event.respawnOf(A, respawns), 10_000L));

            assertEquals(1, respawns.get());
            assertTrue(event.ended.get(), "a body in Room 7 would keep Pietri from the team: the event ends");
            assertEquals(0, session.heldDeadCount());
            assertEquals(List.of("LMPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=PROCEED_AT_FLOOR map=" + ROOM_7 + " registered=3 inFlight=0 floor=3"
                            + " heldMs=0 phase=FARMING companionRun=true"),
                    log.startingWith("LMPQ departure: "));
        }
    }

    @Test
    void fromClearingABodyOnTheClearMapStaysDown() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession clearing = session(run, AgentLmpqSession.Phase.CLEARING);
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(ask(clearing, A, CLEAR_MAP, event, event.respawnOf(A, respawns), 10_000L));
            assertFalse(ask(clearing, B, ROOM_16, event, event.respawnOf(B, respawns), 10_000L));
            assertEquals(0, respawns.get());
            assertEquals(2, clearing.heldDeadCount());
        }
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession clearing = session(run, AgentLmpqSession.Phase.CLEARING);
            AtomicInteger respawns = new AtomicInteger();
            assertTrue(ask(clearing, A, ROOM_7, new FakeEvent(LEADER, A, B), respawns::incrementAndGet, 10_000L),
                    "in CLEARING a body anywhere else still respawns");
            assertEquals(1, respawns.get());
        }
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession farming = session(run, AgentLmpqSession.Phase.FARMING);
            AtomicInteger respawns = new AtomicInteger();
            assertTrue(ask(farming, A, CLEAR_MAP, new FakeEvent(LEADER, A, B), respawns::incrementAndGet, 10_000L),
                    "the clear map counts only from CLEARING on");
            assertEquals(1, respawns.get());
        }
    }

    @Test
    void theLeaderIsNeverHeld() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.CLEARING);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(ask(session, LEADER, ROOM_16, new FakeEvent(LEADER, A, B), respawns::incrementAndGet,
                    10_000L), "his departure ends the stock event at any count");
            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
        }
    }

    @Test
    void neverHolds() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.REGROUPING);
            assertRespawnsAtTheFloor(session, event -> event.cleared = true, A);
            assertRespawnsAtTheFloor(session, event -> event.disposed = true, A);
            assertRespawnsAtTheFloor(session, event -> event.registered.remove(B), B);

            AtomicInteger respawns = new AtomicInteger();
            assertTrue(ask(session, A, ROOM_16, null, respawns::incrementAndGet, 10_000L), "no event bound");
            assertEquals(1, respawns.get());

            session.beginTermination();
            assertRespawnsAtTheFloor(session, event -> { }, A);
            session.fail("test", 11_000L);
            assertRespawnsAtTheFloor(session, event -> { }, A);

            Character outsider = mock(Character.class);
            when(outsider.getId()).thenReturn(763_199);
            AtomicInteger outsiderRespawns = new AtomicInteger();
            assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(outsider, outsiderRespawns::incrementAndGet, 10_000L),
                    "an Agent without an LMPQ session respawns as before (KPQ, HPQ, LPQ, EPQ, the field)");
            assertEquals(1, outsiderRespawns.get());
        }
    }

    @Test
    void aFailedRunNeverHoldsBeforeItsReleaseBegins() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession failed = session(run, AgentLmpqSession.Phase.REGROUPING);
            failed.fail("test", 11_000L);
            assertTrue(failed.terminal());
            assertFalse(failed.terminationStarted(), "the run has failed, but no release has begun yet");
            assertRespawnsAtTheFloor(failed, event -> { }, A);
        }
    }

    @Test
    void aHeldBodyIsReleasedOnceTheEventIsCleared() {
        try (LmpqRunFixture run = new LmpqRunFixture();
             LmpqLogLines log = new LmpqLogLines(AgentLmpqDeathDepartureGate.class)) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.CLEARING);
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();
            assertFalse(ask(session, A, ROOM_16, event, event.respawnOf(A, respawns), 10_000L));

            event.cleared = true; // Pietri cleared the team with the body beside it
            assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", ROOM_16, event, respawns::incrementAndGet, 12_500L));

            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
            assertEquals("LMPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=RELEASED map=809050015 registered=3 inFlight=0 floor=3 heldMs=2500"
                            + " phase=CLEARING companionRun=true",
                    log.startingWith("LMPQ departure: ").get(1));
        }
    }

    @Test
    void aDepartureInFlightCountsAsGone() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.CLEARING);
            FakeEvent event = new FakeEvent(LEADER, A, B, C);
            AtomicInteger respawns = new AtomicInteger();
            AtomicBoolean bLeft = new AtomicBoolean(true);
            // B dies in Room 16 while A's respawn is still on its way out (A is registered until it lands).
            Runnable respawnOfA = () -> {
                bLeft.set(ask(session, B, ROOM_16, event, event.respawnOf(B, respawns), 10_010L));
                event.respawnOf(A, respawns).run();
            };

            assertTrue(ask(session, A, ROOM_7, event, respawnOfA, 10_000L), "four can spare A");

            assertFalse(bLeft.get(), "with A leaving, B's departure would end the event: B waits");
            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(B));
            assertFalse(ask(session, C, ROOM_16, event, event.respawnOf(C, respawns), 10_020L),
                    "A is gone now: three stay, so C waits in Room 16 too");
        }
    }

    @Test
    void aThrowingRespawnReleasesItsReservation() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            AgentLmpqSession session = session(run, AgentLmpqSession.Phase.CLEARING);
            FakeEvent event = new FakeEvent(LEADER, A, B, C);
            AtomicInteger respawns = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> ask(session, A, ROOM_7, event, () -> {
                throw new IllegalStateException("respawn failed before leaving");
            }, 10_000L));
            assertTrue(ask(session, B, ROOM_16, event, event.respawnOf(B, respawns), 10_050L),
                    "A never left, so B may go at four");

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
        }
    }

    @Test
    void theLiveEntryReadsTheBodysMapAndTheBoundEvent() {
        try (LmpqRunFixture run = new LmpqRunFixture()) {
            Character leader = run.human(LEADER, "Crewfriend");
            Character dorran = run.companion(A, "Dorran");
            Character rurik = run.companion(B, "Rurik");
            run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true, AgentLmpqSession.Phase.CLEARING,
                    leader, dorran, rurik);
            when(run.event.getLeaderId()).thenReturn(LEADER);
            AtomicInteger respawns = new AtomicInteger();

            when(dorran.getMapId()).thenReturn(ROOM_16);
            assertFalse(AgentLmpqDeathDepartureGate.respawnOrHold(dorran, respawns::incrementAndGet, 10_000L));
            when(rurik.getMapId()).thenReturn(ROOM_7);
            assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(rurik, respawns::incrementAndGet, 10_000L));

            assertEquals(1, respawns.get(), "Rurik in Room 7 left; Dorran in Room 16 waits");
        }
    }

    private static void assertRespawnsAtTheFloor(AgentLmpqSession session, Consumer<FakeEvent> setUp, int id) {
        FakeEvent event = new FakeEvent(LEADER, A, B);
        setUp.accept(event);
        AtomicInteger respawns = new AtomicInteger();
        assertTrue(ask(session, id, ROOM_16, event, respawns::incrementAndGet, 10_000L));
        assertEquals(1, respawns.get(), "respawned at once, in the same call");
    }
}
