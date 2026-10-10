package server.agents.resources.events;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.AgentDialogueIntentEvent;
import server.agents.capabilities.inventory.AgentBagFullSpeech;
import server.agents.events.AgentEvent;
import server.agents.events.AgentEventBus;
import server.agents.events.AgentEventPriority;
import server.agents.events.AgentEventSubscription;
import server.agents.runtime.AgentEventDispatchRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The inventory-full reaction through an Agent session's real event wiring (deploy review 2026-10-10): an owned
 * companion out with its owner says its own bag-full line (AgentBagFullSpeech, at most once per LOOT_LINE_EVERY_MS),
 * so this reaction says nothing for it; every other Agent keeps it at INVENTORY_COOLDOWN_MS.
 */
class AgentResourceDialogueReactionServiceTest {
    private boolean previousDialogueTransport;

    @BeforeEach
    void enableDialogueTransport() {
        previousDialogueTransport = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true; // agent-engine.yaml ships it on
    }

    @AfterEach
    void restore() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = previousDialogueTransport;
        AgentBagFullSpeech.install(null);
    }

    private static Character agent(int id) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        return agent;
    }

    private static AgentInventoryThresholdChangedEvent equipFull(int agentId) {
        return new AgentInventoryThresholdChangedEvent(agentId, 1_000L, "EQUIP", 0, 96, "FULL", "");
    }

    /** The dialogue intents the session's reactions publish for this fact. */
    private static List<AgentDialogueIntentEvent> reactionsTo(Character agent, AgentEvent fact) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentEventBus bus = AgentSessionEventRuntime.bus(entry);
        List<AgentDialogueIntentEvent> intents = new ArrayList<>();
        AgentEventSubscription subscription = bus.subscribe(AgentDialogueIntentEvent.TYPE,
                event -> intents.add((AgentDialogueIntentEvent) event));
        try {
            bus.publish(fact, AgentEventPriority.IMPORTANT);
            AgentEventDispatchRuntime.drain(entry);
            return intents;
        } finally {
            subscription.close();
            AgentSessionEventRuntime.close(entry);
        }
    }

    @Test
    void anOwnedCompanionOutWithItsOwnerGetsNoInventoryFullReaction() {
        Character companion = agent(5201);
        AgentBagFullSpeech.install(agent -> agent == companion);

        assertEquals(List.of(), reactionsTo(companion, equipFull(5201)));
    }

    @Test
    void itsOtherResourceReactionsStay() {
        Character companion = agent(5202);
        AgentBagFullSpeech.install(agent -> agent == companion);

        List<AgentDialogueIntentEvent> intents = reactionsTo(companion,
                new AgentScrollResolvedEvent(5202, 1_000L, 2040001, "SUCCESS", 100000000, ""));

        assertEquals(1, intents.size(), intents.toString());
        assertEquals(AgentResourceDialogueReactionService.SCROLL_INTENT, intents.getFirst().intentKey());
    }

    @Test
    void everyOtherAgentKeepsTheReactionAtTheInventoryCooldown() {
        Character citizen = agent(5203);
        AgentBagFullSpeech.install(agent -> false); // the host names its companions only

        List<AgentDialogueIntentEvent> intents = reactionsTo(citizen, equipFull(5203));

        assertEquals(1, intents.size(), intents.toString());
        AgentDialogueIntentEvent intent = intents.getFirst();
        assertEquals(AgentResourceDialogueReactionService.INVENTORY_FULL_INTENT, intent.intentKey());
        assertEquals("inventory-full:EQUIP", intent.dedupeKey(), "one cooldown per bag");
        assertEquals(60_000L, intent.cooldownMs(), "agent-engine.yaml INVENTORY_COOLDOWN_MS");
    }
}
