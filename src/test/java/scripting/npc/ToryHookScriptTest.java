package scripting.npc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where Tory (1012112.js) hands a party leader's click to the living world (P6b HPQ step 6), as Lakelis does
 * (LakelisHookPlacementTest): one call, in the leader's branch of "I want to participate", after the isLeader check and
 * before the stock eligibility check and start, the script returning when the hook took the click. The hook is the
 * only change to her script: without its four lines it is the stock script byte for byte (its SHA-256 at 4b0eeac9a).
 * Both NPC styles reach it (the old style skips the menu into the same branch), and its class is loaded there, lazily.
 */
class ToryHookScriptTest {
    private static final Path SCRIPT = Path.of("scripts/npc/1012112.js");
    private static final String CALL = "Java.type('server.living.LivingWorldHooks').partyQuestEntry(cm, \"hpq\")";
    /** The inserted lines, as 9020000.js has them, at the leader branch's indent. */
    private static final String HOOK = ""
            + "                        // companions in the party: the living world answers the click "
            + "(living_world.COMPANION_PQ_ENABLED_KEYS)\n"
            + "                        if (" + CALL + ") {\n"
            + "                            return;\n"
            + "                        }\n";
    /** 1012112.js before the hook (4b0eeac9a), LF line endings. */
    private static final String STOCK_SHA_256 = "430e400eaa2d320fe16917bde72d51dd2c91c6ccaf270d5381b35d39146ad33e";

    @Test
    void oneCallInTheLeadersBranchAfterTheLeaderCheckAndBeforeTheStockStart() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int party = script.indexOf("if (cm.getParty() == null) {");
        int notLeader = script.indexOf("} else if (!cm.isLeader()) {");
        int leaderBranch = script.indexOf("} else {", notLeader);
        int hook = script.indexOf(CALL);
        int eligible = script.indexOf("var eli = em.getEligibleParty(cm.getParty());");
        assertTrue(party > 0 && notLeader > party && eligible > 0, "the stock participate branch is still there");
        assertEquals(hook, script.lastIndexOf(CALL), "one call");
        assertEquals(1, script.split("LivingWorldHooks", -1).length - 1, "no other living-world call");
        assertTrue(hook > notLeader, "after the isLeader check: a member who is not the leader hears the stock line");
        assertTrue(hook < eligible, "before the stock eligibility check and start");
        String before = script.substring(leaderBranch + "} else {".length(), script.indexOf("if (" + CALL));
        assertEquals("//companionsintheparty:thelivingworldanswerstheclick(living_world.COMPANION_PQ_ENABLED_KEYS)",
                before.replaceAll("\\s+", ""), "the first thing in the leader's own branch");
        String after = script.substring(hook + CALL.length(), eligible);
        assertEquals("){return;}", after.replaceAll("\\s+", ""), "the script returns when the living world took the click");
        for (String line : script.split("\n")) {
            assertFalse(!line.startsWith(" ") && line.contains("Java.type('server.living"), "no top-level load: " + line);
        }
    }

    @Test
    void withoutTheHookHerScriptIsTheStockOneByteForByte() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (byte b : bytes) {
            assertTrue(b >= 0 && b != '\r', "1012112.js stays US-ASCII and LF");
        }
        String script = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(script.contains("                    } else {\n" + HOOK
                + "                        var eli = em.getEligibleParty(cm.getParty());\n"), "the hook as inserted");

        String stock = script.replace(HOOK, "");

        assertEquals(STOCK_SHA_256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stock.getBytes(StandardCharsets.US_ASCII))), "nothing else in her script changed");
    }

    @Test
    void theOldNpcStyleReachesTheSameBranch() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.US_ASCII);

        int oldStyle = script.indexOf("} else if (cm.isUsingOldPqNpcStyle()) {");
        assertTrue(oldStyle > 0, script);
        assertTrue(script.substring(oldStyle, script.indexOf("var menu =", oldStyle)).contains("action(1, 0, 0);"),
                "the old style skips the menu with selection 0: 'I want to participate'");
        assertTrue(script.indexOf("if (selection == 0) {") < script.indexOf(CALL));
    }
}
