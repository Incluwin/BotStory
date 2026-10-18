package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 3, the bind race: the run binds its event as soon as its leader is inside, while
 * EventInstanceManager.registerParty is still registering and warping the others one at a time on the leader's
 * thread. A member the event has not held yet is still entering: it is neither a departure nor left out of the run's
 * counts. Only a member that was held and no longer is departs.
 */
class AgentLmpqBindRaceTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** A run at Rolly, not bound yet; Ben has just entered Room 3 and the event holds only him so far. */
    private AgentLmpqSession leaderJustInside() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel);
        session.clearEventInstance();
        run.livePlayers(ben);
        return session;
    }

    @Test
    void onlyAMemberThatWasHeldAndNoLongerIsDeparts() {
        AgentLmpqSession session = leaderJustInside();
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase(), "bound on the leader's entry");
            assertEquals(List.of(), lines.startingWith("LMPQ departed:"), "the others are still entering");

            run.livePlayers(ben, mira, kes, pel);
            AgentLmpqCoordinator.tickSession(session, 11_000L);
            assertEquals(List.of(), lines.startingWith("LMPQ departed:"));

            when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
            when(kes.getEventInstance()).thenReturn(null);
            run.livePlayers(ben, mira, pel);
            AgentLmpqCoordinator.tickSession(session, 12_000L);

            assertEquals(1, lines.startingWith("LMPQ departed:").size());
            assertTrue(session.departed(kes.getId()));
            for (Character member : List.of(ben, mira, pel)) assertFalse(session.departed(member.getId()));
            assertFalse(session.terminal(), session.failure());
        }
    }

    @Test
    void aMemberStillEnteringIsCounted() {
        AgentLmpqSession session = leaderJustInside();
        // Rooms 6 and 7 worked out, so the Tauromacis hold (AgentLmpqTauromacisHoldTest) lets 36 coupons regroup.
        session.rooms().depleted(6, mira.getId(), 2_000L);
        session.rooms().depleted(7, mira.getId(), 2_000L);
        when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(36);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase(), "Mira's coupons count while she enters");
        assertFalse(session.departed(mira.getId()));
    }
}
