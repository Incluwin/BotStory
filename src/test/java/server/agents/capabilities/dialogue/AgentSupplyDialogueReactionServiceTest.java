package server.agents.capabilities.dialogue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.supplies.AgentSupplyAskGuard;
import server.agents.capabilities.supplies.AgentSupplyThresholdChangedEvent;
import server.agents.events.AgentEventBus;
import server.agents.events.AgentEventPriority;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** A supply threshold crossed is said out loud, unless the host keeps that Agent quiet (a mentor, F4). */
class AgentSupplyDialogueReactionServiceTest {
    private static final int AGENT = 1116;

    private final AgentEventBus bus = mock(AgentEventBus.class);
    private final AgentSupplyDialogueReactionService service = new AgentSupplyDialogueReactionService(bus);

    @AfterEach
    void uninstall() {
        AgentSupplyAskGuard.install(null);
    }

    @Test
    void aThresholdCrossedIsSaidOutLoud() {
        service.onAgentEvent(lowOnMp());
        verify(bus).publish(any(AgentDialogueIntentEvent.class), eq(AgentEventPriority.NORMAL));
    }

    @Test
    void anAgentItsHostKeepsQuietSaysNothing() {
        AgentSupplyAskGuard.install(id -> id == AGENT);
        service.onAgentEvent(lowOnMp());
        verify(bus, never()).publish(any(), any());
    }

    private static AgentSupplyThresholdChangedEvent lowOnMp() {
        return new AgentSupplyThresholdChangedEvent(AGENT, 1_000L, 0L, 104_000_100, AgentResourceCategory.MP_POTION,
                1, 20, AgentSupplyUrgency.HEALTHY, AgentSupplyUrgency.LOW, "");
    }
}
