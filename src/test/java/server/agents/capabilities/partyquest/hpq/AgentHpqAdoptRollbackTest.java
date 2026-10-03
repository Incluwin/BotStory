package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;

import java.util.List;

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
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.TOBI;

/**
 * A public HPQ admit that cannot finish leaves nothing behind: the engagement it registered is recovered (each
 * companion handed back to its owner where it stands), its lobby is gone and no HPQ session exists.
 */
class AgentHpqAdoptRollbackTest {
    private final HpqAdmitFixture fixture = new HpqAdmitFixture();

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        when(fixture.admission.prepare(anyString(), eq(fixture.tobiEntry), any(), anyString(), anyLong()))
                .thenReturn(false);

        AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("Tobi could not leave its current activity", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        // Mira was already taken from her activity: the recovery hands her back to Ben, never to TownLife.
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
        fixture.townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
        fixture.maps.verify(AgentMapGatewayRuntime::map, never());
    }

    @Test
    void aRefusedHandoffUnregistersTheLobbyAndRecovers() {
        AgentHpqSession held = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 1L, BEN, 3, 500L);
        held.addMember(741_290, AgentHpqMemberState.MemberType.AGENT);
        held.addMember(741_291, AgentHpqMemberState.MemberType.HUMAN);
        held.addMember(741_292, AgentHpqMemberState.MemberType.HUMAN);
        held.setLeadership(741_290, 741_290);
        AgentHpqSessionRegistry.registerComplete(held); // Ben already operates a run
        try {
            AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

            assertFalse(admitted.success());
            assertEquals("HPQ admission is already reserved: operator-session", admitted.message());
            assertNull(admitted.session());
            assertNothingKept(admitted.engagement());
            assertSame(held, AgentHpqSessionRegistry.forOperator(BEN));
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.tobiEntry, fixture.ben));
        } finally {
            AgentHpqSessionRegistry.remove(held);
        }
    }

    @Test
    void aCompanionStillHeldByAnOlderEngagementRollsTheAdmissionBack() {
        // Tobi's last KPQ is still recovering him, so indexing him in the new engagement throws.
        AgentPartyQuestEngagement older = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 1L, 741_299, 3, 500L);
        older.addMember(TOBI, AgentPartyQuestEngagement.MemberType.AGENT, 500L);
        AgentPartyQuestEngagementRegistry.register(older);
        older.beginRecovery("KPQ ended", 600L);
        try {
            AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                    fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

            assertFalse(admitted.success());
            assertEquals("party-quest member already belongs to another engagement", admitted.message());
            assertNull(admitted.session());
            AgentPartyQuestEngagement engagement = admitted.engagement();
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
            assertNull(AgentPartyQuestEngagementRegistry.forMember(MIRA));
            assertSame(older, AgentPartyQuestEngagementRegistry.forMember(TOBI), "the older engagement keeps Tobi");
            for (int id : List.of(BEN, MIRA, TOBI)) {
                assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
                if (id != BEN) assertNull(AgentHpqSessionRegistry.forMember(id), "session of " + id);
            }
            // Mira had already been taken from her activity: she is handed back to Ben.
            fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
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
            return HpqAdmitFixture.benLeadsMiraAndTobi();
        });

        AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("party service unavailable", admitted.message());
        assertNull(admitted.session());
        assertNothingKept(admitted.engagement());
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.tobiEntry, fixture.ben));
    }

    @Test
    void anInvalidPartyRegistersNothing() {
        when(fixture.tobi.getMapId()).thenReturn(AgentHpqDefinition.STAGE_MAP);

        AgentHpqAdmissionService.AdmissionResult admitted = fixture.keep(AgentHpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));

        assertFalse(admitted.success());
        assertEquals("Tobi is not at the Henesys PQ entrance", admitted.message());
        assertNull(admitted.engagement());
        assertNothingKept(null);
    }

    private static void assertNothingKept(AgentPartyQuestEngagement engagement) {
        if (engagement != null) {
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state(), "recovered");
            assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
            assertTrue(AgentPartyQuestLobbyRegistry.lobbies().stream()
                    .noneMatch(lobby -> lobby.engagementId().equals(engagement.engagementId())), "lobby gone");
        }
        for (int id : List.of(BEN, MIRA, TOBI)) {
            assertNull(AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertNull(AgentPartyQuestLobbyRegistry.forMember(id), "lobby of " + id);
            if (id != BEN) assertNull(AgentHpqSessionRegistry.forMember(id), "session of " + id);
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
    }
}
