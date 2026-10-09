package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Departures (the P6h review rules): a member the event never registered departs after the entry grace instead of
 * stalling the run, one it held and lost departs at once, a departed companion is handed back and never holds the
 * coordinator lease, and once every companion is gone a human still in the event carries on: the run is released
 * without taking him out or disposing his event.
 */
class AgentRjpqDepartureTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.BEAKER_MAP);
    private final MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle =
            mockStatic(AgentPartyQuestLifecycleRuntime.class);
    private final MockedStatic<AgentPartyQuestEngagementRegistry> engagements =
            mockStatic(AgentPartyQuestEngagementRegistry.class);

    @AfterEach
    void close() {
        engagements.close();
        lifecycle.close();
        fixture.close();
    }

    private void tick(long nowMs) {
        AgentRjpqCoordinator.tickSession(fixture.session, nowMs);
    }

    @Test
    void aMemberTheEventNeverRegisteredDepartsAfterTheGraceAndIsHandedBack() {
        when(fixture.character(UNA).getEventInstance()).thenReturn(null);

        tick(1_000L + 4_000L);
        assertFalse(fixture.session.departed(UNA));

        tick(1_000L + 5_000L);
        assertTrue(fixture.session.departed(UNA));
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(UNA, "rjpq", 6_000L));
        assertFalse(fixture.session.terminal(), "the run goes on");
    }

    @Test
    void aMemberTheEventHeldAndLostDepartsAtOnce() {
        tick(2_000L);
        when(fixture.character(TOBI).getEventInstance()).thenReturn(null);

        tick(2_500L);

        assertTrue(fixture.session.departed(TOBI));
        assertFalse(fixture.session.departed(MIRA));
    }

    @Test
    void withEveryCompanionGoneTheHumanCarriesOnInHisEvent() {
        tick(2_000L);
        for (int id : RjpqStageFixture.COMPANIONS) {
            when(fixture.character(id).getEventInstance()).thenReturn(null);
        }

        tick(2_500L);

        assertTrue(fixture.session.terminal());
        assertEquals(AgentRjpqTerminationService.HUMANS_CARRY_ON, fixture.session.failure());
        verify(fixture.event, never()).exitPlayer(fixture.character(BEN));
        verify(fixture.event, never()).dispose();
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(fixture.session.sessionId()),
                eq(false), anyString(), anyLong(), any()));
    }

    @Test
    void aDepartedCompanionNeverHoldsOrClaimsTheCoordinatorLease() {
        AgentRjpqSession session = fixture.session;
        assertTrue(session.claimExecutionTick(MIRA, 1_000L, 3_000L));
        assertFalse(session.claimExecutionTick(TOBI, 1_500L, 3_000L), "the lease is Mira's");

        session.markDeparted(MIRA);

        assertFalse(session.claimExecutionTick(MIRA, 1_600L, 3_000L));
        assertTrue(session.claimExecutionTick(TOBI, 1_600L, 3_000L), "a departed holder's lease is free");
        session.markDeparted(UNA);
        assertFalse(session.claimExpiredExecutionTick(UNA, 9_000L, 3_000L));
    }
}
