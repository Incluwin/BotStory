package server.living.roster;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.dialogue.AgentChatCommandClassifier;
import server.agents.capabilities.dialogue.AgentChatIngressService;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.dialogue.AgentPendingActionRuntime;
import server.agents.capabilities.dialogue.jev.AgentChatIntent;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.commands.AgentDismissCommandService;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentSessionCommandCoordinator;
import server.agents.runtime.AgentRuntimeEntry;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Every leader-level chat intent (docs/BOT_MANAGEMENT_PLAN.md P2a, "CompanionChatVerbs"): the Agent chat routes, the
 * per-Agent flows the targeted and untargeted routes and the whisper bridge reach, the phrases the chat judge maps free
 * text to, and the roster's own chat commands. Each one that can move a rostered companion's row, party seat or
 * registered leader goes through the roster on companion-ops (ROSTER) or asks the claim guard (GUARD); the others are
 * reviewed as leaving all three alone. A new route, flow or roster call fails here until it is reviewed.
 */
class ChatVerbBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final int KIWI = 1;
    private static final int OLWEN = 900;
    private static final int PROBE = 960;

    enum Door {
        /** Carried out by RosterService on companion-ops (CompanionChatVerbs). */
        ROSTER,
        /** Refused for a claimed Agent or a non-controller by AgentClaimGuard. */
        GUARD,
        /** Hands the message to each named or listening Agent: see {@link #FLOWS}. */
        PER_AGENT,
        /** Leaves the roster row, the party and the registered leader as they are. */
        NONE
    }

    /** AgentChatIngressService.Hooks: every leader-level route, in the order the ingress tries them. */
    private static final Map<String, Door> ROUTES = Map.ofEntries(
            entry("pendingOfferRoute", Door.NONE),  // answers an Agent's item offer to the speaker
            entry("recruitRoute", Door.GUARD),      // only an unclaimed online Agent, then ensureCanControl -> mayLoad
            entry("transferRoute", Door.GUARD),     // a claimed Agent is never handed on (refusedNamed, handOver)
            entry("assembleRoute", Door.NONE),      // "gather up": the leader's Agents walk over
            entry("formationRoute", Door.NONE),     // follow spacing
            entry("entriesForLeader", Door.NONE),   // a lookup, not a route
            entry("dismissRoute", Door.ROSTER),     // mayControl, then takeVerb(DISMISS): bench
            entry("targetedRoute", Door.PER_AGENT), // "<name> ...", plus "follow <player>" behind mayFollow
            entry("untargetedRoute", Door.PER_AGENT));

    /** The flows AgentChatOrchestrator runs for one Agent (targeted, untargeted, whispered or judged chat). */
    private static final Map<String, Door> FLOWS = Map.ofEntries(
            entry("AgentChatSessionRequestFlow", Door.ROSTER),  // "logout" only asks; the confirm below decides
            entry("AgentPendingChatActionFlow", Door.ROSTER),   // "yes" to a logout: takeVerb(LOGOUT), bench; a relog
                                                                // comes back to the same leader (one audit sighting)
            entry("AgentChatSupplyRequestFlow", Door.NONE),     // potions and ammo for the speaker
            entry("AgentChatSocialFlow", Door.NONE),            // fame
            entry("AgentChatToggleFlow", Door.NONE),            // support, heals, buff potions, offers
            entry("AgentChatBuffQueryFlow", Door.NONE),         // buff reports
            entry("AgentChatRespecFlow", Door.NONE),            // SP and AP
            entry("AgentChatEquipmentFlow", Door.NONE),         // gear
            entry("AgentChatMovementFlow", Door.NONE),          // follow, stop, grind, patrol, move or farm here
            entry("AgentChatBuildFlow", Door.NONE),             // build prompts
            entry("AgentChatUtilityFlow", Door.NONE),           // trade window, sell, disassemble, crystals
            entry("AgentChatTransferFlow", Door.NONE),          // items handed to the speaker (not a leader transfer)
            entry("AgentChatReportFlow", Door.NONE),            // stats and inventory reports
            entry("AgentChatJobAdvancementFlow", Door.NONE));   // job advancement

    /** Roster and contract calls from ResidentDialogue (map chat and whispers to a companion). */
    private static final Map<String, Door> RESIDENT_CALLS = Map.ofEntries(
            // verbs: each runs inside CompanionChatVerbs.say/show/park, on companion-ops (CompanionChatVerbsTest)
            entry("roster.call", Door.ROSTER), entry("roster.bench", Door.ROSTER), entry("roster.swap", Door.ROSTER),
            entry("roster.setCamp", Door.ROSTER), entry("roster.assign", Door.ROSTER), entry("roster.delegate", Door.ROSTER),
            entry("roster.haulSummary", Door.ROSTER), entry("roster.handOver", Door.ROSTER), entry("roster.sendIt", Door.ROSTER),
            entry("roster.bankIt", Door.ROSTER), entry("roster.wagesStatus", Door.ROSTER), entry("roster.payWages", Door.ROSTER),
            entry("roster.summary", Door.ROSTER), entry("roster.roster", Door.ROSTER), entry("contracts.dismiss", Door.ROSTER),
            // reads that parse the line and check ownership on the chat thread
            entry("roster.ownerOf", Door.NONE), entry("roster.entry", Door.NONE), entry("roster.isOwned", Door.NONE),
            entry("roster.hasCompanions", Door.NONE),
            // a conversation with a Resident nobody owns; accepting its contract recruits through RosterService.recruit,
            // which applies ROSTER_MAX and the seat check (moving the recruit onto companion-ops comes with P3)
            entry("contracts.askToJoin", Door.NONE), entry("contracts.blocker", Door.NONE), entry("contracts.pay", Door.NONE));

    @AfterEach
    void uninstall() {
        AgentClaimGuard.install(null);
        AgentClaimGuard.installVerbs(null);
    }

    @Test
    void everyLeaderLevelRouteIsReviewed() {
        Set<String> routes = new TreeSet<>();
        for (RecordComponent component : AgentChatIngressService.Hooks.class.getRecordComponents()) {
            routes.add(component.getName());
        }
        assertEquals(new TreeSet<>(ROUTES.keySet()), routes);
    }

    @Test
    void everyPerAgentFlowIsReviewed() throws IOException {
        String orchestrator = Files.readString(MAIN.resolve("server/agents/capabilities/dialogue/AgentChatOrchestrator.java"));
        Set<String> flows = new TreeSet<>();
        Matcher call = Pattern.compile("\\b(Agent\\w+Flow)\\.\\w+\\(").matcher(orchestrator);
        while (call.find()) {
            flows.add(call.group(1));
        }
        assertEquals(new TreeSet<>(FLOWS.keySet()), flows);
    }

    @Test
    void everyRosterCallFromTheRostersChatCommandsIsReviewed() throws IOException {
        String dialogue = Files.readString(MAIN.resolve("server/living/resident/ResidentDialogue.java"));
        Set<String> calls = new TreeSet<>();
        Matcher call = Pattern.compile("world\\.(roster|contracts)\\(\\)\\.(\\w+)\\(").matcher(dialogue);
        while (call.find()) {
            calls.add(call.group(1) + "." + call.group(2));
        }
        assertEquals(new TreeSet<>(RESIDENT_CALLS.keySet()), calls);
    }

    @Test
    void theChatJudgeNeverDismissesAndItsOnlyUnloadingPhraseIsTheConfirmedLogout() {
        Map<AgentChatIntent, String> unloading = new TreeMap<>();
        for (AgentChatIntent intent : AgentChatIntent.values()) {
            String canonical = intent.canonicalCommand();
            if (canonical == null) {
                continue;
            }
            assertNull(AgentDismissCommandService.dismissTarget(canonical), intent + " would reach the dismiss route");
            if (AgentChatCommandClassifier.isLogoutRequest(canonical) || AgentChatCommandClassifier.isRelogRequest(canonical)) {
                unloading.put(intent, canonical);
            }
        }
        // its canonical phrase goes through the same per-Agent flows: logout asks, and the confirm takes the roster path
        assertEquals(Set.of(AgentChatIntent.LOGOUT, AgentChatIntent.RELOG), unloading.keySet());
    }

    @Test
    void theDismissRouteBenchesAClaimedCompanionAndLeavesAPlainAgentToTheAgentRoute() {
        Character kiwi = character(KIWI, "Kiwi", mock(Client.class));
        AgentRuntimeEntry olwen = new AgentRuntimeEntry(character(OLWEN, "Olwen", new BotClient(0, 0)), kiwi, null);
        AgentRuntimeEntry probe = new AgentRuntimeEntry(character(PROBE, "Probe", new BotClient(0, 0)), kiwi, null);
        List<String> taken = new ArrayList<>();
        installGuard(Set.of(OLWEN, PROBE));
        AgentClaimGuard.installVerbs((actor, agentId, verb) -> agentId == OLWEN && taken.add(verb + " " + agentId));
        List<String> dismissed = new ArrayList<>();
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks = chatHooks(kiwi, Map.of(KIWI, List.of(olwen, probe)), dismissed);

        assertTrue(hooks.dismissRoute().handle(kiwi, "dismiss Olwen"));
        assertTrue(hooks.dismissRoute().handle(kiwi, "release Olwen"));
        assertTrue(hooks.dismissRoute().handle(kiwi, "dismiss Probe"));

        assertEquals(List.of("DISMISS 900", "DISMISS 900"), taken);
        assertEquals(List.of("Probe"), dismissed, "only the plain Agent is unregistered and left online");
    }

    @Test
    void withNoHostInstalledTheDismissRouteIsTheAgentRoute() {
        Character kiwi = character(KIWI, "Kiwi", mock(Client.class));
        AgentRuntimeEntry olwen = new AgentRuntimeEntry(character(OLWEN, "Olwen", new BotClient(0, 0)), kiwi, null);
        List<String> dismissed = new ArrayList<>();
        AgentChatIngressService.Hooks<AgentRuntimeEntry> hooks = chatHooks(kiwi, Map.of(KIWI, List.of(olwen)), dismissed);

        assertTrue(hooks.dismissRoute().handle(kiwi, "dismiss Olwen"));

        assertEquals(List.of("Olwen"), dismissed);
    }

    @Test
    void aConfirmedLogoutOfAClaimedCompanionNeverDisconnectsIt() {
        Character kiwi = character(KIWI, "Kiwi", mock(Client.class));
        AgentRuntimeEntry olwen = new AgentRuntimeEntry(character(OLWEN, "Olwen", new BotClient(0, 0)), kiwi, null);
        AgentRuntimeEntry probe = new AgentRuntimeEntry(character(PROBE, "Probe", new BotClient(0, 0)), kiwi, null);
        List<String> taken = new ArrayList<>();
        AgentClaimGuard.installVerbs((actor, agentId, verb) -> agentId == OLWEN && taken.add(verb + " " + agentId));

        try (MockedStatic<AgentSessionCommandCoordinator> session = mockStatic(AgentSessionCommandCoordinator.class)) {
            AgentPendingActionRuntime.pendingActionCallbacks(olwen).confirmLogout();
            AgentPendingActionRuntime.pendingActionCallbacks(probe).confirmLogout();

            session.verify(() -> AgentSessionCommandCoordinator.scheduleLogoutConfirm(olwen), never());
            session.verify(() -> AgentSessionCommandCoordinator.scheduleLogoutConfirm(probe)); // a GM Agent logs off as before
        }
        assertEquals(List.of("LOGOUT 900"), taken);
    }

    @Test
    void theLivingWorldInstallsTheVerbRouteAndTakesItDownAgain() throws IOException {
        String living = Files.readString(MAIN.resolve("server/living/LivingWorld.java"));
        assertTrue(living.contains("AgentClaimGuard.installVerbs(world.chatVerbs)"));
        assertTrue(living.contains("AgentClaimGuard.installVerbs(null)"));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static void installGuard(Set<Integer> controlled) {
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return controlled.contains(agentCharacterId);
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return agentCharacterId == OLWEN;
            }
        });
    }

    private static AgentChatIngressService.Hooks<AgentRuntimeEntry> chatHooks(
            Character leader, Map<Integer, List<AgentRuntimeEntry>> entries, List<String> dismissed) {
        AtomicReference<AgentChatIngressService.Hooks<AgentRuntimeEntry>> captured = new AtomicReference<>();
        try (MockedStatic<AgentChatIngressService> ingress = mockStatic(AgentChatIngressService.class)) {
            ingress.when(() -> AgentChatIngressService.handleChat(any(), any(), any(), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(3));
                return null;
            });
            AgentChatRouteCoordinator.handleChat(leader, "hi", AgentReplyChannel.MAP, entries,
                    (leaderId, commandLeader, agentName) -> null,
                    (leaderId, commandLeader, agentName, targetName) -> null,
                    (leaderId, agentName) -> dismissed.add(agentName),
                    new AgentFormationService.FormationState(AgentFormationService.FormationType.STAGGER, 60, 0),
                    60,
                    120);
        }
        return captured.get();
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}
