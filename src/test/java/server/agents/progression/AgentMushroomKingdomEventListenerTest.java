package server.agents.progression;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.operations.events.AgentMobKilledEvent;
import server.agents.operations.events.AgentMobDamagedEvent;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentMushroomKingdomEventListenerTest {
    @Test
    void onlyRelevantYetiDamageRefreshesTheActiveBossWatchdog() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), null, null);
        var state = entry.capabilityStates().require(AgentMushroomKingdomState.STATE_KEY);
        state.begin(1L);
        state.observe(2330, 4, 106021500, new Point(), 2L);
        var listener = new AgentMushroomKingdomEventListener(entry);
        listener.onAgentEvent(new AgentMobDamagedEvent(1, 500_000L, 106021500,
                3300005, 10, 1, "mushroom-kingdom:2330"));
        assertEquals(500_000L, state.objectiveProgressAtMs());
        listener.onAgentEvent(new AgentMobDamagedEvent(1, 600_000L, 106021500,
                3300003, 11, 10, ""));
        listener.onAgentEvent(new AgentMobDamagedEvent(1, 600_000L, 106021400,
                3300005, 12, 10, ""));
        assertEquals(500_000L, state.objectiveProgressAtMs());
        state.block("stopped");
        listener.onAgentEvent(new AgentMobDamagedEvent(1, 700_000L, 106021500,
                3300005, 13, 10, ""));
        assertEquals(500_000L, state.objectiveProgressAtMs());
    }

    @Test
    void countsOnlyHelmetPepeKillsDuringTheRareItemObjective() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMushroomKingdomState state = entry.capabilityStates()
                .require(AgentMushroomKingdomState.STATE_KEY);
        state.begin(1L);
        state.observe(2326, 0, 106021100, new Point(), 2L);
        AgentMushroomKingdomEventListener listener = new AgentMushroomKingdomEventListener(entry);

        listener.onAgentEvent(new AgentMobKilledEvent(
                1, 3L, 106021100, 3300003, 10, 35, "mushroom-kingdom:2326"));
        listener.onAgentEvent(new AgentMobKilledEvent(
                1, 4L, 106021100, 3300004, 11, 35, "mushroom-kingdom:2326"));

        assertEquals(1, state.helmetPepeKills());
    }
}
