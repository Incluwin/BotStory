package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.1: a companion run's companions talk like players on the leader's map (AgentLpqDialogue), never in party
 * plus map chat, never a status line to the human, and the mixed-party flavor lines stay with stock runs.
 */
class AgentLpqCompanionVoiceTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final int S7 = AgentLpqDefinition.stage(7).mapId();
    private static final String SCOUT_CHAT = "Stage 2: two scouts are going first. Everyone else wait for trap clear.";
    private static final String TRAP_CLEAR_CHAT = "Stage 2 trap clear. Everyone enter now.";
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void theScoutsSayOnePlayerLineOnTheLeadersMapAndNoPartyChat() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);

        verify(fx.party, never()).sendPartyChat(any(), anyString());
        fx.said(session, "s2-scouts", crew.get(1), "two of us go in first, there's a trap in there. wait here a sec.");
    }

    @Test
    void theTrapClearIsAPlayerLineAndTheLeaderGetsNoSystemMessage() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        fx.onMap(crew.get(1), AgentLpqDefinition.STAGE_2_TRAP_MAP, new Point(0, 0));
        fx.onMap(crew.get(2), S2, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);

        fx.said(session, "s2-clear", crew.get(3), "trap's clear, let's go in.");
        verify(crew.getFirst(), never()).dropMessage(anyInt(), anyString());
        verify(fx.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void stageSevenSendsTheHumanNoSystemMessage() {
        List<Character> crew = fx.crew(S7, new Point(0, -211));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 10_000L, 1_000L);

        verify(crew.getFirst(), never()).dropMessage(anyInt(), anyString());
        verify(fx.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void stockRunsKeepTheirPlainLines() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        AgentLpqSession scouts = fx.session(false, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));
        fx.tick(scouts, 3_000L);
        verify(fx.party).sendPartyChat(crew.get(1), SCOUT_CHAT);
        AgentLpqSessionRegistry.remove(scouts);

        fx.onMap(crew.get(1), AgentLpqDefinition.STAGE_2_TRAP_MAP, new Point(0, 0));
        fx.onMap(crew.get(2), S2, new Point(0, 0));
        AgentLpqSession clear = fx.session(false, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));
        fx.tick(clear, 3_000L);
        verify(crew.getFirst()).dropMessage(6, TRAP_CLEAR_CHAT);
        AgentLpqSessionRegistry.remove(clear);

        crew.forEach(member -> fx.onMap(member, S7, new Point(0, -211)));
        AgentLpqSession seven = fx.session(false, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));
        fx.tick(seven, 3_000L);
        verify(crew.getFirst()).dropMessage(6, "LPQ Stage 7: hold the bottom and kill the summoned Rombards.");
        fx.saidNothing();
    }

    @Test
    void aCompanionRunHasNoMixedPartyFlavor() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_1, crew.toArray(Character[]::new));
        session.narrateOnce("s1-start");

        fx.tickEvery(session, 3_000L, 18_000L, 500L);

        verify(fx.packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        verify(fx.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void aStockMixedPartyStillHearsItsFlavor() {
        List<Character> crew = fx.crew(S1, new Point(0, 0));
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_1, crew.toArray(Character[]::new));

        fx.tickEvery(session, 3_000L, 18_000L, 500L);

        verify(fx.packets).broadcastChatText(crew.get(1), "Crewfriend, coupons first. Try not to race the ladders.",
                false, 0);
    }
}
