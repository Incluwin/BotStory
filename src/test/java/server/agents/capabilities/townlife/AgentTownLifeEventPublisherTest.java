package server.agents.capabilities.townlife;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A citizen's presence scheduler stops its TownLife on its own thread while the Agent's tick may be ending the same
 * session. The lifecycle event read the session's ids one by one, so a stop between two reads built it with a blank
 * request id and the tick failed ("valid TownLife lifecycle event fields are required", BunnyArcher 2026-10-19
 * 22:49:42). The event now takes the session's identity in one read.
 */
class AgentTownLifeEventPublisherTest {
    @Test
    void aStopBetweenTheSessionsFieldReadsStillPublishesOneWholeEvent() {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(569);
        when(agent.getMapId()).thenReturn(LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID);
        when(agent.getPosition()).thenReturn(new Point(0, 0));
        when(agent.getChair()).thenReturn(-1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, agent, null);
        List<AgentTownLifeLifecycleEvent> events = new ArrayList<>();
        AgentSessionEventRuntime.bus(entry).subscribe(AgentTownLifeLifecycleEvent.TYPE,
                event -> events.add((AgentTownLifeLifecycleEvent) event));
        AgentTownLifeSessionResult started = AgentTownLifeRuntime.requestSession(entry, agent,
                AgentTownLifeEntryRequest.external("visit-569", "presence",
                        AgentTownLifeVisitRequest.leisure(agent.getMapId())),
                AgentTownLifeAdmissionMode.MANUAL_ONLY, 1_000L, 569);
        AgentSessionEventRuntime.bus(entry).drain(20);
        events.clear();
        AgentTownLifeState state = spy(entry.capabilityStates().require(AgentTownLifeState.STATE_KEY));
        doAnswer(read -> { // the other thread's stop lands between this read and the next
            String id = (String) read.callRealMethod();
            state.stop();
            return id;
        }).when(state).sessionId();

        assertDoesNotThrow(() -> AgentTownLifeEventPublisher.lifecycle(entry, agent, state,
                AgentTownLifeLifecycleEvent.Phase.FORCED, "citizen goes AFK", 2_000L));
        AgentSessionEventRuntime.bus(entry).drain(20);

        assertEquals(1, events.size(), events.toString());
        AgentTownLifeLifecycleEvent event = events.getFirst();
        assertEquals(started.handle().sessionId(), event.sessionId());
        assertEquals("visit-569", event.requestId());
        assertEquals("presence", event.callerId());
        assertEquals(LithHarborTownLifeCatalog.LITH_HARBOR_MAP_ID, event.mapId());
    }
}
