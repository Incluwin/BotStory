package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.opq.OpqGmFixture.ADOPTED;
import static server.agents.capabilities.partyquest.opq.OpqGmFixture.CASSE;
import static server.agents.capabilities.partyquest.opq.OpqGmFixture.CREW;

/**
 * P6h OPQ step 1 (C7), "!opqtest party prod [seed]": a GM6 companion owner adopts his own party at Wonky as a
 * companion run through the public admit, with no fixture Agent spawned, loaded or joined; the run answers status and
 * stop, a stop never logs a companion out or breaks up his party, and his own fixture start waits for that stop.
 */
class AgentOpqTestServicePartyProdTest {
    private final OpqGmFixture gm = new OpqGmFixture();

    @AfterEach
    void tearDown() {
        gm.close();
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        List<String> reply = gm.opqtest(gm.crew, 2_000L, "party", "prod", "7");

        assertEquals(List.of(ADOPTED), reply);
        gm.admission.verify(() -> AgentOpqAdmissionService.admit(gm.crew, gm.crew, gm.party, 7L, 2_000L));
        assertTrue(gm.session.memberCoordinationChatEnabled());
        // His own companions only: nothing is spawned, loaded, invited, joined or scheduled.
        gm.interaction.verifyNoInteractions();
        gm.scheduler.verifyNoInteractions();
        verifyNoPartyChange();

        List<String> status = gm.opqtest(gm.crew, 3_000L, "status");
        assertEquals("OPQ phase: PREPARING", status.getFirst(), status::toString);
        assertTrue(status.contains("member Crewfriend: map=200080101 role=IDLE room=null"), status::toString);
        assertTrue(status.contains("member Dorran: map=200080101 role=IDLE room=null"), status::toString);

        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 4_000L, "stop"));
        assertEquals(List.of("No OPQ test is active."), gm.opqtest(gm.crew, 5_000L, "status"));
    }

    @Test
    void theAdoptAliasAndTheOptionalSeed() {
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "adopt", "prod"));
        gm.admission.verify(() -> AgentOpqAdmissionService.admit(gm.crew, gm.crew, gm.party, 2_000L, 2_000L));
    }

    @Test
    void theWaitIsTheCoordinatorsPhaseTimeoutInMinutes() {
        assertEquals(720_000L, AgentOpqCoordinator.phaseTimeoutMs());
        assertTrue(ADOPTED.contains("waits up to 12 minutes"));
    }

    @Test
    void everyRefusalInItsOwnWords() {
        assertEquals(List.of("OPQ adopts a party only as a companion run: !opqtest party prod [seed]."),
                gm.opqtest(gm.crew, 2_000L, "party", "7"));
        assertEquals(List.of("OPQ adopts a party only as a companion run: !opqtest party prod [seed]."),
                gm.opqtest(gm.crew, 2_000L, "party"));

        when(gm.crew.getMapId()).thenReturn(AgentOpqDefinition.STAGING_MAP);
        assertEquals(List.of("Stand at the Unknown Tower entrance (200080101) before adopting a party."),
                gm.opqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.crew.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);

        when(gm.partyGateway.snapshot(gm.crew)).thenReturn(null);
        assertEquals(List.of("Create the desired party before using !opqtest party prod."),
                gm.opqtest(gm.crew, 2_000L, "party", "prod"));

        gm.partyLedBy(CREW);
        when(gm.partyGateway.onlineMembers(gm.crew)).thenReturn(gm.party.subList(0, 5));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                gm.opqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.partyGateway.onlineMembers(gm.crew)).thenReturn(gm.party);

        gm.partyLedBy(CASSE);
        assertEquals(List.of(
                        "Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                gm.opqtest(gm.crew, 2_000L, "party", "prod"));

        gm.admission.verify(() -> AgentOpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()),
                never());
        assertEquals(List.of("No OPQ test is active."), gm.opqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void aRefusedAdmitKeepsNoRun() {
        gm.admission.when(() -> AgentOpqAdmissionService.admit(
                        eq(gm.crew), eq(gm.crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentOpqAdmissionService.AdmissionResult(
                        false, "Dorran needs eight free ETC slots", null, null));

        assertEquals(List.of("Could not adopt the party: Dorran needs eight free ETC slots"),
                gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        assertEquals(List.of("No OPQ test is active."), gm.opqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void adoptionIsSynchronized() throws NoSuchMethodException {
        Method adopt = null;
        for (Method method : AgentOpqTestService.class.getDeclaredMethods()) {
            if (method.getName().equals("adoptCurrentParty")) adopt = method;
        }
        assertTrue(adopt != null && Modifier.isSynchronized(adopt.getModifiers()));
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndAFinishedOneMakesWay() {
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        assertEquals(List.of("Stop the current OPQ test before adopting another party."),
                gm.opqtest(gm.crew, 2_100L, "party", "prod", "7"));

        gm.session.fail("No legal OPQ progress before timeout", 3_000L);

        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 4_000L, "party", "prod", "7"));
        gm.admission.verify(() -> AgentOpqAdmissionService.admit(eq(gm.crew), eq(gm.crew), anyList(), anyLong(),
                anyLong()), times(2));
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
    }

    @Test
    void aFinishedRunWhoseEngagementStillRecoversIsKeptForTheStopThatRecoversIt() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        // Released, but the handback is deferred: a companion is still in the event or down.
        gm.session.fail("No legal OPQ progress before timeout", 3_000L);
        gm.engagement.beginRecovery("No legal OPQ progress before timeout", 3_000L);

        assertEquals(List.of("Stop the current OPQ test before adopting another party."),
                gm.opqtest(gm.crew, 4_000L, "party", "prod", "7"));
        assertEquals(List.of("Stop the current OPQ companion run first."),
                gm.opqtest(gm.crew, System.currentTimeMillis(), "start", "11"));
        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 5_000L, "stop"));

        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(gm.engagement, 5_000L));
        gm.admission.verify(() -> AgentOpqAdmissionService.admit(eq(gm.crew), eq(gm.crew), anyList(), anyLong(),
                anyLong()), times(1));
        gm.scheduler.verifyNoInteractions();
    }

    @Test
    void aFinishedRunWhoseEngagementClosedMakesWay() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.session.fail("No legal OPQ progress before timeout", 3_000L);
        gm.engagement.close(3_500L);

        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 4_000L, "party", "prod", "7"));
        gm.admission.verify(() -> AgentOpqAdmissionService.admit(eq(gm.crew), eq(gm.crew), anyList(), anyLong(),
                anyLong()), times(2));
    }

    @Test
    void stopEndsTheLiveSessionAsACompanionRunAndKeepsTheParty() {
        when(gm.partyGateway.hasParty(any())).thenReturn(true);
        AgentOpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));

        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 3_000L, "stop"));

        assertEquals(AgentOpqSession.Phase.FAILED, gm.session.phase());
        assertEquals("stopped by operator", gm.session.failure());
        assertNull(AgentOpqSessionRegistry.forOperator(CREW));
        verifyNoPartyChange();
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verify(gm.characters, never()).disconnect(any(), anyBoolean(), anyBoolean());
        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
    }

    @Test
    void stopBeforeTheClearTakesEveryoneOutAndDisposesTheChannelsOnlyInstance() {
        // Step 0's abort is '!opqtest stop': OrbisPQ has one lobby per channel, so a stop before the clear frees it.
        AgentOpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(AgentOpqSession.Phase.SPLIT_ROOMS, AgentOpqDefinition.CENTER_MAP);
        when(gm.event.getPlayers()).thenReturn(gm.party);

        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 3_000L, "stop"));

        var order = inOrder(gm.event);
        for (Character member : gm.party) order.verify(gm.event).exitPlayer(member);
        order.verify(gm.event).dispose();
        assertNull(gm.session.eventInstance());
        verifyNoPartyChange();
    }

    @Test
    void stopAfterTheClearTakesOnlyTheCompanionsOutAndKeepsTheEvent() {
        AgentOpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.inside(AgentOpqSession.Phase.RESTORING_MINERVA, AgentOpqDefinition.CENTER_MAP);
        when(gm.event.getPlayers()).thenReturn(gm.party);
        when(gm.event.getProperty("statusStg8")).thenReturn("1");

        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 3_000L, "stop"));

        for (Character companion : gm.party.subList(1, 6)) verify(gm.event).exitPlayer(companion);
        verify(gm.event, never()).exitPlayer(gm.crew);
        verify(gm.event, never()).dispose();
        verifyNoPartyChange();
    }

    @Test
    void stopWithTheSessionGoneRecoversTheEngagementInsteadOfClosingIt() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));

        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 3_000L, "stop"));

        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(gm.engagement, 3_000L));
        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
        assertEquals(AgentPartyQuestEngagement.State.RECOVERING, gm.engagement.state());
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verifyNoPartyChange();
    }

    @Test
    void hisOwnFixtureStartRefusesWhileHisCompanionRunIsLiveAndTheStopStillReachesIt() {
        AgentOpqSessionRegistry.registerComplete(gm.session);
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        when(gm.partyGateway.snapshot(gm.crew)).thenReturn(null); // he left his party; the run is still live

        assertEquals(List.of("Stop the current OPQ companion run first."),
                gm.opqtest(gm.crew, System.currentTimeMillis(), "start", "11"));

        assertEquals("OPQ phase: PREPARING", gm.opqtest(gm.crew, 3_000L, "status").getFirst());
        assertEquals(List.of("OPQ test stopped safely."), gm.opqtest(gm.crew, 4_000L, "stop"));
        assertTrue(gm.session.terminal(), "the stop reached his companion run");
        assertNull(AgentOpqSessionRegistry.forOperator(CREW));
        gm.interaction.verifyNoInteractions();
        gm.scheduler.verifyNoInteractions();
    }

    @Test
    void aFinishedCompanionRunMakesWayForHisOwnFixtureStart() {
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));
        gm.session.fail("No legal OPQ progress before timeout", 3_000L);
        when(gm.partyGateway.snapshot(gm.crew)).thenReturn(null);

        List<String> started = gm.opqtest(gm.crew, System.currentTimeMillis(), "start", "11");

        assertEquals("Six OPQ Agents are being prepared at level 65 with all-job coverage.",
                started.getFirst(), started::toString);
        assertEquals(List.of("OPQ Agents prepared: 0/6"), gm.opqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void anotherOperatorsFixtureStartLeavesTheCompanionRunAlone() {
        AgentOpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.opqtest(gm.crew, 2_000L, "party", "prod", "7"));

        List<String> started = gm.opqtest(gm.overseer, System.currentTimeMillis(), "start", "11");
        assertEquals("Six OPQ Agents are being prepared at level 65 with all-job coverage.",
                started.getFirst(), started::toString);

        assertEquals("OPQ phase: PREPARING", gm.opqtest(gm.crew, 3_000L, "status").getFirst());
        assertEquals(List.of("OPQ Agents prepared: 0/6"), gm.opqtest(gm.overseer, 3_000L, "status"));
        verifyNoPartyChange();
    }

    @Test
    void helpListsTheTwoNewCommands() {
        List<String> help = gm.opqtest(gm.crew, 2_000L);
        assertTrue(help.contains("!opqtest party prod [seed] (adopt your own party as a companion run; you lead)"),
                help::toString);
        assertTrue(help.contains("!opqtest checkpoint <center|garden> (party prod runs only)"), help::toString);
        assertTrue(help.contains("!opqtest start [seed]"), help::toString);
    }

    private void verifyNoPartyChange() {
        verify(gm.partyGateway, never()).joinAgentParty(any(), anyInt());
        verify(gm.partyGateway, never()).createAgentParty(any());
        verify(gm.partyGateway, never()).leaveCurrentParty(any());
        verify(gm.partyGateway, never()).invitePartyMember(any(), any());
    }
}
