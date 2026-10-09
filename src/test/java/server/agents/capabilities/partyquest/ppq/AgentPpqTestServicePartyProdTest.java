package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqAdmitFixture.MIRA;

/**
 * PPQ Step 1 (plan 4.7): "!ppqtest party prod [skipchests] [seed]" adopts the operator's own party of four to six
 * beside Guon as a companion run through the public admit, with nothing spawned, joined or granted; every refusal is
 * in its own words; a stop releases the run as a companion run (the party kept, no test close, nobody logged out); the
 * fixture starts are refused while the adopted run is live.
 */
class AgentPpqTestServicePartyProdTest {
    static final String ADOPTED = "Adopted as a companion run. Talk to Guon within 30 seconds.";

    private PpqAdmitFixture fx;
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentInventoryGatewayRuntime> inventory;

    @BeforeEach
    void open() {
        fresh(0);
    }

    @AfterEach
    void close() {
        closeAll();
    }

    /** Ben and three companions, plus {@code more}; the previous party and its run are closed first. */
    private void fresh(int more) {
        closeAll();
        fx = new PpqAdmitFixture().withMore(more);
        when(fx.party.onlineMembers(any())).thenAnswer(call -> fx.party());
        MockedStatic<AgentAuthorityService> authority = open(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        lifecycle = open(AgentPartyQuestLifecycleRuntime.class);
        cleanup = open(AgentRuntimeCleanupService.class);
        interaction = open(AgentInteractionRuntime.class);
        inventory = open(AgentInventoryGatewayRuntime.class);
    }

    private void closeAll() {
        if (fx == null) return;
        AgentPpqTestService.execute(fx.ben, new String[] {"stop"}, 99_000L);
        // The lifecycle is mocked, so nothing hands the engagement back: drop it with the run.
        for (AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN);
             engagement != null; engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN)) {
            AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
        statics.clear();
        fx.close();
        fx = null;
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private List<String> ppqtest(long nowMs, String... params) {
        return AgentPpqTestService.execute(fx.ben, params, nowMs);
    }

    private AgentPpqSession session() {
        return AgentPpqSessionRegistry.forOperator(BEN);
    }

    @Test
    void adoptsTheOperatorsOwnPartyAsACompanionRun() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));

        AgentPpqSession session = session();
        assertTrue(session.companionAdopted());
        assertTrue(session.memberCoordinationChatEnabled());
        assertEquals(AgentPpqSession.Mode.HUMAN_LEADER, session.mode());
        assertEquals(BEN, session.eventLeaderId());
        assertEquals(4, session.memberCount());
        assertEquals(7L, session.seed());
        assertFalse(session.skipChestRooms());
        // His own companions only: nothing is spawned, invited, joined or granted.
        interaction.verifyNoInteractions();
        inventory.verifyNoInteractions();
        verifyNoPartyChange();
        assertEquals("PPQ phase: PREPARING", ppqtest(3_000L, "status").getFirst());
    }

    @Test
    void theAdoptAliasTheChestFlagAndTheOptionalSeed() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "adopt", "prod", "skipchests", "11"));
        assertTrue(session().skipChestRooms());
        assertEquals(11L, session().seed());
        assertEquals("chest rooms: skipped", ppqtest(3_000L, "status").get(2));

        fresh(0);
        assertEquals(List.of(ADOPTED), ppqtest(4_000L, "party", "prod"));
        assertEquals(4_000L, session().seed());
    }

    @Test
    void fiveAndSixAreAdoptedToo() {
        for (int more = 1; more <= 2; more++) {
            fresh(more);
            assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod"), "party of " + (4 + more));
            assertEquals(4 + more, session().memberCount());
        }
    }

    @Test
    void everyRefusalInItsOwnWords() {
        assertEquals(List.of("PPQ adopts a party only as a companion run: !ppqtest party prod [skipchests] [seed]."),
                ppqtest(2_000L, "party"));
        assertEquals(List.of("PPQ adopts a party only as a companion run: !ppqtest party prod [skipchests] [seed]."),
                ppqtest(2_000L, "party", "7"));

        when(fx.ben.getMapId()).thenReturn(AgentPpqDefinition.ENTRY_MAP);
        assertEquals(List.of("Stand at the Pirate PQ entrance (251010404) first."), ppqtest(2_000L, "party", "prod"));
        when(fx.ben.getMapId()).thenReturn(AgentPpqDefinition.RECRUIT_MAP);

        when(fx.party.snapshot(fx.ben)).thenReturn(null);
        assertEquals(List.of("Create the party first: you and three to five companions."),
                ppqtest(2_000L, "party", "prod"));
        when(fx.party.snapshot(any())).thenAnswer(call -> fx.snapshot());

        when(fx.party.onlineMembers(any())).thenReturn(List.of(fx.ben, fx.mira, fx.tobi));
        assertEquals(List.of("Every party member must be online on this channel before the companion run starts."),
                ppqtest(2_000L, "party", "prod"));
        when(fx.party.onlineMembers(any())).thenAnswer(call -> fx.party());

        AgentPartySnapshot miraLeads = new AgentPartySnapshot(9, fx.party().stream()
                .map(member -> new AgentPartyMemberSnapshot(member.getId(), member.getName(),
                        member.getId() == MIRA, AgentPpqDefinition.RECRUIT_MAP))
                .toList());
        when(fx.party.snapshot(any())).thenReturn(miraLeads);
        assertEquals(List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader."),
                ppqtest(2_000L, "party", "prod"));
        when(fx.party.snapshot(any())).thenAnswer(call -> fx.snapshot());

        when(fx.ben.getLevel()).thenReturn(54);
        assertEquals(List.of("Could not adopt the party: Ben must be level 55-100"), ppqtest(2_000L, "party", "prod"));
        when(fx.ben.getLevel()).thenReturn(101);
        assertEquals(List.of("Could not adopt the party: Ben must be level 55-100"), ppqtest(2_000L, "party", "prod"));
        when(fx.ben.getLevel()).thenReturn(70);

        fx.without(fx.una);
        assertEquals(List.of("Could not adopt the party: A PPQ companion run takes four to six members"),
                ppqtest(2_000L, "party", "prod"));

        assertNull(session());
        assertEquals(List.of("No PPQ test is active."), ppqtest(3_000L, "status"));
    }

    @Test
    void sevenAreRefused() {
        fresh(3);
        assertEquals(List.of("Could not adopt the party: A PPQ companion run takes four to six members"),
                ppqtest(2_000L, "party", "prod"));
        assertNull(session());
    }

    @Test
    void aLiveRunRefusesASecondAdoptAndEveryFixtureStart() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));

        assertEquals(List.of("Stop the current PPQ test before adopting another party."),
                ppqtest(2_100L, "party", "prod", "7"));
        for (String start : List.of("start", "humanleader", "withme", "humanmember", "agentleader")) {
            assertEquals(List.of("A PPQ companion run is live: stop it first (!ppqtest stop)."),
                    ppqtest(2_200L, start), start);
        }
        interaction.verifyNoInteractions();
        verifyNoPartyChange();
    }

    @Test
    void aFinishedRunMakesWayForTheNextAdopt() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));
        AgentPpqSession first = session();
        AgentPpqTerminationService.release(first, "PPQ stalled", 3_000L, true);
        // The handback finished: the lifecycle closed the engagement and took it off the registry.
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN);
        engagement.close(3_500L);
        AgentPartyQuestEngagementRegistry.remove(engagement);

        assertEquals(List.of(ADOPTED), ppqtest(4_000L, "party", "prod", "7"));
        assertNotSame(first, session());
    }

    @Test
    void stopReleasesTheLiveSessionAsACompanionRunAndKeepsTheParty() {
        when(fx.party.hasParty(any())).thenReturn(true);
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));
        AgentPpqSession session = session();

        assertEquals(List.of("PPQ test stopped safely."), ppqtest(3_000L, "stop"));

        assertEquals(AgentPpqSession.Phase.FAILED, session.phase());
        assertEquals("stopped by operator", session.failure());
        assertNull(session());
        verifyNoPartyChange();
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt()), never());
        verify(fx.characters, never()).disconnect(any(), anyBoolean(), anyBoolean());
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
        assertEquals(List.of("No PPQ test is active."), ppqtest(4_000L, "status"));
    }

    @Test
    void stopWithoutASessionRecoversTheEngagement() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));
        AgentPpqSession session = session();
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN);
        AgentPpqSessionRegistry.remove(session);

        assertEquals(List.of("PPQ test stopped safely."), ppqtest(3_000L, "stop"));

        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recover(engagement, 3_000L));
        lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.closeTest(any(), anyLong()), never());
        verifyNoPartyChange();
    }

    private void verifyNoPartyChange() {
        for (Character member : List.of(fx.ben, fx.mira, fx.tobi, fx.una)) {
            verify(fx.party, never()).leaveCurrentParty(member);
        }
        verify(fx.party, never()).joinAgentParty(any(), anyInt());
        verify(fx.party, never()).invitePartyMember(any(), any());
    }
}
