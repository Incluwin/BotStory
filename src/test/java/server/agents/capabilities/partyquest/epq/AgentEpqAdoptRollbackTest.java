package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.TOBI;
import static server.agents.capabilities.partyquest.epq.EpqAdmitFixture.UNA;

/**
 * A public EPQ admit that cannot finish leaves nothing behind: the engagement it registered is recovered (each
 * companion handed back to its owner where it stands), its lobby is gone and no EPQ session exists.
 */
class AgentEpqAdoptRollbackTest {
    private final EpqAdmitFixture fixture = new EpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        when(fixture.admission.prepare(anyString(), eq(fixture.miraEntry), any(), anyString(), anyLong()))
                .thenReturn(false);

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("Mira could not leave its current activity", admitted.message());
        assertNull(admitted.session());
        assertNotNull(admitted.engagement(), "the result carries the engagement it recovered");
        assertNothingKept(admitted.engagement());
        fixture.townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
    }

    @Test
    void aLaterCompanionThatCannotLeaveHandsTheEarlierOnesBack() {
        when(fixture.admission.prepare(anyString(), eq(fixture.unaEntry), any(), anyString(), anyLong()))
                .thenReturn(false);

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertEquals("Una could not leave its current activity", admitted.message());
        assertNothingKept(admitted.engagement());
        // Mira and Tobi were already taken from their activities: the recovery hands them back to Ben.
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.tobiEntry, fixture.ben));
    }

    @Test
    void anOperatorWhoAlreadyRunsAnEpqSessionIsRefusedAndNothingNewIsRegistered() {
        AgentEpqSession held = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 1L, BEN, 500L);
        for (int id = 751_290; id < 751_294; id++) held.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        held.setLeadership(751_290, 751_290);
        AgentEpqSessionRegistry.registerComplete(held); // Ben already operates a run
        try {
            AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

            assertFalse(admitted.success());
            assertEquals("EPQ admission is already reserved: operator-session", admitted.message());
            assertNull(admitted.session());
            assertNull(admitted.engagement(), "nothing was registered");
            assertNothingKept(null);
            assertSame(held, AgentEpqSessionRegistry.forOperator(BEN));
            fixture.movement.verifyNoInteractions();
        } finally {
            AgentEpqSessionRegistry.remove(held);
        }
    }

    @Test
    void aMemberHeldByAnotherEpqSessionIsRefusedByItsCode() {
        AgentEpqSession held = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 1L, 751_290, 500L);
        held.addMember(751_290, AgentEpqMemberState.MemberType.HUMAN);
        held.addMember(TOBI, AgentEpqMemberState.MemberType.AGENT);
        held.addMember(751_291, AgentEpqMemberState.MemberType.AGENT);
        held.addMember(751_292, AgentEpqMemberState.MemberType.AGENT);
        held.setLeadership(751_290, TOBI);
        AgentEpqSessionRegistry.registerComplete(held);
        try {
            AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

            assertEquals("EPQ admission is already reserved: member-" + TOBI, admitted.message());
            assertNull(admitted.engagement());
            assertNothingKept(null);
        } finally {
            AgentEpqSessionRegistry.remove(held);
        }
    }

    @Test
    void aRefusedHandoffOnceTheLobbyIsOpenUnregistersItAndRecovers() {
        AgentEpqAdmissionService.AdmissionResult admitted;
        try (MockedStatic<AgentEpqSessionRegistry> registry =
                     mockStatic(AgentEpqSessionRegistry.class, CALLS_REAL_METHODS)) {
            registry.when(() -> AgentEpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                assertNotNull(AgentPartyQuestLobbyRegistry.forMember(BEN), "the lobby is open");
                throw new IllegalStateException("EPQ member already reserved");
            });
            admitted = fixture.keep(AgentEpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        }

        assertFalse(admitted.success());
        assertEquals("EPQ member already reserved", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.tobiEntry, fixture.ben));
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.unaEntry, fixture.ben));
    }

    @Test
    void anInvalidPartyRegistersNothing() {
        when(fixture.tobi.getMapId()).thenReturn(AgentEpqDefinition.STAGE_ONE_MAP);

        AgentEpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentEpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("Tobi is not at EPQ", admitted.message());
        assertNull(admitted.engagement());
        assertNothingKept(null);
        fixture.movement.verifyNoInteractions();
    }

    private static void assertNothingKept(AgentPartyQuestEngagement engagement) {
        if (engagement != null) {
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                    .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "lobby gone");
        }
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            assertNull(AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
        }
        for (int id : List.of(MIRA, UNA)) assertNull(AgentEpqSessionRegistry.forMember(id), "session of " + id);
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
    }
}
