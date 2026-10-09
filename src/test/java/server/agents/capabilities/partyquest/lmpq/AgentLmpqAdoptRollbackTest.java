package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.PEL;

/**
 * A public LMPQ admit that cannot finish leaves nothing behind: the engagement it registered is recovered (each
 * companion it had taken handed back to its owner where it stands), its lobby is gone and no LMPQ session exists.
 */
class AgentLmpqAdoptRollbackTest {
    private final LmpqAdmitFixture fixture = new LmpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        AgentRuntimeEntry pel = fixture.entry(fixture.pel);
        when(fixture.admission.prepare(anyString(), eq(pel), any(), anyString(), anyLong())).thenReturn(false);

        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Pel could not leave its current activity", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        // The two before Pel were taken from their activities: the recovery hands them back to Ben, never to TownLife.
        for (Character agent : fixture.party().subList(1, 3)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
        }
        fixture.townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
        fixture.maps.verify(AgentMapGatewayRuntime::map, never());
    }

    @Test
    void aCompanionStillHeldByAnOlderEngagementRollsTheAdmissionBack() {
        // Pel's last run is still recovering him, so indexing him in the new engagement throws.
        AgentPartyQuestEngagement older = new AgentPartyQuestEngagement(
                "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 1L, 781_599, 3, 500L);
        older.addMember(PEL, AgentPartyQuestEngagement.MemberType.AGENT, 500L);
        AgentPartyQuestEngagementRegistry.register(older);
        older.beginRecovery("HPQ ended", 600L);
        try {
            AgentLmpqAdmissionService.AdmissionResult admitted =
                    AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("party-quest member already belongs to another engagement", admitted.message());
            assertNull(admitted.session());
            AgentPartyQuestEngagement engagement = admitted.engagement();
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            assertNull(AgentPartyQuestEngagementRegistry.forMember(MIRA));
            assertSame(older, AgentPartyQuestEngagementRegistry.forMember(PEL), "the older engagement keeps Pel");
            for (int id : IDS) {
                assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
                assertNull(AgentLmpqSessionRegistry.forMember(id), "session of " + id);
            }
            AgentRuntimeEntry mira = fixture.entry(fixture.mira);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(mira, fixture.ben));
        } finally {
            AgentPartyQuestEngagementRegistry.remove(older);
        }
    }

    @Test
    void aFailureOnceTheLobbyIsOpenUnregistersItAndRecovers() {
        // Any exception after the lobby is registered: here the party service fails during the handoff.
        when(fixture.party.snapshot(any())).thenAnswer(invocation -> {
            if (AgentPartyQuestLobbyRegistry.forMember(BEN) != null) {
                throw new IllegalStateException("party service unavailable");
            }
            return LmpqAdmitFixture.benLeadsHisThree();
        });

        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("party service unavailable", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        for (Character agent : fixture.party().subList(1, 4)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
        }
    }

    @Test
    void aRefusedHandoffUnregistersTheLobbyAndRecovers() {
        // The handoff validates again and finds the party changed under it: the admit recovers what it made.
        when(fixture.kes.getMapId()).thenAnswer(invocation -> AgentPartyQuestLobbyRegistry.forMember(BEN) != null
                ? AgentLmpqDefinition.mapForRoom(1) : AgentLmpqDefinition.RECRUIT_MAP);

        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Kes is not at the LMPQ entrance", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
    }

    @Test
    void anInvalidPartyRegistersNothing() {
        when(fixture.pel.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));

        AgentLmpqAdmissionService.AdmissionResult admitted =
                AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Pel is not at the LMPQ entrance", admitted.message());
        assertNull(admitted.engagement());
        assertNothingKept(null);
        fixture.movement.verifyNoInteractions();
        assertTrue(AgentPartyQuestEngagementRegistry.engagements().stream()
                .noneMatch(engagement -> engagement.operatorId() == BEN));
    }

    private static void assertNothingKept(AgentPartyQuestEngagement engagement) {
        if (engagement != null) {
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                    .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "lobby gone");
        }
        for (int id : IDS) {
            assertNull(AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
            assertNull(AgentLmpqSessionRegistry.forMember(id), "session of " + id);
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
    }
}
