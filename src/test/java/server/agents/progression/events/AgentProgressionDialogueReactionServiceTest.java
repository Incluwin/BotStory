package server.agents.progression.events;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.dialogue.AgentDialogueIntentEvent;
import server.agents.events.AgentEvent;
import server.agents.events.AgentEventBus;
import server.agents.events.AgentEventPriority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentProgressionDialogueReactionServiceTest {
    private final AgentEventBus bus = mock(AgentEventBus.class);
    private final AgentProgressionDialogueReactionService reactions = new AgentProgressionDialogueReactionService(bus);

    private void level(int from, int to) {
        reactions.onAgentEvent(new AgentLevelChangedEvent(7, 1_000L, from, to, 110, 100000000, ""));
    }

    @Test
    void aGmSetUpOrDownSaysNothing() {
        level(55, 24);
        level(24, 55);
        level(24, 28);
        verify(bus, never()).publish(any(AgentEvent.class), any(AgentEventPriority.class));
    }

    @Test
    void aLevelUpOrAFewAtOnceStillGetTheirRemark() {
        level(24, 25);
        level(24, 27);
        ArgumentCaptor<AgentEvent> published = ArgumentCaptor.forClass(AgentEvent.class);
        verify(bus, org.mockito.Mockito.times(2)).publish(published.capture(), any(AgentEventPriority.class));
        AgentDialogueIntentEvent last = (AgentDialogueIntentEvent) published.getAllValues().get(1);
        assertEquals(AgentProgressionDialogueReactionService.LEVEL_INTENT, last.intentKey());
    }
}
