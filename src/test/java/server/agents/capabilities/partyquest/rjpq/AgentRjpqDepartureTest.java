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

import java.util.List;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Departures (the P6h review rules): a member the event never registered departs after the entry grace instead of
 * stalling the run, one it held and lost departs once it stays missing for LEFT_CONFIRM_MS (2 s), a departed companion
 * is handed back and never holds the coordinator lease, and once every companion is gone a human still in the event
 * carries on: the run is released without taking him out or disposing his event. MagatiaPQ's own end, which walks the
 * players out one by one before it disposes the event, is not a carry-on: the disposal ends the run in its words.
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
    void aMemberTheEventHeldAndLostDepartsOnceItStaysMissing() {
        tick(2_000L);
        when(fixture.character(TOBI).getEventInstance()).thenReturn(null);

        tick(2_500L);
        tick(4_499L);
        assertFalse(fixture.session.departed(TOBI), "missing for less than 2 s");

        tick(4_500L);
        assertTrue(fixture.session.departed(TOBI));
        assertFalse(fixture.session.departed(MIRA));
    }

    @Test
    void aMemberBackInTheEventStartsItsWaitAgain() {
        tick(2_000L);
        when(fixture.character(TOBI).getEventInstance()).thenReturn(null);
        tick(2_500L);
        when(fixture.character(TOBI).getEventInstance()).thenReturn(fixture.event);
        tick(3_000L);
        when(fixture.character(TOBI).getEventInstance()).thenReturn(null);

        tick(4_600L);
        assertFalse(fixture.session.departed(TOBI), "missing since 4.6 s, not 2.5 s");
        tick(6_600L);
        assertTrue(fixture.session.departed(TOBI));
    }

    @Test
    void theStockEndWalkingThePlayersOutIsNotACarryOn() {
        when(fixture.event.getTimeLeft()).thenReturn(900L);
        tick(2_000L);
        // MagatiaPQ_Z.js end(): playerExit for each player in turn, then dispose. Ben is still registered.
        for (int id : RjpqStageFixture.COMPANIONS) {
            when(fixture.character(id).getEventInstance()).thenReturn(null);
        }
        tick(2_500L);
        assertFalse(fixture.session.terminal(), fixture.session.failure());
        assertFalse(fixture.session.departed(MIRA));

        when(fixture.event.isEventDisposed()).thenReturn(true);
        tick(3_000L);

        assertEquals("The RJPQ event ended before the clear", fixture.session.failure());
        assertEquals(List.of("out of time... yulete got away."), fixture.said);
    }

    @Test
    void withEveryCompanionGoneTheHumanCarriesOnInHisEvent() {
        tick(2_000L);
        for (int id : RjpqStageFixture.COMPANIONS) {
            when(fixture.character(id).getEventInstance()).thenReturn(null);
        }

        tick(2_500L);
        assertFalse(fixture.session.terminal(), "not yet: the stock end may be walking them out");
        tick(4_500L);

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
