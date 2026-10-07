package config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigurationBoundaryTest {
    private static final Path AGENT_SOURCES =
            Path.of("src", "main", "java", "server", "agents");
    private static final Pattern TUNING_REFERENCE = Pattern.compile(
            "config\\.AgentTuning\\.(?:intValue|longValue|doubleValue|floatValue|booleanValue|stringValue)"
                    + "\\(\\s*\\\"([^\\\"]+)\\\"\\)");
    private static final Pattern LOCAL_TUNING_PREFIX = Pattern.compile(
            "TUNING_PREFIX\\s*=\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOCAL_TUNING_REFERENCE = Pattern.compile(
            "tuning(?:Int|Long|Double|Float|Boolean)\\(\\\"([^\\\"]+)\\\"\\)");
    private static final Pattern NUMERIC_CONSTANT = Pattern.compile(
            "private\\s+static\\s+final\\s+(?:int|long|double|float)\\s+"
                    + "([A-Z][A-Z0-9_]*)\\s*=\\s*"
                    + "-?(?:\\d[\\d_]*)(?:\\.\\d+)?[dDfFlL]?\\s*;");
    private static final Pattern MUTABLE_POLICY_DEFAULT = Pattern.compile(
            "public\\s+(?:int|long|double|float|boolean)\\s+[A-Z][A-Z0-9_]*\\s*=\\s*"
                    + "(?:-?(?:\\d[\\d_]*)(?:\\.\\d+)?[dDfFlL]?|true|false)\\s*;");
    private static final Pattern NON_TUNABLE_NAME = Pattern.compile(
            "(?:^serialVersionUID$|_ID$|_IDS$|_MAP$|_MAP_ID$|"
                    + "_NPC_ID$|_NPC_IDS$|_ITEM_ID$|_ITEM_IDS$|_QUEST_ID$|_QUEST_IDS$|"
                    + "_SKILL_ID$|_SKILL_IDS$|_JOB_ID$|_JOB_IDS$|_PORTAL_ID$|"
                    + "_DOMAIN$|_VERSION$|OPCODE|^REQUEST_BYTES$|"
                    + "MOVEMENT_OFFSET|FACING_|STAND_|NO_DESTINATION_MAP_ID|"
                    + "VISUAL_DIVERSITY_STRIDE)");
    /**
     * Numeric constants that arrived with the cosmic_agents graft (2026-09-19) and were never moved to
     * agent-engine.yaml. Recorded 2026-10-06 so the guard keeps blocking new constants while the PQ branches that
     * own these files are in flight; remove an entry when its constant moves to AgentTuning.
     */
    private static final Set<String> RECORDED_GRAFT_CONSTANTS = Set.of(
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:ARRIVAL_X_PX",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:ARRIVAL_Y_PX",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:MELEE_HEAD_FIRST_X",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:MELEE_HEAD_SPACING_X",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:RANGED_HEAD_FIRST_X",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogCombatPolicy.java:RANGED_HEAD_SPACING_X",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:BATTLE_EXIT_NPC",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:INTERACTION_RANGE_PX",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:POST_CLEAR_RALLY_DISTANCE_PX",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:POST_CLEAR_RALLY_SPACING_PX",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:POST_CLEAR_TIMEOUT_MS",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:REWARD_EXIT_PORTAL",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:REWARD_FIDGET_INITIAL_STEP_MS",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:REWARD_FIDGET_MIN_PERIOD_MS",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:REWARD_FIDGET_PERIOD_VARIANCE_MS",
            "src/main/java/server/agents/capabilities/expedition/balrog/AgentEasyBalrogScenario.java:REWARD_REACTOR",
            "src/main/java/server/agents/capabilities/movement/AgentMovementSubstepPolicy.java:MAX_SUBSTEPS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:ITEM_REACTOR_DROP_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:LOBBY_CHAT_STAGGER_MS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:LOBBY_ENTRY_DELAY_MS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:LOOT_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:MAZE_EXIT_APPROACH_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_FOUR_MAX_X",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_FOUR_MIN_X",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_TWO_AGGRO_TIMEOUT_MS",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_TWO_BOTTLES_REQUIRED",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_TWO_LURE_PROGRESS_PX",
            "src/main/java/server/agents/capabilities/partyquest/epq/AgentEpqCoordinator.java:STAGE_TWO_LURE_STALL_MS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqCoordinator.java:ACTION_RETRY_MS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqCoordinator.java:NPC_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqCoordinator.java:PORTAL_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqCoordinator.java:REACTOR_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqCoordinator.java:ROOM_LEASE_MS",
            "src/main/java/server/agents/capabilities/partyquest/lmpq/AgentLmpqRuntime.java:COORDINATOR_LEASE_MS",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:REACTOR_PLATFORM_VERTICAL_TOLERANCE_PX",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_2_PASS_LOOT_SETTLE_MS",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_5_MISSING_ROOM_PASS_SETTLE_MS",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_5_PASS_LOOT_SETTLE_MS",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_5_UNOBSERVED_ASSIST_MS",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_BOSS_EDGE_MARGIN_PX",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_FLOOR_LEFT_X",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_FLOOR_RIGHT_X",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_FLOOR_Y",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_FORMATION_RADIUS_PX",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_PRE_BOSS_RALLY_SPACING_PX",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java:STAGE_9_PRE_BOSS_RALLY_X",
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqStageFiveReactorOrder.java:DARK_SIGHT_ROOM",
            "src/main/java/server/agents/capabilities/partyquest/ppq/AgentPpqCoordinator.java:INTERACTION_RADIUS",
            "src/main/java/server/agents/capabilities/partyquest/ppq/AgentPpqCoordinator.java:RETRY_MS",
            "src/main/java/server/agents/capabilities/partyquest/ppq/AgentPpqRuntime.java:COORDINATOR_LEASE_MS",
            "src/main/java/server/agents/capabilities/partyquest/ppq/AgentPpqTestService.java:SPAWN_STAGGER_MS",
            "src/main/java/server/agents/field/AgentEpqTestFixtureService.java:CAPE",
            "src/main/java/server/agents/field/AgentEpqTestFixtureService.java:EARRING",
            "src/main/java/server/agents/field/AgentEpqTestFixtureService.java:SHOES_SPEED_10",
            "src/main/java/server/agents/field/AgentOpqTestFixtureService.java:OPQ_MINIMUM_JUMP_STAT",
            "src/main/java/server/agents/field/AgentPpqTestFixtureService.java:CAPE",
            "src/main/java/server/agents/field/AgentPpqTestFixtureService.java:EARRING",
            "src/main/java/server/agents/field/AgentPpqTestFixtureService.java:SHOES_SPEED_SCROLL",
            "src/main/java/server/agents/progression/AgentSecondJobAdvancementRuntime.java:ELLINIA_TREE_TUNNEL_UPPER_EXIT_Y");

    @Test
    void cosmicServerConfigurationContainsNoAgentOwnedKeys() throws Exception {
        String yaml = Files.readString(Path.of(YamlConfig.CONFIG_FILE_NAME));
        assertFalse(yaml.lines().anyMatch(line -> line.trim().startsWith("AGENT_")));
        assertFalse(Arrays.stream(ServerConfig.class.getFields())
                .map(Field::getName)
                .anyMatch(name -> name.startsWith("AGENT_")));
    }

    @Test
    void dedicatedAgentConfigurationDefinesEveryTypedDeploymentSetting() throws Exception {
        AgentEngineConfig agent = AgentYamlConfig.config.agent;
        assertNotNull(agent);

        Set<String> declared = Arrays.stream(AgentEngineConfig.class.getFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
        String yaml = Files.readString(Path.of(AgentYamlConfig.CONFIG_FILE_NAME));
        Set<String> configured = yaml.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("AGENT_") && line.contains(":"))
                .map(line -> line.substring(0, line.indexOf(':')))
                .collect(Collectors.toSet());
        assertEquals(declared, configured);
    }

    @Test
    void everyAgentConfigurationValueHasPurposeTechnicalAndValueGuidance() throws Exception {
        var lines = Files.readAllLines(Path.of(AgentYamlConfig.CONFIG_FILE_NAME));
        int documentedValues = 0;
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();
            boolean deploymentValue = trimmed.startsWith("AGENT_") && trimmed.contains(":");
            boolean tuningValue = trimmed.startsWith("server.agents.") && trimmed.contains(":");
            if (!deploymentValue && !tuningValue) {
                continue;
            }

            documentedValues++;
            assertTrue(index >= 3, () -> "missing configuration documentation before " + trimmed);
            String values = lines.get(index - 3).trim();
            String purpose = lines.get(index - 2).trim();
            String technical = lines.get(index - 1).trim();
            assertTrue(values.startsWith("# Values: ") && values.contains("Recommended: "),
                    () -> "missing possible values or recommended range before " + trimmed);
            assertTrue(purpose.startsWith("# Purpose: ") && purpose.length() >= 60,
                    () -> "missing useful purpose description before " + trimmed);
            assertTrue(technical.startsWith("# Technical: ") && technical.length() >= 100,
                    () -> "missing useful technical description before " + trimmed);
        }
        assertEquals(AgentEngineConfig.class.getFields().length + AgentTuning.snapshot().size(),
                documentedValues);
    }

    @Test
    void everyRuntimeTuningReferenceHasExactlyOneYamlValueAndNoStaleKey() throws Exception {
        Set<String> referenced = new HashSet<>();
        try (var files = Files.walk(AGENT_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = TUNING_REFERENCE.matcher(Files.readString(file));
                while (matcher.find()) {
                    referenced.add(matcher.group(1));
                }
                String source = Files.readString(file);
                Matcher prefixMatcher = LOCAL_TUNING_PREFIX.matcher(source);
                if (prefixMatcher.find()) {
                    String prefix = prefixMatcher.group(1);
                    Matcher localMatcher = LOCAL_TUNING_REFERENCE.matcher(source);
                    while (localMatcher.find()) {
                        referenced.add(prefix + localMatcher.group(1));
                    }
                }
            }
        }
        assertEquals(referenced, AgentTuning.snapshot().keySet());
    }

    @Test
    void tunableNumericConstantsCannotReturnToAgentSourceFiles() throws Exception {
        Set<String> violations = new HashSet<>();
        try (var files = Files.walk(AGENT_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = NUMERIC_CONSTANT.matcher(Files.readString(file));
                while (matcher.find()) {
                    String constant = matcher.group(1);
                    if (!NON_TUNABLE_NAME.matcher(constant).find()) {
                        violations.add(file.toString().replace('\\', '/') + ":" + constant);
                    }
                }
                if (MUTABLE_POLICY_DEFAULT.matcher(Files.readString(file)).find()) {
                    violations.add(file.toString().replace('\\', '/') + ":mutable-default");
                }
            }
        }
        violations.removeAll(RECORDED_GRAFT_CONSTANTS);
        assertEquals(Set.of(), violations,
                "runtime timing, weights, limits, tolerances, and bounds belong in agent-engine.yaml");
    }
}
