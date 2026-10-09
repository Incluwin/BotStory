package server.agents.capabilities.partyquest.opq;

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
import server.agents.integration.AgentPartySnapshot;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.simulation.AgentAbstractExecutionScope;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.MIRA;

/**
 * P6h OPQ step 1 (C2), the public admit: a human leader's own party with his companions becomes a PRODUCTION OPQ
 * session that is a companion run from the moment it is published, so no Agent tick ever sees it as a stock run.
 * Admission from a director's lobby stays a stock run of six.
 */
class AgentOpqAdoptSeamTest {
    private final OpqAdmitFixture fixture = new OpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentOpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentOpqSessionRegistry> registry =
                     mockStatic(AgentOpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentOpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentOpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals("OPQ party admitted", admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentOpqSession session = admitted.session();
        assertTrue(session.companionAdopted());
        assertSame(session, AgentOpqSessionRegistry.forOperator(BEN));
        assertEquals(AgentOpqSession.Mode.HUMAN_ASSISTED, session.mode());
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertEquals(6, session.memberCount());
        assertEquals(6, session.requestedPartySize());
        assertEquals(AgentOpqMemberState.MemberType.HUMAN, session.member(BEN).memberType());
        assertEquals(AgentOpqMemberState.MemberType.AGENT, session.member(MIRA).memberType());

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("opq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        assertEquals(session.sessionId(), engagement.activeSessionId());
        for (int id : IDS) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentOpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "the lobby is gone");
    }

    @Test
    void aPartyOfFiveWithoutEveryBranchRegistersAsAFiveMemberRun() {
        AgentPartySnapshot five = OpqAdmitFixture.benLeads(fixture.ben, fixture.mira, fixture.kes, fixture.pel,
                fixture.hal);
        when(fixture.party.snapshot(any())).thenReturn(five);

        AgentOpqAdmissionService.AdmissionResult admitted = AgentOpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party().subList(0, 5), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        assertEquals(5, admitted.session().memberCount());
        assertEquals(5, admitted.session().requestedPartySize());
        assertTrue(admitted.session().companionAdopted());
        assertSame(admitted.session(), AgentOpqSessionRegistry.forMember(fixture.hal.getId()));
        assertNull(AgentOpqSessionRegistry.forMember(fixture.gus.getId()));
    }

    @Test
    void eachCompanionIsTakenFromItsActivityOnceAndLeavesItsTownLife() {
        AgentOpqAdmissionService.AdmissionResult admitted =
                AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        assertTrue(admitted.success(), admitted.message());

        for (Character agent : fixture.party().subList(1, 6)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            verify(fixture.admission).prepare(eq(AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID),
                    eq(entry), eq(agent), eq("admitted to autonomous OPQ lobby"), eq(1_000L));
            verify(entry.simulationState()).clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        }
        verify(fixture.admission, never()).prepare(anyString(), any(), eq(fixture.ben), anyString(), anyLong());
        verify(fixture.admission, times(5)).prepare(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void theCompanionLobbyHasNoBranchSeatsAndTheStockOneKeepsItsFive() {
        assertEquals(List.of(), AgentOpqLobbyProfile.companionProfile().memberRequirements());
        assertEquals(List.of("warrior", "magician", "bowman", "thief", "pirate"),
                AgentOpqLobbyProfile.profile().memberRequirements().stream()
                        .map(requirement -> requirement.description()).toList());
        var stock = AgentOpqLobbyProfile.profile();
        var companion = AgentOpqLobbyProfile.companionProfile();
        assertEquals(stock.questKey(), companion.questKey());
        assertEquals(stock.mapId(), companion.mapId());
        assertEquals(stock.entryNpcId(), companion.entryNpcId());
        assertEquals(stock.minimumLevel(), companion.minimumLevel());
        assertEquals(stock.maximumLevel(), companion.maximumLevel());
        assertEquals(6, companion.maximumPartySize());
        assertEquals(stock.minimumXOffset(), companion.minimumXOffset());
        assertEquals(stock.maximumXOffset(), companion.maximumXOffset());
        assertEquals(stock.phrases(), companion.phrases());
        assertEquals(stock.waiterMessages(), companion.waiterMessages());
        assertEquals(stock.inviteResponseMinimumMs(), companion.inviteResponseMinimumMs());
        assertEquals(stock.inviteResponseMaximumMs(), companion.inviteResponseMaximumMs());
    }

    @Test
    void aReservedOperatorOrMemberIsRefusedBeforeAnyEngagement() {
        AgentOpqSession held = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 1L, 743_590, 5, 500L);
        held.addMember(743_590, AgentOpqMemberState.MemberType.HUMAN);
        for (int id : new int[]{743_591, 743_592, 743_593, OpqAdmitFixture.KES}) {
            held.addMember(id, AgentOpqMemberState.MemberType.AGENT);
        }
        held.setLeadership(743_590, 743_591);
        AgentOpqSessionRegistry.registerComplete(held);
        try {
            AgentOpqAdmissionService.AdmissionResult admitted =
                    AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("OPQ is already reserved: member-" + OpqAdmitFixture.KES, admitted.message());
            assertNull(admitted.engagement(), "refused before any engagement");
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            verify(fixture.admission, never()).prepare(anyString(), any(), any(), anyString(), anyLong());
        } finally {
            AgentOpqSessionRegistry.remove(held);
        }
    }

    @Test
    void theAdmissionIsLoggedInEveryMode() {
        try (OpqLogLines lines = new OpqLogLines(AgentOpqAdmissionService.class)) {
            AgentOpqAdmissionService.AdmissionResult admitted =
                    AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
            assertTrue(admitted.success(), admitted.message());
            assertEquals(List.of("OPQ session admitted: session=" + admitted.session().sessionId()
                            + " mode=HUMAN_ASSISTED operator=" + BEN + " leader=" + BEN
                            + " members=6 agents=5 companionRun=true"),
                    lines.startingWith("OPQ session admitted:"));
        }
    }

    @Test
    void onlyThePublicAdmitCanFlagACompanionRun() throws NoSuchMethodException {
        Class<?>[] stock = {AgentPartyQuestEngagement.class, AgentPartyQuestLobbySession.class, Character.class,
                Character.class, List.class, long.class, long.class, AgentOpqSession.Mode.class};
        Class<?>[] flagged = java.util.Arrays.copyOf(stock, stock.length + 1);
        flagged[stock.length] = boolean.class;
        assertTrue(Modifier.isPublic(
                AgentOpqAdmissionService.class.getDeclaredMethod("admitFromLobby", stock).getModifiers()));
        int modifiers = AgentOpqAdmissionService.class.getDeclaredMethod("admitFromLobby", flagged).getModifiers();
        assertFalse(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || Modifier.isPrivate(modifiers),
                "the companion flag is package-private");
    }

    @Test
    void aDirectorLobbyAdmissionStaysAStockRunOfSix() {
        DirectorLobby director = directorLobby();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqAdmissionService.class)) {
            AgentOpqAdmissionService.AdmissionResult admitted = AgentOpqAdmissionService.admitFromLobby(
                    director.engagement(), director.lobby(), fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L,
                    AgentOpqSession.Mode.HUMAN_ASSISTED);

            assertTrue(admitted.success(), admitted.message());
            assertFalse(admitted.session().companionAdopted());
            assertEquals(6, admitted.session().requestedPartySize());
            assertSame(director.engagement(), admitted.engagement());
            assertSame(admitted.session(), AgentOpqSessionRegistry.forOperator(BEN));
            assertEquals(List.of("OPQ session admitted: session=" + admitted.session().sessionId()
                            + " mode=HUMAN_ASSISTED operator=" + BEN + " leader=" + BEN
                            + " members=6 agents=5 companionRun=false"),
                    lines.startingWith("OPQ session admitted:"));
        }
        for (Character agent : fixture.party().subList(1, 6)) {
            verify(fixture.entry(agent).simulationState(), never())
                    .clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        }
    }

    @Test
    void theDirectorsLobbyHandoffAdmitsAStockRun() throws ReflectiveOperationException {
        DirectorLobby director = directorLobby();
        Field runtimeField = AgentOpqLobbyAdmissionRuntime.class.getDeclaredField("RUNTIME");
        runtimeField.setAccessible(true);
        Object runtime = runtimeField.get(null);
        Field handoffField = AgentPartyQuestDirectedAdmissionRuntime.class.getDeclaredField("handoff");
        handoffField.setAccessible(true);
        AgentPartyQuestDirectedAdmissionRuntime.Handoff handoff =
                (AgentPartyQuestDirectedAdmissionRuntime.Handoff) handoffField.get(runtime);

        AgentPartyQuestDirectedAdmissionRuntime.Result result = handoff.admit(director.engagement(),
                director.lobby(), fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertTrue(result.success(), result.message());
        AgentOpqSession session = AgentOpqSessionRegistry.forOperator(BEN);
        assertEquals(AgentOpqSession.Mode.HUMAN_ASSISTED, session.mode());
        assertFalse(session.companionAdopted(), "a director's lobby is never a companion run");
    }

    @Test
    void theStockLobbyAdmissionStillRefusesFive() {
        DirectorLobby director = directorLobby();
        AgentPartySnapshot five = OpqAdmitFixture.benLeads(fixture.ben, fixture.mira, fixture.kes, fixture.pel,
                fixture.hal);
        when(fixture.party.snapshot(any())).thenReturn(five);

        AgentOpqAdmissionService.AdmissionResult admitted = AgentOpqAdmissionService.admitFromLobby(
                director.engagement(), director.lobby(), fixture.ben, fixture.ben, fixture.party().subList(0, 5),
                7L, 1_000L, AgentOpqSession.Mode.HUMAN_ASSISTED);

        assertFalse(admitted.success());
        assertEquals("Missing OPQ coverage: exactly six members, pirate", admitted.message());
        assertNull(AgentOpqSessionRegistry.forOperator(BEN));
    }

    private record DirectorLobby(AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby) {
    }

    /** A ready, registered PRODUCTION lobby of Ben's party, as the director's admission runtime hands it over. */
    private DirectorLobby directorLobby() {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "opq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, BEN, 6, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(
                engagement.engagementId(), AgentOpqLobbyProfile.profile(), 7L, BEN, 6,
                AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, 1_000L);
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
        lobby.reconcileParty(12, BEN, roster, 1_000L);
        lobby.markReady(1_000L);
        engagement.beginLobby(lobby.lobbyId(), 1_000L);
        engagement.lobbyReady(1_000L);
        AgentPartyQuestLobbyRuntime.register(lobby, 1_000L);
        return new DirectorLobby(engagement, lobby);
    }
}
