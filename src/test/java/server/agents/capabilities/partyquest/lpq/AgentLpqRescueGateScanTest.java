package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ C2.3: every rescue primitive of AgentLpqCoordinator starts with the companion-run gate
 * ('if (AgentLpqRescue.withhold('), before any raw world call AgentLpqRescueSiteTest keeps inside the primitives.
 * The scan strips comments and blanks string literals, as AgentLpqRescueSiteTest does.
 */
class AgentLpqRescueGateScanTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java");
    /** Each primitive and the kind its gate names (grantItem passes its kind argument through). */
    private static final Map<String, String> PRIMITIVES = Map.of(
            "restoreEventMember", "EVENT_REREGISTER", "moveWithinEvent", "MAP_MOVE",
            "recoverStageTwoTrap", "TRAP_RETURN", "rescueTeleport", "TELEPORT", "remoteBoxHits", "REMOTE_REACTOR",
            "vacuumItemDrops", "VACUUM", "forcePassTransferToLeader", "FORCE_TRANSFER", "grantItem", "kind",
            "killWithDrops", "KILL_WITH_DROPS");
    private static final List<String> RAW_CALLS = List.of("ACTIONS\\s*\\.\\s*stagePosition\\s*\\(",
            "\\bkillMonsterWithDrops\\s*\\(", "\\bregisterPlayer\\s*\\(", "\\bchangeMapNear\\s*\\(",
            "\\binventory\\s*\\(\\s*\\)\\s*\\.\\s*addItem\\s*\\(", "\\bremoveItemDrop\\s*\\(",
            "\\binventory\\s*\\(\\s*\\)\\s*\\.\\s*removeById\\s*\\(", "ACTIONS\\s*\\.\\s*hitReactor\\s*\\(");

    private static String code() throws IOException {
        String source = Files.readString(SOURCE);
        String noBlockComments = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll(" ");
        String noLineComments = noBlockComments.replaceAll("//[^\n]*", " ");
        return noLineComments.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static String body(String code, String name) {
        Matcher header = Pattern.compile("(?m)^    (?:private )?static [\\w<>]+ " + name + "\\s*\\(").matcher(code);
        assertTrue(header.find(), "no primitive " + name);
        int open = code.indexOf('{', header.end());
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            if (code.charAt(i) == '}' && --depth == 0) return code.substring(open + 1, i);
        }
        throw new AssertionError("unbalanced " + name);
    }

    @Test
    void everyPrimitiveStartsWithTheCompanionRunGate() throws IOException {
        String code = code();
        for (Map.Entry<String, String> primitive : PRIMITIVES.entrySet()) {
            String body = body(code, primitive.getKey()).strip();
            String gate = "if (AgentLpqRescue.withhold(session, "
                    + (primitive.getValue().equals("kind") ? "kind" : "AgentLpqRescue.Kind." + primitive.getValue())
                    + ", site, ";
            assertTrue(body.replaceAll("\\s+", " ").startsWith(gate), primitive.getKey() + " starts with " + gate
                    + " but is: " + body.substring(0, Math.min(160, body.length())));
            int gateEnd = body.indexOf("return");
            for (String raw : RAW_CALLS) {
                Matcher call = Pattern.compile(raw).matcher(body);
                if (call.find()) {
                    assertTrue(call.start() > gateEnd, primitive.getKey() + ": " + raw + " before the gate's return");
                }
            }
        }
    }
}
