package server.living.roster;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatOrchestrator;
import server.agents.capabilities.dialogue.AgentChatRuntime;
import server.agents.capabilities.dialogue.AgentChatUtilityFlow;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentDialogue;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Deploy review 2026-10-10: "sell junk tamsin" in map chat (the name last) sent every companion out to the shop. The
 * roster read the trailing name and handed the line on, and the Agent routes, which take a name only as the first
 * word, gave it to every companion. The roster now sends the sell-now command to Tamsin's Agent alone and keeps the
 * line, wherever the name stands.
 */
class CompanionSellNowTest {
    private static final int ALICE = 10;
    private static final Persona TAMSIN = CompanionChatVerbsTest.persona("tamsin", "Tamsin");
    private static final Persona HADRIK = CompanionChatVerbsTest.persona("hadrik", "Hadrik");

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final CompanionExecutor executor = new CompanionExecutor();
    private final Character alice = character(ALICE, "Alice");
    private final AgentRuntimeEntry tamsin = new AgentRuntimeEntry(agent(900, "Tamsin"), alice, null);
    private final AgentRuntimeEntry hadrik = new AgentRuntimeEntry(agent(901, "Hadrik"), alice, null);
    private ResidentDialogue dialogue;
    private String oldOperators;

    @BeforeEach
    void wire() {
        when(world.roster()).thenReturn(roster);
        when(world.content()).thenReturn(content);
        when(world.executor()).thenReturn(executor);
        when(world.chatVerbs()).thenReturn(new CompanionChatVerbs(world));
        for (Persona persona : List.of(TAMSIN, HADRIK)) {
            when(content.personaByName(persona.id())).thenReturn(persona); // chat is normalized to lower case
            when(content.persona(persona.id())).thenReturn(persona);
            when(roster.ownerOf(persona.id())).thenReturn(ALICE);
            when(roster.entry(ALICE, persona.id())).thenReturn(row(persona.id()));
        }
        when(roster.roster(alice)).thenReturn(List.of(row("tamsin"), row("hadrik")));
        when(roster.hasCompanions(alice)).thenReturn(true);
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, tamsin);
        AgentRuntimeRegistry.registerEntry(ALICE, hadrik);
        oldOperators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = "Alice";
        dialogue = new ResidentDialogue(world);
    }

    @AfterEach
    void unwire() {
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
        AgentRuntimeRegistry.clear();
        executor.shutdown();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sell junk tamsin", "sell my junk tamsin", "sell trash Tamsin", "Tamsin sell junk"})
    void sellNowNamingOneOfTwoCompanionsOutSendsOnlyThatOneToTheShop(String said) {
        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class);
             MockedStatic<AgentShopService> shops = mockStatic(AgentShopService.class)) {
            // each Agent's own chat flow, as the orchestrator runs it for a line it is given
            chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> AgentChatUtilityFlow.handle(
                    call.getArgument(0), call.<AgentChatOrchestrator.Context>getArgument(1).utilityCallbacks()));
            scheduler.when(() -> AgentSchedulerRuntime.afterRandomDelay(any(AgentRuntimeEntry.class), anyInt(), anyInt(),
                    any(Runnable.class))).thenAnswer(call -> {
                        call.<Runnable>getArgument(3).run();
                        return null;
                    });

            assertTrue(dialogue.onMapChat(alice, said), "the roster keeps the line from the untargeted Agent route");
            drainAll(tamsin);
            drainAll(hadrik);

            shops.verify(() -> AgentShopService.requestSellTrashVisit(eq(tamsin), any(), any()));
            shops.verify(() -> AgentShopService.requestSellTrashVisit(eq(hadrik), any(), any()), never());
        }
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, then the chat)
        }
    }

    private static RosterEntry row(String personaId) {
        return new RosterEntry(ALICE, personaId, RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1L,
                1L, 1L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.isLoggedin()).thenReturn(true);
        when(character.getMapId()).thenReturn(104000000);
        return character;
    }

    private static Character agent(int id, String name) {
        Character agent = character(id, name);
        when(agent.getClient()).thenReturn(new BotClient(0, 0));
        return agent;
    }
}
