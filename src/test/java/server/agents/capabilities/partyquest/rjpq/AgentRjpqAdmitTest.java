package server.agents.capabilities.partyquest.rjpq;

import client.inventory.InventoryType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqAdmitFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqAdmitFixture.UNA;

/**
 * The one way an RJPQ run is made: a human leader's party of exactly four beside Romeo or Juliet becomes a companion
 * run of that side under a PRODUCTION engagement, flagged before it is published; anything else is refused with
 * nothing left registered.
 */
class AgentRjpqAdmitTest {
    private final RjpqAdmitFixture fixture = new RjpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicReference<Boolean> adoptedAtPublish = new AtomicReference<>();
        AgentRjpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentRjpqSessionRegistry> registry =
                     mockStatic(AgentRjpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentRjpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                adoptedAtPublish.set(call.<AgentRjpqSession>getArgument(0).companionAdopted());
                return call.callRealMethod();
            });
            admitted = fixture.keep(AgentRjpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        }

        assertTrue(admitted.success(), admitted.message());
        assertEquals(Boolean.TRUE, adoptedAtPublish.get(), "adopted before registerComplete");
        AgentRjpqSession session = admitted.session();
        assertSame(AgentRjpqSide.ZENUMIST, session.side());
        assertSame(session, AgentRjpqSessionRegistry.forOperator(BEN));
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(MIRA, session.executionAgentId());
        assertFalse(session.memberCoordinationChatEnabled(), "the host or the GM command turns coordination on");

        AgentPartyQuestEngagement engagement = admitted.engagement();
        assertEquals("rjpq", engagement.questKey());
        assertEquals(AgentPartyQuestEngagement.Mode.PRODUCTION, engagement.mode());
        assertEquals(AgentPartyQuestEngagement.State.ACTIVE_EVENT, engagement.state());
        assertEquals(session.sessionId(), engagement.activeSessionId());
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertSame(session, AgentRjpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        assertEquals(AgentRjpqMemberState.MemberType.HUMAN, session.member(BEN).memberType());
        assertEquals(AgentRjpqMemberState.MemberType.AGENT, session.member(UNA).memberType());
    }

    @Test
    void besideJulietTheRunIsAnAlcadnoRun() {
        fixture.onAlcadno();

        AgentRjpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertTrue(admitted.success(), admitted.message());
        assertSame(AgentRjpqSide.ALCADNO, admitted.session().side());
        assertEquals("rjpqj", admitted.engagement().questKey());
    }

    @Test
    void threeOrFiveAreRefusedAndNothingIsRegistered() {
        AgentRjpqAdmissionService.AdmissionResult five = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.withPia().party(), 7L, 1_000L));
        AgentRjpqAdmissionService.AdmissionResult three = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.without(fixture.pia).without(fixture.una).party(), 7L, 1_000L));

        for (AgentRjpqAdmissionService.AdmissionResult refused : List.of(five, three)) {
            assertFalse(refused.success());
            assertEquals("Romeo and Juliet takes exactly four", refused.message());
            assertNull(refused.session());
            assertNull(refused.engagement());
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
        assertNull(AgentRjpqSessionRegistry.forOperator(BEN));
    }

    @Test
    void anyoneOffTheRecruitMapOrOutOfBandIsRefusedInStockOrder() {
        when(fixture.tobi.getMapId()).thenReturn(261_000_010);
        assertEquals("Tobi is not beside Romeo.", AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L).message());

        when(fixture.tobi.getMapId()).thenReturn(AgentRjpqSide.ZENUMIST.recruitMapId());
        when(fixture.una.getLevel()).thenReturn(86);
        assertEquals("Una must be level 71 to 85.", AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L).message());
        when(fixture.una.getLevel()).thenReturn(70);
        assertEquals("Una must be level 71 to 85.", AgentRjpqAdmissionService.memberProblem(fixture.una));
        when(fixture.una.getLevel()).thenReturn(71);
        assertEquals("", AgentRjpqAdmissionService.memberProblem(fixture.una));
    }

    @Test
    void aCompanionWithNoRoomForTheClaimsIsRefusedButAHumanIsNot() {
        RjpqAdmitFixture.room(fixture.ben, InventoryType.ETC, 0);
        RjpqAdmitFixture.room(fixture.tobi, InventoryType.EQUIP, 0);

        AgentRjpqAdmissionService.AdmissionResult refused = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        assertEquals("Tobi needs room in the bag first.", refused.message());

        RjpqAdmitFixture.room(fixture.tobi, InventoryType.EQUIP, 1);
        AgentRjpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        assertTrue(admitted.success(), "a human with a full ETC tab may go: he only loses his own marble");
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        when(fixture.admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true, false);

        AgentRjpqAdmissionService.AdmissionResult refused = fixture.keep(AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(refused.success());
        assertEquals("Tobi could not leave its current activity", refused.message());
        assertNull(refused.session());
        assertNotNull(refused.engagement());
        assertNull(AgentRjpqSessionRegistry.forOperator(BEN));
    }

    @Test
    void standingAnywhereElseIsRefusedFirst() {
        when(fixture.ben.getMapId()).thenReturn(261_000_000);

        assertEquals("Stand beside Romeo or Juliet first.", AgentRjpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L).message());
    }

    @Test
    void theAdmissionLineNamesTheSideAndSaysItIsACompanionRun() {
        try (RjpqLogLines lines = new RjpqLogLines(AgentRjpqAdmissionService.class)) {
            AgentRjpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentRjpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
            assertTrue(admitted.success(), admitted.message());
            assertEquals(List.of("RJPQ session admitted: session=" + admitted.session().sessionId()
                            + " side=rjpq operator=" + BEN + " leader=" + BEN + " members=4 agents=3 companionRun=true"),
                    lines.startingWith("RJPQ session admitted:"));
        }
    }
}
