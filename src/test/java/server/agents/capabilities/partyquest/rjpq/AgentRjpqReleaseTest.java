package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An RJPQ run's release (EPQ's companion pattern): the owner's party is always kept; a run that did not complete
 * before the clear takes EVERY participant out of the event and disposes it (taking only the companions out would
 * leave the human alone in a live instance: an exit unregisters before it moves, so the stock end never runs); after
 * the clear only companions still in the event leave it, through the event's own exit, and the event stays.
 */
class AgentRjpqReleaseTest {
    private static final int BEN = 865;
    private static final int MIRA = 11;
    private static final int TOBI = 12;
    private static final int UNA = 13;

    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final Map<Integer, Character> characters = new HashMap<>();
    private final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    private MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;

    @BeforeEach
    void gateways() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        CharacterGateway online = mock(CharacterGateway.class);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            Character character = mock(Character.class);
            when(character.getId()).thenReturn(id);
            when(character.getName()).thenReturn("c" + id);
            characters.put(id, character);
            when(online.findOnlineCharacterById(id)).thenReturn(character);
            if (id != BEN) {
                AgentRuntimeEntry entry = new AgentRuntimeEntry(character, mock(Character.class), null);
                registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
            }
        }
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(online);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
    }

    @AfterEach
    void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private AgentRjpqSession boundSession(EventInstanceManager event) {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ALCADNO, 1L, BEN, 1_000L);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            session.addMember(id, id == BEN ? AgentRjpqMemberState.MemberType.HUMAN
                    : AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(BEN, MIRA);
        session.setCompanionAdopted(true);
        session.bindEventInstance(event, 2_000L);
        return session;
    }

    private EventInstanceManager eventWith(int... ids) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        List<Character> players = new ArrayList<>();
        for (int id : ids) {
            players.add(characters.get(id));
        }
        when(event.getPlayers()).thenReturn(players);
        return event;
    }

    @Test
    void aFailureBeforeTheClearTakesEveryParticipantOutAndDisposesTheEvent() {
        EventInstanceManager event = eventWith(BEN, MIRA, TOBI, UNA);
        AgentRjpqSession session = boundSession(event);
        session.fail("why", 3_000L);

        AgentRjpqTerminationService.release(session, "why", 3_000L, true);

        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            verify(event).exitPlayer(characters.get(id));
        }
        verify(event).dispose();
        assertNull(AgentRjpqSessionRegistry.forOperator(BEN));
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), eq("why"), eq(3_000L), any()), times(1));
    }

    @Test
    void afterTheClearOnlyCompanionsStillInTheEventLeaveAndTheEventStays() {
        EventInstanceManager event = eventWith(BEN, TOBI);
        when(event.isEventCleared()).thenReturn(true);
        AgentRjpqSession session = boundSession(event);
        session.complete(9_000L);

        AgentRjpqTerminationService.release(session, "done", 9_000L, false);

        verify(event).exitPlayer(characters.get(TOBI));
        verify(event, never()).exitPlayer(characters.get(BEN));
        verify(event, never()).dispose();
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), anyString(), anyLong(), any()), times(1));
    }

    @Test
    void theReleaseRunsOnceAndStopsEveryCompanion() {
        EventInstanceManager event = eventWith(BEN, MIRA, TOBI, UNA);
        AgentRjpqSession session = boundSession(event);
        session.fail("why", 3_000L);

        AgentRjpqTerminationService.release(session, "why", 3_000L, true);
        AgentRjpqTerminationService.release(session, "again", 4_000L, true);

        verify(event, times(1)).dispose();
        verify(actions, times(3)).stop(any());
        assertEquals(AgentRjpqSession.Phase.FAILED, session.phase());
    }
}
