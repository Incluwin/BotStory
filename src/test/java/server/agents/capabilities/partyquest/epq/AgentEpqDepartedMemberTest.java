package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EPQ base fix 3: a member the stock event no longer holds has left the run; it does not fail it. EllinPQ.js sends
 * every leaver to the normal 930000800 (every EPQ map's return map) with no event instance, so that is the leaver's
 * signature; a member never registered still stands on the recruit map and is not a leaver. A departed member idles,
 * holds no work role and counts as out when the run exits; seen back in the event, it is a member again.
 */
class AgentEpqDepartedMemberTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);
    private final Character pia = run.companion(5, "Pia", AgentEpqDefinition.BOSS_MAP);
    private final Character cleo = run.human(6, "Cleo", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(AgentEpqSession.Phase phase) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, tobi, una, pia, cleo);
    }

    /** The stock departure: unregistered, standing on the normal 930000800. */
    private void leaves(Character member) {
        run.onMap(member, AgentEpqDefinition.REWARD_MAP);
        when(member.getEventInstance()).thenReturn(null);
    }

    private void registered(Character... members) {
        when(run.event.getPlayers()).thenReturn(List.of(members));
    }

    @Test
    void aMemberTheEventNoLongerHoldsLeavesTheRunWithoutFailingIt() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        leaves(tobi);
        registered(ben, mira, una, pia, cleo);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 3_000L);

            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
            assertEquals(List.of("EPQ departed: session=" + session.sessionId()
                            + " member=Tobi(3) type=AGENT map=930000800 phase=BOSS registered=5 companionRun=true"),
                    lines.startingWith("EPQ departed:"));
        }
        assertEquals(AgentEpqSession.Phase.BOSS, session.phase());
        assertTrue(session.departed(tobi.getId()));
        verify(run.actions, org.mockito.Mockito.atLeastOnce()).stop(run.entry(tobi));
    }

    @Test
    void aDepartedAgentNeverRunsTheReward() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.REWARD, ben, tobi, una, pia, cleo);
        session.markRewardHit(1_500L); // the reward is hit: an Agent there would take the exit now
        leaves(tobi);
        Reactor reward = mock(Reactor.class);
        when(reward.isActive()).thenReturn(true);
        when(reward.getPosition()).thenReturn(new Point(0, 0));
        when(run.map.getReactorById(AgentEpqDefinition.REWARD_REACTOR)).thenReturn(reward);
        when(run.actions.portalPosition(tobi, 1)).thenReturn(new Point(0, 0));

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        verify(run.actions, never()).hitReactor(eq(tobi), anyInt());
        verify(run.epq, never()).enterPortal(eq(tobi), anyInt());
    }

    @Test
    void theRunCompletesWithADepartedMemberOnTheNormalExitMap() {
        AgentEpqSession session = session(AgentEpqSession.Phase.EXITING);
        when(run.event.isEventCleared()).thenReturn(true);
        for (Character member : List.of(ben, mira, una, pia, cleo)) {
            run.onMap(member, AgentEpqDefinition.RECRUIT_MAP); // out through east00
            when(member.getEventInstance()).thenReturn(null);
        }
        leaves(tobi);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void aMemberStillInTheEventOnTheExitMapKeepsTheRunExiting() {
        AgentEpqSession session = session(AgentEpqSession.Phase.EXITING);
        when(run.event.isEventCleared()).thenReturn(true);
        for (Character member : List.of(ben, mira, una, pia, cleo)) {
            run.onMap(member, AgentEpqDefinition.RECRUIT_MAP);
            when(member.getEventInstance()).thenReturn(null);
        }
        run.onMap(tobi, AgentEpqDefinition.REWARD_MAP); // still in the bound event

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.EXITING, session.phase());
    }

    @Test
    void aDepartedHumanIsSeenByTheCoordinatorOnce() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        leaves(cleo); // pressed OK at the floor's count
        registered(ben, mira, tobi, una, pia);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 3_000L);

            assertEquals(List.of("EPQ departed: session=" + session.sessionId()
                            + " member=Cleo(6) type=HUMAN map=930000800 phase=BOSS registered=5 companionRun=true"),
                    lines.startingWith("EPQ departed:"));
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));
        }
        assertTrue(session.departed(cleo.getId()));
    }

    @Test
    void theWorkAgentIsTheLowestAgentThatHasNotLeft() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        assertEquals(mira.getId(), session.workAgentId());
        leaves(mira);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertTrue(session.departed(mira.getId()));
        assertEquals(tobi.getId(), session.workAgentId());
    }

    @Test
    void anAgentEventLeaderStaysTheWorkAgent() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.TEST_OBSERVATION, false,
                AgentEpqSession.Phase.BOSS, una, mira, tobi, pia);
        session.markDeparted(una.getId());
        assertEquals(una.getId(), session.workAgentId());
    }

    @Test
    void withEveryAgentGoneTheWorkAgentIsTodaysChoice() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        for (Character agent : List.of(mira, tobi, una, pia)) session.markDeparted(agent.getId());

        assertEquals(mira.getId(), session.workAgentId());
    }

    @Test
    void aBottleHolderLeavingStageTwoReopensTheBottles() {
        for (Character member : List.of(ben, mira, tobi, una, pia, cleo)) {
            run.onMap(member, AgentEpqDefinition.STAGE_TWO_MAP);
        }
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_TWO);
        Reactor spine = reactor(new Point(-400, 0), (byte) 3);
        Reactor pond = reactor(new Point(400, 0), (byte) 0);
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        when(run.actions.portalPosition(any(), eq(3))).thenReturn(new Point(900, 0));
        session.secureStageTwoBottles(1_500L);
        assertTrue(session.claimAnnouncement("stage2-bottles-secured")); // said when they were secured
        leaves(tobi); // held the fourth bottle; the stock exit deleted it

        AgentEpqCoordinator.tickSession(session, 2_000L);
        assertFalse(session.stageTwoBottlesSecured(), "three bottles are not the thorns' four");

        try (EpqLogLines lines = new EpqLogLines(AgentEpqDialogue.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(una), una, 3_000L); // three: keep working
            assertFalse(session.stageTwoBottlesSecured());
            verify(run.actions, never()).navigate(eq(run.entry(una)), eq(new Point(900, 0)), anyBoolean());

            when(run.actions.itemCount(pia, AgentEpqDefinition.PURIFIED_POISON)).thenReturn(1);
            AgentEpqCoordinator.tickMember(session, run.entry(una), una, 5_000L); // four again
            assertTrue(session.stageTwoBottlesSecured());
            verify(run.actions).navigate(run.entry(una), new Point(900, 0), true);
            assertEquals(List.of(), lines.startingWith("EPQ line: session=" + session.sessionId()
                    + " key=stage2-bottles-secured"), "the secured line is said once per run");
        }
    }

    /**
     * A departed Agent is online and alive (about 50 HP, idling on the normal 930000800), so only its departure keeps
     * it from one of the two lurer slots; holding one, it would leave one side's Stone Bugs unlured.
     */
    @Test
    void aDepartedAgentTakesNoStageTwoLurerSlot() {
        for (Character member : List.of(ben, mira, tobi, una, pia, cleo)) {
            run.onMap(member, AgentEpqDefinition.STAGE_TWO_MAP);
        }
        AgentEpqSession session = session(AgentEpqSession.Phase.STAGE_TWO);
        Reactor spine = reactor(new Point(-400, 0), (byte) 0);
        Reactor pond = reactor(new Point(400, 0), (byte) 0);
        when(run.map.getReactorById(AgentEpqDefinition.SPINE_REACTOR)).thenReturn(spine);
        when(run.map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        leaves(tobi); // the weakest non-carrier Agent by today's order: the first lurer
        registered(ben, mira, una, pia, cleo);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        assertTrue(session.departed(tobi.getId()));
        assertTrue(tobi.getHp() > 0, "a departed Agent is alive");

        AgentEpqCoordinator.tickMember(session, run.entry(pia), pia, 3_000L);

        verify(run.map).instanceMapForceRespawn(); // Pia lures in Tobi's place: no bugs up, so she calls them
        verify(run.actions, never()).navigate(eq(run.entry(pia)), any(), anyBoolean());
    }

    @Test
    void anEndedEventOnlyStopsTheAgent() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        leaves(tobi);
        when(run.event.isEventDisposed()).thenReturn(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ departed:"));
        }
        verify(run.actions).stop(run.entry(tobi));
        assertFalse(session.departed(tobi.getId()));
    }

    @Test
    void aLeaderOutOfTheEventMarksNoDeparture() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        leaves(tobi);
        when(ben.getEventInstance()).thenReturn(null);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ departed:"));
        }
        assertFalse(session.departed(tobi.getId()));
    }

    @Test
    void aMemberInAnotherEventInstanceStillFailsTheRun() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        when(tobi.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals("EPQ member entered a different event instance", session.failure());
    }

    @Test
    void anAgentNotYetRegisteredOnTheRecruitMapIsNoLeaver() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.ENTERING, ben, mira, tobi, una, pia, cleo);
        for (Character member : List.of(ben, tobi, una, pia, cleo)) run.onMap(member, AgentEpqDefinition.ENTRANCE_MAP);
        run.onMap(mira, AgentEpqDefinition.RECRUIT_MAP); // registerParty has not reached her yet
        when(mira.getEventInstance()).thenReturn(null);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

            assertEquals(List.of(), lines.startingWith("EPQ departed:"));
        }
        assertFalse(session.departed(mira.getId()));
        verify(run.party).sendPartyChat(eq(mira), anyString()); // today's lobby path ran

        run.onMap(mira, AgentEpqDefinition.ENTRANCE_MAP);
        when(mira.getEventInstance()).thenReturn(run.event);
        AgentEpqCoordinator.tickSession(session, 3_000L);
        assertEquals(mira.getId(), session.workAgentId());
    }

    @Test
    void aDepartedMemberBackInTheEventIsAMemberAgain() {
        AgentEpqSession session = session(AgentEpqSession.Phase.BOSS);
        leaves(mira);
        AgentEpqCoordinator.tickSession(session, 2_000L);
        assertEquals(tobi.getId(), session.workAgentId());

        run.onMap(mira, AgentEpqDefinition.BOSS_MAP); // a GM warp re-registers her
        when(mira.getEventInstance()).thenReturn(run.event);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 3_000L);
            AgentEpqCoordinator.tickSession(session, 4_000L);

            assertEquals(List.of("EPQ returned: session=" + session.sessionId()
                    + " member=Mira(2) map=930000600 phase=BOSS"), lines.startingWith("EPQ returned:"));
        }
        assertFalse(session.departed(mira.getId()));
        assertEquals(mira.getId(), session.workAgentId());
    }

    private static Reactor reactor(Point position, byte state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(position);
        when(reactor.getState()).thenReturn(state);
        when(reactor.getArea()).thenReturn(new Rectangle(position.x - 30, position.y - 30, 60, 60));
        return reactor;
    }
}
