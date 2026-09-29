package server.agents.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way to spawn or load an Agent, register it under a leader, join it to a party or a messenger,
 * whisper-command or chat-command it, or hand it to another leader is listed here with its count
 * (docs/BOT_MANAGEMENT_PLAN.md P1, "Owner-scoped authority"). Spawns end in
 * AgentControlService.ensureCanControl, which asks AgentClaimGuard.mayLoad; the whisper bridge, the chat
 * routes, the leader transfer and the party-invite decision ask it themselves. A new call site fails this
 * test until someone checks it against the guard and adds it below.
 */
class ClaimGuardBoundaryTest {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Map<String, Pattern> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("spawn", Pattern.compile("\\bspawnAgentForLeader\\w*"));
        ROUTES.put("stationary", Pattern.compile("\\bspawnStationaryAgentForLeader\\w*"));
        ROUTES.put("join", Pattern.compile("\\bjoinAgentToLeaderParty\\b"));
        ROUTES.put("whisper", Pattern.compile("\\bhandleWhisperToAgent\\b"));
        ROUTES.put("transfer", Pattern.compile("\\btransferAgent\\b"));
        ROUTES.put("partyjoin", Pattern.compile("\\bParty\\.joinParty\\("));
        ROUTES.put("gatewayjoin", Pattern.compile("\\bjoinAgentParty\\("));
        ROUTES.put("register", Pattern.compile("\\bregister(Manual|Spawned)?Agent\\("));
        ROUTES.put("load", Pattern.compile("\\bloadOfflineAgent\\("));
        ROUTES.put("mailbox", Pattern.compile("\\bAgentChatMailboxDispatcher\\.handleChat\\b"));
        ROUTES.put("messenger", Pattern.compile("\\bjoinMessenger\\("));
        ROUTES.put("chat", Pattern.compile("\\bAgentChatRouteCoordinator\\.handleChat\\b"));
    }

    private static final Set<String> REVIEWED = Set.of(
            // definitions and the delegation chain that ends in ensureCanControl / the claim checks
            "server/agents/runtime/AgentInteractionRuntime.java spawn 6",
            "server/agents/runtime/AgentInteractionRuntime.java stationary 2",
            "server/agents/runtime/AgentInteractionRuntime.java transfer 3",
            "server/agents/runtime/AgentLifecycleService.java spawn 9",
            "server/agents/integration/cosmic/CosmicAgentSpawnCoordinator.java spawn 10",
            "server/agents/capabilities/party/AgentPartyLifecycleService.java join 1",
            "server/agents/runtime/AgentSpawnPlacementCoordinator.java join 1",
            "server/agents/integration/cosmic/CosmicAgentWhisperCommandBridge.java whisper 2",
            "server/agents/capabilities/dialogue/AgentWhisperCommandService.java whisper 1",
            "server/agents/commands/AgentLifecycleCommandCoordinator.java transfer 2",
            "server/agents/commands/AgentLeaderTransferCoordinator.java transfer 2",
            "server/agents/capabilities/trade/AgentTransferService.java transfer 1",
            "server/agents/integration/PartyGateway.java gatewayjoin 1",
            "server/agents/integration/cosmic/CosmicPartyGateway.java gatewayjoin 1",
            "server/agents/integration/cosmic/CosmicPartyGateway.java partyjoin 1",
            "server/agents/capabilities/party/AgentPartyLifecycleService.java gatewayjoin 1",
            // registration and loading plumbing: every caller is itself listed here (spawns after ensureCanControl,
            // chat recruit/transfer after ensureCanControl / the transfer claim check, a relogin keeps the leader)
            "server/agents/runtime/AgentInteractionRuntime.java register 7",
            "server/agents/runtime/AgentRegistrationCoordinator.java register 9",
            "server/agents/runtime/AgentLifecycleService.java register 5",
            "server/agents/runtime/AgentLifecycleService.java load 3",
            "server/agents/integration/cosmic/CosmicAgentSpawnCoordinator.java register 1",
            "server/agents/integration/cosmic/CosmicAgentReloginCoordinator.java register 2",
            "server/agents/integration/cosmic/CosmicAgentReloginCoordinator.java load 2",
            "server/agents/integration/cosmic/CosmicAgentOfflineLoader.java load 4",
            "server/agents/integration/cosmic/CosmicAgentOfflineLoadService.java load 1",
            // the per-agent chat sink: reached from the leader's own entries, and from the whisper bridge after mayControl
            "server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java mailbox 1",
            "server/agents/integration/cosmic/CosmicAgentWhisperCommandBridge.java mailbox 1",
            // the leader's chat line from the stock chat handlers (the public overload asks mayOperate; the
            // coordinator's own delegation to its generic overload is unqualified, so it is not counted)
            "server/agents/runtime/AgentInteractionRuntime.java chat 1",
            // the living world: roster calls, citizens forming their own parties, a willing citizen's delayed
            // accept, and the loads that materialize its own characters, each behind the lease and loaded checks
            "server/living/roster/RosterService.java spawn 1",
            // P2b: every stationary load (the P1.5 walk-in, a join-then-travel load at camp, a swap's incoming one) goes
            // through RosterService.spawnAt, the same ensureCanControl chain; every roster join (a call, a traveller,
            // a swap and its rollback, a HOLD rejoin, the auditor's in-place rejoin) through joinOwner, on a seat held
            "server/living/roster/RosterService.java stationary 1",
            "server/living/roster/RosterService.java join 1",
            // P4: a hired citizen adopted where it stands (RosterService.adopt) moves its runtime to its owner by the
            // leader-transfer path, once AgentClaimGuard.mayLoad allows the owner it (their roster row and lease)
            "server/living/roster/RosterService.java register 1",
            "server/living/roster/CompanionPartyRouter.java partyjoin 1",
            // the party sync on companion-ops: an owner's own loaded companion rejoins them on a seat held for it,
            // and a human's routed accept (a merge or make-room) joins the party that invited them
            "server/living/roster/LiveCrew.java join 1",
            "server/living/roster/LiveCrew.java partyjoin 1",
            // P3: a menu order is the owner's whisper to their own companion out with them, after mayControl; the bridge
            // asks the claim guard again
            "server/living/roster/CompanionManager.java whisper 1",
            // P3: a crew-wide order is the owner's chat line over exactly their own companions out with them, each one
            // past CompanionDirectory.mayControl first (the generic overload, which skips mayOperate: the owner's own)
            "server/living/roster/CompanionManager.java chat 1",
            "server/living/roster/BenchSessions.java load 1",
            "server/living/resident/ResidentRuntime.java load 1",
            "server/living/population/SocialFabric.java join 1",
            "server/living/population/PresenceScheduler.java load 1",
            "server/living/population/CitizenGenerator.java load 1",
            // stock client handlers; op 4 asks CompanionPartyRouter first, so it loads only a plain Agent the inviter
            // controls, and its bot join follows decidePartyInvite (claim check); op 3 is a human accepting an invite
            "net/server/channel/handlers/PartyOperationHandler.java spawn 1",
            "net/server/channel/handlers/PartyOperationHandler.java partyjoin 2",
            "net/server/channel/handlers/WhisperHandler.java whisper 1",
            // a human accepting a messenger invite, or the inviter's own loaded companion (LivingWorldHooks.ownsCompanion);
            // World holds the definition and PacketCreator the packet of the same name
            "net/server/channel/handlers/MessengerHandler.java messenger 2",
            "net/server/world/World.java messenger 2",
            "tools/PacketCreator.java messenger 1",
            // stock guild creation from a party (humans answering the guild contract)
            "net/server/channel/handlers/GuildOperationHandler.java partyjoin 1",
            // the PQ lobby and queue delay-accept: re-checks decidePartyInvite (and its claim check) when it fires
            "server/agents/capabilities/partyquest/lobby/AgentPartyQuestLobbyRuntime.java partyjoin 1",
            "server/agents/capabilities/partyquest/lobby/AgentPartyQuestQueueRuntime.java partyjoin 1",
            // Agent-only parties formed by the PQ director and lobby admission (an admitted Agent joins an Agent-led lobby)
            "server/agents/capabilities/partyquest/epq/AgentEpqLobbyAdmissionRuntime.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/hpq/AgentHpqLobbyAdmissionRuntime.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/kpq/AgentKpqLobbyAdmissionRuntime.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/lmpq/AgentLmpqLobbyAdmissionRuntime.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/lobby/AgentPartyQuestDirectedAdmissionRuntime.java gatewayjoin 1",
            "server/agents/progression/AgentMushroomKingdomYetiPartyRuntime.java gatewayjoin 3",
            // director, population and observer loads of pooled Agent identities (the population skips loaded characters)
            "server/agents/integration/cosmic/CosmicAgentDirectorSessionService.java load 1",
            "server/agents/integration/cosmic/CosmicAgentPopulationBackend.java load 1",
            "server/agents/observer/AgentObserverRuntime.java load 1",
            // the loopback admin port's headless operator (an operator character, never an Agent it hands out)
            "server/admin/HeadlessOperatorService.java load 1",
            // the legacy SoloMapling bot commands (their own fake characters, not Agents)
            "soloMapling/ArtificialPlayer/BotPartySystem/BotPartyCommands.java partyjoin 1",
            "soloMapling/ArtificialPlayer/BotCommandsPack/MapleMessengerCommands.java messenger 1",
            // GM tools, test fixtures and diagnostics (operator-run, administrators in practice)
            "server/agents/commands/AgentSpawnCommandExecutor.java spawn 1",
            "server/agents/commands/AgentSpawnCommandExecutor.java join 1",
            "server/agents/diagnostics/skillprobe/AgentSkillProbeService.java join 1",
            "server/agents/field/AgentFieldObservationRuntime.java join 1",
            "server/agents/field/AgentFieldObservationRuntime.java stationary 1",
            "server/agents/capabilities/expedition/AgentExpeditionLobbyService.java stationary 1",
            "server/agents/capabilities/expedition/AgentExpeditionLobbyService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/epq/AgentEpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/epq/AgentEpqTestService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/hpq/AgentHpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/hpq/AgentHpqTestService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/kpq/AgentKpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/kpq/AgentKpqTestService.java gatewayjoin 3",
            "server/agents/capabilities/partyquest/lmpq/AgentLmpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/lmpq/AgentLmpqTestService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/lobby/AgentPartyQuestTestDeploymentRuntime.java stationary 1",
            "server/agents/capabilities/partyquest/lpq/AgentLpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/lpq/AgentLpqTestService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/opq/AgentOpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/opq/AgentOpqTestService.java gatewayjoin 1",
            "server/agents/capabilities/partyquest/ppq/AgentPpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/ppq/AgentPpqTestService.java gatewayjoin 2",
            "server/agents/plans/mapleisland/cohort/MapleIslandCohortRuntime.java load 2",
            "server/agents/plans/amherst/AmherstPlanCommandService.java stationary 1",
            "server/agents/plans/mapleisland/MapleIslandPlanCommandService.java stationary 1",
            "server/agents/progression/AgentMushroomKingdomCohortService.java stationary 1",
            "server/agents/progression/AgentSecondJobCohortService.java stationary 1",
            "server/agents/progression/VictoriaFirstJobMvpCommandService.java stationary 1",
            "server/agents/runtime/townlife/ambient/AgentTownLifeAmbientRuntime.java stationary 1");

    @Test
    void everySpawnLoadJoinCommandAndTransferCallSiteIsReviewed() throws IOException {
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                for (Map.Entry<String, Pattern> route : ROUTES.entrySet()) {
                    int count = count(route.getValue(), source);
                    if (count > 0) {
                        actual.add(relative + " " + route.getKey() + " " + count);
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(REVIEWED), actual,
                "check each new spawn/load/register/join/whisper/chat/transfer call site against AgentClaimGuard, then list it here");
    }

    @Test
    void theGuardedRoutesAskTheClaimGuard() throws IOException {
        assertContains("server/agents/runtime/AgentLifecycleService.java", "ownershipService.ensureCanControl(leader, resolved)");
        assertContains("server/agents/integration/cosmic/CosmicAgentSpawnCoordinator.java", "AgentControlService.getInstance()");
        assertContains("server/agents/auth/AgentControlService.java", "AgentClaimGuard.mayLoad(actor, agent.id())");
        assertContains("server/agents/integration/cosmic/CosmicAgentWhisperCommandBridge.java", "AgentClaimGuard.mayControl(leader, target.getId())");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java",
                "refusedNamed(entriesByLeader, commandLeader, command.botName(), true)");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java",
                "refusedNamed(entriesByLeader, commandLeader, agentName, false)");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java", "!mayFollow(leader, target, entry)");
        assertContains("server/agents/commands/AgentLeaderTransferCoordinator.java", "AgentClaimGuard.isClaimed(agent.id())");
        assertContains("server/living/LivingWorld.java", "AgentClaimGuard.install(world.directory)");
        assertContains("net/server/channel/handlers/PartyOperationHandler.java", "LivingWorldHooks.partyInvite(player, invited, name)");
        assertContains("server/agents/capabilities/partyquest/lobby/AgentPartyQuestLobbyRuntime.java",
                "AgentClaimGuard.isClaimed(agent.getId()) && !AgentClaimGuard.mayLoad(inviter, agent.getId())");
        assertContains("net/server/channel/handlers/MessengerHandler.java", "LivingWorldHooks.ownsCompanion(player, target)");
        assertContains("server/living/roster/BenchSessions.java", "CompanionLeases.Kind.BENCH_LIVE");
        assertContains("server/living/resident/ResidentRuntime.java", "CompanionLeases.Kind.RESIDENT");
        assertContains("server/living/population/PresenceScheduler.java", "AgentRuntimeRegistry.hasActiveAgentCharacterId(existing)");
        assertContains("server/living/roster/RosterService.java", "AgentClaimGuard.mayLoad(owner, characterId)");
    }

    private static int count(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static void assertContains(String file, String token) throws IOException {
        assertTrue(Files.readString(MAIN.resolve(file)).contains(token), () -> file + " no longer contains " + token);
    }
}
