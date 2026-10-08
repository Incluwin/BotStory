package server.agents.capabilities.partyquest.opq;

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
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.GUS;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.opq.OpqAdmitFixture.MIRA;

/**
 * P6h OPQ step 1 (C2): a public OPQ admit that cannot finish leaves nothing behind: the engagement it registered is
 * recovered (each companion it had taken handed back to its owner where it stands), its lobby is gone and no OPQ
 * session exists.
 */
class AgentOpqAdoptRollbackTest {
    private final OpqAdmitFixture fixture = new OpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        AgentRuntimeEntry gus = fixture.entry(fixture.gus);
        when(fixture.admission.prepare(anyString(), eq(gus), any(), anyString(), anyLong())).thenReturn(false);

        AgentOpqAdmissionService.AdmissionResult admitted =
                AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Gus could not leave its current activity", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        // The four before Gus were taken from their activities: the recovery hands them back to Ben, never to TownLife.
        for (Character agent : fixture.party().subList(1, 5)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
        }
        fixture.townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
        fixture.maps.verify(AgentMapGatewayRuntime::map, never());
    }

    @Test
    void aCompanionStillHeldByAnOlderEngagementRollsTheAdmissionBack() {
        // Gus's last run is still recovering him, so indexing him in the new engagement throws.
        AgentPartyQuestEngagement older = new AgentPartyQuestEngagement(
                "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 1L, 743_599, 3, 500L);
        older.addMember(GUS, AgentPartyQuestEngagement.MemberType.AGENT, 500L);
        AgentPartyQuestEngagementRegistry.register(older);
        older.beginRecovery("HPQ ended", 600L);
        try {
            AgentOpqAdmissionService.AdmissionResult admitted =
                    AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("party-quest member already belongs to another engagement", admitted.message());
            assertNull(admitted.session());
            AgentPartyQuestEngagement engagement = admitted.engagement();
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            assertNull(AgentPartyQuestEngagementRegistry.forMember(MIRA));
            assertSame(older, AgentPartyQuestEngagementRegistry.forMember(GUS), "the older engagement keeps Gus");
            for (int id : IDS) {
                assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
                assertNull(AgentOpqSessionRegistry.forMember(id), "session of " + id);
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
            return OpqAdmitFixture.benLeadsHisFive();
        });

        AgentOpqAdmissionService.AdmissionResult admitted =
                AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("party service unavailable", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        for (Character agent : fixture.party().subList(1, 6)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
        }
    }

    @Test
    void anInvalidPartyRegistersNothing() {
        when(fixture.pel.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);

        AgentOpqAdmissionService.AdmissionResult admitted =
                AgentOpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Pel is not at OPQ", admitted.message());
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
            assertNull(AgentOpqSessionRegistry.forMember(id), "session of " + id);
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
    }
}
