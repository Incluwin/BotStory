package server.agents.integration.typesafe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import server.agents.capabilities.dialogue.jev.AgentChatIntentJudge;
import server.agents.integration.typesafe.state.JevState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in paid evaluation through the actual request builders and judge; never runs in normal tests. */
class JevChatEvaluationTest {
    @Test
    @EnabledIfSystemProperty(named = "jev.eval", matches = "true")
    void compareStrategiesOnLabeledCommands() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path dataset = Path.of(System.getProperty("jev.eval.dataset", "tools/jev-eval/seed.jsonl"));
        Path output = Path.of(System.getProperty("jev.eval.output", "tools/jev-eval/results/latest.json"));
        TypeSafeSettings settings = TypeSafeSettings.runtime();
        assertTrue(settings.configured(), "Set TYPESAFE_API_KEY to run this paid evaluation");
        List<Map<String, Object>> cases = new ArrayList<>();
        for (String line : Files.readAllLines(dataset)) {
            if (!line.isBlank()) cases.add(json.readValue(line, new TypeReference<>() {}));
        }
        assertFalse(cases.isEmpty(), "No labeled cases; collect confirmations or use the explicit seed corpus");
        List<Map<String, Object>> observations = new ArrayList<>();
        int repeats = Integer.getInteger("jev.eval.repeats", 1);
        assertTrue(repeats > 0);
        for (int repeat = 0; repeat < repeats; repeat++) {
            for (Map<String, Object> row : cases) {
                @SuppressWarnings("unchecked") var state = (Map<String, Object>) row.get("state");
                @SuppressWarnings("unchecked") var expected = (List<String>) row.get("acceptable_commands");
                assertNotNull(state); assertNotNull(expected);
                // Rotate execution order to reduce systematic cache/latency bias between methods.
                List<String> strategies = new ArrayList<>(List.of("flat", "averaged4", "hierarchical"));
                Collections.rotate(strategies, observations.size() / 3 % 3);
                for (String strategy : strategies) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    JevHttpTransport http = new JevHttpTransport();
                    JevTransport transport = (request, config) -> {
                        long start = System.nanoTime();
                        Map<String, Object> trace = new LinkedHashMap<>();
                        trace.put("kind", request.kind());
                        trace.put("questions", request.toJson(config.model()).get("questions"));
                        calls.add(trace);
                        try {
                            JevResponse response = http.send(request, config);
                            trace.put("response", response);
                            return response;
                        } catch (JevTransportException failure) {
                            trace.put("error_status", failure.statusCode());
                            throw failure;
                        } finally {
                            trace.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
                        }
                    };
                    var permutations = strategy.equals("averaged4")
                            ? new JevChoicePermutations(JevChoicePermutations.Mode.LIVE, 4, 64, Set.of(), () -> new Random(42))
                            : JevChoicePermutations.off();
                    JevClient client = new JevClient(settings, transport, new JevCircuitBreaker(), new JevUsageMeter(), permutations);
                    JevState input = JevState.create();
                    state.forEach((key, value) -> input.put(key, value, 100));
                    long start = System.nanoTime();
                    var decision = new AgentChatIntentJudge(client, .75, .45, 3000, strategy.equals("hierarchical")).judge(input);
                    boolean correct = expected.isEmpty()
                            ? decision.outcome() == AgentChatIntentJudge.Outcome.IGNORE
                            : expected.contains(decision.canonicalCommand());
                    Map<String, Object> observation = new LinkedHashMap<>();
                    observation.put("id", row.get("id"));
                    observation.put("provenance", row.get("provenance"));
                    observation.put("split", row.getOrDefault("split", "confirmation"));
                    observation.put("repeat", repeat);
                    observation.put("strategy", strategy);
                    observation.put("acceptable_commands", expected);
                    observation.put("decision", decision);
                    observation.put("correct_command", correct);
                    observation.put("false_act", decision.outcome() == AgentChatIntentJudge.Outcome.ACT && !correct);
                    observation.put("input_tokens", client.meter().inputTokens());
                    observation.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
                    observation.put("calls", calls);
                    observations.add(observation);
                }
                System.out.println("JEV_EVAL completed " + row.get("id"));
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("created_at", Instant.now().toString());
        report.put("dataset", dataset.toString());
        report.put("configured_model", settings.model());
        report.put("act_threshold", .75);
        report.put("ask_threshold", .45);
        report.put("observations", observations);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("JEV_EVAL results: " + output);
    }
}
