package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LPQ base fix 1: a dead Agent leaves the LPQ event only while the event can spare it. The fake event models
 * LudiPQ.js: a member's respawn walks it out of the instance, and changedMap ends the event when the count it sees
 * (taken before the unregister) is at the stock floor of 5, then unregisters the member.
 */
class AgentLpqDeathDepartureGateTest {
    private static final int LEADER = 762_100;
    private static final int A = 762_101;
    private static final int B = 762_102;

    /** LudiPQ.js changedMap on a member leaving the instance, as the gate's event view sees it. */
    private static final class FakeEvent implements AgentLpqDeathDepartureGate.EventView {
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
                    if (registered.size() <= AgentLpqDefinition.MIN_PARTY_SIZE) ended.set(true);
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

    private static AgentLpqSession session(LpqRunFixture run) {
        return run.session(AgentLpqSession.Mode.PRODUCTION, true, AgentLpqSession.Phase.STAGE_9,
                run.human(LEADER, "Crewfriend"), run.companion(A, "Halvard"), run.companion(B, "Lioren"),
                run.companion(762_103, "Pell"), run.companion(762_104, "Kestrel"),
                run.companion(762_105, "Casse"));
    }

    private static FakeEvent sixRegistered() {
        return new FakeEvent(LEADER, A, B, 762_103, 762_104, 762_105);
    }

    @Test
    void twoDueRespawnsAtSixKeepTheEventAlive() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            AgentLpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L));
            assertFalse(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_000L));

            assertEquals(1, respawns.get(), "only the first dead member left");
            assertFalse(event.ended.get(), "the event outlived both deaths");
            assertTrue(event.registered.contains(B), "the held member is still registered");
            assertEquals(1, session.heldDeadCount());
        }
    }

    @Test
    void aHeldMemberLeavesOnceTheFirstIsBack() {
        try (LpqRunFixture run = new LpqRunFixture();
             LpqLogLines log = new LpqLogLines(AgentLpqDeathDepartureGate.class)) {
            AgentLpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();

            AgentLpqDeathDepartureGate.respawnOrHold(session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L);
            assertFalse(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_000L));
            assertFalse(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_050L));
            event.registered.add(A); // the coordinator re-registers the first (restoreEventMember)
            assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_400L));

            assertEquals(2, respawns.get());
            assertFalse(event.ended.get());
            assertEquals(0, session.heldDeadCount());
            String id = session.sessionId();
            assertEquals(List.of(
                    "LPQ departure: session=" + id + " member=Halvard(" + A + ") decision=PROCEED registered=6 "
                            + "inFlight=0 floor=5 heldMs=0 phase=STAGE_9 companionRun=true",
                    "LPQ departure: session=" + id + " member=Lioren(" + B + ") decision=HOLD registered=5 "
                            + "inFlight=0 floor=5 heldMs=0 phase=STAGE_9 companionRun=true",
                    "LPQ departure: session=" + id + " member=Lioren(" + B + ") decision=RELEASED registered=6 "
                            + "inFlight=0 floor=5 heldMs=400 phase=STAGE_9 companionRun=true"),
                    log.startingWith("LPQ departure: "));
        }
    }

    @Test
    void concurrentDueRespawnsCannotBothLeave() throws Exception {
        try (LpqRunFixture run = new LpqRunFixture()) {
            AgentLpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();
            CountDownLatch inFlight = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Runnable slowRespawnOfA = () -> {
                inFlight.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                event.respawnOf(A, respawns).run();
            };
            AtomicBoolean aLeft = new AtomicBoolean();
            Thread first = new Thread(() -> aLeft.set(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, slowRespawnOfA, 10_000L)));
            first.start();
            assertTrue(inFlight.await(10, TimeUnit.SECONDS));

            AtomicBoolean bLeft = new AtomicBoolean(true);
            Thread second = new Thread(() -> bLeft.set(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_010L)));
            second.start();
            second.join(10_000L);
            release.countDown();
            first.join(10_000L);

            assertTrue(aLeft.get());
            assertFalse(bLeft.get(), "the second death waits while the first is still leaving");
            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
        }
    }

    @Test
    void neverHolds() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            AgentLpqSession cleared = session(run);
            assertRespawnsAtTheFloor(cleared, event -> event.cleared = true, A);
            assertRespawnsAtTheFloor(cleared, event -> event.disposed = true, A);
            assertRespawnsAtTheFloor(cleared, event -> { }, LEADER);
            assertRespawnsAtTheFloor(cleared, event -> event.registered.remove(B), B);

            AtomicInteger respawns = new AtomicInteger();
            assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(cleared, A, "Halvard", null, respawns::incrementAndGet,
                    10_000L), "no event bound");
            assertEquals(1, respawns.get());

            cleared.beginTermination();
            assertRespawnsAtTheFloor(cleared, event -> { }, A);
            cleared.fail("test", 11_000L);
            assertRespawnsAtTheFloor(cleared, event -> { }, A);

            Character outsider = mock(Character.class);
            when(outsider.getId()).thenReturn(762_199);
            AtomicInteger outsiderRespawns = new AtomicInteger();
            assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(outsider, outsiderRespawns::incrementAndGet, 10_000L),
                    "an Agent without an LPQ session respawns as before (KPQ, HPQ, the field)");
            assertEquals(1, outsiderRespawns.get());
        }
    }

    private static void assertRespawnsAtTheFloor(AgentLpqSession session,
                                                 java.util.function.Consumer<FakeEvent> setUp, int id) {
        FakeEvent event = new FakeEvent(LEADER, A, B, 762_103, 762_104);
        setUp.accept(event);
        AtomicInteger respawns = new AtomicInteger();
        assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(session, id, "member", event, respawns::incrementAndGet,
                10_000L));
        assertEquals(1, respawns.get(), "respawned at once, in the same call");
    }

    @Test
    void atTheFloorADeadMemberWaits() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            AgentLpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, 762_103, 762_104);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L));

            assertEquals(0, respawns.get());
            assertFalse(event.ended.get());
        }
    }

    @Test
    void aThrowingRespawnReleasesItsReservation() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            AgentLpqSession session = session(run);
            FakeEvent event = sixRegistered();
            AtomicInteger respawns = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> AgentLpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, () -> {
                        throw new IllegalStateException("respawn failed before leaving");
                    }, 10_000L));
            assertTrue(AgentLpqDeathDepartureGate.respawnOrHold(
                    session, B, "Lioren", event, event.respawnOf(B, respawns), 10_050L),
                    "A never left, so B may go at six");

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
        }
    }
}
