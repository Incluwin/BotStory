package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * PPQ Step 1 (plan 4.6, with the P6h review rule): a companion run's release keeps the owner's party, says what the
 * companions carried ('PPQ potions:'), and before the clear takes only its companions out of the event (the human
 * carries on; an empty event is disposed). A stock run is released as it always was.
 */
class AgentPpqTerminationCompanionTest {
    @Test
    void aCompanionRunKeepsThePartyAndLeavesTheHumanHisEvent() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqTerminationService.class)) {
            when(run.party.hasParty(any())).thenReturn(true);
            when(run.actions.hpPotionCount(any())).thenReturn(30);
            when(run.actions.mpPotionCount(any())).thenReturn(40);

            AgentPpqTerminationService.release(run.session, "why", 5_000L, true);

            verify(run.party, never()).leaveCurrentParty(any());
            for (int id : List.of(MIRA, TOBI, UNA)) {
                verify(run.event).exitPlayer(run.character(id));
            }
            verify(run.event, never()).exitPlayer(run.character(BEN));
            verify(run.event, never()).dispose();
            List<String> potions = lines.startingWith("PPQ potions:");
            assertEquals(1, potions.size());
            assertTrue(potions.getFirst().endsWith("end={c11:30/40, c12:30/40, c13:30/40}"), potions.getFirst());
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(run.session.sessionId()),
                    eq(false), eq("why"), eq(5_000L), any()), times(1));
        }
    }

    @Test
    void anEventLeftWithNoPlayerIsDisposed() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            when(run.event.getPlayers()).thenReturn(List.of(run.character(MIRA)), List.of());

            AgentPpqTerminationService.release(run.session, "why", 5_000L, true);

            verify(run.event).dispose();
        }
    }

    @Test
    void pinAStockRunStillLeavesItsPartyAndDisposesItsEvent() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            when(run.party.hasParty(any())).thenReturn(true);

            AgentPpqTerminationService.release(run.session, "why", 5_000L, true);

            verify(run.event).exitPlayer(run.character(BEN));
            verify(run.event).dispose();
            for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
                verify(run.party).leaveCurrentParty(run.character(id));
            }
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(anyString(), eq(false), anyString(),
                    anyLong(), eq(null)), times(1));
        }
    }
}
