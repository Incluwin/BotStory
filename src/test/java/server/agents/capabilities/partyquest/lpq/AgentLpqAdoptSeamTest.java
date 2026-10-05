package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestDirectedAdmissionRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityBootstrap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.MIRA;

/**
 * P6d LPQ step 1, the public admit: a human leader's own party with his companions becomes a PRODUCTION LPQ session
 * that is a companion run from the moment it is published, so no Agent tick or watchdog ever sees it as a stock run.
 * Admission from a director's lobby, and an '!lpqtest' fixture's handoff, stay stock runs.
 */
class AgentLpqAdoptSeamTest {
    private final LpqAdmitFixture fixture = new LpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentLpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentLpqSessionRegistry> registry =
                     mockStatic(AgentLpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentLpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentLpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals("LPQ party admitted", admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentLpqSession session = admitted.session();
        assertTrue(session.companionAdopted());
        assertSame(session, AgentLpqSessionRegistry.forOperator(BEN));
        assertEquals(AgentLpqSession.Mode.PRODUCTION, session.mode());
        assertEquals(AgentLpqSession.PartyOwnership.EXTERNAL, session.partyOwnership());
        assertEquals(AgentLpqSession.BonusMode.HUMAN_CHOICE, session.bonusMode());
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertEquals(6, session.memberCount());
        assertEquals(AgentLpqMemberState.MemberType.HUMAN, session.member(BEN).memberType());
        assertEquals(AgentLpqMemberState.MemberType.AGENT, session.member(MIRA).memberType());

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("lpq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        assertEquals(session.sessionId(), engagement.activeSessionId());
        for (int id : IDS) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentLpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "the lobby is gone");
    }

    @Test
    void eachCompanionIsTakenFromItsActivityOnce() {
        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        assertTrue(admitted.success(), admitted.message());

        for (Character agent : fixture.party().subList(1, 6)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            verify(fixture.admission).prepare(eq(AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID),
                    eq(entry), eq(agent), eq("admitted to autonomous LPQ lobby"), eq(1_000L));
        }
        verify(fixture.admission, never()).prepare(anyString(), any(), eq(fixture.ben), anyString(), anyLong());
        verify(fixture.admission, times(5)).prepare(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void onlyThePublicAdmitCanFlagACompanionRun() throws NoSuchMethodException {
        // As HPQ and EPQ: the public admitFromLobby admits a stock run; the flagged form is package-private, so a
        // companion run is published only through admit(), with its activity preparation and rollback.
        Class<?>[] stock = {AgentPartyQuestEngagement.class, AgentPartyQuestLobbySession.class, Character.class,
                Character.class, List.class, long.class, long.class, AgentLpqSession.Mode.class, int.class,
                AgentLpqSession.HumanRolePreference.class};
        Class<?>[] flagged = java.util.Arrays.copyOf(stock, stock.length + 1);
        flagged[stock.length] = boolean.class;
        assertTrue(Modifier.isPublic(
                AgentLpqAdmissionService.class.getDeclaredMethod("admitFromLobby", stock).getModifiers()));
        int modifiers = AgentLpqAdmissionService.class.getDeclaredMethod("admitFromLobby", flagged).getModifiers();
        assertFalse(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || Modifier.isPrivate(modifiers),
                "the companion flag is package-private");
    }

    @Test
    void aDirectorLobbyAdmissionStaysAStockRun() {
        DirectorLobby director = directorLobby();

        AgentLpqAdmissionService.AdmissionResult admitted = AgentLpqAdmissionService.admitFromLobby(
                director.engagement(), director.lobby(), fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L,
                AgentLpqSession.Mode.PRODUCTION, 0, AgentLpqSession.HumanRolePreference.DEFAULT);

        assertTrue(admitted.success(), admitted.message());
        assertFalse(admitted.session().companionAdopted());
        assertSame(director.engagement(), admitted.engagement());
        assertSame(admitted.session(), AgentLpqSessionRegistry.forOperator(BEN));
    }

    @Test
    void theDirectorsLobbyHandoffAdmitsAStockRun() throws ReflectiveOperationException {
        DirectorLobby director = directorLobby();
        Field runtimeField = AgentLpqLobbyAdmissionRuntime.class.getDeclaredField("RUNTIME");
        runtimeField.setAccessible(true);
        Object runtime = runtimeField.get(null);
        Field handoffField = AgentPartyQuestDirectedAdmissionRuntime.class.getDeclaredField("handoff");
        handoffField.setAccessible(true);
        AgentPartyQuestDirectedAdmissionRuntime.Handoff handoff =
                (AgentPartyQuestDirectedAdmissionRuntime.Handoff) handoffField.get(runtime);

        AgentPartyQuestDirectedAdmissionRuntime.Result result = handoff.admit(director.engagement(),
                director.lobby(), fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertTrue(result.success(), result.message());
        AgentLpqSession session = AgentLpqSessionRegistry.forOperator(BEN);
        assertEquals(AgentLpqSession.Mode.PRODUCTION, session.mode());
        assertFalse(session.companionAdopted(), "a director's lobby is never a companion run");
    }

    @Test
    void theFixtureHandoffAdmitsAnObservationRunNotACompanionRun() throws ReflectiveOperationException {
        // An '!lpqtest' fixture flow (start, boss, humanleader, agentleader) hands its formed lobby to admission as it
        // always has: a TEST_OBSERVATION run, never a companion run (which would keep the party on a failure, route a
        // failure past the clear to failAfterClear and log potions and pace).
        DirectorLobby formed = lobby(AgentPartyQuestEngagement.Mode.TEST_OBSERVATION,
                AgentPartyQuestCandidateScope.OWNER_ONLY);
        when(fixture.party.hasParty(fixture.ben)).thenReturn(true);
        String service = AgentLpqTestService.class.getName();
        Class<?> flow = Class.forName(service + "$Flow");
        Class<?> startStage = Class.forName(service + "$StartStage");
        Class<?> runType = Class.forName(service + "$Run");
        Constructor<?> constructor = runType.getDeclaredConstructor(Character.class, AgentPartyQuestEngagement.class,
                long.class, flow, startStage, AgentLpqSession.HumanRolePreference.class, Set.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(fixture.ben, formed.engagement(), 7L, enumConstant(flow, "HUMAN_LEADER"),
                enumConstant(startStage, "FULL_RUN"), AgentLpqSession.HumanRolePreference.DEFAULT, Set.of());
        Field lobbyField = runType.getDeclaredField("lobby");
        lobbyField.setAccessible(true);
        lobbyField.set(run, formed.lobby());
        Method attemptHandoff = AgentLpqTestService.class.getDeclaredMethod("attemptHandoff", runType);
        attemptHandoff.setAccessible(true);

        attemptHandoff.invoke(null, run);

        Field sessionField = runType.getDeclaredField("session");
        sessionField.setAccessible(true);
        AgentLpqSession session = (AgentLpqSession) sessionField.get(run);
        assertNotNull(session, "the fixture's lobby was handed over");
        assertSame(session, AgentLpqSessionRegistry.forOperator(BEN));
        assertEquals(AgentLpqSession.Mode.TEST_OBSERVATION, session.mode());
        assertFalse(session.companionAdopted(), "an '!lpqtest' fixture run is never a companion run");
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new AssertionError(type + " has no " + name);
    }

    private record DirectorLobby(AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby) {
    }

    /** A ready lobby of Ben's party, as the director's admission runtime hands it over. */
    private DirectorLobby directorLobby() {
        return lobby(AgentPartyQuestEngagement.Mode.PRODUCTION, AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN);
    }

    /** A ready, registered lobby of Ben's party for an engagement in {@code mode}. */
    private DirectorLobby lobby(AgentPartyQuestEngagement.Mode mode, AgentPartyQuestCandidateScope scope) {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement("lpq", mode, 7L, BEN, 6, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(
                engagement.engagementId(), AgentLpqLobbyProfile.profile(), 7L, BEN, 6, scope, 1_000L);
        Set<Integer> roster = new LinkedHashSet<>();
        for (Character member : fixture.party()) {
            boolean agent = member != fixture.ben;
            AgentPartyQuestEngagementRegistry.addAndIndexMember(engagement, member.getId(), agent
                    ? AgentPartyQuestEngagement.MemberType.AGENT : AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
            lobby.addMember(member.getId(), agent ? AgentPartyQuestLobbySession.MemberType.AGENT
                            : AgentPartyQuestLobbySession.MemberType.HUMAN,
                    AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, 1_000L);
            roster.add(member.getId());
        }
        lobby.setCoordinatorAgentId(MIRA);
        lobby.reconcileParty(11, BEN, roster, 1_000L);
        lobby.markReady(1_000L);
        engagement.beginLobby(lobby.lobbyId(), 1_000L);
        engagement.lobbyReady(1_000L);
        AgentPartyQuestLobbyRuntime.register(lobby, 1_000L);
        return new DirectorLobby(engagement, lobby);
    }
}
