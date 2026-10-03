package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.MIRA;
import static server.agents.capabilities.partyquest.hpq.HpqAdmitFixture.TOBI;

/**
 * A companion run's party is its owner's own. However the run ends (stopped, failed or cleared), Ben's companions stay
 * in his party, as KPQ keeps a party it did not form. A stock PRODUCTION run still takes its Agents out of the party.
 */
class AgentHpqCompanionRunPartyTest {
    private final HpqAdmitFixture fixture = new HpqAdmitFixture();
    private MockedStatic<AgentAuthorityService> authority;

    @BeforeEach
    void benLeadsHisCompanionsAtTory() {
        when(fixture.party.hasParty(any())).thenReturn(true);
        when(fixture.party.onlineMembers(fixture.ben)).thenReturn(fixture.party());
    }

    @AfterEach
    void tearDown() {
        if (authority != null) {
            AgentHpqTestService.execute(fixture.ben, new String[]{"stop"}, 9_000L);
            authority.close();
        }
        // The GM path admits outside fixture.keep: drop whatever a failed assertion left registered.
        AgentHpqSessionRegistry.remove(AgentHpqSessionRegistry.forOperator(BEN));
        var engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN);
        if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        fixture.close();
    }

    @Test
    void stoppingAnAdoptedRunKeepsTheCompanionsInTheOwnersParty() {
        authority = mockStatic(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(fixture.ben)).thenReturn(true);
        assertEquals(List.of("Adopted as a companion run. Talk to Tory within 30 seconds."),
                AgentHpqTestService.execute(fixture.ben, new String[]{"party", "prod", "7"}, 1_000L));
        AgentHpqSession session = AgentHpqSessionRegistry.forOperator(BEN);
        assertTrue(session.companionAdopted());

        List<String> stopped = AgentHpqTestService.execute(fixture.ben, new String[]{"stop"}, 2_000L);

        assertTrue(stopped.getFirst().startsWith("Stopped HPQ observation"), stopped::toString);
        assertEquals(AgentHpqSession.Phase.FAILED, session.phase(), "the real stop ends the session");
        assertNull(AgentHpqSessionRegistry.forOperator(BEN));
        verify(fixture.party, never()).leaveCurrentParty(any());
        // Handed back where they stand: they follow Ben again.
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.miraEntry, fixture.ben));
        fixture.movement.verify(() -> AgentMovementCommandRuntime.follow(fixture.tobiEntry, fixture.ben));
    }

    @Test
    void aClearedCompanionRunKeepsItsParty() {
        AgentHpqSession session = admitted();

        AgentHpqTerminationService.complete(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
        verify(fixture.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aFailedCompanionRunKeepsItsParty() {
        AgentHpqSession session = admitted();

        AgentHpqTerminationService.fail(session, "Moon Bunny was defeated", 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        verify(fixture.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aStockProductionRunStillTakesItsAgentsOutOfTheParty() {
        AgentHpqSession session = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 7L, BEN, 3, 1_000L);
        session.addMember(BEN, AgentHpqMemberState.MemberType.HUMAN);
        session.addMember(MIRA, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(TOBI, AgentHpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, MIRA);
        session.setPartyOwnership(AgentHpqSession.PartyOwnership.EXTERNAL);

        AgentHpqTerminationService.complete(session, 2_000L);

        verify(fixture.party).leaveCurrentParty(fixture.mira);
        verify(fixture.party).leaveCurrentParty(fixture.tobi);
        verify(fixture.party, never()).leaveCurrentParty(fixture.ben);
    }

    private AgentHpqSession admitted() {
        AgentHpqAdmissionService.AdmissionResult result = fixture.keep(AgentHpqAdmissionService.admit(
                fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L));
        assertTrue(result.success(), result::message);
        assertTrue(result.session().companionAdopted());
        return result.session();
    }
}
