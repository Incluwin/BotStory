package server.living.roster;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import config.LivingWorldConfig;
import server.agents.capabilities.dialogue.AgentChatOrchestrator;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentChatRuntime;
import server.agents.capabilities.dialogue.AgentChatUtilityFlow;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.TypeSafeSettings;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.phrases.PhraseMatcher;
import server.living.resident.ResidentDialogue;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
    /** The whole commands the Agents carry out here (each one a phrase pack's canonical). */
    private static final Set<String> CANONICAL = Set.of("sell trash", "stop", "heals off");

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final CompanionExecutor executor = new CompanionExecutor();
    private final Character alice = character(ALICE, "Alice");
    private final AgentRuntimeEntry tamsin = new AgentRuntimeEntry(agent(900, "Tamsin"), alice, null);
    private final AgentRuntimeEntry hadrik = new AgentRuntimeEntry(agent(901, "Hadrik"), alice, null);
    private ResidentDialogue dialogue;
    private String oldOperators;
    private JevClient oldJev;

    @BeforeEach
    void wire() {
        oldJev = JevClient.runtime();
        // No API key: a line the judge would read stays on this thread, so no SystemOne call outlives the test.
        JevClient.installRuntime(new JevClient(new TypeSafeSettings("", TypeSafeSettings.DEFAULT_ENDPOINT,
                TypeSafeSettings.DEFAULT_MODEL, 1000), (request, settings) -> {
            throw new AssertionError("no SystemOne call in this test");
        }));
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
        JevClient.installRuntime(oldJev);
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_OPERATOR_NAMES = oldOperators;
        AgentRuntimeRegistry.clear();
        AgentChatIntentRuntime.installPrejudge(null);
        AgentChatIntentRuntime.installLearner(null);
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

    /**
     * Second deploy review 2026-10-10: the fix above took only the four exact sell-now lines. Any other line the phrase
     * packs read as an Agent command ("sell your junk", "dump your junk", "stop", "heals off") with the name last still
     * went on to the untargeted Agent route, whose phrase packs allow two unknown trailing words, so every companion
     * out did it. Such a line now goes to the named companion's Agent alone, as the line with the name first does.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"sell your junk tamsin, sell trash", "sell the junk Tamsin, sell trash",
            "dump your junk tamsin, sell trash", "sell your trash tamsin, sell trash", "stop tamsin, stop",
            "heals off tamsin, heals off",
            "Tamsin sell your junk, sell trash"})
    void aPackCommandWithTheNameLastReachesOnlyThatCompanion(String said, String command) {
        installPhrasePacks();
        List<String> done = new java.util.concurrent.CopyOnWriteArrayList<>();
        String[] draining = {""};

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            // each Agent carries out a whole canonical command and misses the rest, which the phrase packs then read
            chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> {
                String line = call.getArgument(0);
                return CANONICAL.contains(line) && done.add(draining[0] + ":" + line);
            });

            mapChat(said);
            draining[0] = "Tamsin";
            drainAll(tamsin);
            draining[0] = "Hadrik";
            drainAll(hadrik);
        }

        assertEquals(List.of("Tamsin:" + command), done);
    }

    /**
     * The same line to a companion that is not out with its owner reaches no companion: the line names it, and the
     * Agents out are not it. A sell-now line to it is the bench job SELL, as "sell junk tamsin" is; "follow tamsin"
     * stays the Agent routes' own "follow &lt;player&gt;".
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"stop tamsin", "heals off tamsin", "sell your junk tamsin"})
    void aPackCommandWithTheNameLastToABenchedCompanionReachesNoCompanionOut(String said) {
        installPhrasePacks();
        when(roster.entry(ALICE, "tamsin")).thenReturn(row("tamsin", RosterState.BENCHED));
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, hadrik);
        List<String> done = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> {
                String line = call.getArgument(0);
                return CANONICAL.contains(line) && done.add("Hadrik:" + line);
            });

            mapChat(said);
            drainAll(hadrik);
            assertFalse(dialogue.onMapChat(alice, "follow tamsin"), "follow <player> stays the Agent routes' own");
        }

        assertEquals(List.of(), done);
        if (said.startsWith("sell")) {
            verify(roster, timeout(2_000)).assign(alice, TAMSIN, Assignment.SELL, 0, 0);
        }
    }

    /**
     * Third deploy review 2026-10-10: the roster read the line with the name taken off, which frees one of the two
     * unknown trailing words the phrase packs allow, so "here you go tamsin" (an owner handing over the supplies its
     * companion asked for) became "move here" for Tamsin alone, "stop being silly tamsin" "stop" and "trade you later
     * tamsin" "trade". With the name these lines are no command, as the untargeted Agent route read them before: the
     * roster leaves them, and no Agent is given a command.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"here you go tamsin", "here take these tamsin", "here it is tamsin",
            "stop being silly tamsin", "trade you later tamsin"})
    void aSocialLineEndingWithTheNameIsNoCommand(String said) {
        installPhrasePacks();
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            // each Agent records each line its chat flow is given, the whole line or a canonical command
            chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> {
                heard.add(call.getArgument(0));
                return false;
            });

            assertFalse(dialogue.onMapChat(alice, said), "the roster leaves a line that is no command");
            mapChat(said);
            drainAll(tamsin);
            drainAll(hadrik);
        }

        assertEquals(List.of(said, said), heard, "each companion out was given the whole line, and no command");
    }

    /**
     * Third deploy review 2026-10-10: a command with the name last to a companion that is not out went to no Agent
     * and said nothing. Before, it reached the companions out (and one of them answered); now the owner is told.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"where are you tamsin", "stop tamsin"})
    void aPackCommandWithTheNameLastToABenchedCompanionIsAnswered(String said) {
        installPhrasePacks();
        when(roster.entry(ALICE, "tamsin")).thenReturn(row("tamsin", RosterState.BENCHED));
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, hadrik);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            mapChat(said);
            drainAll(hadrik);

            chat.verify(() -> AgentChatRuntime.handleChat(any(), any()), never());
        }

        verify(alice, timeout(2_000)).dropMessage(5, "Tamsin is not out with you right now.");
    }

    /** Map chat as GeneralChatHandler routes it: the living world first, then the Agent routes when it took nothing. */
    private void mapChat(String said) {
        if (!dialogue.onMapChat(alice, said)) {
            AgentChatRouteCoordinator.handleChat(alice, said, AgentReplyChannel.MAP,
                    (leaderId, leader, name) -> null, (leaderId, leader, name, target) -> null,
                    (leaderId, name) -> false);
        }
    }

    /** The real phrase packs, installed as the living world installs them (PhraseMatcher.reload: the prejudge). */
    private void installPhrasePacks() {
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(content.phrases()).thenReturn(ContentLoader.load(Path.of("scripts/config/living-world")).phrases());
        PhraseMatcher phrases = new PhraseMatcher(world);
        phrases.reload();
        when(world.phrases()).thenReturn(phrases);
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, then the chat)
        }
    }

    private static RosterEntry row(String personaId) {
        return row(personaId, RosterState.ACTIVE);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ALICE, personaId, state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1L,
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
