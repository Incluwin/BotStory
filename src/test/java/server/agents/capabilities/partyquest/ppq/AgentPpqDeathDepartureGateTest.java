package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PPQ Step 1b.5 (D12 gate, R4; every mode): a dead Agent leaves the PPQ event only while the event can spare it. The
 * fake event models PiratePQ.js's playerRevive: the respawn presses OK, and the event ends when the team is lacking
 * (getPlayerCount() <= minPlayers, 3) before the member is unregistered. Six, five and four may each lose one; at three
 * a dead member stays down, still registered, until the clear or the end of the run lets it go.
 */
class AgentPpqDeathDepartureGateTest {
    private static final int LEADER = 765_100;
    private static final int A = 765_101;
    private static final int B = 765_102;
    private static final int C = 765_103;
    private static final int D = 765_104;
    private static final int E = 765_105;
    private static final int FLOOR = AgentPartyQuestCatalog.require("ppq").minimumPartySize();

    /** PiratePQ.js playerRevive on a member pressing OK, as the gate's event view sees it. */
    private static final class FakeEvent implements AgentPpqDeathDepartureGate.EventView {
        final Set<Integer> registered = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicBoolean ended = new AtomicBoolean();
        boolean disposed;
        boolean cleared;

        FakeEvent(int... ids) {
            for (int id : ids) registered.add(id);
        }

        /** The respawn of {@code id}: playerRevive's floor check, then the unregister. */
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

    /** Crewfriend leads five companions in a companion run on the second deck. */
    private static AgentPpqSession session() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, LEADER, false, 1_000L);
        session.addMember(LEADER, AgentPpqMemberState.MemberType.HUMAN);
        for (int id : List.of(A, B, C, D, E)) session.addMember(id, AgentPpqMemberState.MemberType.AGENT);
        session.setLeadership(LEADER, A);
        session.setCompanionAdopted(true);
        session.observeStage(AgentPpqStage.DECK_TWO, 1_000L);
        return session;
    }

    private static boolean ask(AgentPpqSession session, int id, FakeEvent event, Runnable respawn, long nowMs) {
        return AgentPpqDeathDepartureGate.respawnOrHold(session, id, "member", event, respawn, nowMs);
    }

    @Test
    void theFloorIsTheCatalogsPpqMinimum() {
        assertEquals(3, FLOOR);
        assertEquals(FLOOR, AgentPpqDeathDepartureGate.floor());
    }

    @Test
    void aDeathAtSixLeavesAndTheEventLivesOn() {
        try (PpqLogLines log = new PpqLogLines(AgentPpqDeathDepartureGate.class)) {
            AgentPpqSession session = session();
            FakeEvent event = new FakeEvent(LEADER, A, B, C, D, E);
            AtomicInteger respawns = new AtomicInteger();

            assertTrue(AgentPpqDeathDepartureGate.respawnOrHold(
                    session, B, "Rurik", event, event.respawnOf(B, respawns), 10_000L));

            assertEquals(1, respawns.get());
            assertFalse(event.ended.get(), "five stay: the event lives on");
            assertEquals(0, session.heldDeadCount());
            assertEquals(List.of("PPQ departure: session=" + session.sessionId() + " member=Rurik(" + B
                            + ") decision=PROCEED registered=6 inFlight=0 floor=3 heldMs=0 stage=DECK_TWO"
                            + " companionRun=true"),
                    log.startingWith("PPQ departure: "));
        }
    }

    @Test
    void aDeathAtFourLeavesThree() {
        AgentPpqSession session = session();
        FakeEvent event = new FakeEvent(LEADER, A, B, C);
        AtomicInteger respawns = new AtomicInteger();

        assertTrue(ask(session, B, event, event.respawnOf(B, respawns), 10_000L));

        assertEquals(1, respawns.get());
        assertFalse(event.ended.get());
        assertEquals(Set.of(LEADER, A, C), event.registeredIds());
    }

    @Test
    void atThreeADeadMemberStaysDownAndRegistered() {
        try (PpqLogLines log = new PpqLogLines(AgentPpqDeathDepartureGate.class)) {
            AgentPpqSession session = session();
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();

            assertFalse(AgentPpqDeathDepartureGate.respawnOrHold(
                    session, B, "Rurik", event, event.respawnOf(B, respawns), 10_000L));
            assertFalse(AgentPpqDeathDepartureGate.respawnOrHold(
                    session, B, "Rurik", event, event.respawnOf(B, respawns), 10_500L));

            assertEquals(0, respawns.get());
            assertFalse(event.ended.get());
            assertTrue(event.registered.contains(B), "the held member is still registered");
            assertEquals(1, session.heldDeadCount());
            assertEquals(List.of("PPQ departure: session=" + session.sessionId() + " member=Rurik(" + B
                            + ") decision=HOLD registered=3 inFlight=0 floor=3 heldMs=0 stage=DECK_TWO"
                            + " companionRun=true"),
                    log.startingWith("PPQ departure: "), "one line per hold");
        }
    }

    @Test
    void twoDeadAtFourOneLeavesOneWaits() {
        AgentPpqSession session = session();
        FakeEvent event = new FakeEvent(LEADER, A, B, C);
        AtomicInteger respawns = new AtomicInteger();
        AtomicBoolean cLeft = new AtomicBoolean(true);
        // C dies while B's respawn is still on its way out (B is registered until it lands).
        Runnable respawnOfB = () -> {
            cLeft.set(ask(session, C, event, event.respawnOf(C, respawns), 10_010L));
            event.respawnOf(B, respawns).run();
        };

        assertTrue(ask(session, B, event, respawnOfB, 10_000L), "four can spare B");

        assertFalse(cLeft.get(), "with B leaving, C's departure would end the event: C waits");
        assertEquals(1, respawns.get());
        assertFalse(event.ended.get());
        assertTrue(event.registered.contains(C));
    }

    @Test
    void aHeldMemberIsReleasedOnceTheEventIsCleared() {
        try (PpqLogLines log = new PpqLogLines(AgentPpqDeathDepartureGate.class)) {
            AgentPpqSession session = session();
            FakeEvent event = new FakeEvent(LEADER, A, B);
            AtomicInteger respawns = new AtomicInteger();
            assertFalse(ask(session, B, event, event.respawnOf(B, respawns), 10_000L));

            event.cleared = true; // Lord Pirate fell and Wu Yang is free
            assertTrue(AgentPpqDeathDepartureGate.respawnOrHold(
                    session, B, "Rurik", event, respawns::incrementAndGet, 13_000L));

            assertEquals(1, respawns.get());
            assertEquals(0, session.heldDeadCount());
            assertEquals("PPQ departure: session=" + session.sessionId() + " member=Rurik(" + B
                            + ") decision=RELEASED registered=3 inFlight=0 floor=3 heldMs=3000 stage=DECK_TWO"
                            + " companionRun=true",
                    log.startingWith("PPQ departure: ").get(1));
        }
    }

    @Test
    void theLeaderIsNeverHeld() {
        AgentPpqSession session = session();
        AtomicInteger respawns = new AtomicInteger();

        assertTrue(ask(session, LEADER, new FakeEvent(LEADER, A, B), respawns::incrementAndGet, 10_000L),
                "his departure ends the stock event at any count");
        assertEquals(1, respawns.get());
        assertEquals(0, session.heldDeadCount());
    }

    @Test
    void neverHolds() {
        AgentPpqSession session = session();
        assertRespawnsAtTheFloor(session, event -> event.cleared = true, B);
        assertRespawnsAtTheFloor(session, event -> event.disposed = true, B);
        assertRespawnsAtTheFloor(session, event -> event.registered.remove(C), C);

        AtomicInteger respawns = new AtomicInteger();
        assertTrue(ask(session, B, null, respawns::incrementAndGet, 10_000L), "no event bound");
        assertEquals(1, respawns.get());

        session.beginTermination();
        assertRespawnsAtTheFloor(session, event -> { }, B);
        session.fail("test", 11_000L);
        assertRespawnsAtTheFloor(session, event -> { }, B);
    }

    @Test
    void aFailedRunNeverHoldsBeforeItsReleaseBegins() {
        AgentPpqSession failed = session();
        failed.fail("test", 11_000L);
        assertFalse(failed.terminationStarted(), "the run has failed, but no release has begun yet");
        assertRespawnsAtTheFloor(failed, event -> { }, B);
    }

    @Test
    void anAgentWithoutAPpqSessionRespawnsAsBefore() {
        Character outsider = mock(Character.class);
        when(outsider.getId()).thenReturn(765_199);
        AtomicInteger respawns = new AtomicInteger();

        assertTrue(AgentPpqDeathDepartureGate.respawnOrHold(outsider, respawns::incrementAndGet, 10_000L));

        assertEquals(1, respawns.get());
    }

    @Test
    void aThrowingRespawnReleasesItsReservation() {
        AgentPpqSession session = session();
        FakeEvent event = new FakeEvent(LEADER, A, B, C);
        AtomicInteger respawns = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> ask(session, B, event, () -> {
            throw new IllegalStateException("respawn failed before leaving");
        }, 10_000L));
        assertTrue(ask(session, C, event, event.respawnOf(C, respawns), 10_050L), "B never left, so C may go at four");

        assertEquals(1, respawns.get());
        assertFalse(event.ended.get());
    }

    /** At the floor (three registered, {@code id} among them) after {@code change}, the member respawns at once. */
    private static void assertRespawnsAtTheFloor(AgentPpqSession session, java.util.function.Consumer<FakeEvent> change,
                                                 int id) {
        FakeEvent event = new FakeEvent(LEADER, A, id == C ? C : B);
        change.accept(event);
        AtomicInteger respawns = new AtomicInteger();
        assertTrue(ask(session, id, event, respawns::incrementAndGet, 10_000L));
        assertEquals(1, respawns.get());
        assertEquals(0, session.heldDeadCount());
    }
}
