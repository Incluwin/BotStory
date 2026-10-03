package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
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
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.TOBI;

/**
 * P6b HPQ step 1, the public admit: a human leader's own party with his companions becomes a PRODUCTION HPQ session
 * that is a companion run from the moment it is published, so no Agent tick ever sees it as a stock run.
 */
class AgentHpqAdoptSeamTest {
    private final HpqAdmitFixture fixture = new HpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentHpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentHpqSessionRegistry> registry =
                     mockStatic(AgentHpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentHpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentHpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = fixture.keep(AgentHpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentHpqSession session = admitted.session();
        assertTrue(session.companionAdopted());
        assertSame(session, AgentHpqSessionRegistry.forOperator(BEN));
        assertEquals(AgentHpqSession.Mode.PRODUCTION, session.mode());
        assertEquals(AgentHpqSession.PartyOwnership.EXTERNAL, session.partyOwnership());
        assertEquals(AgentHpqSession.BonusMode.HUMAN_CHOICE, session.bonusMode());
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertFalse(session.memberCoordinationChatEnabled(), "the host turns coordination chat on");

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("hpq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        for (int id : List.of(BEN, MIRA, TOBI)) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentHpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "the lobby is gone");
    }

    @Test
    void theAdoptedSessionIsTheHostsCompanionRunHandle() {
        AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        assertTrue(admitted.success(), admitted.message());

        AgentCompanionRunSession handle = admitted.session();
        handle.setMemberCoordinationChatEnabled(true);
        handle.setPaceCalibration(null); // HPQ keeps no calibration

        assertEquals(admitted.session().sessionId(), handle.sessionId());
        assertTrue(handle.companionAdopted());
        assertTrue(admitted.session().memberCoordinationChatEnabled());
    }
}
