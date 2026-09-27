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
 * Every way to spawn an Agent for a leader, join it to a leader's party, whisper-command it or hand it
 * to another leader is listed here with its count (docs/BOT_MANAGEMENT_PLAN.md P1, "Owner-scoped
 * authority"). Spawns end in AgentControlService.ensureCanControl, which asks AgentClaimGuard; the
 * whisper bridge, the chat routes and the leader transfer ask it themselves. A new call site fails
 * this test until someone checks it against the guard and adds it below.
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
            // the living world: roster calls, citizens forming their own parties
            "server/living/roster/RosterService.java spawn 1",
            "server/living/roster/RosterService.java join 1",
            "server/living/population/SocialFabric.java join 1",
            // stock client handlers
            "net/server/channel/handlers/PartyOperationHandler.java spawn 1",
            "net/server/channel/handlers/MessengerHandler.java spawn 1",
            "net/server/channel/handlers/MessengerHandler.java join 1",
            "net/server/channel/handlers/WhisperHandler.java whisper 1",
            // GM tools, test fixtures and diagnostics (operator-run, administrators in practice)
            "server/agents/commands/AgentSpawnCommandExecutor.java spawn 1",
            "server/agents/commands/AgentSpawnCommandExecutor.java join 1",
            "server/agents/diagnostics/skillprobe/AgentSkillProbeService.java join 1",
            "server/agents/field/AgentFieldObservationRuntime.java join 1",
            "server/agents/field/AgentFieldObservationRuntime.java stationary 1",
            "server/agents/capabilities/expedition/AgentExpeditionLobbyService.java stationary 1",
            "server/agents/capabilities/partyquest/epq/AgentEpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/hpq/AgentHpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/kpq/AgentKpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/lmpq/AgentLmpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/lobby/AgentPartyQuestTestDeploymentRuntime.java stationary 1",
            "server/agents/capabilities/partyquest/lpq/AgentLpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/opq/AgentOpqTestService.java stationary 1",
            "server/agents/capabilities/partyquest/ppq/AgentPpqTestService.java stationary 1",
            "server/agents/plans/amherst/AmherstPlanCommandService.java stationary 1",
            "server/agents/plans/mapleisland/MapleIslandPlanCommandService.java stationary 1",
            "server/agents/progression/AgentMushroomKingdomCohortService.java stationary 1",
            "server/agents/progression/AgentSecondJobCohortService.java stationary 1",
            "server/agents/progression/VictoriaFirstJobMvpCommandService.java stationary 1",
            "server/agents/runtime/townlife/ambient/AgentTownLifeAmbientRuntime.java stationary 1");

    @Test
    void everySpawnJoinWhisperAndTransferCallSiteIsReviewed() throws IOException {
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
                "check each new spawn/join/whisper/transfer call site against AgentClaimGuard, then list it here");
    }

    @Test
    void theGuardedRoutesAskTheClaimGuard() throws IOException {
        assertContains("server/agents/runtime/AgentLifecycleService.java", "ownershipService.ensureCanControl(leader, resolved)");
        assertContains("server/agents/integration/cosmic/CosmicAgentSpawnCoordinator.java", "AgentControlService.getInstance()");
        assertContains("server/agents/auth/AgentControlService.java", "AgentClaimGuard.mayControl(actor, agent.id())");
        assertContains("server/agents/integration/cosmic/CosmicAgentWhisperCommandBridge.java", "AgentClaimGuard.mayControl(leader, target.getId())");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java", "mayControlNamed(entriesByLeader, transferLeader, agentName)");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java", "mayControlNamed(entriesByLeader, commandLeader, agentName)");
        assertContains("server/agents/capabilities/dialogue/AgentChatRouteCoordinator.java", "!mayFollow(leader, target, entry)");
        assertContains("server/agents/commands/AgentLeaderTransferCoordinator.java", "AgentClaimGuard.isClaimed(agent.id())");
        assertContains("server/living/LivingWorld.java", "AgentClaimGuard.install(world.directory)");
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
