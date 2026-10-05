package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ's release for a companion run (plan 1.6): the owner's party is kept whatever the outcome; after the clear only the
 * companions leave the event, through its own exit, and the event is not disposed; the run's pace goes to the host;
 * and the potions and the released line are logged. A stock release is exactly as before.
 */
class AgentEpqCompanionTerminationTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCompanionRunKeepsTheOwnersPartyWhetherItClearsOrFails() {
        AgentEpqSession cleared = companionRun(AgentEpqSession.Phase.EXITING);
        cleared.complete(5_000L);
        AgentEpqTerminationService.release(cleared, "completed", 5_000L, false);

        AgentEpqSession failed = companionRun(AgentEpqSession.Phase.STAGE_TWO);
        AgentEpqTerminationService.release(failed, "EPQ stalled in STAGE_TWO for 480 seconds", 6_000L, true);

        verify(run.party, never()).leaveCurrentParty(any());
        assertEquals(AgentEpqSession.Phase.COMPLETED, cleared.phase());
        assertEquals(AgentEpqSession.Phase.FAILED, failed.phase());
        assertNull(AgentEpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aStockRunStillTakesEveryMemberOutOfTheParty() {
        for (AgentEpqSession.Mode mode : AgentEpqSession.Mode.values()) {
            AgentEpqSession session = run.session(mode, false, AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
            AgentEpqTerminationService.release(session, "stopped by operator", 5_000L, false);
            for (Character member : List.of(ben, mira, tobi, una)) {
                verify(run.party).leaveCurrentParty(member);
            }
            org.mockito.Mockito.clearInvocations(run.party);
        }
    }

    @Test
    void aCompanionRunFailingAfterTheClearTakesOnlyTheCompanionsOut() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.BOSS);

        AgentEpqTerminationService.release(session, "EPQ stalled in BOSS for 480 seconds", 5_000L, true);

        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event).exitPlayer(una);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void theRewardMapIsNotTheClearAFailureThereStillDisposes() {
        // 930000800 is also every stage's return map: a leader who died or quit stands there before any clear.
        when(run.event.isEventCleared()).thenReturn(false);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.REWARD);

        AgentEpqTerminationService.release(session, "EPQ member entered a different event instance", 5_000L, true);

        var order = inOrder(run.event);
        for (Character member : List.of(ben, mira, tobi, una)) order.verify(run.event).exitPlayer(member);
        order.verify(run.event).dispose();
    }

    @Test
    void beforeTheClearACompanionFailureDisposesAsStockAndAForcedStopAfterItDoesNot() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession early = companionRun(AgentEpqSession.Phase.STAGE_FOUR);
        AgentEpqTerminationService.release(early, "EPQ event timer expired", 5_000L, true);
        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();

        org.mockito.Mockito.clearInvocations(run.event);
        when(run.event.isEventCleared()).thenReturn(true);
        AgentEpqSession late = companionRun(AgentEpqSession.Phase.REWARD);
        AgentEpqTerminationService.release(late, "Agent runtime removed", 6_000L, true);
        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void aFailedCompanionRunStoppedBeforeTheTickReleasesItLeavesTheEventAsTheTickWould() {
        // AgentEpqRuntime.requestStop releases a terminal session without dispose. A companion run keeps its party, so
        // no party leave runs EllinPQ's exit for it: it leaves as the tick's failure release has it leave.
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession early = companionRun(AgentEpqSession.Phase.STAGE_FOUR);
        early.fail("EPQ event timer expired", 5_000L);
        assertTrue(AgentEpqRuntime.requestStop(mira.getId(), "companion recalled", 5_000L));
        var order = inOrder(run.event);
        for (Character member : List.of(ben, mira, tobi, una)) order.verify(run.event).exitPlayer(member);
        order.verify(run.event).dispose();

        org.mockito.Mockito.clearInvocations(run.event);
        when(run.event.isEventCleared()).thenReturn(true);
        AgentEpqSession late = companionRun(AgentEpqSession.Phase.REWARD);
        late.fail("EPQ stalled in REWARD for 480 seconds", 6_000L);
        assertTrue(AgentEpqRuntime.requestStop(tobi.getId(), "companion recalled", 6_000L));
        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event).exitPlayer(una);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
        assertNull(AgentEpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aCompletedCompanionRunReleasedWithoutDisposeLeavesTheEventAlone() {
        when(run.event.getPlayers()).thenReturn(List.of(ben));
        AgentEpqSession cleared = companionRun(AgentEpqSession.Phase.EXITING);
        cleared.complete(5_000L);

        assertTrue(AgentEpqRuntime.requestStop(una.getId(), "companion recalled", 5_000L));

        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        assertEquals(AgentEpqSession.Phase.COMPLETED, cleared.phase());
    }

    @Test
    void aFailedStockRunStoppedBeforeTheTickReleasesItStillLeavesByTheParty() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession stock = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_FOUR, ben, mira, tobi, una);
        stock.fail("EPQ event timer expired", 5_000L);

        assertTrue(AgentEpqRuntime.requestStop(mira.getId(), "recalled", 5_000L));

        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        for (Character member : List.of(ben, mira, tobi, una)) verify(run.party).leaveCurrentParty(member);
    }

    @Test
    void aStockFailureAfterTheClearStillDisposes() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.REWARD, ben, mira, tobi, una);

        AgentEpqTerminationService.release(session, "EPQ stalled in REWARD for 480 seconds", 5_000L, true);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
    }

    @Test
    void aCompanionRunsPaceGoesToTheHostAndAStockRunReportsNone() {
        AgentPqPace pace = new AgentPqPace("epq", "s", 1, 1_000L, 5_000L, null, null, null, null);
        try (MockedStatic<AgentEpqTerminationService> termination =
                     mockStatic(AgentEpqTerminationService.class, CALLS_REAL_METHODS)) {
            termination.when(() -> AgentEpqTerminationService.pace(any(), anyLong())).thenReturn(pace);
            AgentEpqSession companion = companionRun(AgentEpqSession.Phase.EXITING);
            companion.complete(5_000L);
            AgentEpqTerminationService.release(companion, "completed", 5_000L, false);
            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                    companion.sessionId(), true, "completed", 5_000L, pace));

            AgentEpqSession stock = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                    AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
            AgentEpqTerminationService.release(stock, "stopped by operator", 6_000L, false);
            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                    eq(stock.sessionId()), eq(false), eq("stopped by operator"), eq(6_000L), isNull()));
            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                    anyString(), anyBoolean(), anyString(), anyLong()), never());
        }
        assertNull(AgentEpqTerminationService.pace(null, 0L), "step 1 reports no pace yet");
    }

    @Test
    void theCompanionsPotionsAreLoggedOnlyForACompanionRun() {
        when(run.actions.hpPotionCount(mira)).thenReturn(12);
        when(run.actions.mpPotionCount(mira)).thenReturn(30);
        when(run.actions.hpPotionCount(tobi)).thenReturn(0);
        when(run.actions.mpPotionCount(tobi)).thenReturn(5);
        when(run.actions.hpPotionCount(una)).thenReturn(40);
        when(run.actions.mpPotionCount(una)).thenReturn(50);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqSession companion = companionRun(AgentEpqSession.Phase.STAGE_ONE);
            assertEquals("{Mira:12/30, Tobi:0/5, Una:40/50}", AgentEpqTerminationService.potions(companion));
            companion.setPotionsAtEntry("{Mira:20/40, Tobi:3/9, Una:40/50}");
            AgentEpqTerminationService.release(companion, "stopped by operator", 5_000L, false);
            assertEquals(List.of("EPQ potions: session=" + companion.sessionId()
                            + " start={Mira:20/40, Tobi:3/9, Una:40/50} end={Mira:12/30, Tobi:0/5, Una:40/50}"),
                    lines.startingWith("EPQ potions:"));

            AgentEpqSession stock = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                    AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
            AgentEpqTerminationService.release(stock, "stopped by operator", 6_000L, false);
            assertEquals(1, lines.startingWith("EPQ potions:").size(), "none for a stock run");
        }
    }

    @Test
    void theReleasedLineSaysHowThePhaseAndTheClearStoodAndWhatWasRescued() {
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqSession companion = companionRun(AgentEpqSession.Phase.REWARD);
            companion.countRescue("respawn-flowers");
            companion.countRescue("respawn-flowers");
            companion.countRescue("kill-dummy");
            AgentEpqTerminationService.release(companion, "EPQ stalled in REWARD for 480 seconds", 5_000L, true);

            when(run.event.isEventCleared()).thenReturn(false);
            AgentEpqSession stock = run.session(AgentEpqSession.Mode.TEST_OBSERVATION, false,
                    AgentEpqSession.Phase.EXITING, ben, mira, tobi, una);
            stock.complete(6_000L);
            AgentEpqTerminationService.release(stock, "completed", 6_000L, false);

            assertEquals(List.of(
                    "EPQ session released: session=" + companion.sessionId()
                            + " outcome=EPQ stalled in REWARD for 480 seconds phase=REWARD members=4"
                            + " companionRun=true pastTheClear=true rescues={respawn-flowers=2, kill-dummy=1}",
                    "EPQ session released: session=" + stock.sessionId()
                            + " outcome=completed phase=COMPLETED members=4"
                            + " companionRun=false pastTheClear=false rescues={}"),
                    lines.startingWith("EPQ session released:"));
        }
    }

    @Test
    void theClearReadsOnlyTheEvent() {
        assertTrue(!AgentEpqTerminationService.pastTheClear(null));
        when(run.event.isEventCleared()).thenReturn(false);
        assertTrue(!AgentEpqTerminationService.pastTheClear(run.event));
        when(run.event.isEventCleared()).thenReturn(true);
        assertTrue(AgentEpqTerminationService.pastTheClear(run.event));
        when(run.event.isEventCleared()).thenThrow(new IllegalStateException("disposed"));
        assertTrue(!AgentEpqTerminationService.pastTheClear(run.event), "a failing read is not a clear");
    }

    @Test
    void exitAgentsOnlyLeavesTheHumansAndOutsidersAndSurvivesAFailingExit() {
        Character stranger = run.companion(9, "Stranger", AgentEpqDefinition.BOSS_MAP);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, stranger, tobi));
        org.mockito.Mockito.doThrow(new IllegalStateException("gone")).when(run.event).exitPlayer(mira);
        AgentEpqSession session = companionRun(AgentEpqSession.Phase.REWARD);

        AgentEpqTerminationService.exitAgentsOnly(session);

        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).exitPlayer(stranger);
        verify(run.event, never()).dispose();
    }

    private AgentEpqSession companionRun(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una);
    }
}
