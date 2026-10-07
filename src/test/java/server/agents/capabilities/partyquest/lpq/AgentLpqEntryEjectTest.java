package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C3.3 (3.1 step 9, decision D-c): at the end of the entry wait a companion run with five or more inside goes
 * on without the absent, as a party of players would: each one is ejected from the run (out of both indexes, handed
 * back to its owner) and stage 1 begins with five; nobody is re-registered or moved. Fewer than five inside still ends
 * the run without a word.
 */
class AgentLpqEntryEjectTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void fiveInsideGoOnWithoutTheAbsentOne() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        Character pel = crew.get(3);
        fx.outside(pel, AgentLpqDefinition.EXIT_MAP);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.ENTERING, crew.toArray(Character[]::new));

        fx.tick(session, 31_999L);
        assertEquals(AgentLpqSession.Phase.ENTERING, session.phase(), "the wait is not over");
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 32_000L);

            assertEquals(1, log.startingWith("LPQ member left the run and was ejected: session=" + session.sessionId()
                    + " member=4 type=AGENT phase=ENTERING reason=not inside at the end of the entry wait"
                    + " remainingBefore=6").size());
        }

        assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
        assertEquals(5, session.memberCount());
        assertNull(session.member(pel.getId()));
        assertNull(AgentLpqSessionRegistry.forMember(pel.getId()));
        fx.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(pel.getId(), "lpq", 32_000L));
        verify(fx.event, never()).registerPlayer(any(), anyBoolean());
        fx.neverSaid("failed");
    }

    @Test
    void fourInsideStillEndTheRunWithoutAWord() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        fx.outside(crew.get(3), AgentLpqDefinition.EXIT_MAP);
        fx.outside(crew.get(4), AgentLpqDefinition.EXIT_MAP);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.ENTERING, crew.toArray(Character[]::new));

        fx.tick(session, 32_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals(6, session.memberCount(), "nobody ejected");
        fx.saidNothing();
    }
}
