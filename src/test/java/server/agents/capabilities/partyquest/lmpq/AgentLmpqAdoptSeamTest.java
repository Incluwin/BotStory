package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityBootstrap;

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
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.MIRA;

/**
 * P6h LMPQ step 1, the public admit: a human leader's own party with his companions becomes an LMPQ session that is a
 * companion run from the moment it is published, so no Agent tick ever sees it as a stock run. Its mode stays derived
 * (Ben leads: HUMAN_LEADER). Admission from a director's lobby stays a stock run.
 */
class AgentLmpqAdoptSeamTest {
    private final LmpqAdmitFixture fixture = new LmpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentLmpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentLmpqSessionRegistry> registry =
                     mockStatic(AgentLmpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentLmpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentLmpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals("LMPQ party admitted", admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentLmpqSession session = admitted.session();
        assertTrue(session.companionAdopted());
        assertSame(session, AgentLmpqSessionRegistry.forOperator(BEN));
        assertEquals(AgentLmpqSession.Mode.HUMAN_LEADER, session.mode());
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertEquals(4, session.memberCount());
        assertEquals(AgentLmpqMemberState.MemberType.HUMAN, session.member(BEN).memberType());
        assertEquals(AgentLmpqMemberState.MemberType.AGENT, session.member(MIRA).memberType());

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("lmpq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        assertEquals(session.sessionId(), engagement.activeSessionId());
        for (int id : IDS) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentLmpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "the lobby is gone");
    }

    @Test
    void eachCompanionIsTakenFromItsActivityOnce() {
        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
        assertTrue(admitted.success(), admitted.message());

        for (Character agent : fixture.party().subList(1, 4)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            verify(fixture.admission).prepare(eq(AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID),
                    eq(entry), eq(agent), eq("admitted to autonomous LMPQ lobby"), eq(1_000L));
        }
        verify(fixture.admission, never()).prepare(anyString(), any(), eq(fixture.ben), anyString(), anyLong());
        verify(fixture.admission, times(3)).prepare(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void theAdmittedLineNamesTheRunInEveryMode() {
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqAdmissionService.class)) {
            AgentLmpqAdmissionService.AdmissionResult admitted =
                    AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);
            assertTrue(admitted.success(), admitted.message());
            assertEquals(List.of("LMPQ session admitted: session=" + admitted.session().sessionId()
                            + " mode=HUMAN_LEADER operator=" + BEN + " leader=" + BEN
                            + " members=4 agents=3 companionRun=true"),
                    lines.startingWith("LMPQ session admitted:"));
        }
    }

    @Test
    void onlyThePublicAdmitCanFlagACompanionRun() throws NoSuchMethodException {
        Class<?>[] stock = {AgentPartyQuestEngagement.class, AgentPartyQuestLobbySession.class, Character.class,
                Character.class, List.class, long.class, long.class};
        Class<?>[] flagged = java.util.Arrays.copyOf(stock, stock.length + 1);
        flagged[stock.length] = boolean.class;
        assertTrue(Modifier.isPublic(
                AgentLmpqAdmissionService.class.getDeclaredMethod("admitFromLobby", stock).getModifiers()));
        int modifiers = AgentLmpqAdmissionService.class.getDeclaredMethod("admitFromLobby", flagged).getModifiers();
        assertFalse(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || Modifier.isPrivate(modifiers),
                "the companion flag is package-private");
    }

    @Test
    void aDirectorLobbyAdmissionStaysAStockRunAndSaysSo() {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, BEN, 4, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(engagement.engagementId(),
                AgentLmpqLobbyProfile.profile(), 7L, BEN, 4, AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, 1_000L);
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

        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqAdmissionService.class)) {
            AgentLmpqAdmissionService.AdmissionResult admitted = AgentLmpqAdmissionService.admitFromLobby(
                    engagement, lobby, fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertTrue(admitted.success(), admitted.message());
            assertFalse(admitted.session().companionAdopted(), "a director's lobby is never a companion run");
            assertSame(engagement, admitted.engagement());
            assertSame(admitted.session(), AgentLmpqSessionRegistry.forOperator(BEN));
            assertEquals(List.of("LMPQ session admitted: session=" + admitted.session().sessionId()
                            + " mode=HUMAN_LEADER operator=" + BEN + " leader=" + BEN
                            + " members=4 agents=3 companionRun=false"),
                    lines.startingWith("LMPQ session admitted:"));
        }
        verify(fixture.admission, never()).prepare(anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void aReservedPartyIsRefusedBeforeAnyEngagementExists() {
        AgentLmpqSession held = new AgentLmpqSession(AgentLmpqSession.Mode.AUTONOMOUS, 1L, 781_590, 3, 500L);
        held.addMember(781_590, AgentLmpqMemberState.MemberType.AGENT);
        held.addMember(781_591, AgentLmpqMemberState.MemberType.AGENT);
        held.addMember(MIRA, AgentLmpqMemberState.MemberType.AGENT); // Mira is still in another run
        held.setLeadership(781_590, 781_590);
        AgentLmpqSessionRegistry.registerComplete(held);
        try {
            AgentLmpqAdmissionService.AdmissionResult admitted =
                    AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("LMPQ is already reserved: member-" + MIRA, admitted.message());
            assertNull(admitted.session());
            assertNull(admitted.engagement(), "no engagement was made");
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            verify(fixture.admission, never()).prepare(anyString(), any(), any(), anyString(), anyLong());
            fixture.movement.verifyNoInteractions();
            assertSame(held, AgentLmpqSessionRegistry.forMember(MIRA));
        } finally {
            AgentLmpqSessionRegistry.remove(held);
        }
    }
}
