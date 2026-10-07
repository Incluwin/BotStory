package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.7 (revision P1): in a companion run the leader climbs the maze himself. His climb to a new highest row is
 * progress; the companions that reach the top wait at the top portal instead of going through; once they all wait
 * there and he is still below, one of them tells him the code again (S6n) after LEADER_NUDGE_MS; when he reaches the
 * top portal they all go through (the stock gather), and when he went through first they follow.
 */
class AgentLpqStageSixClimbTest {
    private static final int S6 = AgentLpqDefinition.stage(6).mapId();
    private static final int S7 = AgentLpqDefinition.stage(7).mapId();
    private static final Point TOP = new Point(100, -3_100);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** The maze: row r's middle portal (2 + 3r) at y -200r, and the top portal 48 to stage 7. */
    private AgentLpqSession companionsAtTheTop() {
        crew = fx.crew(S6, new Point(0, 0));
        for (int row = 0; row < 15; row++) fx.portal(S6, 2 + 3 * row, new Point(0, -200 * row), 0);
        fx.portal(S6, 48, TOP, S7);
        for (Character companion : crew.subList(1, 6)) fx.moveTo(companion, new Point(TOP.x - 10, TOP.y));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_6, crew.toArray(Character[]::new));
        session.markStage6SequenceAnnounced(2_000L);
        return session;
    }

    @Test
    void hisClimbIsProgress() {
        AgentLpqSession session = companionsAtTheTop();
        fx.tick(session, 3_000L);
        long before = session.lastProgressAtMs();
        fx.moveTo(crew.getFirst(), new Point(0, -400));
        fx.tick(session, 60_000L);

        assertEquals(60_000L, session.lastProgressAtMs(), "row 2 is higher than row 0");
        assertTrue(before < 60_000L);
    }

    @Test
    void theCompanionsWaitAtTheTopAndCallHimUp() {
        AgentLpqSession session = companionsAtTheTop();

        fx.tickEvery(session, 3_000L, 32_000L, 1_000L);
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
        fx.neverSaid("s6-climb");
        fx.tick(session, 33_000L);

        fx.said(session, "s6-climb", crew.get(1),
                "Crewfriend, we're up top. 133 221 333 123 111 from the bottom, left to right.");
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
    }

    @Test
    void whenHeReachesTheTopPortalTheyAllGoThrough() {
        AgentLpqSession session = companionsAtTheTop();
        fx.tick(session, 3_000L);
        fx.moveTo(crew.getFirst(), new Point(TOP.x + 10, TOP.y));

        fx.tick(session, 4_000L);

        for (Character companion : crew.subList(1, 6)) verify(fx.lpq).enterPortal(companion, 48);
    }

    @Test
    void whenHeWentThroughFirstTheyFollow() {
        AgentLpqSession session = companionsAtTheTop();
        fx.tick(session, 3_000L);
        fx.onMap(crew.getFirst(), S7, new Point(0, -211));

        fx.tick(session, 4_000L);

        assertEquals(AgentLpqSession.Phase.STAGE_7, session.phase());
        for (Character companion : crew.subList(1, 6)) verify(fx.lpq).enterPortal(companion, 48);
    }
}
