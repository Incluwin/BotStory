package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.life.Monster;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.9: after the golem a companion run leaves on its own and never fails. The companions leave the dummy alone and
 * never try out00 (the leader's). They wait CLEAR_WAIT_MS for him on the golem's map, then leave through the event's
 * exit; a leader offline, down, out of the event or no longer leading the party is not waited on. The golem's marble is
 * the leader's for GOLEM_MARBLE_LEADER_FIRST_MS, then the work Agent's, and before a leave the companion holding it
 * hands it over at his feet (or drops it where it stands when he cannot take it). The watchdog after the clear leaves
 * instead of failing; EXITING waits for the companions only; a leave with no player left in the event closes it; a run
 * that already ended does nothing more.
 */
class AgentEpqCompanionAfterClearTest {
    private static final int MARBLE = AgentEpqDefinition.GOLEMS_MARBLE;
    private static final Point BEN_SPOT = new Point(300, 150);
    private static final String CLEARED = "we did it! only you can open the way out, Ben. the portal on the right.";
    private static final String CLEARED_DOWN = "we got the golem! sorry you went down, Ben. see you outside.";
    private static final String WAIT_OUTSIDE = "we'll head out and wait for you outside.";

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    AgentEpqCompanionAfterClearTest() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
        when(run.actions.footing(any())).thenReturn(PrimitiveCapabilityGateway.Footing.GROUND);
        when(run.actions.canReach(any(), any(), any())).thenReturn(true);
        when(ben.getPosition()).thenReturn(new Point(BEN_SPOT));
        run.party(ben, mira, tobi, una);
        run.inventory();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    private void tick(AgentEpqSession session, Character member, long nowMs) {
        AgentEpqMemberState state = session.member(member.getId());
        if (state != null) state.deferUntil(0L);
        AgentEpqCoordinator.tickMember(session, run.entry(member), member, nowMs);
    }

    private void tickAgents(AgentEpqSession session, long nowMs) {
        for (Character agent : List.of(mira, tobi, una)) tick(session, agent, nowMs);
    }

    /** The companions are out: the event's exit sent them to the normal 930000800. */
    private void companionsOut() {
        for (Character agent : List.of(mira, tobi, una)) {
            when(agent.getEventInstance()).thenReturn(null);
            run.onMap(agent, AgentEpqDefinition.REWARD_MAP);
        }
    }

    private void said(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    private void neverSaid(String line) {
        verify(run.packets, never()).broadcastChatText(any(), eq(line), anyBoolean(), anyInt());
    }

    @Test
    void theCompanionsLeaveTheDummyAloneTryNoOut00AndTellTheirLeaderOnce() {
        AgentEpqSession session = companionRun();
        Monster dummy = run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        tickAgents(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        tickAgents(session, 20_000L);

        verify(run.map, never()).killMonster(eq(dummy), any(), anyBoolean(), anyShort());
        verify(run.epq, never()).enterPortal(any(), anyInt());
        said(mira, CLEARED);
        assertEquals(Map.of(), session.rescueCounts());
    }

    @Test
    void afterTheWaitTheCompanionsLeaveThroughTheEventsExitAndTheRunCompletes() {
        AgentEpqSession session = companionRun();
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 61_999L);
            verify(run.event, never()).exitPlayer(any());
            neverSaid(WAIT_OUTSIDE);

            AgentEpqCoordinator.tickSession(session, 62_000L);

            said(mira, WAIT_OUTSIDE);
            for (Character agent : List.of(mira, tobi, una)) verify(run.event).exitPlayer(agent);
            verify(run.event, never()).exitPlayer(ben);
            assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=wait leader=Ben marble=none"), lines.startingWith("EPQ after clear:"));

            companionsOut();
            AgentEpqCoordinator.tickSession(session, 63_000L);

            assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        verify(run.event, never()).dispose();
    }

    @Test
    void anOfflineLeaderIsNotWaitedOnAndTheEmptyEventIsClosed() {
        AgentEpqSession session = companionRun();
        run.offline(ben);
        when(run.event.getPlayers()).thenReturn(List.of(mira, tobi, una), List.of());
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            for (Character agent : List.of(mira, tobi, una)) verify(run.event).exitPlayer(agent);
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=offline leader=1 marble=none"), lines.startingWith("EPQ after clear:"));
            verify(run.event, times(1)).dispose();

            companionsOut();
            AgentEpqCoordinator.tickSession(session, 3_000L);

            assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase(), "Ben never came back");
            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        neverSaid(WAIT_OUTSIDE);
    }

    @Test
    void aLeaderDownAfterTheClearHearsTheDownLineAndIsNotWaitedOn() {
        AgentEpqSession session = companionRun();
        when(ben.getHp()).thenReturn(0);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=down").size());
        }
        said(mira, CLEARED_DOWN);
        neverSaid(CLEARED);
        neverSaid(WAIT_OUTSIDE);
        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).dispose(); // he is still registered until he presses OK
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aLeaderOutOfTheEventIsNotWaitedOn() {
        AgentEpqSession session = companionRun();
        when(ben.getEventInstance()).thenReturn(null);
        run.onMap(ben, AgentEpqDefinition.RECRUIT_MAP); // out through east00
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=left").size());
        }
        verify(run.event).exitPlayer(mira);
    }

    @Test
    void aLeaderNoLongerLeadingTheLivePartyIsNotWaitedOn() {
        AgentEpqSession session = companionRun();
        run.party(mira, ben, tobi, una); // the lead passed to Mira
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=lead").size());
        }
        verify(run.event).exitPlayer(mira);
    }

    @Test
    void theGolemsMarbleIsTheLeadersFirstThenTheWorkAgentsAndNeverAnotherCompanions() {
        AgentEpqSession session = companionRun();
        MapItem marble = run.drop(MARBLE, 1, new Point(0, 150), false, 0, 10_000L);

        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 24_999L), "the leader's first claim");
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 25_000L), "the work Agent after it");
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, marble, 25_000L));

        AgentEpqCoordinator.tickSession(session, 11_000L);
        said(mira, "the golem dropped a marble, Ben! grab it, it goes in the fountain outside.");

        tick(session, mira, 24_999L);
        verify(run.actions, never()).navigate(any(), eq(new Point(0, 150)), anyBoolean());
        tick(session, mira, 25_000L);
        verify(run.actions).navigate(run.entry(mira), new Point(0, 150), true);

        run.pickUp(marble);
        run.holds(mira, MARBLE, 1);
        AgentEpqCoordinator.tickSession(session, 26_000L);
        said(mira, "got the golem's marble. i'll drop it in the fountain outside.");
    }

    @Test
    void theWatchdogAfterTheClearLeavesAndNeverFails() {
        AgentEpqSession session = companionRun();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // he took out00, then stood still
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 2_000L + 480_000L);

            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=stall").size());
            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    /**
     * Review A: companions the event's exit left in the event (an exit that threw, or one that returned early for a
     * companion not logged in) never hold the run for good: the next stall after their leave completes it, and it is
     * no failure.
     */
    @Test
    void aStallAfterTheCompanionsLeftCompletesTheRun() {
        AgentEpqSession session = companionRun();
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 62_000L); // the leave; the exit leaves all three in the event
            assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
            assertTrue(session.leftAfterClear());

            AgentEpqCoordinator.tickSession(session, 62_000L + 480_000L - 1L);
            assertEquals(AgentEpqSession.Phase.EXITING, session.phase(), "1 ms before the stall");

            AgentEpqCoordinator.tickSession(session, 62_000L + 480_000L);

            assertEquals(List.of("EPQ after clear: session=" + session.sessionId() + " action=complete why=stall"),
                    lines.startingWith("EPQ after clear: session=" + session.sessionId() + " action=complete"));
            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void exitingCompletesOnceTheCompanionsAreOutWhileTheLeaderIsStillInTheEvent() {
        AgentEpqSession session = companionRun();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // in the event, at the fountain
        AgentEpqCoordinator.transition(session, AgentEpqSession.Phase.EXITING, 2_000L);
        companionsOut();

        AgentEpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void theMarbleHolderHandsItOverAtHisFeetBeforeTheCompanionsLeave() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        when(mira.getPosition()).thenReturn(new Point(0, 150));
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L); // the wait ends: the leave is decided
        verify(run.event, never()).exitPlayer(any());

        tick(session, mira, 62_000L);
        verify(run.actions).navigate(run.entry(mira), BEN_SPOT, true);
        when(mira.getPosition()).thenReturn(new Point(BEN_SPOT));
        when(run.actions.predictDropLanding(eq(mira), any())).thenReturn(new Point(BEN_SPOT));
        tick(session, mira, 63_000L);

        verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        said(mira, "Ben, the golem's marble is at your feet. drop it by the fountain outside.");
        verify(run.event, never()).exitPlayer(any());

        run.holds(mira, MARBLE, 0);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 64_000L);
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=wait leader=Ben marble=handed"), lines.startingWith("EPQ after clear:"));
        }
        verify(run.event).exitPlayer(mira);
    }

    @Test
    void withTheLeaderOfflineTheHolderDropsTheMarbleWhereItStandsAndTheyLeave() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        run.offline(ben);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        verify(run.event, never()).exitPlayer(any());

        tick(session, mira, 2_000L);
        verify(run.inventory()).dropItem(mira, InventoryType.ETC, (short) 1, (short) 1);
        verify(run.packets, never()).broadcastChatText(eq(mira), any(), anyBoolean(), anyInt());

        run.holds(mira, MARBLE, 0);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 3_000L);
            assertEquals(List.of("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=offline leader=1 marble=dropped"), lines.startingWith("EPQ after clear:"));
        }
    }

    @Test
    void theLeaveNeverWaitsPastTheHandOversTimeout() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1); // she never gets to drop it
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L + AgentEpqHandOver.TIMEOUT_MS - 1L);
        verify(run.event, never()).exitPlayer(any());

        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 62_000L + AgentEpqHandOver.TIMEOUT_MS);
            assertEquals(1, lines.startingWith("EPQ after clear: session=" + session.sessionId()
                    + " action=leave why=wait leader=Ben marble=held").size());
        }
    }

    @Test
    void aLeaderWhoTakesOut00InTimeCancelsTheLeave() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L);
        assertTrue(session.leaveDecidedAtMs() > 0L);

        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // out00: the whole event goes to the fountain
        AgentEpqCoordinator.tickSession(session, 62_500L);

        assertEquals(0L, session.leaveDecidedAtMs());
        assertEquals(AgentEpqSession.Phase.REWARD, session.phase());
        verify(run.event, never()).exitPlayer(any());
    }

    @Test
    void pinAStockRunStillKillsTheDummyAndWaitsForEveryoneAtTheExit() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.BOSS, ben, mira, tobi, una);
        Monster dummy = run.monster(AgentEpqDefinition.POST_DEATH_DUMMY);

        tick(session, mira, 2_000L);

        verify(run.map).killMonster(dummy, null, false, (short) 0);
        assertEquals(Map.of("kill-dummy", 1), session.rescueCounts());

        run.onMap(ben, AgentEpqDefinition.REWARD_MAP);
        AgentEpqCoordinator.transition(session, AgentEpqSession.Phase.EXITING, 3_000L);
        companionsOut();
        AgentEpqCoordinator.tickSession(session, 4_000L);
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase(), "Ben is still in the event");
    }

    @Test
    void aRunThatAlreadyEndedDoesNothingMoreAfterTheClear() {
        AgentEpqSession session = companionRun();
        run.holds(mira, MARBLE, 1);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 62_000L); // leave decided, waiting on the marble
        session.fail("stopped by operator", 62_100L); // another thread ended it

        try (EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            tick(session, mira, 62_200L);
            AgentEpqCoordinator.tickSession(session, 62_000L + AgentEpqHandOver.TIMEOUT_MS);

            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        verify(run.inventory(), never()).dropItem(any(), any(), anyShort(), anyShort());
        verify(run.event, never()).exitPlayer(any());
        neverSaid(WAIT_OUTSIDE);
        assertEquals("stopped by operator", session.failure());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void pinACompanionRunThatHasNotEndedActs() {
        AgentEpqSession session = companionRun();
        AgentEpqCoordinator.tickSession(session, 2_000L);
        said(mira, CLEARED);
    }
}
