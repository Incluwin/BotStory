package server.agents.capabilities.partyquest.lpq;

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
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.CAS;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.IDS;
import static server.agents.capabilities.partyquest.lpq.LpqAdmitFixture.MIRA;

/**
 * A public LPQ admit that cannot finish leaves nothing behind: the engagement it registered is recovered (each
 * companion it had taken handed back to its owner where it stands), its lobby is gone and no LPQ session exists.
 */
class AgentLpqAdoptRollbackTest {
    private final LpqAdmitFixture fixture = new LpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        AgentRuntimeEntry cas = fixture.entry(fixture.cas);
        when(fixture.admission.prepare(anyString(), eq(cas), any(), anyString(), anyLong())).thenReturn(false);

        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Cas could not leave its current activity", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        // The four before Cas were taken from their activities: the recovery hands them back to Ben, never to TownLife.
        for (Character agent : fixture.party().subList(1, 5)) {
            AgentRuntimeEntry entry = fixture.entry(agent);
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
        }
        fixture.townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
        fixture.maps.verify(AgentMapGatewayRuntime::map, never());
    }

    @Test
    void aRefusedHandoffUnregistersTheLobbyAndRecovers() {
        AgentLpqSession held = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, BEN, 6, 500L);
        for (int id = 742_590; id < 742_596; id++) {
            held.addMember(id, id == 742_590 ? AgentLpqMemberState.MemberType.AGENT
                    : AgentLpqMemberState.MemberType.HUMAN);
        }
        held.setLeadership(742_590, 742_590);
        AgentLpqSessionRegistry.registerComplete(held); // Ben already operates a run
        try {
            AgentLpqAdmissionService.AdmissionResult admitted =
                    AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("LPQ is reserved: operator-session", admitted.message());
            assertNull(admitted.session());
            assertNothingKept(admitted.engagement());
            assertSame(held, AgentLpqSessionRegistry.forOperator(BEN));
            for (Character agent : fixture.party().subList(1, 6)) {
                AgentRuntimeEntry entry = fixture.entry(agent);
                fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(entry, fixture.ben));
            }
        } finally {
            AgentLpqSessionRegistry.remove(held);
        }
    }

    @Test
    void aCompanionStillHeldByAnOlderEngagementRollsTheAdmissionBack() {
        // Cas's last run is still recovering him, so indexing him in the new engagement throws.
        AgentPartyQuestEngagement older = new AgentPartyQuestEngagement(
                "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 1L, 742_599, 3, 500L);
        older.addMember(CAS, AgentPartyQuestEngagement.MemberType.AGENT, 500L);
        AgentPartyQuestEngagementRegistry.register(older);
        older.beginRecovery("HPQ ended", 600L);
        try {
            AgentLpqAdmissionService.AdmissionResult admitted =
                    AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("party-quest member already belongs to another engagement", admitted.message());
            assertNull(admitted.session());
            AgentPartyQuestEngagement engagement = admitted.engagement();
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            assertNull(AgentPartyQuestEngagementRegistry.forMember(MIRA));
            assertSame(older, AgentPartyQuestEngagementRegistry.forMember(CAS), "the older engagement keeps Cas");
            for (int id : IDS) {
                assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
                assertNull(AgentLpqSessionRegistry.forMember(id), "session of " + id);
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
            return LpqAdmitFixture.benLeadsHisFive();
        });

        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

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
        when(fixture.pel.getMapId()).thenReturn(AgentLpqDefinition.stage(1).mapId());

        AgentLpqAdmissionService.AdmissionResult admitted =
                AgentLpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Pel is not at LPQ", admitted.message());
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
            if (id != BEN) assertNull(AgentLpqSessionRegistry.forMember(id), "session of " + id);
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
    }
}
