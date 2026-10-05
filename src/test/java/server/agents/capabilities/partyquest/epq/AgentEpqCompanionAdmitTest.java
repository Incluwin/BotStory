package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.REX;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.TOBI;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.UNA;

/**
 * P6d EPQ step 1, the public admit: a human leader's own party with his companions becomes a HUMAN_ASSISTED EPQ
 * session under a PRODUCTION engagement, a companion run from the moment it is published, under the stock party rule
 * (four to six adventurers), not the Agent rule of exactly five, one per branch.
 */
class AgentEpqCompanionAdmitTest {
    private final EpqAdmitFixture fixture = new EpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentEpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentEpqSessionRegistry> registry =
                     mockStatic(AgentEpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentEpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentEpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = fixture.keep(AgentEpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentEpqSession session = admitted.session();
        assertTrue(session.companionAdopted());
        assertEquals(AgentEpqSession.Mode.HUMAN_ASSISTED, session.mode());
        assertSame(session, AgentEpqSessionRegistry.forOperator(BEN));
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertFalse(session.memberCoordinationChatEnabled(), "the host turns coordination chat on");

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("epq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        assertEquals(session.sessionId(), engagement.activeSessionId());
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentEpqSessionRegistry.forMember(id), "session of " + id);
            assertTrue(session.member(id) != null, "member " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertEquals(AgentEpqMemberState.MemberType.HUMAN, session.member(BEN).memberType());
        assertEquals(AgentEpqMemberState.MemberType.AGENT, session.member(UNA).memberType());
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "the lobby is gone");
    }

    @Test
    void aBeginnerLedFourMemberPartyIsAdmittedThoughTheAgentRuleRefusesIt() {
        // Ben (Beginner), Mira (Thief), Tobi (Warrior), Una (Magician): no Bowman, no Pirate, four members.
        assertEquals("Missing EPQ coverage: exactly five members, bowman, pirate",
                AgentEpqAdmissionService.validate(fixture.ben, fixture.ben, fixture.party()).message());

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertTrue(admitted.success(), admitted.message());
        assertEquals(4, admitted.session().memberCount());
    }

    @Test
    void aSixMemberPartyIsAdmitted() {
        fixture.withPia().withRex();

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertTrue(admitted.success(), admitted.message());
        assertEquals(6, admitted.session().memberCount());
        assertSame(admitted.session(), AgentEpqSessionRegistry.forMember(REX));
    }

    @Test
    void aThreeMemberPartyIsRefusedAndNothingIsRegistered() {
        fixture.without(fixture.una);

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("The Ellin Forest event accepts four to six members", admitted.message());
        assertNull(admitted.session());
        assertNull(admitted.engagement());
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
        assertNull(AgentEpqSessionRegistry.forOperator(BEN));
        for (int id : List.of(BEN, MIRA, TOBI)) assertNull(AgentPartyQuestLobbyRegistry.forMember(id));
    }

    @Test
    void theAdmissionLineSaysItIsACompanionRun() {
        AgentEpqAdmissionService.AdmissionResult admitted;
        try (EpqLogLines lines = new EpqLogLines(AgentEpqAdmissionService.class)) {
            admitted = fixture.keep(AgentEpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
            assertTrue(admitted.success(), admitted.message());
            assertEquals(List.of("EPQ session admitted: session=" + admitted.session().sessionId()
                            + " mode=HUMAN_ASSISTED operator=" + BEN + " leader=" + BEN
                            + " members=4 agents=3 companionRun=true"),
                    lines.startingWith("EPQ session admitted:"));
        }
    }
}
