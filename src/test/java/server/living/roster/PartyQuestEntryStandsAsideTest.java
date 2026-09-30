package server.living.roster;

import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.Content.Persona;
import server.living.resident.LivingTalk;
import server.living.store.LivingStore.RosterState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Lakelis's hook stands aside (P6, docs/BOT_MANAGEMENT_PLAN.md flow 3): the stock start takes the click, and nothing is
 * sent, opened or written, when the living world is off, the kill switch does not list kpq, an Agent clicked (a
 * background party's headless entry), the clicker leads no party, a KPQ session already holds the party (a !kpqtest
 * adopt), a !kpqtest run's engagement owns a member, or nobody in the party is a companion. Owners are looked up for
 * the party's bots only (no persona lookup for a human).
 */
class PartyQuestEntryStandsAsideTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final NPCConversationManager cm = mock(NPCConversationManager.class);
    private MockedStatic<LivingWorld> living;
    private CompanionPartyQuestService service;

    @BeforeEach
    void annLeadsHadrikAtLakelis() {
        game.moveAnn(KERNING, 1500, 120);
        Persona hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.roster();
        when(game.world.directory().ownerOfCharacter(901)).thenReturn(ANN);
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        service = spy(new CompanionPartyQuestService(game.world));
        when(game.world.partyQuests()).thenReturn(service);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(game.world);
        when(cm.getPlayer()).thenReturn(game.ann);
        clearInvocations(game.store.store);
    }

    @AfterEach
    void close() {
        AgentPresence.install(null);
        living.close();
        game.close();
    }

    @Test
    void aPartyWithACompanionIsNotLeftToTheStockPathByTheGate() {
        assertFalse(service.standsAside(game.ann, "kpq"));
    }

    @Test
    void theWorldOff() {
        living.when(LivingWorld::get).thenReturn(null);

        assertFalse(LivingWorldHooks.partyQuestEntry(cm, "kpq"));
        verify(service, never()).offerAtDesk(any(), any());
        assertUntouched();
    }

    @Test
    void theKillSwitchOff() {
        game.config.COMPANION_PQ_ENABLED_KEYS = "";

        assertFalse(LivingWorldHooks.partyQuestEntry(cm, "kpq"));
        verify(service, never()).standsAside(any(), any());
        verify(game.world.directory(), never()).ownerOfCharacter(anyInt());
        assertUntouched();
    }

    @Test
    void anAgentClicked() {
        AgentPresence.install(chr -> chr.getId() == ANN);

        assertStandsAside();
    }

    @Test
    void noPartyOrNotItsLeader() {
        when(game.party.getLeaderId()).thenReturn(901);
        assertStandsAside();

        when(game.ann.getParty()).thenReturn(null);
        assertStandsAside();
    }

    @Test
    void aKpqSessionAlreadyHoldsTheParty() {
        AgentKpqSession session = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, 1L, ANN, 4, 1_000L);
        session.addMember(ANN, AgentKpqMemberState.MemberType.HUMAN);
        session.addMember(901, AgentKpqMemberState.MemberType.AGENT);
        AgentKpqSessionRegistry.registerComplete(session);
        try {
            assertStandsAside();
        } finally {
            AgentKpqSessionRegistry.remove(session);
        }
    }

    @Test
    void aKpqTestRunOwnsTheLeaderOrAMember() {
        AgentPartyQuestEngagement led = test(ANN);
        AgentPartyQuestEngagementRegistry.register(led);
        try {
            assertStandsAside();
        } finally {
            AgentPartyQuestEngagementRegistry.remove(led);
        }

        AgentPartyQuestEngagement member = test(500);
        member.addMember(901, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        AgentPartyQuestEngagementRegistry.register(member);
        try {
            assertStandsAside();
        } finally {
            AgentPartyQuestEngagementRegistry.remove(member);
        }
    }

    @Test
    void nobodyInThePartyIsACompanion() {
        when(game.world.directory().ownerOfCharacter(901)).thenReturn(0);

        assertStandsAside();
        verify(game.world.directory(), never()).ownerOfCharacter(ANN); // a human: no persona lookup
    }

    private void assertStandsAside() {
        assertTrue(service.standsAside(game.ann, "kpq"));
        assertFalse(LivingWorldHooks.partyQuestEntry(cm, "kpq"));
        assertUntouched();
    }

    /** No window opened, no packet sent, no row or event written. */
    private void assertUntouched() {
        Client client = game.ann.getClient();
        assertFalse(LivingTalk.awaitsReply(game.ann));
        verify(client, never()).sendPacket(any());
        verifyNoInteractions(game.store.store);
    }

    private static AgentPartyQuestEngagement test(int operatorId) {
        return new AgentPartyQuestEngagement("kpq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 1L, operatorId, 4,
                1_000L);
    }
}
