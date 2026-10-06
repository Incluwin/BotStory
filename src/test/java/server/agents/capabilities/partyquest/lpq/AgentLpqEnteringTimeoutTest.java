package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.1: a companion run's ENTERING waits for every member on stage 1 in the leader's event and never
 * re-registers or moves anyone; with all inside it begins stage 1 and says S1 once.
 */
class AgentLpqEnteringTimeoutTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void theEntryTable() {
        long wait = 30_000L;
        assertEquals(AgentLpqCompanionPlay.Entry.ENTERED, AgentLpqCompanionPlay.entering(6, 6, 0L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.ENTERED, AgentLpqCompanionPlay.entering(5, 5, 40_000L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.WAIT, AgentLpqCompanionPlay.entering(5, 6, 29_999L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.WAIT, AgentLpqCompanionPlay.entering(0, 6, 0L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.EJECT_ABSENT, AgentLpqCompanionPlay.entering(5, 6, 30_000L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.GIVE_UP, AgentLpqCompanionPlay.entering(4, 6, 30_000L, wait, 5));
        assertEquals(AgentLpqCompanionPlay.Entry.GIVE_UP, AgentLpqCompanionPlay.entering(4, 5, 30_000L, wait, 5));
    }

    @Test
    void aCompanionOutsideTheEventIsNeverReRegisteredOrMoved() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        fx.outside(crew.get(3), AgentLpqDefinition.EXIT_MAP);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.ENTERING, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 20_000L, 1_000L);

        verify(fx.event, never()).registerPlayer(any(), anyBoolean());
        verify(fx.mapGateway, never()).changeMapNear(any(), any(), any());
        assertEquals(AgentLpqSession.Phase.ENTERING, session.phase());
    }

    @Test
    void withEveryoneInsideStageOneBeginsAndSOneIsSaidOnce() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.ENTERING, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 6_000L, 1_000L);

        assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
        fx.said(session, "s1-start", crew.get(1),
                "Crewfriend, talk to the red balloon so it knows we're here. we'll get the passes.");
    }

    @Test
    void aStockRunReRegistersAnAgentOutsideTheEventAsToday() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        fx.outside(crew.get(3), AgentLpqDefinition.EXIT_MAP);
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.ENTERING, crew.toArray(Character[]::new));

        fx.tick(session, 3_000L);

        verify(fx.event).registerPlayer(crew.get(3), false);
    }
}
