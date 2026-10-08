package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.ADOPTED;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.CASSE;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.CREW;

/**
 * "!lmpqtest party prod [9|16] [seed]": a GM6 companion owner adopts his own party at Rolly as a companion run through
 * the public admit, with no fixture Agent spawned, loaded or joined; the run answers status and stop, a stop never
 * closes the engagement, logs a companion out or breaks up his party, and his own fixture start and spectate refuse
 * while it is live.
 */
class AgentLmpqTestServicePartyProdTest {
    private final LmpqGmFixture gm = new LmpqGmFixture();

    @AfterEach
    void tearDown() {
        gm.close();
    }

    private void verifyNoPartyChange() {
        verify(gm.party, never()).createAgentParty(any());
        verify(gm.party, never()).leaveCurrentParty(any());
        verify(gm.party, never()).invitePartyMember(any(), any());
    }

    private void verifyNobodyDropped() {
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verify(gm.characters, never()).disconnect(any(), anyBoolean(), anyBoolean());
        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        List<String> reply = gm.lmpqtest(gm.crew, 2_000L, "party", "prod", "16", "7");

        assertEquals(List.of(ADOPTED), reply);
        gm.admission.verify(() -> AgentLmpqAdmissionService.admit(
                gm.crew, gm.crew, List.of(gm.crew, gm.lioren, gm.casse, gm.pell), 7L, 2_000L));
        assertTrue(gm.session.memberCoordinationChatEnabled());
        assertEquals(AgentLmpqDefinition.CLEAR_ROOM, gm.session.rendezvousRoom());
        // His own companions only: nothing is spawned, loaded, invited, joined or scheduled.
        gm.interaction.verifyNoInteractions();
        gm.scheduler.verifyNoInteractions();
        verifyNoPartyChange();

        List<String> status = gm.lmpqtest(gm.crew, 3_000L, "status");
        assertTrue(status.getFirst().startsWith("LMPQ phase=PREPARING, rendezvous=16, rooms="), status::toString);
        assertEquals(List.of("Crewfriend: initial=0, route=[], assigned=[], target=0",
                        "Lioren: initial=0, route=[], assigned=[], target=0",
                        "Casse: initial=0, route=[], assigned=[], target=0",
                        "Pell: initial=0, route=[], assigned=[], target=0"), status.subList(1, status.size()));

        assertEquals(List.of("LMPQ test stopped safely; final routes were written to the server log."),
                gm.lmpqtest(gm.crew, 4_000L, "stop"));
        assertEquals(List.of("No LMPQ test is active."), gm.lmpqtest(gm.crew, 5_000L, "status"));
    }

    @Test
    void theOptionsComeInStartsOrder() {
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "adopt", "prod"));
        gm.admission.verify(() -> AgentLmpqAdmissionService.admit(
                gm.crew, gm.crew, List.of(gm.crew, gm.lioren, gm.casse, gm.pell), 2_000L, 2_000L));
        assertEquals(AgentLmpqDefinition.RENDEZVOUS_ROOM, gm.session.rendezvousRoom(), "the default stays");
        gm.lmpqtest(gm.crew, 2_500L, "stop");

        assertEquals(List.of("LMPQ test command failed: rendezvous room must be 9 or 16"),
                gm.lmpqtest(gm.crew, 3_000L, "party", "prod", "10"));
        assertEquals(new AgentLmpqTestService.AdoptOptions(true, 9, 11L),
                AgentLmpqTestService.adoptOptions(new String[]{"party", "prod", "9", "11"}, 1L));
        assertEquals(new AgentLmpqTestService.AdoptOptions(true, 16, 1L),
                AgentLmpqTestService.adoptOptions(new String[]{"party", "prod", "16"}, 1L));
        assertEquals(new AgentLmpqTestService.AdoptOptions(true, 0, 1L),
                AgentLmpqTestService.adoptOptions(new String[]{"party", "PROD"}, 1L));
        assertEquals(false, AgentLmpqTestService.adoptOptions(new String[]{"party", "16"}, 1L).production());
    }

    @Test
    void everyRefusalInItsOwnWords() {
        assertEquals(List.of("LMPQ adopts a party only as a companion run: !lmpqtest party prod [9|16] [seed]."),
                gm.lmpqtest(gm.crew, 2_000L, "party", "16"));
        assertEquals(List.of("LMPQ adopts a party only as a companion run: !lmpqtest party prod [9|16] [seed]."),
                gm.lmpqtest(gm.crew, 2_000L, "party"));

        when(gm.crew.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        assertEquals(List.of("Stand at the Ludibrium Maze entrance (220000000) before adopting a party."),
                gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.crew.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);

        when(gm.party.snapshot(gm.crew)).thenReturn(null);
        assertEquals(List.of("Create the desired party before using !lmpqtest party prod."),
                gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));

        gm.partyLedBy(CREW);
        when(gm.party.onlineMembers(gm.crew)).thenReturn(List.of(gm.crew, gm.lioren, gm.casse));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.party.onlineMembers(gm.crew)).thenReturn(List.of(gm.crew, gm.lioren, gm.casse, gm.pell));

        gm.partyLedBy(CASSE);
        assertEquals(List.of(
                        "Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));

        gm.admission.verify(() -> AgentLmpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()),
                never());
        assertEquals(List.of("No LMPQ test is active."), gm.lmpqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void aRefusedAdmitKeepsNoRun() {
        gm.admission.when(() -> AgentLmpqAdmissionService.admit(
                        eq(gm.crew), eq(gm.crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentLmpqAdmissionService.AdmissionResult(
                        false, "Pell needs a free ETC slot", null, null));

        assertEquals(List.of("Could not adopt the party: Pell needs a free ETC slot"),
                gm.lmpqtest(gm.crew, 2_000L, "party", "prod", "9", "7"));
        assertEquals(List.of("No LMPQ test is active."), gm.lmpqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void theAdoptIsSerialized() throws NoSuchMethodException {
        Method adopt = AgentLmpqTestService.class.getDeclaredMethod("adoptCurrentParty", Character.class,
                AgentLmpqTestService.AdoptOptions.class, long.class);
        assertTrue(Modifier.isSynchronized(adopt.getModifiers()));
        assertTrue(Modifier.isStatic(adopt.getModifiers()));
    }

    @Test
    void theWaitIsTheCoordinatorsIdleLimit() {
        try (MockedStatic<AgentLmpqCoordinator> coordinator = mockStatic(AgentLmpqCoordinator.class,
                CALLS_REAL_METHODS)) {
            coordinator.when(AgentLmpqCoordinator::humanIdleLimitMs).thenReturn(60_000L);
            assertEquals(List.of("Adopted as a companion run. Talk to Rolly now; the run waits up to 1 minute for you."),
                    gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        }
        assertEquals(240_000L, AgentLmpqCoordinator.humanIdleLimitMs());
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndAFinishedOneMakesWay() {
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod", "9", "7"));
        assertEquals(List.of("Stop the current LMPQ test before adopting another party."),
                gm.lmpqtest(gm.crew, 2_100L, "party", "prod", "9", "7"));

        gm.session.fail("LMPQ stalled in FARMING", 3_000L); // released: its engagement is not registered here

        // (The mocked admit hands back the same, now failed, session: no rendezvous is set on it.)
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 4_000L, "party", "prod"));
        gm.admission.verify(() -> AgentLmpqAdmissionService.admit(eq(gm.crew), eq(gm.crew), anyList(), anyLong(),
                anyLong()), times(2));
        verifyNobodyDropped();
    }

    @Test
    void aFinishedRunWhoseEngagementStillRecoversIsKeptForTheStopThatRecoversIt() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        gm.session.fail("LMPQ stalled in FARMING", 3_000L);
        gm.engagement.beginRecovery("LMPQ stalled in FARMING", 3_000L); // a handback still deferred

        assertEquals(List.of("Stop the current LMPQ test before adopting another party."),
                gm.lmpqtest(gm.crew, 4_000L, "party", "prod"));
        assertEquals(List.of("Stop the current LMPQ companion run first."),
                gm.lmpqtest(gm.crew, 4_100L, "start", "3"));
        gm.lmpqtest(gm.crew, 5_000L, "stop");

        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(gm.engagement, 5_000L));
        verifyNobodyDropped();
    }

    @Test
    void aFinishedRunWhoseEngagementClosedMakesWay() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        gm.session.fail("LMPQ stalled in FARMING", 3_000L);
        gm.engagement.close(3_500L);

        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 4_000L, "party", "prod"));
    }

    @Test
    void stopReleasesTheLiveSessionAsACompanionRunAndKeepsTheParty() {
        when(gm.party.hasParty(any())).thenReturn(true);
        AgentLmpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));

        gm.lmpqtest(gm.crew, 3_000L, "stop");

        assertEquals(AgentLmpqSession.Phase.FAILED, gm.session.phase());
        assertEquals("stopped by operator", gm.session.failure());
        assertNull(AgentLmpqSessionRegistry.forOperator(CREW));
        verifyNoPartyChange();
        verifyNobodyDropped();
    }

    @Test
    void stopBeforeTheClearTakesEveryoneOutAndDisposesTheChannelsOnlyLobby() {
        AgentLmpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        gm.inside(AgentLmpqDefinition.mapForRoom(5));

        gm.lmpqtest(gm.crew, 3_000L, "stop");

        for (Character member : List.of(gm.crew, gm.lioren, gm.casse, gm.pell)) verify(gm.event).exitPlayer(member);
        verify(gm.event).dispose();
        verifyNoPartyChange();
        verifyNobodyDropped();
    }

    @Test
    void stopAfterTheClearTakesOnlyTheCompanionsOutAndKeepsTheEvent() {
        AgentLmpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        gm.inside(AgentLmpqDefinition.REWARD_MAP);
        when(gm.event.isEventCleared()).thenReturn(true);

        gm.lmpqtest(gm.crew, 3_000L, "stop");

        for (Character companion : List.of(gm.lioren, gm.casse, gm.pell)) verify(gm.event).exitPlayer(companion);
        verify(gm.event, never()).exitPlayer(gm.crew);
        verify(gm.event, never()).dispose();
        verifyNoPartyChange();
        verifyNobodyDropped();
    }

    @Test
    void stopWithTheSessionGoneRecoversTheEngagementInsteadOfClosingIt() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));

        gm.lmpqtest(gm.crew, 3_000L, "stop");

        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(gm.engagement, 3_000L));
        assertEquals(AgentPartyQuestEngagement.State.RECOVERING, gm.engagement.state());
        verifyNobodyDropped();
        verifyNoPartyChange();
    }

    @Test
    void hisFixtureStartAndSpectateRefuseWhileHisCompanionRunIsLive() {
        AgentLmpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.lmpqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.crew.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(4)); // he walked into the maze

        assertEquals(List.of("Stop the current LMPQ companion run first."),
                gm.lmpqtest(gm.crew, 3_000L, "start", "3"));
        assertEquals(List.of("A companion run's leader walks the maze himself."),
                gm.lmpqtest(gm.crew, 3_000L, "spectate", "Lioren"));
        assertTrue(gm.lmpqtest(gm.crew, 3_000L, "status").getFirst().startsWith("LMPQ phase=PREPARING"));
        gm.interaction.verifyNoInteractions();
        gm.scheduler.verifyNoInteractions();
    }

    @Test
    void helpListsTheNewCommand() {
        List<String> help = gm.lmpqtest(gm.crew, 2_000L);
        assertTrue(help.contains("!lmpqtest party prod [9|16] [seed] (adopt your own party as a companion run; you lead)"),
                help::toString);
    }
}
