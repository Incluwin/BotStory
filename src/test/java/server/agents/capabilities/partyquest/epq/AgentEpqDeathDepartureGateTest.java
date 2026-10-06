package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 5: a dead Agent leaves the EPQ event only while the event can spare it. The fake event models
 * EllinPQ.js: a member's respawn walks it out of the instance, and changedMap ends the event when the count it sees
 * (taken before the unregister) is at the stock floor of 4, then unregisters the member.
 */
class AgentEpqDeathDepartureGateTest {
    private static final int LEADER = 1;
    private static final int A = 2;
    private static final int B = 3;
    private static final int C = 4;
    private static final int D = 5;
    private static final int E = 6;

    /** EllinPQ.js changedMap on a member leaving the instance, as the gate's event view sees it. */
    private static final class FakeEvent implements AgentEpqDeathDepartureGate.EventView {
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
                    if (registered.size() <= AgentEpqDefinition.MIN_PARTY_SIZE) ended.set(true);
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

    private static AgentEpqSession session(EpqRunFixture run) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.STAGE_ONE,
                run.human(LEADER, "Crewfriend", AgentEpqDefinition.STAGE_ONE_MAP),
                run.companion(A, "Halvard", AgentEpqDefinition.STAGE_ONE_MAP),
                run.companion(B, "Kestrel", AgentEpqDefinition.STAGE_ONE_MAP),
                run.companion(C, "Pell", AgentEpqDefinition.STAGE_ONE_MAP),
                run.companion(D, "Lioren", AgentEpqDefinition.STAGE_ONE_MAP),
                run.companion(E, "Casse", AgentEpqDefinition.STAGE_ONE_MAP));
    }

    @Test
    void aboveTheFloorADeadAgentLeaves() {
        try (EpqRunFixture run = new EpqRunFixture();
             EpqLogLines log = new EpqLogLines(AgentEpqDeathDepartureGate.class)) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C, D, E);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L));
            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, B, "Kestrel", event, event.respawnOf(B, respawns), 20_000L));

            assertEquals(2, respawns.get());
            assertFalse(event.ended.get(), "four stay: the event lives on");
            assertEquals(Set.of(LEADER, C, D, E), event.registeredIds());
            String id = session.sessionId();
            assertEquals(List.of(
                    "EPQ departure: session=" + id + " member=Halvard(" + A + ") decision=PROCEED registered=6 "
                            + "inFlight=0 floor=4 heldMs=0 phase=STAGE_ONE companionRun=true",
                    "EPQ departure: session=" + id + " member=Kestrel(" + B + ") decision=PROCEED registered=5 "
                            + "inFlight=0 floor=4 heldMs=0 phase=STAGE_ONE companionRun=true"),
                    log.startingWith("EPQ departure: "));
        }
    }

    @Test
    void atTheFloorADeadAgentWaits() {
        try (EpqRunFixture run = new EpqRunFixture();
             EpqLogLines log = new EpqLogLines(AgentEpqDeathDepartureGate.class)) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C);
            AtomicInteger respawns = new AtomicInteger();

            for (long now = 10_000L; now <= 10_200L; now += 100L) {
                assertFalse(AgentEpqDeathDepartureGate.respawnOrHold(
                        session, A, "Halvard", event, event.respawnOf(A, respawns), now));
            }

            assertEquals(0, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(A), "the held member is still registered");
            assertEquals(1, session.heldDeadCount());
            assertEquals(List.of("EPQ departure: session=" + session.sessionId() + " member=Halvard(" + A
                    + ") decision=HOLD registered=4 inFlight=0 floor=4 heldMs=0 phase=STAGE_ONE companionRun=true"),
                    log.startingWith("EPQ departure: "), "one HOLD line per hold");
        }
    }

    @Test
    void twoDueRespawnsAtFiveKeepTheEventAlive() {
        try (EpqRunFixture run = new EpqRunFixture()) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C, D);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L));
            assertFalse(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, B, "Kestrel", event, event.respawnOf(B, respawns), 10_000L));

            assertEquals(1, respawns.get(), "only the first dead member left");
            assertFalse(event.ended.get(), "the event outlived both deaths");
            assertTrue(event.registered.contains(B));
            assertEquals(1, session.heldDeadCount());
        }
    }

    @Test
    void concurrentDueRespawnsCannotBothLeave() throws Exception {
        try (EpqRunFixture run = new EpqRunFixture();
             EpqLogLines log = new EpqLogLines(AgentEpqDeathDepartureGate.class)) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C, D);
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
            Thread first = new Thread(() -> aLeft.set(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, slowRespawnOfA, 10_000L)));
            first.start();
            assertTrue(inFlight.await(10, TimeUnit.SECONDS));

            AtomicBoolean bLeft = new AtomicBoolean(true);
            Thread second = new Thread(() -> bLeft.set(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, B, "Kestrel", event, event.respawnOf(B, respawns), 10_010L)));
            second.start();
            second.join(10_000L);
            release.countDown();
            first.join(10_000L);

            assertTrue(aLeft.get());
            assertFalse(bLeft.get(), "the second death waits while the first is still leaving");
            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(log.startingWith("EPQ departure: ").contains("EPQ departure: session=" + session.sessionId()
                    + " member=Kestrel(" + B + ") decision=HOLD registered=5 inFlight=1 floor=4 heldMs=0 "
                    + "phase=STAGE_ONE companionRun=true"), log.startingWith("EPQ departure: ")::toString);
        }
    }

    @Test
    void aHeldMemberLeavesOnceTheEventClears() {
        try (EpqRunFixture run = new EpqRunFixture();
             EpqLogLines log = new EpqLogLines(AgentEpqDeathDepartureGate.class)) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, event.respawnOf(A, respawns), 10_000L));
            event.cleared = true;
            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, () -> respawns.incrementAndGet(), 10_400L));

            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
            assertEquals("EPQ departure: session=" + session.sessionId() + " member=Halvard(" + A
                    + ") decision=RELEASED registered=4 inFlight=0 floor=4 heldMs=400 phase=STAGE_ONE "
                    + "companionRun=true", log.startingWith("EPQ departure: ").getLast());
        }
    }

    @Test
    void neverHolds() {
        try (EpqRunFixture run = new EpqRunFixture()) {
            AgentEpqSession session = session(run);
            assertRespawnsAtTheFloor(session, event -> event.cleared = true, A);
            assertRespawnsAtTheFloor(session, event -> event.disposed = true, A);
            assertRespawnsAtTheFloor(session, event -> { }, LEADER);
            assertRespawnsAtTheFloor(session, event -> event.registered.remove(D), D);

            AtomicInteger respawns = new AtomicInteger();
            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(session, A, "Halvard", null,
                    respawns::incrementAndGet, 10_000L), "no event bound");
            assertEquals(1, respawns.get());

            session.beginTermination();
            assertRespawnsAtTheFloor(session, event -> { }, A);
            session.fail("test", 11_000L);
            assertRespawnsAtTheFloor(session, event -> { }, A);
        }
    }

    @Test
    void aFailedRunNeverHoldsBeforeItsReleaseBegins() {
        try (EpqRunFixture run = new EpqRunFixture()) {
            AgentEpqSession failed = session(run);
            failed.fail("test", 11_000L);
            assertTrue(failed.terminal());
            assertFalse(failed.terminationStarted(), "the run has failed, but no release has begun yet");
            assertRespawnsAtTheFloor(failed, event -> { }, A);
        }
    }

    @Test
    void anAgentWithoutAnEpqSessionRespawnsAtOnce() {
        Character outsider = mock(Character.class);
        when(outsider.getId()).thenReturn(762_199);
        AtomicInteger respawns = new AtomicInteger();

        assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(outsider, respawns::incrementAndGet, 10_000L),
                "an Agent without an EPQ session respawns as before (KPQ, HPQ, LPQ, the field)");
        assertEquals(1, respawns.get(), "in the same call");
        assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(null, respawns::incrementAndGet, 10_000L));
        assertEquals(2, respawns.get());
    }

    @Test
    void thePublicEntryReadsTheBoundEvent() {
        try (EpqRunFixture run = new EpqRunFixture()) {
            AgentEpqSession session = session(run);
            Character halvard = AgentEpqCoordinator.character(A);
            List<Character> four = List.of(AgentEpqCoordinator.character(LEADER), halvard,
                    AgentEpqCoordinator.character(B), AgentEpqCoordinator.character(C));
            when(run.event.getPlayers()).thenReturn(four);
            when(run.event.getLeaderId()).thenReturn(LEADER);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentEpqDeathDepartureGate.respawnOrHold(halvard, respawns::incrementAndGet, 10_000L),
                    "four registered: the bound event cannot spare Halvard");
            assertEquals(0, respawns.get());
            assertEquals(1, session.heldDeadCount());
        }
    }

    @Test
    void aThrowingRespawnReleasesItsReservation() {
        try (EpqRunFixture run = new EpqRunFixture()) {
            AgentEpqSession session = session(run);
            FakeEvent event = new FakeEvent(LEADER, A, B, C, D);
            AtomicInteger respawns = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> AgentEpqDeathDepartureGate.respawnOrHold(
                    session, A, "Halvard", event, () -> {
                        throw new IllegalStateException("respawn failed before leaving");
                    }, 10_000L));
            assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(
                    session, B, "Kestrel", event, event.respawnOf(B, respawns), 10_050L),
                    "Halvard never left, so Kestrel may go at five");

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get());
        }
    }

    @Test
    void theFloorIsTheScriptsFloor() throws Exception {
        String script = Files.readString(Path.of("scripts/event/EllinPQ.js"));
        Matcher floor = Pattern.compile("var minPlayers = (\\d+)").matcher(script);
        assertTrue(floor.find(), "EllinPQ.js names its floor");
        assertEquals(AgentEpqDefinition.MIN_PARTY_SIZE, Integer.parseInt(floor.group(1)));
        int changedMap = script.indexOf("function changedMap(");
        int next = script.indexOf("function ", changedMap + 1);
        assertTrue(changedMap >= 0 && next > changedMap);
        assertTrue(script.substring(changedMap, next).contains("isEventTeamLackingNow(true, minPlayers"),
                "a member leaving the instance ends the event at the floor");
    }

    private static void assertRespawnsAtTheFloor(AgentEpqSession session,
                                                 java.util.function.Consumer<FakeEvent> setUp, int id) {
        FakeEvent event = new FakeEvent(LEADER, A, B, C);
        setUp.accept(event);
        AtomicInteger respawns = new AtomicInteger();
        assertTrue(AgentEpqDeathDepartureGate.respawnOrHold(session, id, "member", event, respawns::incrementAndGet,
                10_000L));
        assertEquals(1, respawns.get(), "respawned at once, in the same call");
    }
}
