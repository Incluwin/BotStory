package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 8, one coordinator pass at a time: the lease holder's own tick and the watchdog can both hold the
 * lease (it refuses only another holder), so the pass itself is claimed. While a pass is under way neither entry
 * starts another; the claim is released after every pass, one that throws included.
 */
class AgentLmpqCoordinatingGuardTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** A run whose event has ended: any coordinator pass fails it at once. */
    private AgentLmpqSession endedRun() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(run.event.isEventDisposed()).thenReturn(true);
        return session;
    }

    @Test
    void noSecondPassStartsWhileOneIsUnderWay() {
        AgentLmpqSession session = endedRun();
        assertTrue(session.tryBeginCoordinating(), "a pass is under way on another thread");

        AgentLmpqRuntime.tick(run.entry(mira), mira, 10_000L);
        AgentLmpqWatchdogRuntime.tick(session, 20_000L); // Mira's lease has run out: the watchdog may claim it
        assertFalse(session.terminal(), "neither entry ran a second pass");

        session.endCoordinating();
        AgentLmpqRuntime.tick(run.entry(mira), mira, 21_000L);
        assertEquals("LMPQ event ended before the clear", session.failure());
    }

    @Test
    void theClaimIsReleasedAfterAPassThatThrows() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        when(ben.getHp()).thenThrow(new IllegalStateException("leader unloaded mid-pass"));

        assertThrows(IllegalStateException.class, () -> AgentLmpqRuntime.coordinate(session, 10_000L));

        assertTrue(session.tryBeginCoordinating(), "the pass that threw let go of its claim");
        session.endCoordinating();
    }
}
