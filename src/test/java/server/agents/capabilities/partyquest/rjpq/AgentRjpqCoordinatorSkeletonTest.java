package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 1 of the RJPQ coordinator: the run binds its event when the leader enters it, moves its phase forward from the
 * maps its members stand on (logged as 'RJPQ phase:'), ends with the event, and keeps every companion out of FOLLOW on
 * every tick of the run (map-sync would pull a following companion into its leader's room).
 */
class AgentRjpqCoordinatorSkeletonTest {
    private static final int BEN = 865;
    private static final int MIRA = 11;
    private static final int TOBI = 12;
    private static final int UNA = 13;

    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final Map<Integer, Character> characters = new HashMap<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
    private final PartyQuestGateway partyQuest = mock(PartyQuestGateway.class);
    private MockedStatic<AgentModeStateRuntime> modes;

    @BeforeEach
    void gateways() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        CharacterGateway online = mock(CharacterGateway.class);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            Character character = mock(Character.class);
            when(character.getId()).thenReturn(id);
            when(character.getName()).thenReturn("c" + id);
            when(character.getHp()).thenReturn(1_000);
            when(character.getMapId()).thenReturn(AgentRjpqSide.ZENUMIST.recruitMapId());
            characters.put(id, character);
            when(online.findOnlineCharacterById(id)).thenAnswer(call -> characters.get(id));
            if (id != BEN) {
                AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
                entries.put(id, entry);
                registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
                identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenAnswer(call -> characters.get(id));
            }
        }
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(online);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(actions);
        open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(partyQuest);
        modes = open(AgentModeStateRuntime.class);
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

    private static AgentRjpqSession session() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ZENUMIST, 1L, BEN, 1_000L);
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            session.addMember(id, id == BEN ? AgentRjpqMemberState.MemberType.HUMAN
                    : AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(BEN, MIRA);
        session.setCompanionAdopted(true);
        return session;
    }

    private EventInstanceManager enter(int... ids) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getLeaderId()).thenReturn(BEN);
        when(event.getTimeLeft()).thenReturn(2_700_000L);
        for (int id : ids) {
            when(characters.get(id).getEventInstance()).thenReturn(event);
            when(characters.get(id).getMapId()).thenReturn(AgentRjpqDefinition.BOOKS_MAP);
        }
        return event;
    }

    @Test
    void everyStageMapNamesItsPhaseAndTheExitNamesNone() {
        assertEquals(AgentRjpqSession.Phase.BOOKS, AgentRjpqCoordinator.phaseFor(926_100_000));
        assertEquals(AgentRjpqSession.Phase.TUNNEL, AgentRjpqCoordinator.phaseFor(926_100_001));
        assertEquals(AgentRjpqSession.Phase.BEAKERS, AgentRjpqCoordinator.phaseFor(926_100_100));
        assertEquals(AgentRjpqSession.Phase.KEYS, AgentRjpqCoordinator.phaseFor(926_100_200));
        assertEquals(AgentRjpqSession.Phase.LABS, AgentRjpqCoordinator.phaseFor(926_100_201));
        assertEquals(AgentRjpqSession.Phase.LABS, AgentRjpqCoordinator.phaseFor(926_100_202));
        assertEquals(AgentRjpqSession.Phase.OFFICE, AgentRjpqCoordinator.phaseFor(926_100_203));
        assertEquals(AgentRjpqSession.Phase.UNITS, AgentRjpqCoordinator.phaseFor(926_100_300));
        assertEquals(AgentRjpqSession.Phase.UNITS, AgentRjpqCoordinator.phaseFor(926_100_304));
        assertEquals(AgentRjpqSession.Phase.CENTER, AgentRjpqCoordinator.phaseFor(926_100_400));
        assertEquals(AgentRjpqSession.Phase.CENTER, AgentRjpqCoordinator.phaseFor(926_100_401));
        assertEquals(AgentRjpqSession.Phase.CLAIMING, AgentRjpqCoordinator.phaseFor(926_100_500));
        assertEquals(AgentRjpqSession.Phase.CLAIMING, AgentRjpqCoordinator.phaseFor(926_100_600));
        assertNull(AgentRjpqCoordinator.phaseFor(926_100_700));
        assertNull(AgentRjpqCoordinator.phaseFor(261_000_011));
    }

    @Test
    void aCompanionIsTakenOutOfFollowOnEveryTickItFollows() {
        AgentRjpqSession session = session();
        AgentRuntimeEntry tobi = entries.get(TOBI);
        modes.when(() -> AgentModeStateRuntime.following(tobi)).thenReturn(true, false, true);

        AgentRjpqCoordinator.tickMember(session, tobi, characters.get(TOBI), 2_000L);
        AgentRjpqCoordinator.tickMember(session, tobi, characters.get(TOBI), 3_000L);
        AgentRjpqCoordinator.tickMember(session, tobi, characters.get(TOBI), 600_000L);

        verify(actions, times(2)).stop(tobi);
    }

    @Test
    void theHumanLeaderIsNeverStopped() {
        AgentRjpqSession session = session();
        AgentRuntimeEntry ben = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
        modes.when(() -> AgentModeStateRuntime.following(ben)).thenReturn(true);

        AgentRjpqCoordinator.tickMember(session, ben, characters.get(BEN), 2_000L);

        verify(actions, never()).stop(any());
    }

    @Test
    void theEventIsBoundWhenTheLeaderEntersAndThePhaseFollowsTheFurthestMember() {
        AgentRjpqSession session = session();
        EventInstanceManager event = enter(BEN, MIRA, TOBI, UNA);
        when(characters.get(UNA).getMapId()).thenReturn(AgentRjpqDefinition.TUNNEL_MAP);

        try (RjpqLogLines lines = new RjpqLogLines(AgentRjpqCoordinator.class)) {
            AgentRjpqCoordinator.tickSession(session, 5_000L);

            assertSame(event, session.eventInstance());
            assertEquals(AgentRjpqSession.Phase.TUNNEL, session.phase());
            assertEquals(2, lines.startingWith("RJPQ phase:").size());
            assertTrue(lines.startingWith("RJPQ phase:").get(0).contains("PREPARING->ENTERING"));
            assertTrue(lines.startingWith("RJPQ phase:").get(1).contains("ENTERING->TUNNEL"));
        }
        verify(partyQuest).prepareNpcScripts(2_112_007, 2_112_000, 2_112_006, 2_112_001, 2_112_018);
    }

    @Test
    void aLeaderWhoNeverStartsTheEventEndsTheRunAfterTheEntryWait() {
        AgentRjpqSession session = session();

        AgentRjpqCoordinator.tickSession(session, 1_000L + 29_000L);
        assertEquals(AgentRjpqSession.Phase.PREPARING, session.phase());
        AgentRjpqCoordinator.tickSession(session, 1_000L + 31_000L);

        assertEquals(AgentRjpqSession.Phase.FAILED, session.phase());
        assertEquals("The RJPQ leader did not start the event in time", session.failure());
    }

    @Test
    void theRunEndsWithItsEventFailedBeforeTheClearAndCompletedAfterIt() {
        AgentRjpqSession beforeTheClear = session();
        EventInstanceManager first = enter(BEN, MIRA, TOBI, UNA);
        AgentRjpqCoordinator.tickSession(beforeTheClear, 5_000L);
        when(first.isEventDisposed()).thenReturn(true);
        AgentRjpqCoordinator.tickSession(beforeTheClear, 6_000L);
        assertEquals("The RJPQ event ended before the clear", beforeTheClear.failure());

        AgentRjpqSession afterTheClear = session();
        EventInstanceManager second = enter(BEN, MIRA, TOBI, UNA);
        AgentRjpqCoordinator.tickSession(afterTheClear, 5_000L);
        when(second.isEventCleared()).thenReturn(true);
        AgentRjpqCoordinator.tickSession(afterTheClear, 6_000L);
        assertEquals(AgentRjpqSession.Phase.CLAIMING, afterTheClear.phase());
        assertEquals(6_000L, afterTheClear.clearedAtMs());
        when(second.isEventDisposed()).thenReturn(true);
        AgentRjpqCoordinator.tickSession(afterTheClear, 7_000L);
        assertEquals(AgentRjpqSession.Phase.COMPLETED, afterTheClear.phase());
    }

    @Test
    void anOfflineLeaderEndsTheRunBeforeTheClear() {
        AgentRjpqSession session = session();
        enter(BEN, MIRA, TOBI, UNA);
        AgentRjpqCoordinator.tickSession(session, 5_000L);
        characters.remove(BEN);

        AgentRjpqCoordinator.tickSession(session, 6_000L);

        assertEquals("The RJPQ event leader is offline", session.failure());
    }

    @Test
    void aMemberAnywhereElseNeverMovesThePhase() {
        AgentRjpqSession session = session();
        enter(BEN, MIRA, TOBI, UNA);
        when(characters.get(MIRA).getMapId()).thenReturn(AgentRjpqDefinition.EXIT_MAP);
        when(characters.get(TOBI).getMapId()).thenReturn(926_110_401);

        AgentRjpqCoordinator.tickSession(session, 5_000L);

        assertEquals(AgentRjpqSession.Phase.BOOKS, session.phase(),
                "the exit and the other side's maps are not stages of this run");
        verify(actions, never()).stop(any());
    }
}
