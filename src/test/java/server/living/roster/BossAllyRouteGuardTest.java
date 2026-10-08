package server.living.roster;

import org.junit.jupiter.api.Test;
import server.agents.progression.AgentVictoriaRouteRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bench allies (P7 Part B) never walk to Mu Young's room (105100100) or away from it: they load at the top of her
 * stairs and unload at its way out. The walking route graphs stay closed around her room on purpose. The way in is a
 * script portal of Another Entrance (105090200) and the ways out are her room's script portals; with walking edges for
 * them, an Agent at the temple with a critical potion request would set off on a long shop trip instead of asking its
 * party (supply trips measure with routeHops), and no ally walk from a town camp (19-28 maps) fits the sign-up clock.
 */
class BossAllyRouteGuardTest {
    private static final int MU_YOUNGS_ROOM = 105100100;
    private static final int STAIRWAY = 105100000;
    private static final int ANOTHER_ENTRANCE = 105090200;
    private static final int SLEEPYWOOD = 105040300;

    @Test
    void noWalkLeavesMuYoungsRoomOrReachesItFromAnotherEntrance() {
        assertEquals(1, AgentVictoriaRouteRuntime.routeHops(null, STAIRWAY, MU_YOUNGS_ROOM, 0L),
                "the graphs know her room: the stairway leads down to it");
        assertEquals(-1, AgentVictoriaRouteRuntime.routeHops(null, MU_YOUNGS_ROOM, SLEEPYWOOD, 0L),
                "no walk out of her room");
        assertEquals(-1, AgentVictoriaRouteRuntime.routeHops(null, ANOTHER_ENTRANCE, MU_YOUNGS_ROOM, 0L),
                "no walk in through the temple's script portal");
    }
}
