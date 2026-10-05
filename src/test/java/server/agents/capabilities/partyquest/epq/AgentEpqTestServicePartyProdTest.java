package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.epq.EpqGmFixture.ADOPTED;
import static server.agents.capabilities.partyquest.epq.EpqGmFixture.CASSE;
import static server.agents.capabilities.partyquest.epq.EpqGmFixture.CREW;

/**
 * "!epqtest party prod [seed]": a GM6 companion owner adopts his own party at Ellin as a companion run through the
 * public admit, with no fixture Agent spawned, loaded or joined; the run answers status and stop, a stop never logs a
 * companion out or breaks up his party, and another operator's fixture start leaves the run alone.
 */
class AgentEpqTestServicePartyProdTest {
    private final EpqGmFixture gm = new EpqGmFixture();

    @AfterEach
    void tearDown() {
        gm.close();
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        List<String> reply = gm.epqtest(gm.crew, 2_000L, "party", "prod", "7");

        assertEquals(List.of(ADOPTED), reply);
        gm.admission.verify(() -> AgentEpqAdmissionService.admit(
                gm.crew, gm.crew, List.of(gm.crew, gm.lioren, gm.casse, gm.pell, gm.kestrel), 7L, 2_000L));
        assertTrue(gm.session.memberCoordinationChatEnabled());
        // His own companions only: nothing is spawned, loaded, invited, joined or scheduled.
        gm.interaction.verifyNoInteractions();
        gm.scheduler.verifyNoInteractions();
        verifyNoPartyChange();

        List<String> status = gm.epqtest(gm.crew, 3_000L, "status");
        assertEquals("EPQ phase: PREPARING", status.getFirst(), status::toString);

        assertEquals(List.of("EPQ test stopped safely."), gm.epqtest(gm.crew, 4_000L, "stop"));
        assertEquals(List.of("No EPQ test is active."), gm.epqtest(gm.crew, 5_000L, "status"));
    }

    @Test
    void theAdoptAliasAndTheOptionalSeed() {
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "adopt", "prod"));
        gm.admission.verify(() -> AgentEpqAdmissionService.admit(
                gm.crew, gm.crew, List.of(gm.crew, gm.lioren, gm.casse, gm.pell, gm.kestrel), 2_000L, 2_000L));
    }

    @Test
    void everyRefusalInItsOwnWords() {
        assertEquals(List.of("EPQ adopts a party only as a companion run: !epqtest party prod [seed]."),
                gm.epqtest(gm.crew, 2_000L, "party", "7"));
        assertEquals(List.of("EPQ adopts a party only as a companion run: !epqtest party prod [seed]."),
                gm.epqtest(gm.crew, 2_000L, "party"));

        when(gm.crew.getMapId()).thenReturn(AgentEpqDefinition.ENTRANCE_MAP);
        assertEquals(List.of("Stand at the Ellin Forest PQ entrance (300030100) before adopting a party."),
                gm.epqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.crew.getMapId()).thenReturn(AgentEpqDefinition.RECRUIT_MAP);

        when(gm.party.snapshot(gm.crew)).thenReturn(null);
        assertEquals(List.of("Create the desired party before using !epqtest party prod."),
                gm.epqtest(gm.crew, 2_000L, "party", "prod"));

        gm.partyLedBy(CREW);
        when(gm.party.onlineMembers(gm.crew)).thenReturn(List.of(gm.crew, gm.lioren, gm.casse, gm.pell));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                gm.epqtest(gm.crew, 2_000L, "party", "prod"));
        when(gm.party.onlineMembers(gm.crew)).thenReturn(List.of(gm.crew, gm.lioren, gm.casse, gm.pell, gm.kestrel));

        gm.partyLedBy(CASSE);
        assertEquals(List.of(
                        "Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                gm.epqtest(gm.crew, 2_000L, "party", "prod"));

        gm.admission.verify(() -> AgentEpqAdmissionService.admit(any(), any(), anyList(), anyLong(), anyLong()),
                never());
        assertEquals(List.of("No EPQ test is active."), gm.epqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void aRefusedAdmitKeepsNoRun() {
        gm.admission.when(() -> AgentEpqAdmissionService.admit(
                        eq(gm.crew), eq(gm.crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentEpqAdmissionService.AdmissionResult(
                        false, "Kestrel needs four ETC slots and one USE slot", null, null));

        assertEquals(List.of("Could not adopt the party: Kestrel needs four ETC slots and one USE slot"),
                gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));
        assertEquals(List.of("No EPQ test is active."), gm.epqtest(gm.crew, 3_000L, "status"));
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndAFinishedOneMakesWay() {
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));
        assertEquals(List.of("Stop the current EPQ test before adopting another party."),
                gm.epqtest(gm.crew, 2_100L, "party", "prod", "7"));

        gm.session.fail("EPQ stalled in STAGE_ONE for 480 seconds", 3_000L);

        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 4_000L, "party", "prod", "7"));
        gm.admission.verify(() -> AgentEpqAdmissionService.admit(eq(gm.crew), eq(gm.crew), anyList(), anyLong(),
                anyLong()), times(2));
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
    }

    @Test
    void stopReleasesTheLiveSessionAsACompanionRunAndKeepsTheParty() {
        when(gm.party.hasParty(any())).thenReturn(true);
        AgentEpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));

        assertEquals(List.of("EPQ test stopped safely."), gm.epqtest(gm.crew, 3_000L, "stop"));

        assertEquals(AgentEpqSession.Phase.FAILED, gm.session.phase());
        assertEquals("stopped by operator", gm.session.failure());
        assertNull(AgentEpqSessionRegistry.forOperator(CREW));
        verifyNoPartyChange();
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verify(gm.characters, never()).disconnect(any(), anyBoolean(), anyBoolean());
        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
    }

    @Test
    void stopWithTheSessionGoneRecoversTheEngagementInsteadOfClosingIt() {
        AgentPartyQuestEngagementRegistry.register(gm.engagement);
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));

        assertEquals(List.of("EPQ test stopped safely."), gm.epqtest(gm.crew, 3_000L, "stop"));

        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(gm.engagement, 3_000L));
        gm.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
        assertEquals(server.agents.capabilities.partyquest.AgentPartyQuestEngagement.State.RECOVERING,
                gm.engagement.state());
        gm.cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verifyNoPartyChange();
    }

    @Test
    void anotherOperatorsFixtureStartLeavesTheCompanionRunAlone() {
        AgentEpqSessionRegistry.registerComplete(gm.session);
        assertEquals(List.of(ADOPTED), gm.epqtest(gm.crew, 2_000L, "party", "prod", "7"));

        List<String> started = gm.epqtest(gm.overseer, System.currentTimeMillis(), "start", "11");
        assertEquals("Five EPQ Agents are preparing: Warrior, Magician, Bowman, Thief, and Pirate.",
                started.getFirst(), started::toString);

        assertFalse(gm.session.terminal(), "the companion run is not released");
        assertSame(gm.session, AgentEpqSessionRegistry.forOperator(CREW));
        assertEquals("EPQ phase: PREPARING", gm.epqtest(gm.crew, 3_000L, "status").getFirst());
        assertEquals(List.of("EPQ Agents prepared: 0/5", "flow: AGENTS_ONLY"),
                gm.epqtest(gm.overseer, 3_000L, "status"));

        AgentEpqTestService.resetReservedAgents(4_000L);

        assertEquals(List.of("No EPQ test is active."), gm.epqtest(gm.overseer, 5_000L, "status"),
                "the fixture run is closed");
        assertFalse(gm.session.terminal());
        assertEquals("EPQ phase: PREPARING", gm.epqtest(gm.crew, 5_000L, "status").getFirst());
        verifyNoPartyChange();
    }

    @Test
    void helpListsTheTwoNewCommands() {
        List<String> help = gm.epqtest(gm.crew, 2_000L);
        assertTrue(help.contains("!epqtest party prod [seed] (adopt your own party as a companion run; you lead)"),
                help::toString);
        assertTrue(help.contains("!epqtest checkpoint <maze|flowers|climb|boss> (party prod runs only)"),
                help::toString);
    }

    private void verifyNoPartyChange() {
        verify(gm.party, never()).joinAgentParty(any(), anyInt());
        verify(gm.party, never()).createAgentParty(any());
        verify(gm.party, never()).leaveCurrentParty(any());
        verify(gm.party, never()).invitePartyMember(any(), any());
    }
}
