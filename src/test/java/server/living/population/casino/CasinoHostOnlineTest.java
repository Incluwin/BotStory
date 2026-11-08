package server.living.population.casino;

import client.Character;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A venue sees a citizen that logged off as gone. Its session keeps the old character on its last map, so live
 * 2026-11-08 Neshao held the bar 21 min after logging off and a dealer kept dealing to an empty seat.
 */
class CasinoHostOnlineTest {
    @Test
    void aLoggedInCitizenIsThere() {
        Character agent = mock(Character.class);
        when(agent.isLoggedin()).thenReturn(true);

        assertSame(agent, CasinoHost.online(session(agent)));
    }

    @Test
    void aCitizenThatLoggedOffIsGoneThoughItsSessionStillHoldsIt() {
        Character agent = mock(Character.class);
        when(agent.isLoggedin()).thenReturn(false);
        when(agent.getMapId()).thenReturn(100000203); // still "on" Game Park

        assertNull(CasinoHost.online(session(agent)));
    }

    @Test
    void noSessionOrNoCharacterIsGone() {
        assertNull(CasinoHost.online(null));
        assertNull(CasinoHost.online(session(null)));
    }

    private static CitizenSession session(Character agent) {
        CitizenSession session = mock(CitizenSession.class);
        when(session.agent()).thenReturn(agent);
        return session;
    }
}
