package scripting.npc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where Lakelis (9020000.js) hands a party leader's click to the living world (P6): in the leader's branch of "I want to
 * participate", before the stock eligibility check and start, with the script returning when the hook took the click.
 * Both NPC styles reach it (the old style skips the menu into the same branch). The hook class is loaded there, lazily:
 * the prepared engine evaluates only the top level off-thread (NPCScriptManagerPreparedEngineTest).
 */
class LakelisHookPlacementTest {
    private static final String HOOK =
            "if (Java.type('server.living.LivingWorldHooks').partyQuestEntry(cm, \"kpq\")) {";

    @Test
    void theHookSitsInTheLeadersBranchBeforeTheStockStartAndReturns() throws IOException {
        String script = Files.readString(Path.of("scripts/npc/9020000.js"), StandardCharsets.US_ASCII);

        int leader = script.indexOf("} else if (!cm.isLeader()) {");
        int hook = script.indexOf(HOOK);
        int eligible = script.indexOf("var eli = em.getEligibleParty(cm.getParty());");
        assertTrue(leader > 0 && eligible > 0, "the stock participate branch is still there");
        assertTrue(hook > leader && hook < eligible, "after the leader check, before the eligibility check");
        assertEquals(hook, script.lastIndexOf(HOOK), "one hook");
        String after = script.substring(hook + HOOK.length(), eligible);
        assertEquals("return;}", after.replaceAll("\\s+", ""), "the script returns when the living world took the click");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
    }
}
