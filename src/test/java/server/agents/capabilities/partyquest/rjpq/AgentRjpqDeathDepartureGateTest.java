package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before the clear a run of exactly four is at its floor, so every dead companion stays down on its map (a respawn
 * walks it out and MagatiaPQ ends the event); once the event is cleared, disposed or the run is ending, it respawns.
 */
class AgentRjpqDeathDepartureGateTest {
    private static final int BEN = 865;
    private static final int MIRA = 11;
    private static final int TOBI = 12;
    private static final int UNA = 13;

    private static final class FakeEvent implements AgentRjpqDeathDepartureGate.EventView {
        final Set<Integer> registered = new HashSet<>(Set.of(BEN, MIRA, TOBI, UNA));
        boolean disposed;
        boolean cleared;

        @Override
        public boolean disposed() {
            return disposed;
        }

        @Override
        public boolean cleared() {
            return cleared;
        }

        @Override
        public int leaderId() {
            return BEN;
        }

        @Override
        public Set<Integer> registeredIds() {
            return Set.copyOf(registered);
        }
    }

    private static AgentRjpqSession session() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ZENUMIST, 1L, BEN, 0L);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            session.addMember(id, id == BEN ? AgentRjpqMemberState.MemberType.HUMAN
                    : AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(BEN, MIRA);
        session.setCompanionAdopted(true);
        return session;
    }

    @Test
    void beforeTheClearADeadCompanionIsHeldForAsLongAsItTakes() {
        AgentRjpqSession session = session();
        FakeEvent event = new FakeEvent();
        AtomicInteger respawns = new AtomicInteger();

        assertFalse(AgentRjpqDeathDepartureGate.respawnOrHold(session, TOBI, "Tobi", event,
                respawns::incrementAndGet, dying -> 1, 10_000L));
        assertFalse(AgentRjpqDeathDepartureGate.respawnOrHold(session, TOBI, "Tobi", event,
                respawns::incrementAndGet, dying -> 1, 2_710_000L), "forty-five minutes later, still held");

        assertEquals(0, respawns.get());
        assertEquals(1, session.heldDeadCount());
    }

    @Test
    void twoDeadCompanionsAreBothHeld() {
        AgentRjpqSession session = session();
        FakeEvent event = new FakeEvent();
        AtomicInteger respawns = new AtomicInteger();

        assertFalse(AgentRjpqDeathDepartureGate.respawnOrHold(session, TOBI, "Tobi", event,
                respawns::incrementAndGet, dying -> 1, 10_000L));
        assertFalse(AgentRjpqDeathDepartureGate.respawnOrHold(session, UNA, "Una", event,
                respawns::incrementAndGet, dying -> 1, 11_000L));

        assertEquals(0, respawns.get());
        assertEquals(2, session.heldDeadCount());
    }

    @Test
    void atTheClearTheHeldCorpseIsReleasedAndRespawns() {
        AgentRjpqSession session = session();
        FakeEvent event = new FakeEvent();
        AtomicInteger respawns = new AtomicInteger();
        AgentRjpqDeathDepartureGate.respawnOrHold(session, TOBI, "Tobi", event, respawns::incrementAndGet, dying -> 1, 10_000L);

        event.cleared = true;

        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(session, TOBI, "Tobi", event,
                respawns::incrementAndGet, dying -> 1, 70_000L));
        assertEquals(1, respawns.get());
        assertEquals(0, session.heldDeadCount());
    }

    @Test
    void anEndingRunADisposedEventAndAnUnregisteredMemberRespawnAtOnce() {
        AtomicInteger respawns = new AtomicInteger();
        AgentRjpqSession ending = session();
        ending.beginTermination();
        FakeEvent disposed = new FakeEvent();
        disposed.disposed = true;
        FakeEvent gone = new FakeEvent();
        gone.registered.remove(UNA);

        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(ending, TOBI, "Tobi", new FakeEvent(),
                respawns::incrementAndGet, dying -> 1, 10_000L));
        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(session(), TOBI, "Tobi", disposed,
                respawns::incrementAndGet, dying -> 1, 10_000L));
        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(session(), UNA, "Una", gone,
                respawns::incrementAndGet, dying -> 1, 10_000L));
        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(session(), TOBI, "Tobi", null,
                respawns::incrementAndGet, dying -> 1, 10_000L));

        assertEquals(4, respawns.get());
    }

    @Test
    void anAgentWithoutAnRjpqSessionRespawnsAsBefore() {
        AtomicInteger respawns = new AtomicInteger();
        client.Character stranger = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(stranger.getId()).thenReturn(99_999_001);

        assertTrue(AgentRjpqDeathDepartureGate.respawnOrHold(stranger, respawns::incrementAndGet, 10_000L));
        assertEquals(1, respawns.get());
    }
}
