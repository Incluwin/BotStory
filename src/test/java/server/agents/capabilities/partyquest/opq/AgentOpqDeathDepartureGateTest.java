package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

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
 * P6h OB7: a dead Agent leaves the OPQ event only while the event can spare it. The fake event models OrbisPQ.js: a
 * member's respawn walks it out of the tower, and changedMap ends the event when the count it sees (taken before the
 * unregister) is at the stock floor (the catalog's OPQ minimum, 5), then unregisters the member. At the floor a dead
 * member stays down, still registered, wherever it lies (as LPQ and EPQ).
 */
class AgentOpqDeathDepartureGateTest {
    private static final int LEADER = 764_100;
    private static final int A = 764_101;
    private static final int B = 764_102;
    private static final int C = 764_103;
    private static final int D = 764_104;
    private static final int E = 764_105;
    private static final int FLOOR = AgentPartyQuestCatalog.require("opq").minimumPartySize();

    /** OrbisPQ.js changedMap on a member leaving the tower, as the gate's event view sees it. */
    private static final class FakeEvent implements AgentOpqDeathDepartureGate.EventView {
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
                    if (registered.size() <= FLOOR) ended.set(true);
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

    private static AgentOpqSession session(OpqRunFixture run) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.SPLIT_ROOMS,
                run.human(LEADER, "Crewfriend"), run.companion(A, "Dorran"), run.companion(B, "Rurik"),
                run.companion(C, "Yara"), run.companion(D, "HappyWolf"), run.companion(E, "IceCookie"));
    }

    private static FakeEvent sixRegistered() {
        return new FakeEvent(LEADER, A, B, C, D, E);
    }

    private static FakeEvent fiveRegistered() {
        return new FakeEvent(LEADER, A, B, C, D);
    }

    private static boolean ask(AgentOpqSession session, int id, FakeEvent event, Runnable respawn, long nowMs) {
        return AgentOpqDeathDepartureGate.respawnOrHold(session, id, "member", event, respawn, nowMs);
    }

    @Test
    void theFloorIsTheCatalogsOpqMinimum() {
        assertEquals(5, FLOOR);
        assertEquals(FLOOR, AgentOpqDeathDepartureGate.floor());
    }

    @Test
    void aDeathAtSixLeavesAndTheEventLivesOn() {
        try (OpqRunFixture run = new OpqRunFixture();
             OpqLogLines log = new OpqLogLines(AgentOpqDeathDepartureGate.class)) {
            AgentOpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentOpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", event, event.respawnOf(A, respawns), 10_000L));

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get(), "five stay: the event lives on");
            assertEquals(Set.of(LEADER, B, C, D, E), event.registeredIds());
            assertEquals(0, session.heldDeadCount());
            assertEquals(List.of("OPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=PROCEED registered=6 inFlight=0 floor=5 heldMs=0 phase=SPLIT_ROOMS"
                            + " companionRun=true"),
                    log.startingWith("OPQ departure: "));
        }
    }

    @Test
    void atFiveADeadMemberStaysDownAndRegistered() {
        try (OpqRunFixture run = new OpqRunFixture();
             OpqLogLines log = new OpqLogLines(AgentOpqDeathDepartureGate.class)) {
            AgentOpqSession session = session(run);
            FakeEvent event = fiveRegistered();
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentOpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", event, event.respawnOf(A, respawns), 10_000L));
            assertFalse(AgentOpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", event, event.respawnOf(A, respawns), 10_500L));

            assertEquals(0, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(A), "the held member is still registered");
            assertEquals(1, session.heldDeadCount());
            assertEquals(List.of("OPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=HOLD registered=5 inFlight=0 floor=5 heldMs=0 phase=SPLIT_ROOMS"
                            + " companionRun=true"),
                    log.startingWith("OPQ departure: "), "one line per hold");
        }
    }

    @Test
    void aDepartureInFlightCountsAsGone() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();
            AtomicBoolean bLeft = new AtomicBoolean(true);
            // B dies while A's respawn is still on its way out (A is registered until it lands).
            Runnable respawnOfA = () -> {
                bLeft.set(ask(session, B, event, event.respawnOf(B, respawns), 10_010L));
                event.respawnOf(A, respawns).run();
            };

            assertTrue(ask(session, A, event, respawnOfA, 10_000L), "six can spare A");

            assertFalse(bLeft.get(), "with A leaving, B's departure would end the event: B waits");
            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(B));
        }
    }

    @Test
    void aHeldMemberIsReleasedOnceTheEventIsCleared() {
        try (OpqRunFixture run = new OpqRunFixture();
             OpqLogLines log = new OpqLogLines(AgentOpqDeathDepartureGate.class)) {
            AgentOpqSession session = session(run);
            FakeEvent event = fiveRegistered();
            AtomicInteger respawns = new AtomicInteger();
            assertFalse(ask(session, A, event, event.respawnOf(A, respawns), 10_000L));

            event.cleared = true; // Minerva is freed
            assertTrue(AgentOpqDeathDepartureGate.respawnOrHold(
                    session, A, "Dorran", event, respawns::incrementAndGet, 13_000L));

            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
            assertEquals("OPQ departure: session=" + session.sessionId() + " member=Dorran(" + A
                            + ") decision=RELEASED registered=5 inFlight=0 floor=5 heldMs=3000 phase=SPLIT_ROOMS"
                            + " companionRun=true",
                    log.startingWith("OPQ departure: ").get(1));
        }
    }

    @Test
    void theLeaderIsNeverHeld() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = session(run);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(ask(session, LEADER, fiveRegistered(), respawns::incrementAndGet, 10_000L),
                    "his departure ends the stock event at any count");
            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
        }
    }

    @Test
    void neverHolds() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = session(run);
            assertRespawnsAtTheFloor(session, event -> event.cleared = true, A);
            assertRespawnsAtTheFloor(session, event -> event.disposed = true, A);
            assertRespawnsAtTheFloor(session, event -> event.registered.remove(B), B);

            AtomicInteger respawns = new AtomicInteger();
            assertTrue(ask(session, A, null, respawns::incrementAndGet, 10_000L), "no event bound");
            assertEquals(1, respawns.get());

            session.beginTermination();
            assertRespawnsAtTheFloor(session, event -> { }, A);
            session.fail("test", 11_000L);
            assertRespawnsAtTheFloor(session, event -> { }, A);

            Character outsider = mock(Character.class);
            when(outsider.getId()).thenReturn(764_199);
            AtomicInteger outsiderRespawns = new AtomicInteger();
            assertTrue(AgentOpqDeathDepartureGate.respawnOrHold(outsider, outsiderRespawns::incrementAndGet, 10_000L),
                    "an Agent without an OPQ session respawns as before (KPQ, HPQ, LPQ, EPQ, LMPQ, the field)");
            assertEquals(1, outsiderRespawns.get());
        }
    }

    @Test
    void aFailedRunNeverHoldsBeforeItsReleaseBegins() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession failed = session(run);
            failed.fail("test", 11_000L);
            assertTrue(failed.terminal());
            assertFalse(failed.terminationStarted(), "the run has failed, but no release has begun yet");
            assertRespawnsAtTheFloor(failed, event -> { }, A);
        }
    }

    @Test
    void aThrowingRespawnReleasesItsReservation() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> ask(session, A, event, () -> {
                throw new IllegalStateException("respawn failed before leaving");
            }, 10_000L));
            assertTrue(ask(session, B, event, event.respawnOf(B, respawns), 10_050L),
                    "A never left, so B may go at six");

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
        }
    }

    @Test
    void theLiveEntryReadsTheBoundEvent() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            Character leader = run.human(LEADER, "Crewfriend");
            Character dorran = run.companion(A, "Dorran");
            run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.GARDEN, leader, dorran,
                    run.companion(B, "Rurik"), run.companion(C, "Yara"), run.companion(D, "HappyWolf"));
            when(run.event.getLeaderId()).thenReturn(LEADER);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentOpqDeathDepartureGate.respawnOrHold(dorran, respawns::incrementAndGet, 10_000L),
                    "five registered: Dorran waits");
            assertTrue(AgentOpqDeathDepartureGate.respawnOrHold(leader, respawns::incrementAndGet, 10_000L));

            assertEquals(1, respawns.get());
        }
    }

    private static void assertRespawnsAtTheFloor(AgentOpqSession session, Consumer<FakeEvent> setUp, int id) {
        FakeEvent event = fiveRegistered();
        setUp.accept(event);
        AtomicInteger respawns = new AtomicInteger();
        assertTrue(ask(session, id, event, respawns::incrementAndGet, 10_000L));
        assertEquals(1, respawns.get(), "respawned at once, in the same call");
    }
}
