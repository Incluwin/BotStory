package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.1: a companion run's PREPARING. The leader clicks the Red Sign himself; nobody walks to the sign and
 * nobody announces anything, the run goes on once he stands in the event on stage 1, and it ends without a word after
 * COMPANION_ENTRY_WAIT_MS (30 s) if he has not. A stock run still walks its Agents to the sign.
 */
class AgentLpqCompanionPrepareTest {
    private static final Point SIGN = new Point(189, 1_664);
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private List<Character> crewAtTheSign() {
        fx.npcAt(AgentLpqDefinition.ENTRY_NPC, SIGN);
        List<Character> crew = fx.crew(AgentLpqDefinition.RECRUIT_MAP, new Point(-200, 2_012));
        crew.forEach(member -> fx.outside(member, AgentLpqDefinition.RECRUIT_MAP));
        fx.moveTo(crew.getFirst(), SIGN);
        return crew;
    }

    @Test
    void nobodyWalksToTheSignAndNobodyAnnouncesAnything() {
        List<Character> crew = crewAtTheSign();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));

        fx.tickEvery(session, 2_000L, 12_000L, 1_000L);

        verify(fx.actions, never()).navigate(any(), any(), anyBoolean());
        verify(fx.packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        fx.saidNothing();
        assertEquals(AgentLpqSession.Phase.PREPARING, session.phase());
    }

    @Test
    void theRunGoesOnOnceTheLeaderStandsInTheEventOnStageOne() {
        List<Character> crew = crewAtTheSign();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));
        fx.tick(session, 2_000L);
        fx.inside(crew.getFirst());
        fx.onMap(crew.getFirst(), AgentLpqDefinition.stage(1).mapId(), new Point(0, 0));
        try (LpqLogLines log = new LpqLogLines(AgentLpqSession.class)) {
            fx.tick(session, 9_000L);

            assertEquals(AgentLpqSession.Phase.ENTERING, session.phase());
            assertEquals(9_000L, session.enteredEventAtMs());
            assertEquals(1, log.startingWith("LPQ phase: session=" + session.sessionId() + " PREPARING->ENTERING")
                    .stream().filter(line -> line.endsWith("companionRun=true")).count());
        }
    }

    @Test
    void theRunEndsWithoutAWordWhenTheLeaderDoesNotEnterWithinTheEntryWait() {
        List<Character> crew = crewAtTheSign();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));

        fx.tick(session, 30_999L);
        assertEquals(AgentLpqSession.Phase.PREPARING, session.phase(), "PREPARING began at 1,000 ms");
        fx.tick(session, 31_001L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("The companion run's leader did not take the party into LPQ within the entry wait",
                session.failure());
        fx.saidNothing();
    }

    @Test
    void aStockRunStillWalksItsAgentsToTheSign() {
        List<Character> crew = crewAtTheSign();
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));

        fx.tick(session, 2_000L);

        verify(fx.actions, atLeastOnce()).navigate(eq(fx.entry(crew.get(1))), any(), anyBoolean());
        fx.tick(session, 31_001L);
        assertEquals(AgentLpqSession.Phase.PREPARING, session.phase(), "a stock run waits up to PHASE_TIMEOUT_MS");
    }
}
