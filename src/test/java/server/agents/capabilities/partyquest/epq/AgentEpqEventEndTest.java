package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 2: an EPQ run whose stock event ended, or whose leader left it, fails at once before the clear, with
 * one 'EPQ event end detail:' line and the failure "The EPQ event instance ended or expired"; after the clear the check
 * is inert. 930000800 is every stage's return map, so the leader standing there reads as the reward phase only once
 * the bound event reports its clear and the leader is still in it, and an Agent there runs the reward only in the
 * reward phase.
 */
class AgentEpqEventEndTest {
    private static final String ENDED = "The EPQ event instance ended or expired";

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession boss() {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
    }

    @Test
    void aLeaderOutOfTheEventBeforeTheClearFailsTheRunAtOnce() {
        AgentEpqSession session = boss();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // the stock exit map, every stage's return map
        when(ben.getEventInstance()).thenReturn(null);
        when(run.event.getPlayers()).thenReturn(List.of(mira, tobi, una));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            List<String> detail = lines.startingWith("EPQ event end detail:");
            assertEquals(1, detail.size(), lines.startingWith("EPQ ")::toString);
            assertTrue(detail.getFirst().startsWith("EPQ event end detail: session=" + session.sessionId()
                    + " leaderMap=930000800 leaderHp=500 leaderEvent=none eventDisposed=false registered=3"),
                    detail::toString);
            assertEquals(List.of("EPQ session failure: session=" + session.sessionId() + " phase=BOSS reason="
                    + ENDED + " members=4 companionRun=true"), lines.startingWith("EPQ session failure:"));
            assertEquals(List.of(), lines.startingWith("EPQ phase:"), "never the reward phase");
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals(ENDED, session.failure());
    }

    @Test
    void aDisposedEventFailsTheRunAtOnce() {
        AgentEpqSession session = boss();
        when(run.event.isEventDisposed()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            List<String> detail = lines.startingWith("EPQ event end detail:");
            assertEquals(1, detail.size());
            assertTrue(detail.getFirst().contains(" leaderEvent=bound eventDisposed=true "), detail::toString);
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals(ENDED, session.failure());
    }

    @Test
    void aLeaderInAnotherEventIsNamedSo() {
        AgentEpqSession session = boss();
        when(ben.getEventInstance()).thenReturn(org.mockito.Mockito.mock(scripting.event.EventInstanceManager.class));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            List<String> detail = lines.startingWith("EPQ event end detail:");
            assertEquals(1, detail.size());
            assertTrue(detail.getFirst().contains(" leaderEvent=other "), detail::toString);
        }
        assertEquals(ENDED, session.failure());
    }

    @Test
    void afterTheClearALeaderInTheEventOnTheExitMapReadsAsTheReward() {
        AgentEpqSession session = boss();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP);
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
            assertEquals(List.of(), lines.startingWith("EPQ event end detail:"));
        }
        assertEquals(AgentEpqSession.Phase.REWARD, session.phase());
    }

    @Test
    void afterTheClearALeaderWhoLeftFailsNothing() {
        AgentEpqSession session = boss();
        run.onMap(ben, AgentEpqDefinition.RECRUIT_MAP); // out through east00
        when(ben.getEventInstance()).thenReturn(null);
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
            assertEquals(List.of(), lines.startingWith("EPQ event end detail:"));
        }
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
    }

    @Test
    void anUnboundRunFailsNothing() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);
        session.clearEventInstance();
        for (Character member : List.of(ben, mira, tobi, una)) {
            run.onMap(member, AgentEpqDefinition.RECRUIT_MAP);
            when(member.getEventInstance()).thenReturn(null);
        }
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.PREPARING, session.phase());
    }

    @Test
    void anOfflineLeaderTakesOnlyTheWatchdogPath() {
        AgentEpqSession session = boss();
        run.offline(ben);
        when(run.event.isEventDisposed()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ event end detail:"));
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
    }

    @Test
    void beforeTheClearALeaderOnTheExitMapInsideTheEventIsNotTheReward() {
        AgentEpqSession session = boss();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // the stage-4 quit warp: still in the event
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ phase:"));
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
    }

    @Test
    void afterTheClearALeaderOnTheNormalExitMapIsNotTheReward() {
        AgentEpqSession session = boss();
        run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // died after the clear: the normal 930000800
        when(ben.getEventInstance()).thenReturn(null);
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ phase:"));
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
    }

    @Test
    void anAgentOnTheExitMapBeforeTheRewardPhaseOnlyStops() {
        AgentEpqSession session = boss();
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP);
        rewardReactor(); // active: today's reward() would hit it, giving everyone on the map its EXP

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).stop(run.entry(mira));
        verify(run.actions, never()).hitReactor(eq(mira), anyInt());
        verify(run.epq, never()).enterPortal(eq(mira), anyInt());
        assertFalse(session.rewardHit());
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
    }

    @Test
    void anAgentOnTheExitMapInTheRewardPhaseHitsTheRewardAsBefore() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.REWARD, ben, mira, tobi, una);
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP);
        Reactor reward = rewardReactor();
        when(run.actions.hitReactor(mira, reward.getObjectId())).thenReturn(true);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).hitReactor(mira, reward.getObjectId());
        assertTrue(session.rewardHit());
    }

    /**
     * The leader took out00 (warpEventTeam to the instance 930000800) and then east00 to 300030100 before any
     * coordinator pass saw him on 930000800, so the phase never read REWARD (the backward PREPARING is ignored). An
     * Agent the cleared event still holds on 930000800 is at the reward: it hits the reward and takes the exit as
     * before fix 2, instead of idling there until the watchdog fails a cleared run.
     */
    @Test
    void afterTheClearAnAgentTheEventHoldsOnTheExitMapTakesTheRewardThoughThePhaseNeverReadIt() {
        AgentEpqSession session = boss();
        when(run.event.isEventCleared()).thenReturn(true);
        run.onMap(ben, AgentEpqDefinition.RECRUIT_MAP); // out through east00 already
        when(ben.getEventInstance()).thenReturn(null);
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP); // still in the bound event
        Reactor reward = rewardReactor();
        when(run.actions.hitReactor(mira, reward.getObjectId())).thenReturn(true);
        when(run.actions.portalPosition(mira, 1)).thenReturn(new Point(0, 0));
        when(run.epq.enterPortal(mira, 1)).thenReturn(true);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);
        verify(run.actions).hitReactor(mira, reward.getObjectId());
        assertTrue(session.rewardHit());

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L);
        verify(run.epq).enterPortal(mira, 1);
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void afterTheClearAnAgentOnTheNormalExitMapStillOnlyStops() {
        AgentEpqSession session = boss();
        when(run.event.isEventCleared()).thenReturn(true);
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP); // died after the clear: the normal 930000800
        when(mira.getEventInstance()).thenReturn(null);
        rewardReactor();

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.actions).stop(run.entry(mira));
        verify(run.actions, never()).hitReactor(eq(mira), anyInt());
        verify(run.epq, never()).enterPortal(eq(mira), anyInt());
    }

    /**
     * A release (a GM's '!epqtest stop' on another thread) marks the run terminating, exits every player, disposes the
     * event and fails the run only at its end; a lease holder's pass in that window sees no event end and no departure.
     */
    @Test
    void aRunBeingReleasedLogsNoEventEndAndNoDeparture() {
        AgentEpqSession session = boss();
        assertTrue(session.beginTermination());
        run.onMap(tobi, AgentEpqDefinition.REWARD_MAP); // exited before the leader: the normal 930000800
        when(tobi.getEventInstance()).thenReturn(null);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, una));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            run.onMap(ben, AgentEpqDefinition.REWARD_MAP); // then the leader, and the event is disposed
            when(ben.getEventInstance()).thenReturn(null);
            when(run.event.isEventDisposed()).thenReturn(true);
            AgentEpqCoordinator.tickSession(session, 3_000L);

            assertEquals(List.of(), lines.startingWith("EPQ departed:"));
            assertEquals(List.of(), lines.startingWith("EPQ event end detail:"));
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertFalse(session.departed(tobi.getId()));
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase(), "the release fails the run itself, with its reason");
    }

    @Test
    void theDetailNeverThrows() {
        AgentEpqSession session = boss();
        Character broken = org.mockito.Mockito.mock(Character.class);
        when(broken.getMapId()).thenThrow(new IllegalStateException("unloading"));

        assertEquals("detail unavailable", AgentEpqCoordinator.eventEndDetail(session, broken));
    }

    private Reactor rewardReactor() {
        Reactor reward = org.mockito.Mockito.mock(Reactor.class);
        when(reward.isActive()).thenReturn(true);
        when(reward.getObjectId()).thenReturn(77);
        when(reward.getPosition()).thenReturn(new Point(0, 0));
        when(run.map.getReactorById(AgentEpqDefinition.REWARD_REACTOR)).thenReturn(reward);
        return reward;
    }
}
