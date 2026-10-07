package server.agents.runtime.async;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentSchedulerBlockingBoundaryTest {
    private static final Path AGENT_SOURCE = Path.of("src/main/java/server/agents");
    private static final Pattern THREAD_SLEEP = Pattern.compile("Thread\\.sleep\\s*\\(");
    private static final List<Pattern> BLOCKING_PATTERNS = List.of(
            Pattern.compile("\\.join\\s*\\(\\s*\\)"),
            THREAD_SLEEP,
            Pattern.compile("\\.get\\s*\\([^)]*TimeUnit"));
    /**
     * GM-only operator tools (combat arena, skill probe). Each run starts its own worker thread (combat-arena-id,
     * skill-probe-id) and sleeps there between scripted steps while the scheduler ticks the Agent; the scheduler
     * thread never runs them. Only Thread.sleep is exempt; the other blocking patterns still apply.
     */
    private static final Set<Path> OPERATOR_WORKER_THREAD_TOOLS = Set.of(
            AGENT_SOURCE.resolve("diagnostics/arena/AgentCombatArenaService.java"),
            AGENT_SOURCE.resolve("diagnostics/skillprobe/AgentSkillProbeService.java"));

    @Test
    void agentProductionCodeContainsNoDirectWaitPrimitive() throws Exception {
        try (var files = Files.walk(AGENT_SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (Pattern pattern : BLOCKING_PATTERNS) {
                    if (pattern == THREAD_SLEEP && OPERATOR_WORKER_THREAD_TOOLS.contains(file)) {
                        continue;
                    }
                    assertFalse(pattern.matcher(source).find(),
                            file + " contains blocking pattern " + pattern.pattern());
                }
            }
        }
    }

    @Test
    void synchronousNavigationGraphAccessIsRestrictedToExplicitTools() throws Exception {
        try (var files = Files.walk(AGENT_SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("AgentNavigationGraphService.java")
                        || name.equals("AgentNavigationProbe.java")) {
                    continue;
                }
                assertFalse(
                        Files.readString(file).contains("AgentNavigationGraphService.getGraph("),
                        file + " may block on synchronous graph construction");
            }
        }
    }
}
