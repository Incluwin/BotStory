package server.living.roster;

import client.BotClient;
import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentChatRuntime;
import server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.TypeSafeSettings;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fourth deploy review 2026-10-10: the second review's name-last routing ("stop tamsin" to Tamsin alone) read every
 * line the phrase packs took, with the owner's companion named last, as a command to that one companion. But a name
 * last is often the verb's object or one of several addressees: "fame tamsin" made Tamsin fame her owner instead of
 * the crew faming her, "wait for tamsin" stopped only Tamsin (or, with her on her way, told the owner she was not out
 * and nobody waited), and "stop hadrik tamsin" stopped only Tamsin. A name last now picks one companion only when the
 * rest of the line is one whole command that takes no person (ResidentDialogue.NAME_LAST_COMMANDS); every other
 * line goes on to the untargeted Agent route, as before the second review. "go sell trash" with the name sells now,
 * as the Agent route always did, not the plan-11.2 SELL errand. And a command to a companion out whose Agent is not
 * registered yet (a login, a channel change) is answered, not swallowed.
 */
class CompanionNameLastRoutingTest {
    private static final int ALICE = 10;
    private static final Persona TAMSIN = CompanionChatVerbsTest.persona("tamsin", "Tamsin");
    private static final Persona HADRIK = CompanionChatVerbsTest.persona("hadrik", "Hadrik");
    /** The whole commands the Agents carry out here (each one a phrase pack's canonical). */
    private static final Set<String> CANONICAL = Set.of("sell trash", "stop");

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingContent content = mock(LivingContent.class);
    private final CompanionExecutor executor = new CompanionExecutor();
    private final Character alice = character(ALICE, "Alice");
    private final AgentRuntimeEntry tamsin = new AgentRuntimeEntry(agent(900, "Tamsin"), alice, null);
    private final AgentRuntimeEntry hadrik = new AgentRuntimeEntry(agent(901, "Hadrik"), alice, null);
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private final String[] draining = {""};
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
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.config()).thenReturn(new LivingWorldConfig());
        for (Persona persona : List.of(TAMSIN, HADRIK)) {
            when(content.personaByName(persona.id())).thenReturn(persona); // chat is normalized to lower case
            when(content.persona(persona.id())).thenReturn(persona);
            when(roster.ownerOf(persona.id())).thenReturn(ALICE);
            when(roster.entry(ALICE, persona.id())).thenReturn(row(persona.id(), RosterState.ACTIVE));
        }
        when(roster.roster(alice)).thenReturn(List.of(row("tamsin", RosterState.ACTIVE), row("hadrik", RosterState.ACTIVE)));
        when(roster.hasCompanions(alice)).thenReturn(true);
        when(content.phrases()).thenReturn(ContentLoader.load(Path.of("scripts/config/living-world")).phrases());
        PhraseMatcher phrases = new PhraseMatcher(world);
        phrases.reload(); // as the living world installs them: the Agents' prejudge too
        when(world.phrases()).thenReturn(phrases);
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

    /**
     * A name last that is the verb's object, follows a preposition, or is one of two names: each companion out is
     * given the whole line, as the untargeted Agent route gave it before the second review (STOP_PATTERN stops every
     * companion at "wait for tamsin"; FAME_PATTERN fames Tamsin).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fame tamsin", "wait for tamsin", "stay with tamsin", "train with tamsin",
            "stay close to tamsin", "stop hadrik tamsin", "help tamsin", "attack tamsin", "guard tamsin",
            "greet tamsin", "trade with tamsin", "heals on tamsin", "wait on tamsin"})
    void aNameLastThatIsNoAddresseeGoesToEveryCompanionOut(String said) {
        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            hearEverything(chat);

            mapChat(said);
            drainBoth();
        }

        assertEquals(List.of("Tamsin:" + said, "Hadrik:" + said), heard);
        verify(alice, never()).dropMessage(anyInt(), anyString());
    }

    /**
     * "wait for tamsin" while Tamsin is on her way over: the owner was told "Tamsin is not out with you right now."
     * and Hadrik kept walking. Hadrik is given the line, and nobody says she is not out.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wait for tamsin", "stay close to tamsin"})
    void waitingForACompanionOnItsWayStopsTheCrewOut(String said) {
        when(roster.entry(ALICE, "tamsin")).thenReturn(row("tamsin", RosterState.TRAVELLING));
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, hadrik);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            hearEverything(chat);

            mapChat(said);
            drainBoth();
        }

        assertEquals(List.of("Hadrik:" + said), heard);
        verify(alice, after(300).never()).dropMessage(anyInt(), anyString());
    }

    /**
     * "go sell trash" with Tamsin's name, first or last, to Tamsin out with Alice: she sells her junk now, as the
     * Agent route's phrase packs (prefix "go", alias "sell trash") always made her; she is not sent away on the
     * plan-11.2 SELL errand ("on it. call me when you want me back.").
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Tamsin go sell trash", "tamsin go sell my junk", "go sell trash tamsin",
            "go sell my junk Tamsin", "go sell my trash tamsin"})
    void goSellTrashToACompanionOutSellsNow(String said) {
        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            // each Agent carries out a whole canonical command and misses the rest, which the phrase packs then read
            chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> {
                String line = call.getArgument(0);
                return CANONICAL.contains(line) && heard.add(draining[0] + ":" + line);
            });

            mapChat(said);
            drainBoth();
        }

        assertEquals(List.of("Tamsin:sell trash"), heard);
        verify(roster, after(300).never()).delegate(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tamsin's row says she is out, but her Agent is not registered yet (a login, a channel change): the roster took
     * "tamsin sell junk" or "stop tamsin" for her Agent, found none and said nothing. The owner is told she is not
     * out yet, as the Crew window says for the same state (CompanionManager.out).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tamsin sell junk", "sell junk tamsin", "stop tamsin", "heals off tamsin"})
    void aCommandToACompanionWhoseAgentIsNotRegisteredYetIsAnswered(String said) {
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, hadrik);

        try (MockedStatic<AgentChatRuntime> chat = mockStatic(AgentChatRuntime.class);
             MockedStatic<AgentSocialDialogueRuntime> social = mockStatic(AgentSocialDialogueRuntime.class)) {
            hearEverything(chat);

            mapChat(said);
            drainBoth();
        }

        assertEquals(List.of(), heard, "the line names Tamsin: Hadrik is not given it");
        verify(alice, timeout(2_000)).dropMessage(5, "Tamsin is not out with you right now.");
    }

    /** Each Agent records each line its chat flow is given and takes it, so nothing falls through to the judge. */
    private void hearEverything(MockedStatic<AgentChatRuntime> chat) {
        chat.when(() -> AgentChatRuntime.handleChat(any(), any())).thenAnswer(call -> heard.add(draining[0] + ":"
                + call.getArgument(0)));
    }

    /** Map chat as GeneralChatHandler routes it: the living world first, then the Agent routes when it took nothing. */
    private void mapChat(String said) {
        if (!dialogue.onMapChat(alice, said)) {
            AgentChatRouteCoordinator.handleChat(alice, said, AgentReplyChannel.MAP,
                    (leaderId, leader, name) -> null, (leaderId, leader, name, target) -> null,
                    (leaderId, name) -> false);
        }
    }

    private void drainBoth() {
        draining[0] = "Tamsin";
        drainAll(tamsin);
        draining[0] = "Hadrik";
        drainAll(hadrik);
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // each pass may queue the next step (the reply channel, then the chat)
        }
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
